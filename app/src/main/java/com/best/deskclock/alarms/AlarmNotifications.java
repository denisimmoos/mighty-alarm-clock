/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.alarms;

import static com.best.deskclock.settings.PreferencesDefaultValues.ALARM_SNOOZE_DURATION_DISABLED;
import static com.best.deskclock.utils.NotificationUtils.ALARM_MISSED_NOTIFICATION_CHANNEL_ID;
import static com.best.deskclock.utils.NotificationUtils.ALARM_SNOOZE_NOTIFICATION_CHANNEL_ID;
import static com.best.deskclock.utils.NotificationUtils.ALARM_UPCOMING_NOTIFICATION_CHANNEL_ID;
import static com.best.deskclock.utils.NotificationUtils.FIRING_NOTIFICATION_CHANNEL_ID;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.best.deskclock.DeskClock;
import com.best.deskclock.R;
import com.best.deskclock.mighty.calendar.CalendarAlarmRules;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.utils.AlarmUtils;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.SdkUtils;
import com.best.deskclock.utils.Utils;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Objects;

public final class AlarmNotifications {
    public static final String EXTRA_NOTIFICATION_ID = "extra_notification_id";
    public static final String EXTRA_MISSED_ALARM_NOTIFICATION = "extra_missed_alarm_notification";
    public static final String EXTRA_MISSED_ALARM_INSTANCE_ID = "extra_missed_alarm_instance_id";

    /**
     * Formats times such that chronological order and lexicographical order agree.
     */
    private static final DateFormat SORT_KEY_FORMAT =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    /**
     * This value is coordinated with group ids from
     * {link com.best.deskclock.data.NotificationModel}
     */
    private static final String UPCOMING_GROUP_KEY = "1";

    /**
     * This value is coordinated with group ids from
     * {link com.best.deskclock.data.NotificationModel}
     */
    private static final String MISSED_GROUP_KEY = "4";

    /**
     * This value is coordinated with notification ids from
     * {link com.best.deskclock.data.NotificationModel}
     */
    private static final int ALARM_GROUP_NOTIFICATION_ID = Integer.MAX_VALUE - 4;

    /**
     * This value is coordinated with notification ids from
     * {link com.best.deskclock.data.NotificationModel}
     */
    private static final int ALARM_GROUP_MISSED_NOTIFICATION_ID = Integer.MAX_VALUE - 5;

    /**
     * This value is coordinated with notification ids from
     * {link com.best.deskclock.data.NotificationModel}
     */
    private static final int ALARM_FIRING_NOTIFICATION_ID = Integer.MAX_VALUE - 7;

    private static boolean isGroupSummary(Notification n) {
        return (n.flags & Notification.FLAG_GROUP_SUMMARY) == Notification.FLAG_GROUP_SUMMARY;
    }

    /**
     * Method which returns the first active notification for a given group. If a notification was
     * just posted, provide it to make sure it is included as a potential result. If a notification
     * was just canceled, provide the id so that it is not included as a potential result. These
     * extra parameters are needed due to a race condition which exists in
     * {@link NotificationManager#getActiveNotifications()}.
     *
     * @param context                Context from which to grab the NotificationManager
     * @param group                  The group key to query for notifications
     * @param canceledNotificationId The id of the just-canceled notification (-1 if none)
     * @param postedNotification     The notification that was just posted
     * @return The first active notification for the group
     */

    private static Notification getFirstActiveNotification(Context context, String group, int canceledNotificationId,
                                                           Notification postedNotification) {

        final NotificationManager nm = context.getApplicationContext().getSystemService(NotificationManager.class);
        final StatusBarNotification[] notifications = nm.getActiveNotifications();
        Notification firstActiveNotification = postedNotification;

        for (StatusBarNotification statusBarNotification : notifications) {
            final Notification n = statusBarNotification.getNotification();
            if (!isGroupSummary(n) && group.equals(n.getGroup()) && statusBarNotification.getId() != canceledNotificationId) {
                if (firstActiveNotification == null || n.getSortKey().compareTo(firstActiveNotification.getSortKey()) < 0) {
                    firstActiveNotification = n;
                }
            }
        }

        return firstActiveNotification;
    }

    private static Notification getActiveGroupSummaryNotification(Context context, String group) {
        final NotificationManager nm = context.getApplicationContext().getSystemService(NotificationManager.class);
        final StatusBarNotification[] notifications = nm.getActiveNotifications();

        for (StatusBarNotification statusBarNotification : notifications) {
            final Notification n = statusBarNotification.getNotification();
            if (isGroupSummary(n) && group.equals(n.getGroup())) {
                return n;
            }
        }

        return null;
    }

    private static void updateUpcomingAlarmGroupNotification(Context context, int canceledNotificationId, Notification postedNotification) {
        final NotificationManager nm = context.getApplicationContext().getSystemService(NotificationManager.class);
        final Notification firstUpcoming = getFirstActiveNotification(
            context, UPCOMING_GROUP_KEY, canceledNotificationId, postedNotification);

        if (firstUpcoming == null) {
            nm.cancel(ALARM_GROUP_NOTIFICATION_ID);
            return;
        }

        Notification summary = getActiveGroupSummaryNotification(context, UPCOMING_GROUP_KEY);

        if (summary == null || !Objects.equals(summary.contentIntent, firstUpcoming.contentIntent)) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Always false, because notification activation is always checked when the application is started.
                return;
            }

            summary = new NotificationCompat.Builder(context, ALARM_UPCOMING_NOTIFICATION_CHANNEL_ID)
                .setShowWhen(false)
                .setContentIntent(firstUpcoming.contentIntent)
                .setColor(ContextCompat.getColor(context, R.color.md_theme_primary))
                .setSmallIcon(R.drawable.ic_tab_alarm_static)
                .setGroup(UPCOMING_GROUP_KEY)
                .setGroupSummary(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setLocalOnly(true)
                .build();

            nm.notify(ALARM_GROUP_NOTIFICATION_ID, summary);
        }
    }

    public static void updateMissedAlarmGroupNotification(Context context, int canceledNotificationId, Notification postedNotification) {
        final NotificationManager nm = context.getApplicationContext().getSystemService(NotificationManager.class);
        final Notification firstMissed = getFirstActiveNotification(context, MISSED_GROUP_KEY,
            canceledNotificationId, postedNotification);

        if (firstMissed == null) {
            nm.cancel(ALARM_GROUP_MISSED_NOTIFICATION_ID);
            return;
        }

        Notification summary = getActiveGroupSummaryNotification(context, MISSED_GROUP_KEY);

        if (summary == null || !Objects.equals(summary.contentIntent, firstMissed.contentIntent)) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Always false, because notification activation is always checked when the application is started.
                return;
            }

            summary = new NotificationCompat.Builder(context, ALARM_MISSED_NOTIFICATION_CHANNEL_ID)
                .setShowWhen(false)
                .setContentIntent(firstMissed.contentIntent)
                .setColor(ContextCompat.getColor(context, R.color.md_theme_primary))
                .setSmallIcon(R.drawable.ic_tab_alarm_static)
                .setGroup(MISSED_GROUP_KEY)
                .setGroupSummary(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setLocalOnly(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setOnlyAlertOnce(true)
                .build();

            nm.notify(ALARM_GROUP_MISSED_NOTIFICATION_ID, summary);
        }
    }

    public static synchronized void showUpcomingNotification(Context context, AlarmInstance instance) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Always false, because notification activation is always checked when the application is started.
            return;
        }

        LogUtils.v("Displaying upcoming alarm notification for alarm instance: " + instance.mId);

        final Context localizedContext = Utils.getLocalizedContext(context);

        final Alarm alarm = Alarm.getAlarm(context.getContentResolver(), instance.mAlarmId);

        if (alarm == null) {
            LogUtils.wtf("Failed to retrieve alarm with ID: %d", instance.mAlarmId);
            return;
        }

        final int id = instance.hashCode();

        final String contentTitle = localizedContext.getString(R.string.alarm_alert_predismiss_title);

        final String dismissActionTitle = localizedContext.getString(R.string.alarm_alert_dismiss_text);

        // Setup content action if instance is owned by alarm
        Intent viewAlarmIntent = createViewAlarmIntent(context, instance);
        PendingIntent contentIntent = PendingIntent.getActivity(context, id, viewAlarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, ALARM_UPCOMING_NOTIFICATION_CHANNEL_ID)
            .setShowWhen(false)
            .setContentTitle(contentTitle)
            .setContentText(AlarmUtils.getAlarmText(localizedContext, instance, true))
            .setContentIntent(contentIntent)
            .setColor(ContextCompat.getColor(context, R.color.md_theme_primary))
            .setSmallIcon(R.drawable.ic_tab_alarm_static)
            .setAutoCancel(false)
            .setSortKey(createSortKey(instance))
            .setDefaults(0) // No sound on Android 7 and earlier versions
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .setOngoing(true)
            .setGroup(UPCOMING_GROUP_KEY);

        // Setup up dismiss action
        Intent dismissIntent = AlarmStateManager.createStateChangeIntent(context,
            AlarmStateManager.ALARM_DISMISS_TAG, instance, AlarmInstance.PREDISMISSED_STATE);
        builder.addAction(R.drawable.ic_alarm_off, dismissActionTitle, PendingIntent.getService(
            context, id, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        hideCalendarDetailsWhenLocked(context, localizedContext, builder, instance);
        final Notification notification = builder.build();

        nm.notify(id, notification);

        updateUpcomingAlarmGroupNotification(context, -1, notification);
    }

    public static synchronized void showSnoozeNotification(Context context, AlarmInstance instance) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Always false, because notification activation is always checked when the application is started.
            return;
        }

        LogUtils.v("Displaying snoozed notification for alarm instance: " + instance.mId);

        final Context localizedContext = Utils.getLocalizedContext(context);

        final Alarm alarm = Alarm.getAlarm(context.getContentResolver(), instance.mAlarmId);

        if (alarm == null) {
            LogUtils.wtf("Failed to retrieve alarm with ID: %d", instance.mAlarmId);
            return;
        }

        String dismissActionTitle = localizedContext.getString(R.string.alarm_alert_dismiss_text);

        final int id = instance.hashCode();

        // Setup content action if instance is owned by alarm
        Intent viewAlarmIntent = createViewAlarmIntent(context, instance);
        PendingIntent contentIntent = PendingIntent.getActivity(context, id, viewAlarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Setup up dismiss action
        Intent dismissIntent = AlarmStateManager.createStateChangeIntent(
            context, AlarmStateManager.ALARM_DISMISS_TAG, instance, AlarmInstance.DISMISSED_STATE);
        PendingIntent dismissPendingIntent = PendingIntent.getService(
            context, id, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, ALARM_SNOOZE_NOTIFICATION_CHANNEL_ID)
            .setShowWhen(false)
            .setContentTitle(instance.getLabelOrDefault(localizedContext))
            .setContentText(localizedContext.getString(R.string.alarm_alert_snooze_until,
                AlarmUtils.getFormattedTime(localizedContext, instance.getAlarmTime())))
            .setContentIntent(contentIntent)
            .setColor(ContextCompat.getColor(context, R.color.md_theme_primary))
            .setSmallIcon(R.drawable.ic_tab_alarm_static)
            .setAutoCancel(false)
            .setSortKey(createSortKey(instance))
            .setDefaults(0) // No sound on Android 7 and earlier versions
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .setGroup(UPCOMING_GROUP_KEY);

        builder.addAction(R.drawable.ic_alarm_off, dismissActionTitle, dismissPendingIntent);

        if (alarm.isRecurring()) {
            Intent disableIntent = AlarmStateManager.createStateChangeIntent(
                context, AlarmStateManager.ALARM_DISABLE_TAG, instance, AlarmInstance.DISMISSED_STATE);
            disableIntent.putExtra(AlarmStateManager.FROM_NOTIFICATION_EXTRA, true);
            PendingIntent disablePendingIntent = PendingIntent.getService(
                context, id + 1, disableIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.ic_alarm_off,
                localizedContext.getString(R.string.alarm_alert_disable_text), disablePendingIntent);
        }

        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        hideCalendarDetailsWhenLocked(context, localizedContext, builder, instance);
        final Notification notification = builder.build();

        nm.notify(id, notification);

        updateUpcomingAlarmGroupNotification(context, -1, notification);
    }

    @SuppressLint("LaunchActivityFromNotification")
    static synchronized void showMissedNotification(Context context, AlarmInstance instance) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Always false, because notification activation is always checked when the application is started.
            return;
        }

        LogUtils.v("Displaying missed notification for alarm instance: " + instance.mId);

        final Context localizedContext = Utils.getLocalizedContext(context);

        String label = instance.mLabel;
        String alarmTime = AlarmUtils.getFormattedTime(localizedContext, instance.getAlarmTime());
        final int id = instance.hashCode();

        // Setup dismiss intent
        Intent dismissIntent = new Intent(context, AlarmStateManager.class)
            .setAction(AlarmStateManager.ACTION_DISMISS_MISSED_ALARM)
            .putExtra(EXTRA_NOTIFICATION_ID, id)
            .putExtra(EXTRA_MISSED_ALARM_INSTANCE_ID, instance.mId);
        PendingIntent deleteIntent = PendingIntent.getBroadcast(
            context, id, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Setup content intent
        Intent viewAlarmIntent = createViewAlarmIntent(context, instance);
        viewAlarmIntent.putExtra(EXTRA_MISSED_ALARM_NOTIFICATION, true);
        viewAlarmIntent.putExtra(EXTRA_NOTIFICATION_ID, id);
        viewAlarmIntent.putExtra(EXTRA_MISSED_ALARM_INSTANCE_ID, instance.mId);
        PendingIntent contentIntent = PendingIntent.getActivity(
            context, id, viewAlarmIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, ALARM_MISSED_NOTIFICATION_CHANNEL_ID)
            .setShowWhen(false)
            .setContentTitle(localizedContext.getString(R.string.alarm_missed_title))
            .setContentText(label.isEmpty()
                ? alarmTime
                : localizedContext.getString(R.string.alarm_missed_text, alarmTime, label))
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .setColor(ContextCompat.getColor(context, R.color.md_theme_primary))
            .setSortKey(createSortKey(instance))
            .setSmallIcon(R.drawable.ic_tab_alarm_static)
            .setDefaults(NotificationCompat.DEFAULT_ALL) // Sound + Vibrate on Android 7 and earlier versions
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setGroup(MISSED_GROUP_KEY);

        NotificationManagerCompat nm = NotificationManagerCompat.from(context);

        hideCalendarDetailsWhenLocked(context, localizedContext, builder, instance);
        final Notification notification = builder.build();

        nm.notify(id, notification);
        updateMissedAlarmGroupNotification(context, -1, notification);
    }

    /** Request codes of the snooze buttons of a ringing calendar alarm, one for each length. */
    private static final int CALENDAR_SNOOZE_REQUEST_CODE = Integer.MAX_VALUE - 1000;

    @SuppressLint("FullScreenIntentPolicy")
    static synchronized void showAlarmNotification(Context context, AlarmInstance instance) {
        LogUtils.v("Displaying alarm notification for alarm instance: " + instance.mId);

        final Context localizedContext = Utils.getLocalizedContext(context);

        final Alarm alarm = Alarm.getAlarm(context.getContentResolver(), instance.mAlarmId);

        if (alarm == null) {
            LogUtils.wtf("Failed to retrieve alarm with ID: %d", instance.mAlarmId);
            return;
        }

        String dismissActionTitle = localizedContext.getString(R.string.alarm_alert_dismiss_text);

        // Setup Content Action
        Intent viewAlarmIntent = AlarmInstance.createIntent(context, AlarmActivity.class, instance.mId);
        PendingIntent contentIntent = PendingIntent.getActivity(context, ALARM_FIRING_NOTIFICATION_ID,
            viewAlarmIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Setup up dismiss action
        Intent dismissIntent = AlarmStateManager.createStateChangeIntent(context,
            AlarmStateManager.ALARM_DISMISS_TAG, instance, AlarmInstance.DISMISSED_STATE);
        dismissIntent.putExtra(AlarmStateManager.FROM_NOTIFICATION_EXTRA, true);
        PendingIntent dismissPendingIntent = PendingIntent.getService(context,
            ALARM_FIRING_NOTIFICATION_ID, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, FIRING_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(instance.getLabelOrDefault(localizedContext))
            .setContentText(AlarmUtils.getFormattedTime(localizedContext, instance.getAlarmTime()))
            .setContentIntent(contentIntent)
            .setDeleteIntent(dismissPendingIntent)
            .setColor(ContextCompat.getColor(context, R.color.md_theme_primary))
            .setSmallIcon(R.drawable.ic_tab_alarm_static)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
            .setWhen(0)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(true);

        // Setup Snooze Action only if snooze duration has NOT been set to "None" in the settings
        // or if "Enable alarm snooze actions" is enabled in the alarm editing panel.
        // Calendar alarms that were snoozed as often as their profile allows get no Snooze button.
        final com.best.deskclock.provider.CalendarProfile calendarProfile =
            CalendarAlarmRules.getProfileForAlarm(context.getContentResolver(), instance.mAlarmId);
        final int[] snoozeChoices = calendarProfile == null
            || CalendarAlarmRules.isSnoozeLimitReached(calendarProfile, instance)
            ? new int[0] : calendarProfile.getSnoozeChoices();
        if (snoozeChoices.length == 3) {
            // A calendar alarm offers its profile's three snooze lengths. A notification shows
            // at most three actions, so they are buttons in the notification itself, next to
            // the Dismiss action.
            // The expanded view has room for the title, the time, the bubbles and the extend
            // line. The folded and heads-up views get about 48 dp, so they use a one-line layout.
            final android.widget.RemoteViews big = new android.widget.RemoteViews(
                context.getPackageName(), R.layout.calendar_alarm_notification);
            final android.widget.RemoteViews small = new android.widget.RemoteViews(
                context.getPackageName(), R.layout.calendar_alarm_notification_small);
            big.setTextViewText(R.id.time, AlarmUtils.getFormattedTime(localizedContext, instance.getAlarmTime()));
            // Below the bubbles: snoozing extends the event, if the profile does that.
            final com.best.deskclock.provider.CalendarAlarmEvent calendarEvent =
                com.best.deskclock.provider.CalendarAlarmEvent.getEventForAlarm(context.getContentResolver(), alarm.id);
            final boolean extendsEvent = calendarEvent != null && calendarEvent.endAlarmId == alarm.id
                && calendarProfile.extendOnSnooze
                && com.best.deskclock.mighty.calendar.CalendarEventExtender.hasPermission(context);
            for (android.widget.RemoteViews content : new android.widget.RemoteViews[]{small, big}) {
                content.setTextViewText(R.id.title, instance.getLabelOrDefault(localizedContext));
                if (extendsEvent) {
                    content.setTextViewText(R.id.extend_hint,
                        localizedContext.getString(R.string.calendar_alarm_snooze_extends));
                    content.setViewVisibility(R.id.extend_hint, android.view.View.VISIBLE);
                }
            }
            final int[] buttons = {R.id.snooze_1, R.id.snooze_2, R.id.snooze_3};
            for (int i = 0; i < buttons.length; i++) {
                final Intent snoozeIntent = AlarmStateManager.createStateChangeIntent(
                    context, AlarmStateManager.ALARM_SNOOZE_TAG, instance, AlarmInstance.SNOOZE_STATE);
                snoozeIntent.putExtra(AlarmStateManager.FROM_NOTIFICATION_EXTRA, true);
                snoozeIntent.putExtra(AlarmStateManager.SNOOZE_MINUTES_EXTRA, snoozeChoices[i]);
                // Its own request code, so each button keeps its own length.
                final PendingIntent snoozePendingIntent = PendingIntent.getService(context,
                    CALENDAR_SNOOZE_REQUEST_CODE + i, snoozeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                for (android.widget.RemoteViews content : new android.widget.RemoteViews[]{small, big}) {
                    content.setTextViewText(buttons[i],
                        localizedContext.getString(R.string.calendar_alarm_snooze_action, snoozeChoices[i]));
                    content.setOnClickPendingIntent(buttons[i], snoozePendingIntent);
                    content.setContentDescription(buttons[i], localizedContext.getResources().getQuantityString(
                        R.plurals.alarm_alert_snooze_set, snoozeChoices[i], snoozeChoices[i]));
                }
            }
            notification.setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setCustomHeadsUpContentView(small);
        } else if (instance.mSnoozeDuration != ALARM_SNOOZE_DURATION_DISABLED
            && !CalendarAlarmRules.isSnoozeLimitReached(calendarProfile, instance)) {
            Intent snoozeIntent = AlarmStateManager.createStateChangeIntent(
                context, AlarmStateManager.ALARM_SNOOZE_TAG, instance, AlarmInstance.SNOOZE_STATE);
            snoozeIntent.putExtra(AlarmStateManager.FROM_NOTIFICATION_EXTRA, true);

            PendingIntent snoozePendingIntent = PendingIntent.getService(context,
                ALARM_FIRING_NOTIFICATION_ID, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            notification.addAction(R.drawable.ic_snooze, localizedContext.getString(R.string.alarm_alert_snooze_text), snoozePendingIntent);
        }

        notification.addAction(R.drawable.ic_alarm_off, dismissActionTitle, dismissPendingIntent);

        if (alarm.isRecurring()) {
            Intent disableIntent = AlarmStateManager.createStateChangeIntent(context,
                AlarmStateManager.ALARM_DISABLE_TAG, instance, AlarmInstance.DISMISSED_STATE);
            disableIntent.putExtra(AlarmStateManager.FROM_NOTIFICATION_EXTRA, true);
            PendingIntent disablePendingIntent = PendingIntent.getService(context,
                ALARM_FIRING_NOTIFICATION_ID - 1, disableIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            notification.addAction(R.drawable.ic_alarm_off,
                localizedContext.getString(R.string.alarm_alert_disable_text), disablePendingIntent);
        }

        // Setup fullscreen intent
        Intent fullScreenIntent = AlarmInstance.createIntent(context, AlarmActivity.class, instance.mId);
        // Set action, so we can be different from content pending intent
        fullScreenIntent.setAction("fullscreen_activity");
        fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        notification.setFullScreenIntent(PendingIntent.getActivity(context, ALARM_FIRING_NOTIFICATION_ID, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE), true);

        clearNotification(context, instance);

        hideCalendarDetailsWhenLocked(context, localizedContext, notification, instance);
        final Notification builtNotification = notification.build();

        if (context instanceof Service) {
            int foregroundServiceType = 0;

            if (SdkUtils.isAtLeastAndroid14()) {
                foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            }

            ServiceCompat.startForeground((Service) context, ALARM_FIRING_NOTIFICATION_ID, builtNotification, foregroundServiceType);
        } else {
            NotificationManagerCompat nm = NotificationManagerCompat.from(context);

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Always false, because notification activation is always checked when the application is started.
                return;
            }

            nm.notify(ALARM_FIRING_NOTIFICATION_ID, builtNotification);
        }
    }

    public static synchronized void clearNotification(Context context, AlarmInstance instance) {
        LogUtils.v("Clearing notifications for alarm instance: " + instance.mId);
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        final int id = instance.hashCode();
        nm.cancel(id);
        updateUpcomingAlarmGroupNotification(context, id, null);
        updateMissedAlarmGroupNotification(context, id, null);
    }

    /**
     * Updates the notification for an existing alarm.
     */
    public static void updateNotification(Context context, AlarmInstance instance) {
        switch (instance.mAlarmState) {
            case AlarmInstance.NOTIFICATION_STATE -> showUpcomingNotification(context, instance);
            case AlarmInstance.FIRED_STATE -> showAlarmNotification(context, instance);
            case AlarmInstance.SNOOZE_STATE -> showSnoozeNotification(context, instance);
            case AlarmInstance.MISSED_STATE -> showMissedNotification(context, instance);
            default -> LogUtils.d("No notification to update");
        }
    }

    static Intent createViewAlarmIntent(Context context, AlarmInstance instance) {
        final long alarmId = instance.mAlarmId == null ? Alarm.INVALID_ID : instance.mAlarmId;
        // Alarms are listed in the Calendar tab.
        return Alarm.createIntent(context, DeskClock.class, alarmId)
            .setAction(DeskClock.ACTION_SHOW_CALENDAR_ALARMS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * Alarm notifications are sorted chronologically. Missed alarms are sorted chronologically
     * <strong>after</strong> all upcoming/snoozed alarms by including the "MISSED" prefix on the
     * sort key.
     *
     * @param instance the alarm instance for which the notification is generated
     * @return the sort key that specifies the order of this alarm notification
     */
    private static String createSortKey(AlarmInstance instance) {
        final String timeKey = SORT_KEY_FORMAT.format(instance.getAlarmTime().getTime());
        final boolean missedAlarm = instance.mAlarmState == AlarmInstance.MISSED_STATE;
        return missedAlarm ? ("MISSED " + timeKey) : timeKey;
    }

    /**
     * Keeps the event title of a calendar alarm off the lock screen when its profile hides the
     * event details there. The lock screen then shows only "Calendar alarm" and the time.
     */
    private static void hideCalendarDetailsWhenLocked(Context context, Context localizedContext,
                                                      NotificationCompat.Builder builder, AlarmInstance instance) {
        final com.best.deskclock.provider.CalendarProfile profile =
            CalendarAlarmRules.getProfileForAlarm(context.getContentResolver(), instance.mAlarmId);
        if (profile == null || profile.showDetailsLocked) {
            return;
        }
        // Only shown inside the real notification, so its channel doesn't matter.
        final Notification publicVersion = new NotificationCompat.Builder(context, FIRING_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_alarm_static)
            .setColor(ContextCompat.getColor(context, R.color.md_theme_primary))
            .setContentTitle(localizedContext.getString(R.string.calendar_alarm_hidden_title))
            .setContentText(AlarmUtils.getFormattedTime(localizedContext, instance.getAlarmTime()))
            .build();
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion);
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.best.deskclock.R;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.provider.EventLogStore;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.SdkUtils;

import java.util.List;

/**
 * Keeps a calendar alarm quiet when its profile only rings near certain Wi-Fi networks and the
 * phone is not near any of them. Checked each time the alarm is about to ring, also after a snooze.
 */
public final class CalendarWifiGate {

    // Same channel as the other calendar alarm notices.
    private static final String CHANNEL_ID = "calendar_alert_status";

    private CalendarWifiGate() {
    }

    /** What the Wi-Fi check means for a profile's alarms right now. */
    public enum Status {
        /** The profile has no Wi-Fi check. */
        NO_CHECK,
        /** Near one of the networks: the alarms ring. */
        NEAR,
        /** Not near any of the networks: the alarms are skipped. */
        AWAY,
        /** Wi-Fi can't be checked: the alarms are skipped. */
        UNKNOWN,
        /** The phone's Wi-Fi is off and the profile rings anyway. */
        WIFI_OFF_RINGS,
        /** The phone's Wi-Fi is off: the alarms are skipped. */
        WIFI_OFF_SKIPS
    }

    /**
     * @param scan see {@link CalendarWifi#check(Context, List, boolean)}.
     * @return what the Wi-Fi check means for the profile's alarms right now.
     */
    public static Status status(Context context, CalendarProfile profile, boolean scan) {
        if (profile == null || !profile.wifiCheck) {
            return Status.NO_CHECK;
        }
        final List<CalendarWifi.Network> networks = CalendarWifi.parse(profile.wifiNetworks);
        if (networks.isEmpty()) {
            return Status.NO_CHECK;
        }
        if (!CalendarWifi.isWifiEnabled(context)) {
            return profile.wifiOffRings ? Status.WIFI_OFF_RINGS : Status.WIFI_OFF_SKIPS;
        }
        return switch (CalendarWifi.check(context, networks, scan)) {
            case NEAR -> Status.NEAR;
            case NOT_NEAR -> Status.AWAY;
            case UNKNOWN -> Status.UNKNOWN;
            case WIFI_OFF -> profile.wifiOffRings ? Status.WIFI_OFF_RINGS : Status.WIFI_OFF_SKIPS;
        };
    }

    /**
     * @return the text shown on a profile for the status, or {@code null} without a Wi-Fi check.
     */
    public static String describe(Context context, Status status) {
        return switch (status) {
            case NO_CHECK -> null;
            case NEAR -> context.getString(R.string.calendar_alarm_wifi_status_near);
            case AWAY -> context.getString(R.string.calendar_alarm_wifi_status_away);
            case UNKNOWN -> context.getString(R.string.calendar_alarm_wifi_status_unknown);
            case WIFI_OFF_RINGS -> context.getString(R.string.calendar_alarm_wifi_status_off_rings);
            case WIFI_OFF_SKIPS -> context.getString(R.string.calendar_alarm_wifi_status_off_skips);
        };
    }

    /**
     * Shows the status on a line with a Wi-Fi icon: red when the alarms are skipped, a yellow
     * icon when they ring only because Wi-Fi is off and "ring anyway" is on.
     */
    public static void showStatus(android.widget.TextView view, Status status, String text) {
        view.setVisibility(text == null ? android.view.View.GONE : android.view.View.VISIBLE);
        view.setText(text);
        final int normal = com.google.android.material.color.MaterialColors.getColor(view,
            com.google.android.material.R.attr.colorOnSurfaceVariant);
        final int textColor;
        final int iconColor;
        if (isSkipping(status)) {
            textColor = iconColor = com.google.android.material.color.MaterialColors.getColor(view,
                androidx.appcompat.R.attr.colorError);
        } else if (status == Status.WIFI_OFF_RINGS) {
            textColor = normal;
            iconColor = ContextCompat.getColor(view.getContext(), R.color.calendar_alarm_wifi_warning);
        } else {
            textColor = iconColor = normal;
        }
        view.setTextColor(textColor);
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(view,
            android.content.res.ColorStateList.valueOf(iconColor));
    }

    /**
     * @return {@code true} if alarms with this status are skipped.
     */
    public static boolean isSkipping(Status status) {
        return status == Status.AWAY || status == Status.UNKNOWN || status == Status.WIFI_OFF_SKIPS;
    }

    /**
     * @return the reason the alarm must stay quiet, or {@code null} if it may ring.
     */
    public static CalendarWifi.Result shouldSkip(Context context, AlarmInstance instance) {
        if (instance.mAlarmId == null) {
            return null;
        }
        final CalendarProfile profile;
        try {
            profile = CalendarAlarmRules.getProfileForAlarm(context.getContentResolver(), instance.mAlarmId);
        } catch (Exception e) {
            LogUtils.e("CalendarWifiGate: could not read the profile of alarm " + instance.mAlarmId, e);
            return null;
        }
        final Status status = status(context, profile, true);
        if (status != Status.NO_CHECK) {
            LogUtils.i("CalendarWifiGate: alarm %d, Wi-Fi check %s", instance.mAlarmId, status);
        }
        return switch (status) {
            case AWAY -> CalendarWifi.Result.NOT_NEAR;
            case UNKNOWN -> CalendarWifi.Result.UNKNOWN;
            case WIFI_OFF_SKIPS -> CalendarWifi.Result.WIFI_OFF;
            default -> null;
        };
    }

    /**
     * Tells the user that an alarm was skipped and why.
     */
    public static void notifySkipped(Context context, AlarmInstance instance, CalendarWifi.Result reason) {
        try {
            EventLogStore.logEvent(context.getContentResolver(), "CALENDAR_WIFI_SKIPPED", null,
                instance.mLabel, "reason=" + reason);
        } catch (Exception e) {
            LogUtils.e("CalendarWifiGate: could not log the skipped alarm", e);
        }
        try {
            if (SdkUtils.isAtLeastAndroid13() && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if (SdkUtils.isAtLeastAndroid8()) {
                final NotificationManager nm = context.getSystemService(NotificationManager.class);
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.calendar_alarm_status_channel), NotificationManager.IMPORTANCE_DEFAULT));
            }
            final String text = context.getString(switch (reason) {
                case UNKNOWN -> R.string.calendar_alarm_wifi_skipped_unknown;
                case WIFI_OFF -> R.string.calendar_alarm_wifi_skipped_off;
                default -> R.string.calendar_alarm_wifi_skipped_away;
            });
            final String label = instance.mLabel == null || instance.mLabel.isEmpty()
                ? context.getString(R.string.calendar_alarm_hidden_title) : instance.mLabel;
            final Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            final int id = (int) (0x0CA20000L + (instance.mId & 0xFFFF));
            final PendingIntent contentIntent = open == null ? null
                : PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE);
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tab_calendar_alarm)
                .setContentTitle(context.getString(R.string.calendar_alarm_wifi_skipped_title, label))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(contentIntent);
            NotificationManagerCompat.from(context).notify(id, builder.build());
        } catch (Exception e) {
            LogUtils.e("CalendarWifiGate: could not show the skipped notification", e);
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.R;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.SdkUtils;

/**
 * Keeps track of how the calendar checks are doing: when the calendar was last read, and a
 * notification when calendar access was taken away while profiles still need it.
 */
public final class CalendarAlertStatus {

    private static final String KEY_PERMISSION_NOTIFIED = "key_calendar_alert_permission_notified";
    /** Set by the permissions screen once it asked Android for calendar access. */
    public static final String KEY_CALENDAR_PERMISSION_ASKED = "key_calendar_permission_asked";
    private static final String CHANNEL_ID = "calendar_alert_status";
    private static final int NOTIFICATION_ID = 0x0CA1;

    private CalendarAlertStatus() {
    }

    static void update(Context context, boolean canReadCalendar, boolean hasProfiles, long now) {
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        if (canReadCalendar) {
            prefs.edit()
                .putLong(CalendarAlertSync.KEY_LAST_CHECK, now)
                .putBoolean(KEY_PERMISSION_NOTIFIED, false)
                .apply();
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
        } else if (hasProfiles && !prefs.getBoolean(KEY_PERMISSION_NOTIFIED, false)) {
            if (showPermissionLostNotification(context)) {
                prefs.edit().putBoolean(KEY_PERMISSION_NOTIFIED, true).apply();
            }
        }
    }

    /**
     * @return when the calendar was last read, or 0 if never.
     */
    public static long getLastCheck(Context context) {
        return DeskClockApplication.getDefaultSharedPreferences(context).getLong(CalendarAlertSync.KEY_LAST_CHECK, 0);
    }

    /**
     * @return {@code true} if Android may delay the calendar checks to save battery.
     */
    public static boolean isBatteryOptimized(Context context) {
        final PowerManager pm = context.getSystemService(PowerManager.class);
        return pm != null && !pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private static boolean showPermissionLostNotification(Context context) {
        try {
            if (SdkUtils.isAtLeastAndroid13() && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (SdkUtils.isAtLeastAndroid8()) {
                final NotificationManager nm = context.getSystemService(NotificationManager.class);
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.calendar_alert_status_channel), NotificationManager.IMPORTANCE_DEFAULT));
            }
            final Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            final PendingIntent contentIntent = open == null ? null
                : PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE);
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tab_calendar_alert)
                .setContentTitle(context.getString(R.string.calendar_alert_permission_lost_title))
                .setContentText(context.getString(R.string.calendar_alert_permission_lost_text))
                .setStyle(new NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.calendar_alert_permission_lost_text)))
                .setAutoCancel(true)
                .setContentIntent(contentIntent);
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
            return true;
        } catch (Exception e) {
            LogUtils.e("CalendarAlertStatus: could not show the permission notification", e);
            return false;
        }
    }

    /**
     * Removes what this phone remembers about its calendar checks, for example after settings
     * from another phone were restored.
     */
    public static void clearDeviceState(android.content.SharedPreferences.Editor editor) {
        editor.remove(CalendarAlertSync.KEY_LAST_CHECK).remove(KEY_PERMISSION_NOTIFIED)
            // Whether Android was already asked for calendar access on this phone.
            .remove(KEY_CALENDAR_PERMISSION_ASKED);
    }
}

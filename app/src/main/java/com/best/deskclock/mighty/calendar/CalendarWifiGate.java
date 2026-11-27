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
        if (profile == null || !profile.wifiCheck) {
            return null;
        }
        final List<CalendarWifi.Network> networks = CalendarWifi.parse(profile.wifiNetworks);
        if (networks.isEmpty()) {
            return null;
        }
        if (profile.wifiOffRings && !CalendarWifi.isWifiEnabled(context)) {
            LogUtils.i("CalendarWifiGate: alarm %d rings, Wi-Fi is turned off", instance.mAlarmId);
            return null;
        }
        final CalendarWifi.Result result = CalendarWifi.check(context, networks);
        LogUtils.i("CalendarWifiGate: alarm %d, Wi-Fi check %s", instance.mAlarmId, result);
        return result == CalendarWifi.Result.NEAR ? null : result;
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
            final String text = context.getString(reason == CalendarWifi.Result.UNKNOWN
                ? R.string.calendar_alarm_wifi_skipped_unknown : R.string.calendar_alarm_wifi_skipped_away);
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

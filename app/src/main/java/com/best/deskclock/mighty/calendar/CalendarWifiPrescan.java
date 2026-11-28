// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;

import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.utils.LogUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Asks Android for a fresh Wi-Fi scan shortly before an alarm of a profile with a Wi-Fi
 * condition rings. While the phone sleeps, Android scans rarely, and without fresh results a phone
 * that is only in range of the chosen Wi-Fi (not connected to it) would count as away.
 */
public final class CalendarWifiPrescan extends BroadcastReceiver {

    /** How long before the alarm the scan is asked for. A scan takes a few seconds. */
    static final long LEAD_MS = TimeUnit.MINUTES.toMillis(2);

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context appContext = context.getApplicationContext();
        final PendingResult result = goAsync();
        AppExecutors.getDiskIO().execute(() -> {
            try {
                startScan(appContext);
                scheduleNow(appContext);
            } finally {
                result.finish();
            }
        });
    }

    /**
     * Plans the scan for the next alarm with a Wi-Fi condition, or cancels it if there is none.
     * Reads the database, like the caller that updates the next alarm.
     */
    public static void schedule(Context context) {
        scheduleNow(context.getApplicationContext());
    }

    static void scheduleNow(Context context) {
        try {
            final long next = nextScanTime(context, System.currentTimeMillis());
            final AlarmManager am = context.getSystemService(AlarmManager.class);
            final PendingIntent operation = createPendingIntent(context);
            if (next < 0) {
                am.cancel(operation);
                return;
            }
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation);
            } catch (SecurityException e) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation);
            }
        } catch (Exception e) {
            LogUtils.e("CalendarWifiPrescan: could not plan the Wi-Fi scan", e);
        }
    }

    /**
     * @return when to scan for the next alarm (armed or snoozed) of a profile with a Wi-Fi
     * condition, or -1 if there is none still to come.
     */
    static long nextScanTime(Context context, long now) {
        final ContentResolver cr = context.getContentResolver();
        final List<AlarmInstance> instances = AlarmInstance.getInstances(cr,
            AlarmInstance.ALARM_STATE + "<" + AlarmInstance.FIRED_STATE);
        final Map<Long, Boolean> checksWifi = new HashMap<>();
        long next = -1;
        for (AlarmInstance instance : instances) {
            if (instance.mAlarmId == null) {
                continue;
            }
            final long scanTime = instance.getAlarmTime().getTimeInMillis() - LEAD_MS;
            if (scanTime <= now || (next >= 0 && scanTime >= next)) {
                continue;
            }
            Boolean wifi = checksWifi.get(instance.mAlarmId);
            if (wifi == null) {
                final CalendarProfile profile = CalendarAlarmRules.getProfileForAlarm(cr, instance.mAlarmId);
                wifi = profile != null && profile.wifiCheck && !CalendarWifi.parse(profile.wifiNetworks).isEmpty();
                checksWifi.put(instance.mAlarmId, wifi);
            }
            if (wifi) {
                next = scanTime;
            }
        }
        return next;
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private static void startScan(Context context) {
        if (!CalendarWifi.hasBackgroundPermission(context)) {
            return;
        }
        try {
            final WifiManager wifi = context.getSystemService(WifiManager.class);
            if (wifi != null && wifi.isWifiEnabled()) {
                LogUtils.i("CalendarWifiPrescan: scanning before a calendar alarm: " + wifi.startScan());
            }
        } catch (Exception e) {
            LogUtils.e("CalendarWifiPrescan: could not start a Wi-Fi scan", e);
        }
    }

    private static PendingIntent createPendingIntent(Context context) {
        final Intent intent = new Intent(context, CalendarWifiPrescan.class);
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

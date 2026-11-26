// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.best.deskclock.utils.LogUtils;

import java.util.concurrent.TimeUnit;

/**
 * Checks the calendar about once an hour, also while the phone is idle (Doze), where the regular
 * background checks may be put off for hours. This way an event added shortly before it starts
 * still gets its alarm.
 */
public final class CalendarAlertWakeReceiver extends BroadcastReceiver {

    private static final long INTERVAL_MS = TimeUnit.HOURS.toMillis(1);

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context appContext = context.getApplicationContext();
        final PendingResult result = goAsync();
        CalendarAlertSync.EXECUTOR.execute(() -> {
            try {
                CalendarAlertSync.syncAll(appContext);
                // With all profiles switched off there is nothing to check until one is on again.
                if (CalendarAlertWorker.hasEnabledProfile(appContext)) {
                    schedule(appContext);
                }
            } finally {
                result.finish();
            }
        });
    }

    static void schedule(Context context) {
        try {
            final AlarmManager am = context.getSystemService(AlarmManager.class);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + INTERVAL_MS,
                createPendingIntent(context));
        } catch (Exception e) {
            LogUtils.e("CalendarAlertWakeReceiver: could not schedule the hourly check", e);
        }
    }

    static void cancel(Context context) {
        try {
            context.getSystemService(AlarmManager.class).cancel(createPendingIntent(context));
        } catch (Exception e) {
            LogUtils.e("CalendarAlertWakeReceiver: could not cancel the hourly check", e);
        }
    }

    private static PendingIntent createPendingIntent(Context context) {
        final Intent intent = new Intent(context, CalendarAlertWakeReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

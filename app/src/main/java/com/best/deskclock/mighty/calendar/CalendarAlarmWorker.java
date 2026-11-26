// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.content.Context;
import android.provider.CalendarContract;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.SdkUtils;

import java.util.concurrent.TimeUnit;

/**
 * Runs {@link CalendarAlarmSync} in the background: every 15 minutes, and soon after anything in
 * the system calendar changes.
 */
public final class CalendarAlarmWorker extends Worker {

    private static final String PERIODIC_WORK_NAME = "calendar_alarm_sync_periodic";
    private static final String CHANGE_WORK_NAME = "calendar_alarm_sync_on_change";
    private static final String NOW_WORK_NAME = "calendar_alarm_sync_now";

    private static final String TAG_CHANGE_TRIGGER = "calendar_alarm_change_trigger";

    /**
     * Work scheduled by builds from before the rename to "Calendar Alarm". It names a worker
     * class that no longer exists, so it is cancelled.
     */
    private static final String[] OLD_WORK_NAMES = {
        "calendar_alert_sync_periodic", "calendar_alert_sync_on_change", "calendar_alert_sync_now"
    };

    public CalendarAlarmWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            CalendarAlarmSync.syncAll(getApplicationContext());
            return Result.success();
        } catch (Throwable e) {
            LogUtils.e("CalendarAlarmWorker: sync failed", e);
            return Result.retry();
        } finally {
            if (getTags().contains(TAG_CHANGE_TRIGGER) && hasEnabledProfile(getApplicationContext())) {
                // A content trigger fires once, so watch the calendar again. This work is still
                // running, so the new request is chained after it instead of replacing it.
                scheduleOnCalendarChange(getApplicationContext(), ExistingWorkPolicy.APPEND_OR_REPLACE);
            }
        }
    }

    /**
     * Schedules the periodic sync and the calendar change watch if any profile is on, and
     * cancels them otherwise. Safe to call often.
     */
    public static void ensureScheduled(Context context) {
        final Context appContext = context.getApplicationContext();
        CalendarAlarmSync.EXECUTOR.execute(() -> {
            try {
                cancelOldWork(appContext);
                if (!hasEnabledProfile(appContext)) {
                    // Switched-off profiles have no alarms, so nothing needs to be checked.
                    cancelAll(appContext);
                    return;
                }
                CalendarAlarmWakeReceiver.schedule(appContext);
                final PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    CalendarAlarmWorker.class, 15, TimeUnit.MINUTES).build();
                WorkManager.getInstance(appContext)
                    .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
                scheduleOnCalendarChange(appContext, ExistingWorkPolicy.KEEP);
            } catch (Throwable e) {
                LogUtils.e("CalendarAlarmWorker: failed to schedule", e);
            }
        });
    }

    private static void cancelOldWork(Context context) {
        final WorkManager workManager = WorkManager.getInstance(context);
        for (String name : OLD_WORK_NAMES) {
            workManager.cancelUniqueWork(name);
        }
    }

    static boolean hasEnabledProfile(Context context) {
        for (CalendarProfile profile : CalendarProfile.getProfiles(context.getContentResolver())) {
            if (profile.enabled) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs a sync as soon as possible, for example after a profile was edited.
     */
    public static void syncNow(Context context) {
        final OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CalendarAlarmWorker.class).build();
        WorkManager.getInstance(context.getApplicationContext())
            .enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    private static void scheduleOnCalendarChange(Context context, ExistingWorkPolicy policy) {
        if (!SdkUtils.isAtLeastAndroid7()) {
            // Content URI triggers need Android 7. Older devices rely on the periodic sync.
            return;
        }
        final Constraints constraints = new Constraints.Builder()
            .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
            .setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
            .build();
        final OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CalendarAlarmWorker.class)
            .setConstraints(constraints)
            .addTag(TAG_CHANGE_TRIGGER)
            .build();
        WorkManager.getInstance(context)
            .enqueueUniqueWork(CHANGE_WORK_NAME, policy, request);
    }

    private static void cancelAll(Context context) {
        CalendarAlarmWakeReceiver.cancel(context);
        final WorkManager workManager = WorkManager.getInstance(context);
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME);
        workManager.cancelUniqueWork(CHANGE_WORK_NAME);
    }
}

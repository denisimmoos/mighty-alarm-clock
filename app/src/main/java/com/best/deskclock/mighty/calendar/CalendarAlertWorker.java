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
 * Runs {@link CalendarAlertSync} in the background: every 15 minutes, and soon after anything in
 * the system calendar changes.
 */
public final class CalendarAlertWorker extends Worker {

    private static final String PERIODIC_WORK_NAME = "calendar_alert_sync_periodic";
    private static final String CHANGE_WORK_NAME = "calendar_alert_sync_on_change";
    private static final String NOW_WORK_NAME = "calendar_alert_sync_now";

    private static final String TAG_CHANGE_TRIGGER = "calendar_alert_change_trigger";

    public CalendarAlertWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            CalendarAlertSync.syncAll(getApplicationContext());
            return Result.success();
        } catch (Throwable e) {
            LogUtils.e("CalendarAlertWorker: sync failed", e);
            return Result.retry();
        } finally {
            if (getTags().contains(TAG_CHANGE_TRIGGER)) {
                // A content trigger fires once, so watch the calendar again. This work is still
                // running, so the new request is chained after it instead of replacing it.
                scheduleOnCalendarChange(getApplicationContext(), ExistingWorkPolicy.APPEND_OR_REPLACE);
            }
        }
    }

    /**
     * Schedules the periodic sync and the calendar change watch if any profile exists, and
     * cancels them otherwise. Safe to call often.
     */
    public static void ensureScheduled(Context context) {
        final Context appContext = context.getApplicationContext();
        CalendarAlertSync.EXECUTOR.execute(() -> {
            try {
                if (CalendarProfile.getProfiles(appContext.getContentResolver()).isEmpty()) {
                    cancelAll(appContext);
                    return;
                }
                CalendarAlertWakeReceiver.schedule(appContext);
                final PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    CalendarAlertWorker.class, 15, TimeUnit.MINUTES).build();
                WorkManager.getInstance(appContext)
                    .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
                scheduleOnCalendarChange(appContext, ExistingWorkPolicy.KEEP);
            } catch (Throwable e) {
                LogUtils.e("CalendarAlertWorker: failed to schedule", e);
            }
        });
    }

    /**
     * Runs a sync as soon as possible, for example after a profile was edited.
     */
    public static void syncNow(Context context) {
        final OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CalendarAlertWorker.class).build();
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
        final OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CalendarAlertWorker.class)
            .setConstraints(constraints)
            .addTag(TAG_CHANGE_TRIGGER)
            .build();
        WorkManager.getInstance(context)
            .enqueueUniqueWork(CHANGE_WORK_NAME, policy, request);
    }

    private static void cancelAll(Context context) {
        CalendarAlertWakeReceiver.cancel(context);
        final WorkManager workManager = WorkManager.getInstance(context);
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME);
        workManager.cancelUniqueWork(CHANGE_WORK_NAME);
    }
}

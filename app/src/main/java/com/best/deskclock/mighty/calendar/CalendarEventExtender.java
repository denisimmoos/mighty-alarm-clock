// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.best.deskclock.R;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.CalendarAlarmEvent;
import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.provider.EventLogStore;
import com.best.deskclock.utils.LogUtils;

/**
 * Extends a calendar event when its alarm at the end is snoozed, if the profile asks for that:
 * the event then ends when the snoozed alarm rings again.
 */
public final class CalendarEventExtender {

    private CalendarEventExtender() {
    }

    /**
     * @return {@code true} if the app may change calendar events.
     */
    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
            == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Called when an alarm was snoozed until {@code newEnd}. Does nothing unless it is the alarm at
     * the end of a calendar event whose profile extends events.
     */
    public static void onSnoozed(Context context, AlarmInstance instance, long newEnd) {
        if (instance.mAlarmId == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final long alarmId = instance.mAlarmId;
        CalendarAlarmSync.EXECUTOR.execute(() -> {
            Result result;
            try {
                result = extend(appContext, alarmId, newEnd);
            } catch (Throwable e) {
                LogUtils.e("CalendarEventExtender: could not extend the event of alarm " + alarmId, e);
                result = Result.FAILED;
            }
            report(appContext, result, newEnd);
        });
    }

    /** What happened when an alarm was snoozed. */
    enum Result {
        /** Not the alarm at the end of an event that should be extended. */
        NOT_APPLICABLE,
        EXTENDED,
        NO_PERMISSION,
        FAILED
    }

    /**
     * Tells the user whether the event was extended, with a short message and in the event log,
     * since this happens in the background after the alarm screen is gone.
     */
    private static void report(Context context, Result result, long newEnd) {
        final String message;
        final String type;
        switch (result) {
            case EXTENDED -> {
                message = context.getString(R.string.calendar_alarm_extended_until,
                    android.text.format.DateFormat.getTimeFormat(context).format(new java.util.Date(newEnd)));
                type = "CALENDAR_EVENT_EXTENDED";
            }
            case NO_PERMISSION -> {
                message = context.getString(R.string.calendar_alarm_extend_no_permission);
                type = "CALENDAR_EVENT_NOT_EXTENDED";
            }
            case FAILED -> {
                message = context.getString(R.string.calendar_alarm_extend_failed);
                type = "CALENDAR_EVENT_NOT_EXTENDED";
            }
            default -> {
                return;
            }
        }
        EventLogStore.logEvent(context.getContentResolver(), type, null, null, message);
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    /**
     * @return whether the event was extended.
     */
    static Result extend(Context context, long alarmId, long newEnd) {
        final ContentResolver cr = context.getContentResolver();
        final CalendarAlarmEvent event = CalendarAlarmEvent.getEventForAlarm(cr, alarmId);
        if (event == null || event.endAlarmId != alarmId || event.end <= 0 || newEnd <= event.end) {
            return Result.NOT_APPLICABLE;
        }
        final CalendarProfile profile = CalendarProfile.getProfile(cr, event.profileId);
        if (profile == null || !profile.extendOnSnooze) {
            return Result.NOT_APPLICABLE;
        }
        if (!hasPermission(context)) {
            LogUtils.w("CalendarEventExtender: no permission to change the calendar");
            return Result.NO_PERMISSION;
        }

        final Uri eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId);
        final boolean recurring;
        try (Cursor c = cr.query(eventUri, new String[]{CalendarContract.Events.RRULE,
            CalendarContract.Events.RDATE}, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                LogUtils.w("CalendarEventExtender: event " + event.eventId + " not found");
                return Result.FAILED;
            }
            recurring = !TextUtils.isEmpty(c.getString(0)) || !TextUtils.isEmpty(c.getString(1));
        }

        long changedId = event.eventId;
        if (recurring) {
            // Only this occurrence changes: it becomes an exception with its own event id.
            final ContentValues values = new ContentValues();
            values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, event.begin);
            values.put(CalendarContract.Events.DTSTART, event.begin);
            values.put(CalendarContract.Events.DTEND, newEnd);
            final Uri exception = cr.insert(Uri.withAppendedPath(CalendarContract.Events.CONTENT_EXCEPTION_URI,
                String.valueOf(event.eventId)), values);
            final long newId = exception == null ? -1 : ContentUris.parseId(exception);
            if (newId < 0) {
                LogUtils.w("CalendarEventExtender: could not change occurrence of event " + event.eventId);
                return Result.FAILED;
            }
            // Keep the snoozed alarm linked to the changed occurrence, so the next check
            // doesn't add a second alarm for it.
            event.eventId = newId;
            event.eventKey = CalendarAlarmEvent.buildKey(newId, event.begin);
            changedId = newId;
            event.save(cr);
        }
        // Set the end on the event itself, also on a new occurrence: the calendar may have given
        // it the length of the repeating event. An event can't have both an end and a length, so
        // the length is cleared; otherwise the calendar refuses the change for events that store
        // a length instead of an end.
        final Uri changedUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, changedId);
        final ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.DTEND, newEnd);
        values.putNull(CalendarContract.Events.DURATION);
        if (cr.update(changedUri, values, null, null) <= 0) {
            LogUtils.w("CalendarEventExtender: could not change event " + changedId);
            return Result.FAILED;
        }
        // Check that the calendar took the new end.
        try (Cursor c = cr.query(changedUri, new String[]{CalendarContract.Events.DTEND}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0) && c.getLong(0) != newEnd) {
                LogUtils.w("CalendarEventExtender: event " + changedId + " still ends at " + c.getLong(0));
                return Result.FAILED;
            }
        }
        event.end = newEnd;
        event.save(cr);
        LogUtils.i("CalendarEventExtender: event " + event.eventId + " now ends at " + newEnd);
        return Result.EXTENDED;
    }
}

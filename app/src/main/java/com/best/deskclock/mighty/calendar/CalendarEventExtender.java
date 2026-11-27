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
import android.provider.CalendarContract;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.CalendarAlarmEvent;
import com.best.deskclock.provider.CalendarProfile;
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
            try {
                extend(appContext, alarmId, newEnd);
            } catch (Throwable e) {
                LogUtils.e("CalendarEventExtender: could not extend the event of alarm " + alarmId, e);
            }
        });
    }

    /**
     * @return {@code true} if the event was extended.
     */
    static boolean extend(Context context, long alarmId, long newEnd) {
        final ContentResolver cr = context.getContentResolver();
        final CalendarAlarmEvent event = CalendarAlarmEvent.getEventForAlarm(cr, alarmId);
        if (event == null || event.endAlarmId != alarmId || event.end <= 0 || newEnd <= event.end) {
            return false;
        }
        final CalendarProfile profile = CalendarProfile.getProfile(cr, event.profileId);
        if (profile == null || !profile.extendOnSnooze) {
            return false;
        }
        if (!hasPermission(context)) {
            LogUtils.w("CalendarEventExtender: no permission to change the calendar");
            return false;
        }

        final Uri eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId);
        final boolean recurring;
        try (Cursor c = cr.query(eventUri, new String[]{CalendarContract.Events.RRULE,
            CalendarContract.Events.RDATE}, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                LogUtils.w("CalendarEventExtender: event " + event.eventId + " not found");
                return false;
            }
            recurring = !TextUtils.isEmpty(c.getString(0)) || !TextUtils.isEmpty(c.getString(1));
        }

        if (recurring) {
            // Only this occurrence changes: it becomes an exception with its own event id.
            final ContentValues values = new ContentValues();
            values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, event.begin);
            values.put(CalendarContract.Events.DTSTART, event.begin);
            values.put(CalendarContract.Events.DTEND, newEnd);
            final Uri exception = cr.insert(Uri.withAppendedPath(CalendarContract.Events.CONTENT_EXCEPTION_URI,
                String.valueOf(event.eventId)), values);
            if (exception == null) {
                LogUtils.w("CalendarEventExtender: could not change occurrence of event " + event.eventId);
                return false;
            }
            final long newId = ContentUris.parseId(exception);
            if (newId >= 0) {
                // Keep the snoozed alarm linked to the changed occurrence, so the next check
                // doesn't add a second alarm for it.
                event.eventId = newId;
                event.eventKey = CalendarAlarmEvent.buildKey(newId, event.begin);
            }
        } else {
            final ContentValues values = new ContentValues();
            values.put(CalendarContract.Events.DTEND, newEnd);
            if (cr.update(eventUri, values, null, null) <= 0) {
                LogUtils.w("CalendarEventExtender: could not change event " + event.eventId);
                return false;
            }
        }
        event.end = newEnd;
        event.save(cr);
        LogUtils.i("CalendarEventExtender: event " + event.eventId + " now ends at " + newEnd);
        return true;
    }
}

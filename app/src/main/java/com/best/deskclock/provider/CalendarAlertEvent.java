// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.provider;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One upcoming calendar event occurrence matched by a {@link CalendarProfile}, linked to the
 * alarm that was created for it.
 */
public final class CalendarAlertEvent implements ClockContract.CalendarAlertEventsColumns {

    public static final long INVALID_ID = -1;

    /**
     * Value of {@link #alarmId} once the event's alarm was used up: it rang and was dismissed, or
     * was dismissed early. The sync then does not create the alarm again.
     */
    public static final long ALARM_CONSUMED = -2;

    private static final String[] QUERY_COLUMNS = {
        _ID,
        PROFILE_ID,
        EVENT_KEY,
        EVENT_ID,
        TITLE,
        BEGIN,
        USER_ENABLED,
        ALARM_ID
    };

    private static final int ID_INDEX = 0;
    private static final int PROFILE_ID_INDEX = 1;
    private static final int EVENT_KEY_INDEX = 2;
    private static final int EVENT_ID_INDEX = 3;
    private static final int TITLE_INDEX = 4;
    private static final int BEGIN_INDEX = 5;
    private static final int USER_ENABLED_INDEX = 6;
    private static final int ALARM_ID_INDEX = 7;

    public long id;
    public long profileId;
    public String eventKey;
    public long eventId;
    public String title;
    public long begin;
    public boolean userEnabled;
    public long alarmId;

    public CalendarAlertEvent(long profileId, long eventId, long begin, String title) {
        this.id = INVALID_ID;
        this.profileId = profileId;
        this.eventId = eventId;
        this.begin = begin;
        this.title = title;
        this.eventKey = buildKey(eventId, begin);
        this.userEnabled = true;
        this.alarmId = Alarm.INVALID_ID;
    }

    public CalendarAlertEvent(Cursor c) {
        id = c.getLong(ID_INDEX);
        profileId = c.getLong(PROFILE_ID_INDEX);
        eventKey = c.getString(EVENT_KEY_INDEX);
        eventId = c.getLong(EVENT_ID_INDEX);
        title = c.getString(TITLE_INDEX);
        begin = c.getLong(BEGIN_INDEX);
        userEnabled = c.getInt(USER_ENABLED_INDEX) == 1;
        alarmId = c.getLong(ALARM_ID_INDEX);
    }

    /**
     * Builds the key of an event occurrence. Recurring events share one event id, so the start
     * time is part of the key. When an event is moved, it gets a new key and a new alarm.
     */
    public static String buildKey(long eventId, long begin) {
        return eventId + "@" + begin;
    }

    public ContentValues createContentValues() {
        final ContentValues values = new ContentValues(QUERY_COLUMNS.length);
        if (id != INVALID_ID) {
            values.put(_ID, id);
        }
        values.put(PROFILE_ID, profileId);
        values.put(EVENT_KEY, eventKey);
        values.put(EVENT_ID, eventId);
        values.put(TITLE, title == null ? "" : title);
        values.put(BEGIN, begin);
        values.put(USER_ENABLED, userEnabled ? 1 : 0);
        values.put(ALARM_ID, alarmId);
        return values;
    }

    public static Uri getContentUri(long id) {
        return ContentUris.withAppendedId(CONTENT_URI, id);
    }

    @Nullable
    public static CalendarAlertEvent getEvent(ContentResolver cr, long id) {
        try (Cursor cursor = cr.query(getContentUri(id), QUERY_COLUMNS, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return new CalendarAlertEvent(cursor);
            }
        }
        return null;
    }

    /**
     * @return the events of the given profile, earliest first.
     */
    public static List<CalendarAlertEvent> getEventsForProfile(ContentResolver cr, long profileId) {
        return query(cr, PROFILE_ID + "=?", new String[]{String.valueOf(profileId)});
    }

    public static List<CalendarAlertEvent> getAllEvents(ContentResolver cr) {
        return query(cr, null, null);
    }

    /**
     * @return the event linked to the given alarm, or {@code null} if the alarm was not created
     * by a calendar profile.
     */
    @Nullable
    public static CalendarAlertEvent getEventForAlarm(ContentResolver cr, long alarmId) {
        if (alarmId == Alarm.INVALID_ID) {
            return null;
        }
        final List<CalendarAlertEvent> events = query(cr, ALARM_ID + "=?", new String[]{String.valueOf(alarmId)});
        return events.isEmpty() ? null : events.get(0);
    }

    private static List<CalendarAlertEvent> query(ContentResolver cr, String selection, String[] args) {
        final List<CalendarAlertEvent> result = new ArrayList<>();
        try (Cursor cursor = cr.query(CONTENT_URI, QUERY_COLUMNS, selection, args, BEGIN + " ASC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    result.add(new CalendarAlertEvent(cursor));
                }
            }
        }
        return result;
    }

    /**
     * Inserts the row, or replaces the existing row with the same profile and event key.
     */
    public void save(ContentResolver cr) {
        if (id == INVALID_ID) {
            final Uri uri = cr.insert(CONTENT_URI, createContentValues());
            id = ContentUris.parseId(uri);
        } else {
            cr.update(getContentUri(id), createContentValues(), null, null);
        }
    }

    public void delete(ContentResolver cr) {
        if (id != INVALID_ID) {
            cr.delete(getContentUri(id), null, null);
        }
    }

    public static void deleteEventsForProfile(ContentResolver cr, long profileId) {
        cr.delete(CONTENT_URI, PROFILE_ID + "=?", new String[]{String.valueOf(profileId)});
    }

    /**
     * SQL selection that leaves out alarms created by calendar profiles. Use it on queries of the
     * alarms table, or of the alarms joined with instances, where calendar alarms must not show.
     */
    public static String buildExcludeCalendarAlarmsSelection(String alarmIdColumn) {
        return alarmIdColumn + " NOT IN (SELECT " + ALARM_ID + " FROM "
            + ClockDatabaseHelper.CALENDAR_ALERT_EVENTS_TABLE_NAME + ")";
    }

    @NonNull
    @Override
    public String toString() {
        return "CalendarAlertEvent{id=" + id + ", profileId=" + profileId + ", key='" + eventKey
            + "', begin=" + begin + ", userEnabled=" + userEnabled + ", alarmId=" + alarmId + "}";
    }
}

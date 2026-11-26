// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.best.deskclock.utils.LogUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads calendars and events from the system calendar provider. Every Google account that syncs
 * its calendars on the device shows up here, so no Google sign-in is needed in the app.
 */
public final class DeviceCalendars {

    private DeviceCalendars() {
    }

    /**
     * A calendar from the system calendar provider.
     */
    public static final class CalendarInfo {
        public final long id;
        public final String accountName;
        public final String accountType;
        public final String displayName;
        public final int color;

        CalendarInfo(long id, String accountName, String accountType, String displayName, int color) {
            this.id = id;
            this.accountName = accountName == null ? "" : accountName;
            this.accountType = accountType == null ? "" : accountType;
            this.displayName = displayName == null ? "" : displayName;
            this.color = color;
        }

        @NonNull
        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * One occurrence of a calendar event.
     */
    public static final class EventInstance {
        public final long eventId;
        public final long begin;
        public final String title;

        EventInstance(long eventId, long begin, String title) {
            this.eventId = eventId;
            this.begin = begin;
            this.title = title == null ? "" : title;
        }
    }

    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @return every calendar on the device, sorted by account and name. Empty without permission.
     */
    public static List<CalendarInfo> getCalendars(Context context) {
        final List<CalendarInfo> result = new ArrayList<>();
        if (!hasPermission(context)) {
            return result;
        }

        final String[] projection = {
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR
        };
        final String sort = CalendarContract.Calendars.ACCOUNT_NAME + " ASC, "
            + CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC";

        try (Cursor c = context.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
            projection, null, null, sort)) {
            if (c != null) {
                while (c.moveToNext()) {
                    result.add(new CalendarInfo(c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getInt(4)));
                }
            }
        } catch (SecurityException e) {
            LogUtils.e("DeviceCalendars: no permission to read calendars", e);
        }
        return result;
    }

    /**
     * @return the calendar with the given id, or {@code null} if it no longer exists.
     */
    public static CalendarInfo getCalendar(Context context, long calendarId) {
        for (CalendarInfo info : getCalendars(context)) {
            if (info.id == calendarId) {
                return info;
            }
        }
        return null;
    }

    /**
     * @return the timed (not all-day), not cancelled event occurrences of the calendar that start
     * strictly after {@code fromMillis} and no later than {@code toMillis}, earliest first.
     */
    public static List<EventInstance> getEventInstances(Context context, long calendarId,
                                                        long fromMillis, long toMillis) {
        final List<EventInstance> result = new ArrayList<>();
        if (!hasPermission(context) || calendarId < 0) {
            return result;
        }

        final Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, fromMillis);
        ContentUris.appendId(builder, toMillis);

        final String[] projection = {
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE
        };
        final String selection = CalendarContract.Instances.CALENDAR_ID + "=? AND "
            + CalendarContract.Instances.ALL_DAY + "=0 AND ("
            + CalendarContract.Instances.STATUS + " IS NULL OR "
            + CalendarContract.Instances.STATUS + "!=" + CalendarContract.Instances.STATUS_CANCELED + ")";
        final String[] args = {String.valueOf(calendarId)};

        final ContentResolver cr = context.getContentResolver();
        try (Cursor c = cr.query(builder.build(), projection, selection, args,
            CalendarContract.Instances.BEGIN + " ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    final long begin = c.getLong(1);
                    if (begin > fromMillis && begin <= toMillis) {
                        result.add(new EventInstance(c.getLong(0), begin, c.getString(2)));
                    }
                }
            }
        } catch (SecurityException e) {
            LogUtils.e("DeviceCalendars: no permission to read events", e);
        }
        return dropDeletedEvents(cr, result);
    }

    /**
     * Some calendar apps move an event by deleting it and adding a new one. Until the calendar
     * syncs, the old event is only marked deleted and may still show up as an instance.
     */
    private static List<EventInstance> dropDeletedEvents(ContentResolver cr, List<EventInstance> instances) {
        if (instances.isEmpty()) {
            return instances;
        }
        final java.util.Set<Long> ids = new java.util.HashSet<>();
        for (EventInstance instance : instances) {
            ids.add(instance.eventId);
        }
        final StringBuilder in = new StringBuilder();
        for (Long id : ids) {
            in.append(in.length() == 0 ? "" : ",").append(id);
        }
        final java.util.Set<Long> deleted = new java.util.HashSet<>();
        try (Cursor c = cr.query(CalendarContract.Events.CONTENT_URI,
            new String[] {CalendarContract.Events._ID},
            CalendarContract.Events._ID + " IN (" + in + ") AND " + CalendarContract.Events.DELETED + "=1",
            null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    deleted.add(c.getLong(0));
                }
            }
        } catch (Exception e) {
            LogUtils.w("DeviceCalendars: could not check for deleted events: " + e);
            return instances;
        }
        if (deleted.isEmpty()) {
            return instances;
        }
        final List<EventInstance> kept = new ArrayList<>();
        for (EventInstance instance : instances) {
            if (!deleted.contains(instance.eventId)) {
                kept.add(instance);
            }
        }
        return kept;
    }
}

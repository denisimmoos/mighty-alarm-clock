// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
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
        /** When the alarm should ring: the start, or the chosen time of day for all-day events. */
        public final long begin;
        public final String title;
        /** One of the {@link CalendarContract.Attendees} ATTENDEE_STATUS values. */
        public final int selfAttendeeStatus;
        /** When the event ends, 0 if unknown or for all-day events. */
        public final long end;

        EventInstance(long eventId, long begin, String title) {
            this(eventId, begin, title, CalendarContract.Attendees.ATTENDEE_STATUS_NONE, 0);
        }

        EventInstance(long eventId, long begin, String title, int selfAttendeeStatus) {
            this(eventId, begin, title, selfAttendeeStatus, 0);
        }

        EventInstance(long eventId, long begin, String title, int selfAttendeeStatus, long end) {
            this.eventId = eventId;
            this.begin = begin;
            this.title = title == null ? "" : title;
            this.selfAttendeeStatus = selfAttendeeStatus;
            this.end = end;
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
        final List<CalendarInfo> calendars = readCalendars(context);
        return calendars == null ? new ArrayList<>() : calendars;
    }

    /**
     * @return every calendar on the device, or {@code null} if they could not be read.
     */
    @androidx.annotation.Nullable
    private static List<CalendarInfo> readCalendars(Context context) {
        final List<CalendarInfo> result = new ArrayList<>();
        if (!hasPermission(context)) {
            return null;
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
            if (c == null) {
                return null;
            }
            {
                while (c.moveToNext()) {
                    result.add(new CalendarInfo(c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getInt(4)));
                }
            }
        } catch (Exception e) {
            LogUtils.e("DeviceCalendars: could not read calendars", e);
            return null;
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
     * @return the not cancelled event occurrences of the calendar whose alarm time is strictly
     * after {@code fromMillis} and no later than {@code toMillis}, earliest first. All-day events
     * are included only if {@code allDayMinutes} is a time of day, and then ring at that time.
     */
    public static List<EventInstance> getEventInstances(Context context, long calendarId,
                                                        long fromMillis, long toMillis) {
        return getEventInstances(context, calendarId, fromMillis, toMillis, -1);
    }

    @androidx.annotation.Nullable
    public static List<EventInstance> getEventInstances(Context context, long calendarId,
                                                        long fromMillis, long toMillis, int allDayMinutes) {
        final List<EventInstance> result = new ArrayList<>();
        if (!hasPermission(context) || calendarId < 0) {
            return result;
        }

        final boolean includeAllDay = allDayMinutes >= 0;
        // All-day events start at midnight UTC, which can be before now even if their alarm isn't.
        final long queryFrom = includeAllDay ? fromMillis - DAY_MS - DAY_MS : fromMillis;
        final Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, queryFrom);
        ContentUris.appendId(builder, toMillis + (includeAllDay ? DAY_MS : 0));

        final String[] projection = {
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.END
        };
        final String selection = CalendarContract.Instances.CALENDAR_ID + "=? AND "
            + (includeAllDay ? "" : CalendarContract.Instances.ALL_DAY + "=0 AND ")
            + "(" + CalendarContract.Instances.STATUS + " IS NULL OR "
            + CalendarContract.Instances.STATUS + "!=" + CalendarContract.Instances.STATUS_CANCELED + ")";
        final String[] args = {String.valueOf(calendarId)};

        final ContentResolver cr = context.getContentResolver();
        try (Cursor c = cr.query(builder.build(), projection, selection, args,
            CalendarContract.Instances.BEGIN + " ASC")) {
            if (c == null) {
                // The calendar could not be read (for example right after a restart). Unknown is
                // not the same as "no events", so the caller keeps what is scheduled.
                return null;
            }
            {
                final int allDayIndex = c.getColumnIndex(CalendarContract.Instances.ALL_DAY);
                final int statusIndex = c.getColumnIndex(CalendarContract.Instances.SELF_ATTENDEE_STATUS);
                final int endIndex = c.getColumnIndex(CalendarContract.Instances.END);
                while (c.moveToNext()) {
                    final boolean allDay = allDayIndex >= 0 && c.getInt(allDayIndex) == 1;
                    if (allDay && !includeAllDay) {
                        continue;
                    }
                    final long begin = allDay ? allDayAlarmTime(c.getLong(1), allDayMinutes) : c.getLong(1);
                    // All-day events have no end time to ring at.
                    final long end = allDay || endIndex < 0 || c.isNull(endIndex) ? 0 : c.getLong(endIndex);
                    // Upcoming events, and events already running, which can still ring at their end.
                    if ((begin > fromMillis || end > fromMillis) && begin <= toMillis) {
                        final int status = statusIndex >= 0 && !c.isNull(statusIndex)
                            ? c.getInt(statusIndex) : CalendarContract.Attendees.ATTENDEE_STATUS_NONE;
                        result.add(new EventInstance(c.getLong(0), begin, c.getString(2), status, end));
                    }
                }
            }
        } catch (Exception e) {
            LogUtils.e("DeviceCalendars: could not read events", e);
            return null;
        }
        java.util.Collections.sort(result, (x, y) -> Long.compare(x.begin, y.begin));
        return dropDeletedEvents(cr, result);
    }

    /**
     * Asks Android to sync the calendar's account (for example Google) with the phone right away,
     * in both directions: changes made here reach Google, and changes made by others, for example
     * in a shared calendar, reach the phone without waiting for the next regular sync.
     *
     * @return {@code true} if a sync was requested.
     */
    public static boolean requestSync(Context context, long calendarId) {
        final ContentResolver cr = context.getContentResolver();
        if (!hasPermission(context)) {
            return false;
        }
        String name = null;
        String type = null;
        try (Cursor c = cr.query(CalendarContract.Calendars.CONTENT_URI, new String[]{
            CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (c.getLong(0) == calendarId) {
                    name = c.getString(1);
                    type = c.getString(2);
                    break;
                }
            }
        } catch (RuntimeException e) {
            LogUtils.w("DeviceCalendars: could not read the calendar's account: " + e);
            return false;
        }
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(type)
            || CalendarContract.ACCOUNT_TYPE_LOCAL.equals(type)) {
            // A calendar only on this phone has nothing to sync.
            return false;
        }
        final android.os.Bundle extras = new android.os.Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        try {
            ContentResolver.requestSync(new android.accounts.Account(name, type), CalendarContract.AUTHORITY, extras);
        } catch (RuntimeException e) {
            LogUtils.w("DeviceCalendars: could not request a calendar sync: " + e);
            return false;
        }
        return true;
    }

    /**
     * Asks Android to sync the calendars of all switched-on profiles right away, so events that
     * others added or changed in a shared calendar show up soon. Each account is synced once.
     */
    public static void requestSyncForProfiles(Context context) {
        final java.util.Set<Long> done = new java.util.HashSet<>();
        for (com.best.deskclock.provider.CalendarProfile profile
                : com.best.deskclock.provider.CalendarProfile.getProfiles(context.getContentResolver())) {
            if (profile.enabled && profile.calendarId >= 0 && done.add(profile.calendarId)) {
                requestSync(context, profile.calendarId);
            }
        }
    }

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /**
     * All-day events start at midnight UTC of their day. The alarm rings on that day at the given
     * local time of day.
     */
    static long allDayAlarmTime(long utcMidnight, int minutesOfDay) {
        final java.util.Calendar utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(utcMidnight);
        final java.util.Calendar local = java.util.Calendar.getInstance();
        local.clear();
        local.set(utc.get(java.util.Calendar.YEAR), utc.get(java.util.Calendar.MONTH),
            utc.get(java.util.Calendar.DAY_OF_MONTH), minutesOfDay / 60, minutesOfDay % 60, 0);
        return local.getTimeInMillis();
    }

    /**
     * Finds the calendar a profile watches. Android may give the number of a removed calendar to a
     * new one, and a restored backup may carry the number of another calendar of the same account,
     * so the number alone is not enough.
     *
     * @return the calendar, or {@code null} if no calendar can be read right now; a calendar with
     * id {@code -1} if the profile's calendar is not on this phone.
     */
    @androidx.annotation.Nullable
    public static CalendarInfo findProfileCalendar(Context context, long calendarId, String accountName,
                                                   String calendarName) {
        final List<CalendarInfo> calendars = readCalendars(context);
        if (calendars == null) {
            return null;
        }
        CalendarInfo byId = null;
        for (CalendarInfo info : calendars) {
            if (info.id == calendarId
                && (TextUtils.isEmpty(accountName) || accountName.equals(info.accountName))) {
                byId = info;
            }
        }
        if (byId != null && (TextUtils.isEmpty(calendarName) || calendarName.equals(byId.displayName))) {
            return byId;
        }
        if (!TextUtils.isEmpty(calendarName)) {
            for (CalendarInfo info : calendars) {
                if (calendarName.equals(info.displayName)
                    && (TextUtils.isEmpty(accountName) || accountName.equals(info.accountName))) {
                    return info;
                }
            }
        }
        // Same account and number, but the calendar was renamed.
        return byId != null ? byId : MISSING_CALENDAR;
    }

    private static final CalendarInfo MISSING_CALENDAR = new CalendarInfo(-1, "", "", "", 0);

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

    /**
     * Description and place of a calendar event, as currently stored in the calendar.
     */
    public record EventDetails(String description, String location) {
    }

    /**
     * @return the event's description and location (empty strings when not set), or {@code null}
     * if the event can't be read. Does a provider query, so call it off the main thread.
     */
    @androidx.annotation.Nullable
    public static EventDetails getEventDetails(Context context, long eventId) {
        if (eventId < 0 || !hasPermission(context)) {
            return null;
        }
        final String[] projection = {CalendarContract.Events.DESCRIPTION, CalendarContract.Events.EVENT_LOCATION};
        try (Cursor c = context.getContentResolver().query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), projection, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            final String description = c.isNull(0) ? "" : c.getString(0).trim();
            final String location = c.isNull(1) ? "" : c.getString(1).trim();
            return new EventDetails(description, location);
        } catch (Exception e) {
            LogUtils.e("DeviceCalendars: could not read event details", e);
            return null;
        }
    }
}

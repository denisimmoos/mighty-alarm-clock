// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.CalendarContract;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.CalendarAlarmEvent;
import com.best.deskclock.provider.CalendarProfile;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CalendarAlarmSyncTest {

    private static final long CALENDAR_ID = 7;
    private static final long OTHER_CALENDAR_ID = 8;
    private static final long EVENT_LENGTH_MS = TimeUnit.HOURS.toMillis(1);

    /** Events served by the fake calendar provider. */
    private static final List<FakeEvent> EVENTS = new ArrayList<>();

    private Application mContext;
    private ContentResolver mCr;
    private CalendarProfile mProfile;

    private record FakeEvent(long id, long calendarId, long begin, String title, int allDay, int status) {
        FakeEvent(long id, long calendarId, long begin, String title) {
            this(id, calendarId, begin, title, 0, CalendarContract.Attendees.ATTENDEE_STATUS_NONE);
        }

    }

    /**
     * Stands in for the system calendar provider. It only answers instance queries.
     */
    public static final class FakeCalendarProvider extends ContentProvider {
        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] args, String sort) {
            if (uri.getPathSegments().get(0).equals("calendars")) {
                final MatrixCursor calendars = new MatrixCursor(projection);
                for (java.util.Map.Entry<Long, String> entry : CALENDARS.entrySet()) {
                    calendars.addRow(java.util.Arrays.copyOf(new Object[]{entry.getKey(), entry.getValue(),
                        "com.google", "Calendar " + entry.getKey(), 0}, projection.length));
                }
                return calendars;
            }
            if (uri.getPathSegments().get(0).equals("events") && uri.getPathSegments().size() == 2
                && CalendarContract.Events.RRULE.equals(projection[0])) {
                // Whether the event repeats.
                final MatrixCursor rule = new MatrixCursor(projection);
                final long id = Long.parseLong(uri.getLastPathSegment());
                rule.addRow(new Object[]{RECURRING.contains(id) ? "FREQ=WEEKLY" : null, null});
                return rule;
            }
            if (uri.getPathSegments().get(0).equals("events") && uri.getPathSegments().size() == 2
                && CalendarContract.Events.DTEND.equals(projection[0])) {
                // The stored end of one event.
                final MatrixCursor end = new MatrixCursor(projection);
                final Object[] row = new Object[projection.length];
                row[0] = DTENDS.get(Long.parseLong(uri.getLastPathSegment()));
                end.addRow(row);
                return end;
            }
            if (uri.getPathSegments().get(0).equals("events") && uri.getPathSegments().size() == 2) {
                // One event: its description and location.
                final MatrixCursor details = new MatrixCursor(projection);
                final String[] row = EVENT_DETAILS.get(Long.parseLong(uri.getLastPathSegment()));
                if (row != null) {
                    details.addRow(new Object[]{row[0], row[1]});
                }
                return details;
            }
            if (uri.getPathSegments().get(0).equals("events")) {
                final MatrixCursor deleted = new MatrixCursor(projection);
                for (Long id : DELETED_IDS) {
                    deleted.addRow(new Object[]{id});
                }
                return deleted;
            }
            final List<String> segments = uri.getPathSegments();
            final long from = Long.parseLong(segments.get(segments.size() - 2));
            final long to = Long.parseLong(segments.get(segments.size() - 1));
            final long calendarId = Long.parseLong(args[0]);
            final MatrixCursor cursor = new MatrixCursor(projection);
            for (FakeEvent event : EVENTS) {
                // Like the real provider: every instance that overlaps the window.
                if (event.calendarId() == calendarId && endOf(event) > from && event.begin() <= to) {
                    final Object[] row = new Object[projection.length];
                    for (int i = 0; i < projection.length; i++) {
                        switch (projection[i]) {
                            case CalendarContract.Instances.EVENT_ID -> row[i] = event.id();
                            case CalendarContract.Instances.BEGIN -> row[i] = event.begin();
                            // Every fake event lasts one hour.
                            case CalendarContract.Instances.END -> row[i] = endOf(event);
                            case CalendarContract.Instances.TITLE -> row[i] = event.title();
                            case CalendarContract.Instances.ALL_DAY -> row[i] = event.allDay();
                            case CalendarContract.Instances.SELF_ATTENDEE_STATUS -> row[i] = event.status();
                            default -> row[i] = null;
                        }
                    }
                    cursor.addRow(row);
                }
            }
            return cursor;
        }

        @Override
        public String getType(@NonNull Uri uri) {
            return null;
        }

        @Override
        public Uri insert(@NonNull Uri uri, ContentValues values) {
            // A changed occurrence of a repeating event becomes a new event.
            CHANGES.add(uri + " " + values);
            // Like some calendars, the new occurrence gets the length of the repeating event, and
            // stores it as a length.
            if (values.containsKey(CalendarContract.Events.DTSTART)) {
                DTENDS.put(EXCEPTION_ID, values.getAsLong(CalendarContract.Events.DTSTART) + EVENT_LENGTH_MS);
                WITH_DURATION.add(EXCEPTION_ID);
            }
            return Uri.parse("content://" + CalendarContract.AUTHORITY + "/events/" + EXCEPTION_ID);
        }

        @Override
        public int delete(@NonNull Uri uri, String selection, String[] args) {
            CHANGES.add("delete " + uri);
            return 1;
        }

        @Override
        public int update(@NonNull Uri uri, ContentValues values, String selection, String[] args) {
            final long id = Long.parseLong(uri.getLastPathSegment());
            // Like the real calendar: an event can't have both an end and a length.
            if (values.containsKey(CalendarContract.Events.DTEND) && WITH_DURATION.contains(id)
                && !(values.containsKey(CalendarContract.Events.DURATION)
                    && values.get(CalendarContract.Events.DURATION) == null)) {
                throw new IllegalArgumentException("Cannot have both DTEND and DURATION in an event");
            }
            if (REFUSED.contains(id)) {
                return 0;
            }
            CHANGES.add(uri + " " + values);
            if (values.containsKey(CalendarContract.Events.DTEND)) {
                DTENDS.put(id, values.getAsLong(CalendarContract.Events.DTEND));
                WITH_DURATION.remove(id);
            }
            return 1;
        }
    }

    @Before
    public void setUp() {
        clearEvents();
        mContext = ApplicationProvider.getApplicationContext();
        mCr = mContext.getContentResolver();
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.READ_CALENDAR);
        Robolectric.setupContentProvider(FakeCalendarProvider.class, CalendarContract.AUTHORITY);

        mProfile = new CalendarProfile();
        mProfile.name = "Work";
        mProfile.calendarId = CALENDAR_ID;
        mProfile.calendarName = "Work";
        mProfile.accountName = "someone@example.com";
        mProfile.titleRegex = "^Alarm:";
        mProfile.ringtone = Uri.parse("content://media/internal/audio/media/1");
        mProfile.autoSilenceDuration = CalendarProfile.RING_UNTIL_DISMISSED;
        mProfile.snoozeDuration = 5;
        mProfile.maxSnoozeCount = 2;
        mProfile.addProfile(mCr);
    }

    static final java.util.Set<Long> DELETED_IDS = new java.util.HashSet<>();

    /** Repeating events, and what was written to the calendar. */
    static final java.util.Set<Long> RECURRING = new java.util.HashSet<>();
    static final List<String> CHANGES = new ArrayList<>();
    static final long EXCEPTION_ID = 99;
    /** Events that store a length instead of an end, by event id. */
    static final java.util.Set<Long> WITH_DURATION = new java.util.HashSet<>();
    /** The end stored by the fake calendar for changed events, by event id. */
    static final java.util.Map<Long, Long> DTENDS = new java.util.HashMap<>();
    /** Events the fake calendar won't change, by event id. */
    static final java.util.Set<Long> REFUSED = new java.util.HashSet<>();
    /** End times that differ from the usual one hour, by event id. */
    static final java.util.Map<Long, Long> ENDS = new java.util.HashMap<>();

    static long endOf(FakeEvent event) {
        final Long end = ENDS.get(event.id());
        return end != null ? end : event.begin() + EVENT_LENGTH_MS;
    }

    static final java.util.Map<Long, String> CALENDARS = new java.util.TreeMap<>();

    /** Description and location by event id. */
    static final java.util.Map<Long, String[]> EVENT_DETAILS = new java.util.HashMap<>();

    static void clearEvents() {
        EVENTS.clear();
        EVENT_DETAILS.clear();
        DELETED_IDS.clear();
        RECURRING.clear();
        ENDS.clear();
        CHANGES.clear();
        WITH_DURATION.clear();
        DTENDS.clear();
        REFUSED.clear();
        CALENDARS.clear();
        CALENDARS.put(CALENDAR_ID, "someone@example.com");
        CALENDARS.put(OTHER_CALENDAR_ID, "someone@example.com");
    }

    private static long inHours(int hours) {
        final Calendar c = Calendar.getInstance();
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.HOUR_OF_DAY, hours);
        return c.getTimeInMillis();
    }

    static void addEvent(long id, long calendarId, long begin, String title) {
        EVENTS.add(new FakeEvent(id, calendarId, begin, title));
    }

    private List<CalendarAlarmEvent> events() {
        return CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id);
    }

    @Test
    public void matchingEventsBecomeAlarmsAtTheirStartTime() {
        final long begin = inHours(3);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "Alarm: Dentist"));
        EVENTS.add(new FakeEvent(2, CALENDAR_ID, inHours(4), "Team meeting"));
        EVENTS.add(new FakeEvent(3, OTHER_CALENDAR_ID, inHours(5), "Alarm: other calendar"));
        EVENTS.add(new FakeEvent(4, CALENDAR_ID, inHours(24 * 8), "Alarm: too far ahead"));

        CalendarAlarmSync.syncAll(mContext);

        final List<CalendarAlarmEvent> rows = events();
        assertEquals(1, rows.size());
        final Alarm alarm = Alarm.getAlarm(mCr, rows.get(0).alarmId);
        assertNotNull(alarm);
        assertTrue(alarm.enabled);
        assertEquals("Alarm: Dentist", alarm.label);
        assertEquals(CalendarProfile.RING_UNTIL_DISMISSED, alarm.autoSilenceDuration);
        assertEquals(5, alarm.snoozeDuration);
        assertEquals(mProfile.ringtone, alarm.alert);

        final List<AlarmInstance> instances = AlarmInstance.getInstancesByAlarmId(mCr, alarm.id);
        assertEquals(1, instances.size());
        assertEquals(begin, instances.get(0).getAlarmTime().getTimeInMillis());

        // Calendar alarms stay out of the normal alarm list.
        assertTrue(Alarm.getUserAlarms(mCr).isEmpty());
    }

    @Test
    public void syncIsStableAndFollowsMovedEvents() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        final long firstAlarmId = events().get(0).alarmId;

        // A second sync changes nothing.
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, events().size());
        assertEquals(firstAlarmId, events().get(0).alarmId);

        // Moving the event replaces the alarm.
        EVENTS.clear();
        final long newBegin = inHours(6);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, newBegin, "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);

        // The old alarm is gone; SQLite may give its id to the new alarm.
        final List<CalendarAlarmEvent> rows = events();
        assertEquals(1, rows.size());
        assertEquals(newBegin, rows.get(0).begin);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
        final List<AlarmInstance> moved = AlarmInstance.getInstancesByAlarmId(mCr, rows.get(0).alarmId);
        assertEquals(1, moved.size());
        assertEquals(newBegin, moved.get(0).getAlarmTime().getTimeInMillis());

        // Deleting the event removes its alarm.
        EVENTS.clear();
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(events().isEmpty());
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
    }

    @Test
    public void singleEventCanBeSwitchedOffAndOn() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        final CalendarAlarmEvent row = events().get(0);

        CalendarAlarmSync.setEventEnabled(mContext, row.id, false);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());

        // The switch survives later syncs.
        CalendarAlarmSync.syncAll(mContext);
        assertFalse(events().get(0).userEnabled);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());

        CalendarAlarmSync.setEventEnabled(mContext, row.id, true);
        assertNotNull(Alarm.getAlarm(mCr, events().get(0).alarmId));
    }

    @Test
    public void dismissedAlarmIsNotRecreated() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        final long alarmId = events().get(0).alarmId;

        // Dismissing a one-time calendar alarm deletes it.
        Alarm.deleteAlarm(mCr, alarmId);
        assertEquals(CalendarAlarmEvent.ALARM_CONSUMED, events().get(0).alarmId);

        CalendarAlarmSync.syncAll(mContext);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        assertEquals(CalendarAlarmEvent.ALARM_CONSUMED, events().get(0).alarmId);
    }

    @Test
    public void disablingTheProfileRemovesItsAlarms() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());

        mProfile.enabled = false;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        assertEquals(1, events().size());

        mProfile.enabled = true;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void deletingTheProfileRemovesItsAlarms() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);

        CalendarAlarmSync.deleteProfile(mContext, mProfile.id);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        assertTrue(CalendarAlarmEvent.getAllEvents(mCr).isEmpty());
        assertNull(CalendarProfile.getProfile(mCr, mProfile.id));
    }

    @Test
    public void snoozeLimitFollowsTheProfile() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, events().get(0).alarmId).get(0);

        instance.mSnoozeCount = 1;
        assertFalse(CalendarAlarmRules.isSnoozeLimitReached(mCr, instance));
        instance.mSnoozeCount = 2;
        assertTrue(CalendarAlarmRules.isSnoozeLimitReached(mCr, instance));

        mProfile.maxSnoozeCount = 0;
        mProfile.updateProfile(mCr);
        instance.mSnoozeCount = 50;
        assertFalse(CalendarAlarmRules.isSnoozeLimitReached(mCr, instance));
    }

    @Test
    public void lookaheadWindowFollowsTheProfile() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(24 * 20), "Alarm: in 20 days"));
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(events().isEmpty());

        mProfile.lookaheadDays = 30;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, events().size());
        assertNotNull(Alarm.getAlarm(mCr, events().get(0).alarmId));

        // Values outside 7..30 are clamped.
        mProfile.lookaheadDays = 365;
        mProfile.updateProfile(mCr);
        assertEquals(30, CalendarProfile.getProfile(mCr, mProfile.id).lookaheadDays);
    }

    @Test
    public void flashlightIsOffByDefaultAndFollowsTheProfile() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: flash"));
        CalendarAlarmSync.syncAll(mContext);
        final long alarmId = events().get(0).alarmId;
        org.junit.Assert.assertFalse(Alarm.getAlarm(mCr, alarmId).flash);
        org.junit.Assert.assertFalse(com.best.deskclock.provider.AlarmInstance
            .getInstancesByAlarmId(mCr, alarmId).get(0).mFlash);

        mProfile.flash = true;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(Alarm.getAlarm(mCr, alarmId).flash);
        assertTrue(com.best.deskclock.provider.AlarmInstance
            .getInstancesByAlarmId(mCr, alarmId).get(0).mFlash);
    }

    @Test
    public void vibrateOnlyMakesTheAlarmSilentAndVibrating() {
        mProfile.vibrate = false;
        mProfile.vibrateOnly = true;
        mProfile.updateProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: quiet"));
        CalendarAlarmSync.syncAll(mContext);
        final long alarmId = events().get(0).alarmId;
        final com.best.deskclock.provider.AlarmInstance instance = com.best.deskclock.provider.AlarmInstance
            .getInstancesByAlarmId(mCr, alarmId).get(0);
        assertEquals(com.best.deskclock.utils.RingtoneUtils.RINGTONE_SILENT, instance.mRingtone);
        assertTrue(instance.mVibrate);

        // Turning it off brings the sound back.
        mProfile.vibrateOnly = false;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        org.junit.Assert.assertNotEquals(com.best.deskclock.utils.RingtoneUtils.RINGTONE_SILENT,
            Alarm.getAlarm(mCr, alarmId).alert);
    }

    @Test
    public void eventMovedByDeleteAndAddLeavesOneAlert() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);

        // The calendar app deleted event 1 (still waiting to sync) and added event 2 at the new time.
        DELETED_IDS.add(1L);
        EVENTS.add(new FakeEvent(2, CALENDAR_ID, inHours(5), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);

        final List<CalendarAlarmEvent> rows = events();
        assertEquals(1, rows.size());
        assertEquals(inHours(5), rows.get(0).begin);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void switchingTheProfileOffStopsRingingAndScheduledAlarms() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: later"));
        EVENTS.add(new FakeEvent(2, CALENDAR_ID, inHours(4), "Alarm: much later"));
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(2, Alarm.getAlarms(mCr, null).size());

        // The first alarm is ringing right now.
        final long ringingAlarmId = events().get(0).alarmId;
        AlarmInstance ringing = AlarmInstance.getInstancesByAlarmId(mCr, ringingAlarmId).get(0);
        com.best.deskclock.alarms.AlarmStateManager.setFiredState(mContext, ringing);

        mProfile.enabled = false;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncProfile(mContext, mProfile.id);

        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        assertTrue(AlarmInstance.getInstances(mCr, null).isEmpty());

        // Switching it back on brings the future alarms back.
        mProfile.enabled = true;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncProfile(mContext, mProfile.id);
        assertEquals(2, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void flashOnlyBlinksWithoutSoundOrVibration() {
        mProfile.flashOnly = true;
        mProfile.flash = false;
        mProfile.updateProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: flash only"));
        CalendarAlarmSync.syncAll(mContext);
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, events().get(0).alarmId).get(0);
        assertEquals(com.best.deskclock.utils.RingtoneUtils.RINGTONE_SILENT, instance.mRingtone);
        org.junit.Assert.assertFalse(instance.mVibrate);
        assertTrue(instance.mFlash);
    }

    @Test
    public void invalidRegexMatchesNothing() {
        mProfile.titleRegex = "Alarm:(";
        mProfile.updateProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm:( Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(events().isEmpty());
    }

    @Test
    public void eventStartingSoonIsNotSkipped() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2)
            - (System.currentTimeMillis() % 60000), "Alarm: soon"));
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void eventMatchedByTwoProfilesGetsOneAlarm() {
        final CalendarProfile second = new CalendarProfile();
        second.name = "Second";
        second.calendarId = CALENDAR_ID;
        second.accountName = "someone@example.com";
        second.titleRegex = "Dentist";
        second.addProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));

        CalendarAlarmSync.syncAll(mContext);
        CalendarAlarmSync.syncAll(mContext);

        assertEquals(1, Alarm.getAlarms(mCr, null).size());
        assertEquals(1, AlarmInstance.getInstances(mCr, null).size());
    }

    /** Review finding 1: switching an alarm off must not hand its event to the next profile. */
    @Test
    public void switchedOffAlarmIsNotTakenOverByAnotherProfile() {
        final CalendarProfile second = new CalendarProfile();
        second.name = "Dentist";
        second.calendarId = CALENDAR_ID;
        second.accountName = "someone@example.com";
        second.titleRegex = "Dentist";
        second.addProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());

        CalendarAlarmSync.setEventEnabled(mContext, events().get(0).id, false);
        CalendarAlarmSync.syncAll(mContext);
        CalendarAlarmSync.syncAll(mContext);

        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        assertTrue(CalendarAlarmEvent.getEventsForProfile(mCr, second.id).isEmpty());

        // Switched on again, the first profile rings for it as before.
        CalendarAlarmSync.setEventEnabled(mContext, events().get(0).id, true);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    /** The "Next alarm" widget menu finds the next alarm's event and profile. */
    @Test
    public void widgetMenuFindsTheNextAlarmsEventAndProfile() {
        assertNull(NextAlarmMenuActivity.loadTarget(mContext));
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(5), "Alarm: Later"));
        EVENTS.add(new FakeEvent(2, CALENDAR_ID, inHours(2), "Alarm: Sooner"));
        CalendarAlarmSync.syncAll(mContext);

        NextAlarmMenuActivity.Target target = NextAlarmMenuActivity.loadTarget(mContext);
        assertNotNull(target);
        assertEquals("Alarm: Sooner", target.event.title);
        assertEquals(mProfile.id, target.profile.id);

        // Disabled from the menu, the widget moves on to the next event.
        CalendarAlarmSync.setEventEnabled(mContext, target.event.id, false);
        target = NextAlarmMenuActivity.loadTarget(mContext);
        assertNotNull(target);
        assertEquals("Alarm: Later", target.event.title);
    }

    /** Deleting a repeating event from the widget menu cancels only that occurrence. */
    @Test
    public void widgetMenuDeletesOnlyOneOccurrenceOfARepeatingEvent() {
        final long first = inHours(2);
        final long second = first + TimeUnit.DAYS.toMillis(1);
        RECURRING.add(1L);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, first, "Alarm: Weekly"));
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, second, "Alarm: Weekly"));
        CalendarAlarmSync.syncAll(mContext);
        final CalendarAlarmEvent event = NextAlarmMenuActivity.loadTarget(mContext).event;
        assertEquals(first, event.begin);

        assertTrue(NextAlarmMenuActivity.isRecurring(mCr, event.eventId));
        assertTrue(NextAlarmMenuActivity.deleteFromCalendar(mContext, event, true));

        assertEquals(1, CHANGES.size());
        final String change = CHANGES.get(0);
        // An exception for this one occurrence, never a delete of the series.
        assertTrue(change, change.startsWith(CalendarContract.Events.CONTENT_EXCEPTION_URI + "/1 "));
        assertTrue(change, change.contains(CalendarContract.Events.ORIGINAL_INSTANCE_TIME + "=" + first));
        assertTrue(change, change.contains(CalendarContract.Events.STATUS + "=" + CalendarContract.Events.STATUS_CANCELED));
    }

    /** A single event is deleted as a whole. */
    @Test
    public void widgetMenuDeletesASingleEvent() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(2), "Alarm: Once"));
        CalendarAlarmSync.syncAll(mContext);
        final CalendarAlarmEvent event = NextAlarmMenuActivity.loadTarget(mContext).event;

        assertFalse(NextAlarmMenuActivity.isRecurring(mCr, event.eventId));
        assertTrue(NextAlarmMenuActivity.deleteFromCalendar(mContext, event, false));
        assertEquals(List.of("delete " + CalendarContract.Events.CONTENT_URI + "/1"), CHANGES);
    }

    /** Disabling one occurrence of a repeating event keeps the alarms of the other occurrences. */
    @Test
    public void widgetMenuDisablesOnlyOneOccurrenceOfARepeatingEvent() {
        final long first = inHours(2);
        final long second = first + TimeUnit.DAYS.toMillis(1);
        RECURRING.add(1L);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, first, "Alarm: Weekly"));
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, second, "Alarm: Weekly"));
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(2, Alarm.getAlarms(mCr, null).size());

        CalendarAlarmSync.setEventEnabled(mContext, NextAlarmMenuActivity.loadTarget(mContext).event.id, false);
        CalendarAlarmSync.syncAll(mContext);

        assertEquals(1, Alarm.getAlarms(mCr, null).size());
        final NextAlarmMenuActivity.Target next = NextAlarmMenuActivity.loadTarget(mContext);
        assertEquals(second, next.event.begin);
        assertTrue(CHANGES.isEmpty());
    }

    /** Review finding 4: a regex that gives up on a title keeps the event's alarm. */
    @Test
    public void titleTheRegexGivesUpOnKeepsItsAlarm() {
        mProfile.titleRegex = "(.*a){15}x";
        mProfile.updateProfile(mCr);
        final long begin = inHours(3);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "aaaaaaaaaaaaaaax"));
        CalendarAlarmSync.syncAll(mContext);
        final long alarmId = events().get(0).alarmId;
        assertNotNull(Alarm.getAlarm(mCr, alarmId));

        final String runaway = "a".repeat(80);
        assertNull(CalendarProfile.matchTitle(mProfile.compileTitlePattern(), runaway));
        EVENTS.clear();
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, runaway));
        CalendarAlarmSync.syncAll(mContext);

        assertEquals(1, events().size());
        assertNotNull(Alarm.getAlarm(mCr, alarmId));
        // A plain mismatch still removes the alarm.
        EVENTS.clear();
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "b"));
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
    }

    /** Review finding 3: a Wi-Fi scan is planned shortly before an alarm with a Wi-Fi condition. */
    @Test
    public void wifiScanIsPlannedBeforeAlarmsWithAWifiCondition() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        final long now = System.currentTimeMillis();
        assertEquals(-1, CalendarWifiPrescan.nextScanTime(mContext, now));

        mProfile.wifiCheck = true;
        mProfile.wifiNetworks = CalendarWifi.toJson(List.of(new CalendarWifi.Network("Home", "")));
        mProfile.updateProfile(mCr);
        final AlarmInstance instance = AlarmInstance.getInstances(mCr, null).get(0);
        assertEquals(instance.getAlarmTime().getTimeInMillis() - CalendarWifiPrescan.LEAD_MS,
            CalendarWifiPrescan.nextScanTime(mContext, now));
    }

    @Test
    public void declinedEventsAreSkippedAndMaybeOnlyWhenAsked() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: declined", 0,
            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED));
        EVENTS.add(new FakeEvent(2, CALENDAR_ID, inHours(4), "Alarm: maybe", 0,
            CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE));
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, events().size());
        assertEquals("Alarm: maybe", events().get(0).title);

        mProfile.skipTentative = true;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(events().isEmpty());
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
    }

    @Test
    public void allDayEventsRingAtTheChosenTimeOnlyWhenSwitchedOn() {
        final Calendar day = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        day.add(Calendar.DAY_OF_MONTH, 2);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, day.getTimeInMillis(), "Alarm: holiday", 1,
            CalendarContract.Attendees.ATTENDEE_STATUS_NONE));

        CalendarAlarmSync.syncAll(mContext);
        assertTrue(events().isEmpty());

        mProfile.allDayMinutes = 8 * 60 + 30;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, events().size());
        final Calendar expected = Calendar.getInstance();
        expected.clear();
        expected.set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), 8, 30, 0);
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, events().get(0).alarmId).get(0);
        assertEquals(expected.getTimeInMillis(), instance.getAlarmTime().getTimeInMillis());
    }

    @Test
    public void calendarOfAnotherAccountRemovesAlarms() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());

        // The account was removed and the calendar id now belongs to someone else.
        CALENDARS.put(CALENDAR_ID, "stranger@example.com");
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
    }

    @Test
    public void lostPermissionKeepsScheduledAlarms() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        EVENTS.clear();

        Shadows.shadowOf(mContext).denyPermissions(Manifest.permission.READ_CALENDAR);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void calendarAlarmsAreNotRepeatedWhenMissed() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(0, Alarm.getAlarm(mCr, events().get(0).alarmId).missedAlarmRepeatLimit);
    }

    @Test
    public void ignoreCaseMatchesAnyCase() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "ALARM: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(events().isEmpty());

        mProfile.ignoreCase = true;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, events().size());
    }

    @Test
    public void alarmTimeRoundsToTheMinuteButNeverIntoThePast() {
        final long minute = 60_000L;
        final long base = 1_000L * minute;
        assertEquals(base, CalendarAlarmSync.alarmTimeFor(base + 45_000, base - 1));
        assertEquals(base + minute, CalendarAlarmSync.alarmTimeFor(base + 45_000, base + 10_000));
        assertEquals(base, CalendarAlarmSync.alarmTimeFor(base, base - 5_000));
    }

    @Test
    public void profilesCanBeRestoredFromTheBackupFile() throws Exception {
        mProfile.calendarName = "Calendar 7";
        mProfile.ignoreCase = true;
        mProfile.allDayMinutes = 7 * 60;
        mProfile.updateProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "alarm: Dentist"));
        final org.json.JSONArray backup = CalendarProfileRestore.exportProfiles(mCr);

        // Lost the profile, and the calendar got another id (for example on a new phone).
        CalendarAlarmSync.deleteProfile(mContext, mProfile.id);
        CALENDARS.clear();
        CALENDARS.put(42L, "someone@example.com");
        EVENTS.clear();
        EVENTS.add(new FakeEvent(1, 42, inHours(3), "alarm: Dentist"));
        // The fake names calendars "Calendar <id>", so keep the old name for id 42.
        backup.getJSONObject(0).put("calendar_name", "Calendar 42");

        CalendarProfileRestore.replaceAll(mContext, backup);
        final List<CalendarProfile> profiles = CalendarProfile.getProfiles(mCr);
        assertEquals(1, profiles.size());
        final CalendarProfile restored = profiles.get(0);
        assertEquals(42, restored.calendarId);
        assertEquals("^Alarm:", restored.titleRegex);
        assertTrue(restored.ignoreCase);
        assertEquals(7 * 60, restored.allDayMinutes);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());

        // Restoring the same file again replaces instead of adding.
        CalendarProfileRestore.replaceAll(mContext, backup);
        assertEquals(1, CalendarProfile.getProfiles(mCr).size());
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void restoredProfileWithUnknownCalendarWaitsForOneToBeChosen() throws Exception {
        final org.json.JSONArray backup = CalendarProfileRestore.exportProfiles(mCr);
        CalendarProfileRestore.replaceAll(mContext, backup);
        assertEquals(-1, CalendarProfile.getProfiles(mCr).get(0).calendarId);
    }

    @Test
    public void backupWithoutProfilesKeepsTheExistingOnes() {
        CalendarProfileRestore.replaceAll(mContext, null);
        assertEquals(1, CalendarProfile.getProfiles(mCr).size());
    }

    @Test
    public void dismissedAlarmStaysDismissedAfterSwitchingTheProfileOffAndOn() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        Alarm.deleteAlarm(mCr, events().get(0).alarmId);

        mProfile.enabled = false;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        mProfile.enabled = true;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);

        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        assertEquals(CalendarAlarmEvent.ALARM_CONSUMED, events().get(0).alarmId);
    }

    @Test
    public void profileFollowsItsCalendarByNameWhenTheNumberPointsToAnother() {
        // For example a backup restored without calendar access: the number belongs to another
        // calendar of the same account on this phone.
        mProfile.calendarId = OTHER_CALENDAR_ID;
        mProfile.calendarName = "Calendar " + CALENDAR_ID;
        mProfile.updateProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        EVENTS.add(new FakeEvent(2, OTHER_CALENDAR_ID, inHours(4), "Alarm: Wrong calendar"));

        CalendarAlarmSync.syncAll(mContext);

        assertEquals(CALENDAR_ID, CalendarProfile.getProfile(mCr, mProfile.id).calendarId);
        assertEquals(1, events().size());
        assertEquals(1, events().get(0).eventId);
    }

    @Test
    public void resetRemovesAllProfilesAndEvents() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        final long alarmId = events().get(0).alarmId;

        CalendarAlarmSync.deleteAllProfiles(mContext);
        Alarm.deleteAlarm(mCr, alarmId);

        assertTrue(CalendarProfile.getProfiles(mCr).isEmpty());
        assertTrue(CalendarAlarmEvent.getAllEvents(mCr).isEmpty());
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
    }

    @Test
    public void lockScreenDetailsOptionIsSavedAndOnByDefault() {
        assertTrue(CalendarProfile.getProfile(mCr, mProfile.id).showDetailsLocked);
        mProfile.showDetailsLocked = false;
        mProfile.updateProfile(mCr);
        assertFalse(CalendarProfile.getProfile(mCr, mProfile.id).showDetailsLocked);
    }

    @Test
    public void restoreForgetsTheOtherPhonesCalendarCheckState() {
        final android.content.SharedPreferences prefs =
            com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences(mContext);
        prefs.edit().putLong(CalendarAlarmSync.KEY_LAST_CHECK, 1234L)
            .putBoolean("key_calendar_alert_permission_notified", true)
            .putBoolean(CalendarAlarmStatus.KEY_CALENDAR_PERMISSION_ASKED, true).commit();
        final android.content.SharedPreferences.Editor editor = prefs.edit();
        CalendarAlarmStatus.clearDeviceState(editor);
        editor.commit();
        assertFalse(prefs.contains(CalendarAlarmSync.KEY_LAST_CHECK));
        assertFalse(prefs.contains("key_calendar_alert_permission_notified"));
        assertFalse(prefs.contains(CalendarAlarmStatus.KEY_CALENDAR_PERMISSION_ASKED));
    }

    @Test
    public void restoreKeepsDismissedAndSwitchedOffAlarmsOff() throws Exception {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        EVENTS.add(new FakeEvent(2, CALENDAR_ID, inHours(4), "Alarm: Gym"));
        EVENTS.add(new FakeEvent(3, CALENDAR_ID, inHours(5), "Alarm: Train"));
        CalendarAlarmSync.syncAll(mContext);
        final List<CalendarAlarmEvent> before = events();
        Alarm.deleteAlarm(mCr, before.get(0).alarmId);
        CalendarAlarmSync.setEventEnabled(mContext, before.get(1).id, false);

        CalendarProfileRestore.replaceAll(mContext, CalendarProfileRestore.exportProfiles(mCr));

        final List<CalendarProfile> profiles = CalendarProfile.getProfiles(mCr);
        assertEquals(1, profiles.size());
        final List<CalendarAlarmEvent> after = CalendarAlarmEvent.getEventsForProfile(mCr, profiles.get(0).id);
        assertEquals(3, after.size());
        assertEquals(CalendarAlarmEvent.ALARM_CONSUMED, after.get(0).alarmId);
        assertFalse(after.get(1).userEnabled);
        assertTrue(after.get(2).alarmId >= 0);
        // Only the train still rings.
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void offsetIsOffByDefaultAndRingsTheChosenMinutesBefore() {
        final CalendarProfile fresh = CalendarProfile.getProfile(mCr, mProfile.id);
        assertFalse(fresh.offsetEnabled);
        assertEquals(CalendarProfile.DEFAULT_OFFSET_MINUTES, fresh.offsetMinutes);
        assertEquals(10, CalendarProfile.DEFAULT_OFFSET_MINUTES);

        final long begin = inHours(3);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        final long alarmId = events().get(0).alarmId;
        assertEquals(begin, AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0).getAlarmTime().getTimeInMillis());

        // Switching the offset on moves the alarm 10 minutes earlier.
        mProfile.offsetEnabled = true;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        List<AlarmInstance> instances = AlarmInstance.getInstancesByAlarmId(mCr, events().get(0).alarmId);
        assertEquals(1, instances.size());
        assertEquals(begin - TimeUnit.MINUTES.toMillis(10), instances.get(0).getAlarmTime().getTimeInMillis());

        // Other minutes are kept per profile.
        mProfile.offsetMinutes = 45;
        mProfile.updateProfile(mCr);
        assertEquals(45, CalendarProfile.getProfile(mCr, mProfile.id).offsetMinutes);
        CalendarAlarmSync.syncAll(mContext);
        instances = AlarmInstance.getInstancesByAlarmId(mCr, events().get(0).alarmId);
        assertEquals(begin - TimeUnit.MINUTES.toMillis(45), instances.get(0).getAlarmTime().getTimeInMillis());

        // Switching it off again rings at the start.
        mProfile.offsetEnabled = false;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        instances = AlarmInstance.getInstancesByAlarmId(mCr, events().get(0).alarmId);
        assertEquals(begin, instances.get(0).getAlarmTime().getTimeInMillis());
    }

    @Test
    public void offsetThatHasAlreadyPassedRingsAtTheNextMinute() {
        final CalendarProfile profile = new CalendarProfile();
        profile.offsetEnabled = true;
        profile.offsetMinutes = 30;
        final long minute = 60_000L;
        final long now = 1_000L * minute + 5_000;
        final long begin = 1_010L * minute;
        // The event starts in 10 minutes, so 30 minutes before is already gone.
        assertEquals(1_001L * minute, CalendarAlarmSync.ringTimeFor(profile, begin, now));
        // Far enough ahead, it rings 30 minutes before.
        assertEquals(1_070L * minute, CalendarAlarmSync.ringTimeFor(profile, 1_100L * minute, now));
        // Without an offset, at the start.
        profile.offsetEnabled = false;
        assertEquals(begin, CalendarAlarmSync.ringTimeFor(profile, begin, now));
        // Out of range minutes are clamped.
        assertEquals(CalendarProfile.MAX_OFFSET_MINUTES, CalendarProfile.clampOffsetMinutes(10_000));
        assertEquals(CalendarProfile.MIN_OFFSET_MINUTES, CalendarProfile.clampOffsetMinutes(0));
    }

    @Test
    public void escalationIsOffByDefaultAndRampsTheVolume() {
        assertFalse(CalendarProfile.getProfile(mCr, mProfile.id).escalation);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(0, Alarm.getAlarm(mCr, events().get(0).alarmId).crescendoDuration);

        mProfile.escalation = true;
        mProfile.updateProfile(mCr);
        assertTrue(CalendarProfile.getProfile(mCr, mProfile.id).escalation);
        CalendarAlarmSync.syncAll(mContext);
        final long alarmId = events().get(0).alarmId;
        assertEquals(CalendarAlarmSync.ESCALATION_SECONDS, Alarm.getAlarm(mCr, alarmId).crescendoDuration);
        // The scheduled instance, and so every snooze of it, gets the same ramp.
        assertEquals(CalendarAlarmSync.ESCALATION_SECONDS,
            AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0).mCrescendoDuration);

        // A short maximum ring time gets a shorter ramp, so the alarm still reaches full strength.
        mProfile.autoSilenceDuration = 60;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(30, Alarm.getAlarm(mCr, events().get(0).alarmId).crescendoDuration);
        mProfile.autoSilenceDuration = 5 * 60;
        assertEquals(CalendarAlarmSync.ESCALATION_SECONDS, CalendarAlarmSync.escalationSecondsFor(mProfile));
        mProfile.autoSilenceDuration = 4;
        assertEquals(CalendarAlarmSync.MIN_ESCALATION_SECONDS, CalendarAlarmSync.escalationSecondsFor(mProfile));
    }

    @Test
    public void offsetAndEscalationAreRestoredFromTheBackup() throws Exception {
        mProfile.offsetEnabled = true;
        mProfile.offsetMinutes = 25;
        mProfile.escalation = true;
        mProfile.updateProfile(mCr);
        final org.json.JSONArray backup = CalendarProfileRestore.exportProfiles(mCr);

        CalendarAlarmSync.deleteProfile(mContext, mProfile.id);
        CalendarProfileRestore.replaceAll(mContext, backup);
        final CalendarProfile restored = CalendarProfile.getProfiles(mCr).get(0);
        assertTrue(restored.offsetEnabled);
        assertEquals(25, restored.offsetMinutes);
        assertTrue(restored.escalation);

        // A backup from before these settings existed restores them switched off.
        backup.getJSONObject(0).remove("offset_enabled");
        backup.getJSONObject(0).remove("offset_minutes");
        backup.getJSONObject(0).remove("escalation");
        CalendarProfileRestore.replaceAll(mContext, backup);
        final CalendarProfile old = CalendarProfile.getProfiles(mCr).get(0);
        assertFalse(old.offsetEnabled);
        assertEquals(CalendarProfile.DEFAULT_OFFSET_MINUTES, old.offsetMinutes);
        assertFalse(old.escalation);
    }

    @Test
    public void earlyAlarmAlsoRingsAtTheStartByDefault() {
        assertTrue(CalendarProfile.getProfile(mCr, mProfile.id).ringAtStart);
        final long begin = inHours(5);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "Alarm: Flight"));
        mProfile.offsetEnabled = true;
        mProfile.offsetMinutes = 2 * 60 + 15;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);

        CalendarAlarmEvent row = events().get(0);
        assertTrue(row.alarmId >= 0);
        assertTrue(row.startAlarmId >= 0);
        assertEquals(begin - TimeUnit.MINUTES.toMillis(135),
            AlarmInstance.getInstancesByAlarmId(mCr, row.alarmId).get(0).getAlarmTime().getTimeInMillis());
        assertEquals(begin,
            AlarmInstance.getInstancesByAlarmId(mCr, row.startAlarmId).get(0).getAlarmTime().getTimeInMillis());
        // Both belong to the event and stay out of the normal alarm list.
        assertEquals(row.id, CalendarAlarmEvent.getEventForAlarm(mCr, row.startAlarmId).id);
        assertTrue(Alarm.getUserAlarms(mCr).isEmpty());
        assertEquals(2, Alarm.getAlarms(mCr, null).size());

        // Dismissing the early alarm keeps the one at the start.
        Alarm.deleteAlarm(mCr, row.alarmId);
        CalendarAlarmSync.syncAll(mContext);
        row = events().get(0);
        assertEquals(CalendarAlarmEvent.ALARM_CONSUMED, row.alarmId);
        assertTrue(row.startAlarmId >= 0);
        assertFalse(row.isDismissed());
        assertEquals(1, Alarm.getAlarms(mCr, null).size());

        // Dismissing that one too leaves the event done.
        Alarm.deleteAlarm(mCr, row.startAlarmId);
        CalendarAlarmSync.syncAll(mContext);
        row = events().get(0);
        assertTrue(row.isDismissed());
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
    }

    @Test
    public void ringAtStartCanBeSwitchedOff() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(5), "Alarm: Flight"));
        mProfile.offsetEnabled = true;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(2, Alarm.getAlarms(mCr, null).size());

        mProfile.ringAtStart = false;
        mProfile.updateProfile(mCr);
        assertFalse(CalendarProfile.getProfile(mCr, mProfile.id).ringAtStart);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
        assertEquals(Alarm.INVALID_ID, events().get(0).startAlarmId);

        // Without an offset there is only ever one alarm.
        mProfile.ringAtStart = true;
        mProfile.offsetEnabled = false;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());

        // Switching the profile off removes both alarms.
        mProfile.offsetEnabled = true;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(2, Alarm.getAlarms(mCr, null).size());
        mProfile.enabled = false;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        CalendarAlarmSync.deleteProfile(mContext, mProfile.id);
        assertTrue(events().isEmpty());
    }

    @Test
    public void offsetGoesUpToTwelveHours() {
        assertEquals(12 * 60, CalendarProfile.MAX_OFFSET_MINUTES);
        assertEquals(12 * 60, CalendarProfile.clampOffsetMinutes(13 * 60));
        final long begin = inHours(20);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "Alarm: Trip"));
        mProfile.offsetEnabled = true;
        mProfile.offsetMinutes = 12 * 60;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(begin - TimeUnit.HOURS.toMillis(12), AlarmInstance.getInstancesByAlarmId(mCr,
            events().get(0).alarmId).get(0).getAlarmTime().getTimeInMillis());
    }

    @Test
    public void earlyDismissedStateAndRingAtStartAreRestored() throws Exception {
        mProfile.offsetEnabled = true;
        mProfile.ringAtStart = false;
        mProfile.updateProfile(mCr);
        org.json.JSONArray backup = CalendarProfileRestore.exportProfiles(mCr);
        CalendarAlarmSync.deleteProfile(mContext, mProfile.id);
        CalendarProfileRestore.replaceAll(mContext, backup);
        assertFalse(CalendarProfile.getProfiles(mCr).get(0).ringAtStart);

        // The early alarm was dismissed, the one at the start still waits.
        mProfile = CalendarProfile.getProfiles(mCr).get(0);
        mProfile.calendarId = CALENDAR_ID;
        mProfile.calendarName = "Calendar 7";
        mProfile.ringAtStart = true;
        mProfile.updateProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(5), "Alarm: Flight"));
        CalendarAlarmSync.syncAll(mContext);
        Alarm.deleteAlarm(mCr, events().get(0).alarmId);
        backup = CalendarProfileRestore.exportProfiles(mCr);
        CalendarProfileRestore.replaceAll(mContext, backup);

        final CalendarAlarmEvent row = CalendarAlarmEvent.getEventsForProfile(mCr,
            CalendarProfile.getProfiles(mCr).get(0).id).get(0);
        assertEquals(CalendarAlarmEvent.ALARM_CONSUMED, row.alarmId);
        assertTrue(row.startAlarmId >= 0);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void timeUntilTheEventIsShownInHoursAndMinutes() {
        final long now = 1_000_000_000L;
        assertEquals("Event in 10 min", mContext.getString(com.best.deskclock.R.string.calendar_alarm_event_in,
            CalendarEventText.formatTimeUntil(mContext, now + 10 * 60_000L, now)));
        assertEquals("2 h 15 min", CalendarEventText.formatTimeUntil(mContext, now + 135 * 60_000L, now));
        assertEquals("12 h", CalendarEventText.formatTimeUntil(mContext, now + 12 * 3_600_000L, now));
        // A few seconds after ringing it still says 10 minutes.
        assertEquals("10 min", CalendarEventText.formatTimeUntil(mContext, now + 10 * 60_000L - 5_000, now));
        // The alarm at the start shows nothing.
        assertNull(CalendarEventText.formatTimeUntil(mContext, now + 30_000, now));
        assertNull(CalendarEventText.formatTimeUntil(mContext, now - 60_000, now));
    }

    @Test
    public void alarmAtTheStartIsKeptInTheLastMinuteBeforeTheEvent() {
        final CalendarProfile profile = new CalendarProfile();
        profile.offsetEnabled = true;
        profile.offsetMinutes = 10;
        final long minute = 60_000L;
        final long begin = 1_000L * minute;
        // Hours before: both alarms.
        assertArrayEquals(new boolean[]{true, true}, CalendarAlarmSync.planAlarms(profile, begin, begin - 300 * minute, false));
        // Between the early alarm and the start: the start alarm stays.
        assertTrue(CalendarAlarmSync.planAlarms(profile, begin, begin - 5 * minute, false)[1]);
        // 30 seconds before the start, a check must not drop the alarm at the start.
        assertArrayEquals(new boolean[]{false, true}, CalendarAlarmSync.planAlarms(profile, begin, begin - 30_000, false));
        // Switching the event on again after the early time only arms the start.
        assertArrayEquals(new boolean[]{false, true}, CalendarAlarmSync.planAlarms(profile, begin, begin - 5 * minute, true));
        // Before the early time, switching it on arms both.
        assertArrayEquals(new boolean[]{true, true}, CalendarAlarmSync.planAlarms(profile, begin, begin - 20 * minute, true));
        // Without the second ring, or without an offset, there is one alarm.
        profile.ringAtStart = false;
        assertArrayEquals(new boolean[]{true, false}, CalendarAlarmSync.planAlarms(profile, begin, begin - 30_000, false));
        profile.ringAtStart = true;
        profile.offsetEnabled = false;
        assertArrayEquals(new boolean[]{true, false}, CalendarAlarmSync.planAlarms(profile, begin, begin - 300 * minute, false));
    }

    @Test
    public void switchingAnEventOnAgainAfterTheEarlyTimeOnlyRingsAtTheStart() {
        final long begin = Calendar.getInstance().getTimeInMillis() + TimeUnit.MINUTES.toMillis(5);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "Alarm: Soon"));
        mProfile.offsetEnabled = true;
        mProfile.offsetMinutes = 10;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        final long rowId = events().get(0).id;
        CalendarAlarmSync.setEventEnabled(mContext, rowId, false);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        CalendarAlarmSync.setEventEnabled(mContext, rowId, true);
        final CalendarAlarmEvent row = events().get(0);
        assertEquals(Alarm.INVALID_ID, row.alarmId);
        assertTrue(row.startAlarmId >= 0);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void ringAtEndIsOffByDefaultAndAddsAnAlarmAtTheEnd() {
        final long begin = inHours(3);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "Alarm: Meeting"));
        assertFalse(CalendarProfile.getProfile(mCr, mProfile.id).ringAtEnd);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
        assertEquals(Alarm.INVALID_ID, events().get(0).endAlarmId);
        assertEquals(begin + EVENT_LENGTH_MS, events().get(0).end);

        mProfile.ringAtEnd = true;
        mProfile.updateProfile(mCr);
        assertTrue(CalendarProfile.getProfile(mCr, mProfile.id).ringAtEnd);
        CalendarAlarmSync.syncAll(mContext);
        final CalendarAlarmEvent row = events().get(0);
        assertTrue(row.alarmId >= 0);
        assertTrue(row.endAlarmId >= 0);
        assertEquals(2, Alarm.getAlarms(mCr, null).size());
        final List<AlarmInstance> atEnd = AlarmInstance.getInstancesByAlarmId(mCr, row.endAlarmId);
        assertEquals(1, atEnd.size());
        assertEquals(begin + EVENT_LENGTH_MS, atEnd.get(0).getAlarmTime().getTimeInMillis());
        // The alarm at the end belongs to the event and stays out of the normal alarm list.
        assertEquals(row.id, CalendarAlarmEvent.getEventForAlarm(mCr, row.endAlarmId).id);
        assertTrue(Alarm.getUserAlarms(mCr).isEmpty());

        // Dismissing the alarm at the start keeps the one at the end.
        Alarm.deleteAlarm(mCr, row.alarmId);
        CalendarAlarmSync.syncAll(mContext);
        assertFalse(events().get(0).isDismissed());
        assertEquals(1, Alarm.getAlarms(mCr, null).size());

        // Switching the option off removes it again.
        mProfile.ringAtEnd = false;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        assertTrue(events().get(0).isDismissed());
    }

    @Test
    public void alarmAtTheEndStaysWhileTheEventIsRunning() {
        mProfile.ringAtEnd = true;
        mProfile.updateProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Meeting"));
        CalendarAlarmSync.syncAll(mContext);
        // The event has started: the calendar no longer lists it as upcoming.
        final CalendarAlarmEvent row = events().get(0);
        Alarm.deleteAlarm(mCr, row.alarmId);
        final long now = System.currentTimeMillis();
        final ContentValues values = new ContentValues();
        values.put("begin", now - TimeUnit.MINUTES.toMillis(10));
        values.put("event_end", now + TimeUnit.MINUTES.toMillis(50));
        mCr.update(CalendarAlarmEvent.getContentUri(row.id), values, null, null);
        EVENTS.clear();
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, events().size());
        assertTrue(Alarm.getAlarm(mCr, row.endAlarmId) != null);

        // Switching the profile's option off removes the alarm at the end.
        mProfile.ringAtEnd = false;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
    }

    @Test
    public void ringAtEndAndItsDismissedStateAreRestored() throws Exception {
        mProfile.ringAtEnd = true;
        mProfile.updateProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(5), "Alarm: Flight"));
        CalendarAlarmSync.syncAll(mContext);
        // The alarm at the end was dismissed early, the one at the start still waits.
        Alarm.deleteAlarm(mCr, events().get(0).endAlarmId);
        final org.json.JSONArray backup = CalendarProfileRestore.exportProfiles(mCr);
        CalendarProfileRestore.replaceAll(mContext, backup);

        final CalendarProfile restored = CalendarProfile.getProfiles(mCr).get(0);
        assertTrue(restored.ringAtEnd);
        restored.calendarId = CALENDAR_ID;
        restored.calendarName = "Calendar 7";
        restored.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        final CalendarAlarmEvent row = CalendarAlarmEvent.getEventsForProfile(mCr, restored.id).get(0);
        assertTrue(row.alarmId >= 0);
        assertEquals(CalendarAlarmEvent.ALARM_CONSUMED, row.endAlarmId);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void runningEventWhoseStartAlreadyRangStillRingsAtItsEnd() {
        mProfile.ringAtEnd = true;
        mProfile.updateProfile(mCr);
        final long begin = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "Alarm: Workshop"));
        CalendarAlarmSync.syncAll(mContext);

        // No alarm for the start that has passed, one for the end that is still to come.
        final CalendarAlarmEvent row = events().get(0);
        assertTrue(row.alarmId < 0);
        assertTrue(row.endAlarmId >= 0);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
        assertEquals(CalendarAlarmSync.alarmTimeFor(begin + EVENT_LENGTH_MS, System.currentTimeMillis()),
            AlarmInstance.getInstancesByAlarmId(mCr, row.endAlarmId).get(0).getAlarmTime().getTimeInMillis());

        // It is listed until it ends.
        final long now = System.currentTimeMillis();
        assertTrue(row.isUpcoming(now));
        assertTrue(row.isRunningWithEndAlarm(now));

        // A running event without the option gets no alarm.
        mProfile.ringAtEnd = false;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
    }

    @Test
    public void snoozingTheEndAlarmExtendsTheEventOnlyWhenAsked() {
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.WRITE_CALENDAR);
        mProfile.ringAtEnd = true;
        mProfile.updateProfile(mCr);
        assertFalse(CalendarProfile.getProfile(mCr, mProfile.id).extendOnSnooze);
        final long begin = inHours(3);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "Alarm: Meeting"));
        CalendarAlarmSync.syncAll(mContext);
        final CalendarAlarmEvent row = events().get(0);
        final long newEnd = row.end + TimeUnit.MINUTES.toMillis(5);

        // Off by default; and the alarm at the start never extends the event.
        assertEquals(CalendarEventExtender.Result.NOT_APPLICABLE,
            CalendarEventExtender.extend(mContext, row.endAlarmId, newEnd));
        mProfile.extendOnSnooze = true;
        mProfile.updateProfile(mCr);
        assertTrue(CalendarProfile.getProfile(mCr, mProfile.id).extendOnSnooze);
        assertEquals(CalendarEventExtender.Result.NOT_APPLICABLE,
            CalendarEventExtender.extend(mContext, row.alarmId, newEnd));
        assertTrue(CHANGES.isEmpty());

        // The event stores a length instead of an end (some calendar apps do that); the
        // calendar refuses an end next to a length, so the length is cleared.
        WITH_DURATION.add(1L);
        assertEquals(CalendarEventExtender.Result.EXTENDED,
            CalendarEventExtender.extend(mContext, row.endAlarmId, newEnd));
        assertEquals(1, CHANGES.size());
        assertTrue(CHANGES.get(0), CHANGES.get(0).contains("/events/1 ") && CHANGES.get(0).contains("dtend=" + newEnd));
        assertEquals(Long.valueOf(newEnd), DTENDS.get(1L));
        assertEquals(newEnd, events().get(0).end);
        // The calendar's account is synced right away, so the change reaches Google quickly.
        final android.accounts.Account account = new android.accounts.Account("someone@example.com", "com.google");
        assertTrue(org.robolectric.shadows.ShadowContentResolver.getStatus(account, CalendarContract.AUTHORITY)
            .syncRequests > 0);
    }

    @Test
    public void snoozingTheEndAlarmOfARepeatingEventOnlyChangesThatOccurrence() {
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.WRITE_CALENDAR);
        mProfile.ringAtEnd = true;
        mProfile.extendOnSnooze = true;
        mProfile.updateProfile(mCr);
        RECURRING.add(1L);
        final long begin = inHours(3);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "Alarm: Standup"));
        CalendarAlarmSync.syncAll(mContext);
        final CalendarAlarmEvent row = events().get(0);
        final long newEnd = row.end + TimeUnit.MINUTES.toMillis(5);

        assertEquals(CalendarEventExtender.Result.EXTENDED,
            CalendarEventExtender.extend(mContext, row.endAlarmId, newEnd));
        assertEquals(2, CHANGES.size());
        assertTrue(CHANGES.get(0), CHANGES.get(0).contains("exception/1")
            && CHANGES.get(0).contains("originalInstanceTime=" + begin));
        // The new occurrence got the old length; its end is then set again.
        assertTrue(CHANGES.get(1), CHANGES.get(1).contains("/events/" + EXCEPTION_ID + " ")
            && CHANGES.get(1).contains("dtend=" + newEnd));
        assertEquals(Long.valueOf(newEnd), DTENDS.get(EXCEPTION_ID));
        // The snoozed alarm now belongs to the changed occurrence.
        final CalendarAlarmEvent changed = events().get(0);
        assertEquals(EXCEPTION_ID, changed.eventId);
        assertEquals(row.endAlarmId, changed.endAlarmId);
    }

    @Test
    public void refusedChangeIsReportedAndKeepsTheEnd() {
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.WRITE_CALENDAR);
        mProfile.ringAtEnd = true;
        mProfile.extendOnSnooze = true;
        mProfile.updateProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Meeting"));
        CalendarAlarmSync.syncAll(mContext);
        final CalendarAlarmEvent row = events().get(0);
        REFUSED.add(1L);
        assertEquals(CalendarEventExtender.Result.FAILED,
            CalendarEventExtender.extend(mContext, row.endAlarmId, row.end + 60_000));
        assertEquals(row.end, events().get(0).end);
    }

    @Test
    public void endPutBackByTheCalendarIsReported() {
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.WRITE_CALENDAR);
        mProfile.ringAtEnd = true;
        mProfile.extendOnSnooze = true;
        mProfile.updateProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Meeting"));
        CalendarAlarmSync.syncAll(mContext);
        final CalendarAlarmEvent row = events().get(0);
        final long newEnd = row.end + 600_000;
        assertEquals(CalendarEventExtender.Result.EXTENDED,
            CalendarEventExtender.extend(mContext, row.endAlarmId, newEnd));
        assertTrue(CalendarEventExtender.verifyLater(mContext, row.endAlarmId, newEnd));
        // A sync puts the old end back.
        DTENDS.put(1L, row.end);
        assertFalse(CalendarEventExtender.verifyLater(mContext, row.endAlarmId, newEnd));
        boolean logged = false;
        for (com.best.deskclock.provider.EventLogStore.EventLogEntry entry
                : com.best.deskclock.provider.EventLogStore.getEvents(mCr)) {
            logged |= "CALENDAR_EVENT_EXTEND_UNDONE".equals(entry.eventType);
        }
        assertTrue(logged);
    }

    @Test
    public void extendingNeedsPermissionToChangeTheCalendar() {
        mProfile.ringAtEnd = true;
        mProfile.extendOnSnooze = true;
        mProfile.updateProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Meeting"));
        CalendarAlarmSync.syncAll(mContext);
        final CalendarAlarmEvent row = events().get(0);
        assertEquals(CalendarEventExtender.Result.NO_PERMISSION,
            CalendarEventExtender.extend(mContext, row.endAlarmId, row.end + 60_000));
        assertTrue(CHANGES.isEmpty());
    }

    @Test
    public void changedEndTimeMovesTheAlarmAtTheEnd() {
        mProfile.ringAtEnd = true;
        mProfile.updateProfile(mCr);
        final long begin = inHours(3);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "Alarm: Meeting"));
        CalendarAlarmSync.syncAll(mContext);
        final long endAlarmId = events().get(0).endAlarmId;

        final long newEnd = begin + TimeUnit.HOURS.toMillis(2);
        ENDS.put(1L, newEnd);
        CalendarAlarmSync.syncAll(mContext);
        final CalendarAlarmEvent row = events().get(0);
        assertEquals(newEnd, row.end);
        assertEquals(newEnd, AlarmInstance.getInstancesByAlarmId(mCr, row.endAlarmId).get(0)
            .getAlarmTime().getTimeInMillis());
        assertEquals(endAlarmId, row.endAlarmId);
    }

    @Test
    public void changedEndTimeOfARunningEventMovesTheAlarmAtTheEnd() {
        mProfile.ringAtEnd = true;
        mProfile.updateProfile(mCr);
        final long begin = inHours(0) - TimeUnit.MINUTES.toMillis(30);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "Alarm: Workshop"));
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(events().get(0).endAlarmId >= 0);

        final long newEnd = inHours(3);
        ENDS.put(1L, newEnd);
        CalendarAlarmSync.syncAll(mContext);
        final CalendarAlarmEvent row = events().get(0);
        assertEquals(newEnd, row.end);
        assertEquals(newEnd, AlarmInstance.getInstancesByAlarmId(mCr, row.endAlarmId).get(0)
            .getAlarmTime().getTimeInMillis());
    }

    @Test
    public void alarmDueRightNowIsKeptWhenTheEventHasJustStarted() throws Exception {
        final long begin = System.currentTimeMillis() + 1500;
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "Alarm: Now"));
        CalendarAlarmSync.syncAll(mContext);
        final long alarmId = events().get(0).alarmId;
        assertTrue(alarmId >= 0);

        // The event has started, the alarm was not delivered yet. A calendar check now must not
        // remove it.
        Thread.sleep(1600);
        CalendarAlarmSync.syncAll(mContext);
        assertNotNull(Alarm.getAlarm(mCr, alarmId));
        assertEquals(alarmId, events().get(0).alarmId);
    }
}

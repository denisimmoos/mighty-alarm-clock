// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

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
import com.best.deskclock.provider.CalendarAlertEvent;
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
public class CalendarAlertSyncTest {

    private static final long CALENDAR_ID = 7;
    private static final long OTHER_CALENDAR_ID = 8;

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
                    calendars.addRow(new Object[]{entry.getKey(), entry.getValue(), "com.google",
                        "Calendar " + entry.getKey(), 0});
                }
                return calendars;
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
                if (event.calendarId() == calendarId && event.begin() >= from && event.begin() <= to) {
                    final Object[] row = new Object[projection.length];
                    for (int i = 0; i < projection.length; i++) {
                        switch (projection[i]) {
                            case CalendarContract.Instances.EVENT_ID -> row[i] = event.id();
                            case CalendarContract.Instances.BEGIN -> row[i] = event.begin();
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
            return null;
        }

        @Override
        public int delete(@NonNull Uri uri, String selection, String[] args) {
            return 0;
        }

        @Override
        public int update(@NonNull Uri uri, ContentValues values, String selection, String[] args) {
            return 0;
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

    static final java.util.Map<Long, String> CALENDARS = new java.util.TreeMap<>();

    /** Description and location by event id. */
    static final java.util.Map<Long, String[]> EVENT_DETAILS = new java.util.HashMap<>();

    static void clearEvents() {
        EVENTS.clear();
        EVENT_DETAILS.clear();
        DELETED_IDS.clear();
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

    private List<CalendarAlertEvent> events() {
        return CalendarAlertEvent.getEventsForProfile(mCr, mProfile.id);
    }

    @Test
    public void matchingEventsBecomeAlarmsAtTheirStartTime() {
        final long begin = inHours(3);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, begin, "Alarm: Dentist"));
        EVENTS.add(new FakeEvent(2, CALENDAR_ID, inHours(4), "Team meeting"));
        EVENTS.add(new FakeEvent(3, OTHER_CALENDAR_ID, inHours(5), "Alarm: other calendar"));
        EVENTS.add(new FakeEvent(4, CALENDAR_ID, inHours(24 * 8), "Alarm: too far ahead"));

        CalendarAlertSync.syncAll(mContext);

        final List<CalendarAlertEvent> rows = events();
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
        CalendarAlertSync.syncAll(mContext);
        final long firstAlarmId = events().get(0).alarmId;

        // A second sync changes nothing.
        CalendarAlertSync.syncAll(mContext);
        assertEquals(1, events().size());
        assertEquals(firstAlarmId, events().get(0).alarmId);

        // Moving the event replaces the alarm.
        EVENTS.clear();
        final long newBegin = inHours(6);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, newBegin, "Alarm: Dentist"));
        CalendarAlertSync.syncAll(mContext);

        // The old alarm is gone; SQLite may give its id to the new alarm.
        final List<CalendarAlertEvent> rows = events();
        assertEquals(1, rows.size());
        assertEquals(newBegin, rows.get(0).begin);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
        final List<AlarmInstance> moved = AlarmInstance.getInstancesByAlarmId(mCr, rows.get(0).alarmId);
        assertEquals(1, moved.size());
        assertEquals(newBegin, moved.get(0).getAlarmTime().getTimeInMillis());

        // Deleting the event removes its alarm.
        EVENTS.clear();
        CalendarAlertSync.syncAll(mContext);
        assertTrue(events().isEmpty());
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
    }

    @Test
    public void singleEventCanBeSwitchedOffAndOn() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlertSync.syncAll(mContext);
        final CalendarAlertEvent row = events().get(0);

        CalendarAlertSync.setEventEnabled(mContext, row.id, false);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());

        // The switch survives later syncs.
        CalendarAlertSync.syncAll(mContext);
        assertFalse(events().get(0).userEnabled);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());

        CalendarAlertSync.setEventEnabled(mContext, row.id, true);
        assertNotNull(Alarm.getAlarm(mCr, events().get(0).alarmId));
    }

    @Test
    public void dismissedAlarmIsNotRecreated() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlertSync.syncAll(mContext);
        final long alarmId = events().get(0).alarmId;

        // Dismissing a one-time calendar alarm deletes it.
        Alarm.deleteAlarm(mCr, alarmId);
        assertEquals(CalendarAlertEvent.ALARM_CONSUMED, events().get(0).alarmId);

        CalendarAlertSync.syncAll(mContext);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        assertEquals(CalendarAlertEvent.ALARM_CONSUMED, events().get(0).alarmId);
    }

    @Test
    public void disablingTheProfileRemovesItsAlarms() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlertSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());

        mProfile.enabled = false;
        mProfile.updateProfile(mCr);
        CalendarAlertSync.syncAll(mContext);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        assertEquals(1, events().size());

        mProfile.enabled = true;
        mProfile.updateProfile(mCr);
        CalendarAlertSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void deletingTheProfileRemovesItsAlarms() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlertSync.syncAll(mContext);

        CalendarAlertSync.deleteProfile(mContext, mProfile.id);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        assertTrue(CalendarAlertEvent.getAllEvents(mCr).isEmpty());
        assertNull(CalendarProfile.getProfile(mCr, mProfile.id));
    }

    @Test
    public void snoozeLimitFollowsTheProfile() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlertSync.syncAll(mContext);
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, events().get(0).alarmId).get(0);

        instance.mSnoozeCount = 1;
        assertFalse(CalendarAlertRules.isSnoozeLimitReached(mCr, instance));
        instance.mSnoozeCount = 2;
        assertTrue(CalendarAlertRules.isSnoozeLimitReached(mCr, instance));

        mProfile.maxSnoozeCount = 0;
        mProfile.updateProfile(mCr);
        instance.mSnoozeCount = 50;
        assertFalse(CalendarAlertRules.isSnoozeLimitReached(mCr, instance));
    }

    @Test
    public void lookaheadWindowFollowsTheProfile() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(24 * 20), "Alarm: in 20 days"));
        CalendarAlertSync.syncAll(mContext);
        assertTrue(events().isEmpty());

        mProfile.lookaheadDays = 30;
        mProfile.updateProfile(mCr);
        CalendarAlertSync.syncAll(mContext);
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
        CalendarAlertSync.syncAll(mContext);
        final long alarmId = events().get(0).alarmId;
        org.junit.Assert.assertFalse(Alarm.getAlarm(mCr, alarmId).flash);
        org.junit.Assert.assertFalse(com.best.deskclock.provider.AlarmInstance
            .getInstancesByAlarmId(mCr, alarmId).get(0).mFlash);

        mProfile.flash = true;
        mProfile.updateProfile(mCr);
        CalendarAlertSync.syncAll(mContext);
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
        CalendarAlertSync.syncAll(mContext);
        final long alarmId = events().get(0).alarmId;
        final com.best.deskclock.provider.AlarmInstance instance = com.best.deskclock.provider.AlarmInstance
            .getInstancesByAlarmId(mCr, alarmId).get(0);
        assertEquals(com.best.deskclock.utils.RingtoneUtils.RINGTONE_SILENT, instance.mRingtone);
        assertTrue(instance.mVibrate);

        // Turning it off brings the sound back.
        mProfile.vibrateOnly = false;
        mProfile.updateProfile(mCr);
        CalendarAlertSync.syncAll(mContext);
        org.junit.Assert.assertNotEquals(com.best.deskclock.utils.RingtoneUtils.RINGTONE_SILENT,
            Alarm.getAlarm(mCr, alarmId).alert);
    }

    @Test
    public void eventMovedByDeleteAndAddLeavesOneAlert() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlertSync.syncAll(mContext);

        // The calendar app deleted event 1 (still waiting to sync) and added event 2 at the new time.
        DELETED_IDS.add(1L);
        EVENTS.add(new FakeEvent(2, CALENDAR_ID, inHours(5), "Alarm: Dentist"));
        CalendarAlertSync.syncAll(mContext);

        final List<CalendarAlertEvent> rows = events();
        assertEquals(1, rows.size());
        assertEquals(inHours(5), rows.get(0).begin);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void switchingTheProfileOffStopsRingingAndScheduledAlarms() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: later"));
        EVENTS.add(new FakeEvent(2, CALENDAR_ID, inHours(4), "Alarm: much later"));
        CalendarAlertSync.syncAll(mContext);
        assertEquals(2, Alarm.getAlarms(mCr, null).size());

        // The first alarm is ringing right now.
        final long ringingAlarmId = events().get(0).alarmId;
        AlarmInstance ringing = AlarmInstance.getInstancesByAlarmId(mCr, ringingAlarmId).get(0);
        com.best.deskclock.alarms.AlarmStateManager.setFiredState(mContext, ringing);

        mProfile.enabled = false;
        mProfile.updateProfile(mCr);
        CalendarAlertSync.syncProfile(mContext, mProfile.id);

        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        assertTrue(AlarmInstance.getInstances(mCr, null).isEmpty());

        // Switching it back on brings the future alarms back.
        mProfile.enabled = true;
        mProfile.updateProfile(mCr);
        CalendarAlertSync.syncProfile(mContext, mProfile.id);
        assertEquals(2, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void flashOnlyBlinksWithoutSoundOrVibration() {
        mProfile.flashOnly = true;
        mProfile.flash = false;
        mProfile.updateProfile(mCr);
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: flash only"));
        CalendarAlertSync.syncAll(mContext);
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
        CalendarAlertSync.syncAll(mContext);
        assertTrue(events().isEmpty());
    }

    @Test
    public void eventStartingSoonIsNotSkipped() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2)
            - (System.currentTimeMillis() % 60000), "Alarm: soon"));
        CalendarAlertSync.syncAll(mContext);
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

        CalendarAlertSync.syncAll(mContext);
        CalendarAlertSync.syncAll(mContext);

        assertEquals(1, Alarm.getAlarms(mCr, null).size());
        assertEquals(1, AlarmInstance.getInstances(mCr, null).size());
    }

    @Test
    public void declinedEventsAreSkippedAndMaybeOnlyWhenAsked() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: declined", 0,
            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED));
        EVENTS.add(new FakeEvent(2, CALENDAR_ID, inHours(4), "Alarm: maybe", 0,
            CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE));
        CalendarAlertSync.syncAll(mContext);
        assertEquals(1, events().size());
        assertEquals("Alarm: maybe", events().get(0).title);

        mProfile.skipTentative = true;
        mProfile.updateProfile(mCr);
        CalendarAlertSync.syncAll(mContext);
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

        CalendarAlertSync.syncAll(mContext);
        assertTrue(events().isEmpty());

        mProfile.allDayMinutes = 8 * 60 + 30;
        mProfile.updateProfile(mCr);
        CalendarAlertSync.syncAll(mContext);
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
        CalendarAlertSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());

        // The account was removed and the calendar id now belongs to someone else.
        CALENDARS.put(CALENDAR_ID, "stranger@example.com");
        CalendarAlertSync.syncAll(mContext);
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
    }

    @Test
    public void lostPermissionKeepsScheduledAlarms() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlertSync.syncAll(mContext);
        EVENTS.clear();

        Shadows.shadowOf(mContext).denyPermissions(Manifest.permission.READ_CALENDAR);
        CalendarAlertSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void calendarAlarmsAreNotRepeatedWhenMissed() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlertSync.syncAll(mContext);
        assertEquals(0, Alarm.getAlarm(mCr, events().get(0).alarmId).missedAlarmRepeatLimit);
    }

    @Test
    public void ignoreCaseMatchesAnyCase() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "ALARM: Dentist"));
        CalendarAlertSync.syncAll(mContext);
        assertTrue(events().isEmpty());

        mProfile.ignoreCase = true;
        mProfile.updateProfile(mCr);
        CalendarAlertSync.syncAll(mContext);
        assertEquals(1, events().size());
    }

    @Test
    public void alarmTimeRoundsToTheMinuteButNeverIntoThePast() {
        final long minute = 60_000L;
        final long base = 1_000L * minute;
        assertEquals(base, CalendarAlertSync.alarmTimeFor(base + 45_000, base - 1));
        assertEquals(base + minute, CalendarAlertSync.alarmTimeFor(base + 45_000, base + 10_000));
        assertEquals(base, CalendarAlertSync.alarmTimeFor(base, base - 5_000));
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
        CalendarAlertSync.deleteProfile(mContext, mProfile.id);
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
        CalendarAlertSync.syncAll(mContext);
        Alarm.deleteAlarm(mCr, events().get(0).alarmId);

        mProfile.enabled = false;
        mProfile.updateProfile(mCr);
        CalendarAlertSync.syncAll(mContext);
        mProfile.enabled = true;
        mProfile.updateProfile(mCr);
        CalendarAlertSync.syncAll(mContext);

        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        assertEquals(CalendarAlertEvent.ALARM_CONSUMED, events().get(0).alarmId);
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

        CalendarAlertSync.syncAll(mContext);

        assertEquals(CALENDAR_ID, CalendarProfile.getProfile(mCr, mProfile.id).calendarId);
        assertEquals(1, events().size());
        assertEquals(1, events().get(0).eventId);
    }

    @Test
    public void resetRemovesAllProfilesAndEvents() {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        CalendarAlertSync.syncAll(mContext);
        final long alarmId = events().get(0).alarmId;

        CalendarAlertSync.deleteAllProfiles(mContext);
        Alarm.deleteAlarm(mCr, alarmId);

        assertTrue(CalendarProfile.getProfiles(mCr).isEmpty());
        assertTrue(CalendarAlertEvent.getAllEvents(mCr).isEmpty());
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
        prefs.edit().putLong(CalendarAlertSync.KEY_LAST_CHECK, 1234L)
            .putBoolean("key_calendar_alert_permission_notified", true)
            .putBoolean(CalendarAlertStatus.KEY_CALENDAR_PERMISSION_ASKED, true).commit();
        final android.content.SharedPreferences.Editor editor = prefs.edit();
        CalendarAlertStatus.clearDeviceState(editor);
        editor.commit();
        assertFalse(prefs.contains(CalendarAlertSync.KEY_LAST_CHECK));
        assertFalse(prefs.contains("key_calendar_alert_permission_notified"));
        assertFalse(prefs.contains(CalendarAlertStatus.KEY_CALENDAR_PERMISSION_ASKED));
    }

    @Test
    public void restoreKeepsDismissedAndSwitchedOffAlarmsOff() throws Exception {
        EVENTS.add(new FakeEvent(1, CALENDAR_ID, inHours(3), "Alarm: Dentist"));
        EVENTS.add(new FakeEvent(2, CALENDAR_ID, inHours(4), "Alarm: Gym"));
        EVENTS.add(new FakeEvent(3, CALENDAR_ID, inHours(5), "Alarm: Train"));
        CalendarAlertSync.syncAll(mContext);
        final List<CalendarAlertEvent> before = events();
        Alarm.deleteAlarm(mCr, before.get(0).alarmId);
        CalendarAlertSync.setEventEnabled(mContext, before.get(1).id, false);

        CalendarProfileRestore.replaceAll(mContext, CalendarProfileRestore.exportProfiles(mCr));

        final List<CalendarProfile> profiles = CalendarProfile.getProfiles(mCr);
        assertEquals(1, profiles.size());
        final List<CalendarAlertEvent> after = CalendarAlertEvent.getEventsForProfile(mCr, profiles.get(0).id);
        assertEquals(3, after.size());
        assertEquals(CalendarAlertEvent.ALARM_CONSUMED, after.get(0).alarmId);
        assertFalse(after.get(1).userEnabled);
        assertTrue(after.get(2).alarmId >= 0);
        // Only the train still rings.
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }
}

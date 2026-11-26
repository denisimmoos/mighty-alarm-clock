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

    private record FakeEvent(long id, long calendarId, long begin, String title) {
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
                    cursor.addRow(new Object[]{event.id(), event.begin(), event.title()});
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

    static void clearEvents() {
        EVENTS.clear();
        DELETED_IDS.clear();
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
}

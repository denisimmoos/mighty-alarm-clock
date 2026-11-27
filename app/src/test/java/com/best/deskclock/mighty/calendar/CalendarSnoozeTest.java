// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.content.ContentResolver;
import android.provider.CalendarContract;

import androidx.test.core.app.ApplicationProvider;

import com.best.deskclock.alarms.AlarmStateManager;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.CalendarAlarmEvent;
import com.best.deskclock.provider.CalendarProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * A snoozed calendar alarm rings again: the early alarm, the one at the start and the one at the
 * end, also after calendar checks, a changed event end and an event extended by the snooze.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CalendarSnoozeTest {

    private static final int SNOOZE_MINUTES = 5;

    private Application mContext;
    private ContentResolver mCr;
    private CalendarProfile mProfile;
    private long mBegin;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mCr = mContext.getContentResolver();
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.READ_CALENDAR);
        Robolectric.setupContentProvider(CalendarAlarmSyncTest.FakeCalendarProvider.class, CalendarContract.AUTHORITY);

        mProfile = new CalendarProfile();
        mProfile.name = "Work";
        mProfile.calendarId = 7;
        mProfile.calendarName = "Calendar 7";
        mProfile.accountName = "someone@example.com";
        mProfile.titleRegex = "^Alarm:";
        mProfile.snoozeDuration = SNOOZE_MINUTES;
        mProfile.offsetEnabled = true;
        mProfile.offsetMinutes = 10;
        mProfile.ringAtStart = true;
        mProfile.ringAtEnd = true;
        mProfile.addProfile(mCr);

        final Calendar c = Calendar.getInstance();
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.HOUR_OF_DAY, 2);
        mBegin = c.getTimeInMillis();
        CalendarAlarmSyncTest.clearEvents();
        CalendarAlarmSyncTest.addEvent(1, 7, mBegin, "Alarm: Meeting");
        CalendarAlarmSync.syncAll(mContext);
    }

    @After
    public void drain() throws Exception {
        CalendarAlarmSync.EXECUTOR.submit(() -> { }).get();
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
    }

    private CalendarAlarmEvent row() {
        return CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0);
    }

    /**
     * Rings the alarm, snoozes it, runs a calendar check, and checks that it is still snoozed,
     * scheduled with Android, and rings again.
     *
     * @return the time it was snoozed until
     */
    private long ringSnoozeAndRingAgain(long alarmId) throws Exception {
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        assertEquals(AlarmInstance.FIRED_STATE, AlarmInstance.getInstance(mCr, instance.mId).mAlarmState);

        final long before = System.currentTimeMillis();
        AlarmStateManager.setSnoozeState(mContext, AlarmInstance.getInstance(mCr, instance.mId), false);
        CalendarAlarmSync.EXECUTOR.submit(() -> { }).get();
        final long snoozedUntil = checkSnoozed(instance.mId, before);

        // A calendar check must leave the snoozed alarm alone.
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(snoozedUntil, checkSnoozed(instance.mId, before));

        // When the snooze is over, it rings again.
        AlarmStateManager.setFiredState(mContext, AlarmInstance.getInstance(mCr, instance.mId));
        assertEquals(AlarmInstance.FIRED_STATE, AlarmInstance.getInstance(mCr, instance.mId).mAlarmState);
        return snoozedUntil;
    }

    private long checkSnoozed(long instanceId, long before) {
        final AlarmInstance snoozed = AlarmInstance.getInstance(mCr, instanceId);
        assertNotNull("snoozed alarm was removed", snoozed);
        assertEquals(AlarmInstance.SNOOZE_STATE, snoozed.mAlarmState);
        final long until = snoozed.getAlarmTime().getTimeInMillis();
        final long expected = before + TimeUnit.MINUTES.toMillis(SNOOZE_MINUTES);
        assertTrue("snoozed until " + until, Math.abs(until - expected) < TimeUnit.MINUTES.toMillis(1));
        // Android is asked to wake the app then.
        final ShadowAlarmManager alarms = Shadows.shadowOf(mContext.getSystemService(AlarmManager.class));
        boolean scheduled = false;
        for (ShadowAlarmManager.ScheduledAlarm alarm : alarms.getScheduledAlarms()) {
            if (Math.abs(alarm.getTriggerAtMs() - until) < 1000) {
                scheduled = true;
            }
        }
        assertTrue("no wake-up scheduled for the snooze", scheduled);
        return until;
    }

    @Test
    public void earlyAlarmRingsAgainAfterSnooze() throws Exception {
        ringSnoozeAndRingAgain(row().alarmId);
    }

    @Test
    public void alarmAtTheStartRingsAgainAfterSnooze() throws Exception {
        ringSnoozeAndRingAgain(row().startAlarmId);
    }

    @Test
    public void alarmAtTheEndRingsAgainAfterSnooze() throws Exception {
        final long endAlarmId = row().endAlarmId;
        ringSnoozeAndRingAgain(endAlarmId);
        // Without "extend", the event keeps its end.
        assertEquals(mBegin + TimeUnit.HOURS.toMillis(1), row().end);
        assertTrue(CalendarAlarmSyncTest.CHANGES.isEmpty());
    }

    @Test
    public void alarmAtTheEndRingsAgainWhenSnoozingExtendsTheEvent() throws Exception {
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.WRITE_CALENDAR);
        mProfile.extendOnSnooze = true;
        mProfile.updateProfile(mCr);
        // A running event that is about to end, so the snooze reaches past its end.
        final long now = System.currentTimeMillis();
        CalendarAlarmSyncTest.clearEvents();
        CalendarAlarmSyncTest.addEvent(2, 7, now - TimeUnit.MINUTES.toMillis(30), "Alarm: Workshop");
        CalendarAlarmSyncTest.ENDS.put(2L, now + TimeUnit.SECONDS.toMillis(90));
        CalendarAlarmSync.syncAll(mContext);
        final long endAlarmId = row().endAlarmId;
        assertTrue(endAlarmId >= 0);
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, endAlarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        final long before = System.currentTimeMillis();
        AlarmStateManager.setSnoozeState(mContext, AlarmInstance.getInstance(mCr, instance.mId), false);
        CalendarAlarmSync.EXECUTOR.submit(() -> { }).get();
        final long until = checkSnoozed(instance.mId, before);

        // The event now ends when the snoozed alarm rings again.
        assertEquals(1, CalendarAlarmSyncTest.CHANGES.size());
        assertEquals(until, row().end);
        // The calendar reports the new end; the check keeps the snoozed alarm as it is.
        CalendarAlarmSyncTest.ENDS.put(2L, until);
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(until, checkSnoozed(instance.mId, before));
        assertEquals(endAlarmId, row().endAlarmId);
        AlarmStateManager.setFiredState(mContext, AlarmInstance.getInstance(mCr, instance.mId));
        assertEquals(AlarmInstance.FIRED_STATE, AlarmInstance.getInstance(mCr, instance.mId).mAlarmState);
    }

    @Test
    public void profileOffersThreeSnoozeLengthsInOrder() {
        final CalendarProfile fresh = new CalendarProfile();
        org.junit.Assert.assertArrayEquals(new int[]{5, 10, 20}, fresh.getSnoozeChoices());
        // Saved and read back.
        mProfile.snoozeDuration = 3;
        mProfile.snoozeMinutes2 = 15;
        mProfile.snoozeMinutes3 = 45;
        mProfile.updateProfile(mCr);
        org.junit.Assert.assertArrayEquals(new int[]{3, 15, 45},
            CalendarProfile.getProfile(mCr, mProfile.id).getSnoozeChoices());
        // Lengths out of order are moved up so each is longer than the one before.
        mProfile.snoozeDuration = 30;
        org.junit.Assert.assertArrayEquals(new int[]{30, 60, 120}, mProfile.getSnoozeChoices());
        mProfile.snoozeDuration = 999;
        mProfile.snoozeMinutes2 = 999;
        mProfile.snoozeMinutes3 = 999;
        org.junit.Assert.assertArrayEquals(new int[]{997, 998, 999}, mProfile.getSnoozeChoices());
        // No snooze, no buttons.
        mProfile.snoozeDuration = CalendarProfile.SNOOZE_DISABLED;
        assertEquals(0, mProfile.getSnoozeChoices().length);

        assertTrue(CalendarProfile.isSnoozeOrderValid(5, 10, 20));
        assertTrue(CalendarProfile.isSnoozeOrderValid(1, 2, 999));
        org.junit.Assert.assertFalse(CalendarProfile.isSnoozeOrderValid(10, 10, 20));
        org.junit.Assert.assertFalse(CalendarProfile.isSnoozeOrderValid(5, 20, 10));
        org.junit.Assert.assertFalse(CalendarProfile.isSnoozeOrderValid(20, 10, 30));
        org.junit.Assert.assertFalse(CalendarProfile.isSnoozeOrderValid(0, 10, 20));
        org.junit.Assert.assertFalse(CalendarProfile.isSnoozeOrderValid(5, 10, 1000));
    }

    @Test
    public void snoozingForTheLongestLengthExtendsTheEventByIt() throws Exception {
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.WRITE_CALENDAR);
        mProfile.extendOnSnooze = true;
        mProfile.updateProfile(mCr);
        final long now = System.currentTimeMillis();
        CalendarAlarmSyncTest.clearEvents();
        CalendarAlarmSyncTest.addEvent(3, 7, now - TimeUnit.MINUTES.toMillis(30), "Alarm: Review");
        CalendarAlarmSyncTest.ENDS.put(3L, now + TimeUnit.SECONDS.toMillis(90));
        CalendarAlarmSync.syncAll(mContext);
        final long endAlarmId = row().endAlarmId;
        assertTrue(endAlarmId >= 0);
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, endAlarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        final long before = System.currentTimeMillis();
        final int longest = mProfile.getSnoozeChoices()[2];
        AlarmStateManager.setSnoozeState(mContext, AlarmInstance.getInstance(mCr, instance.mId), false, longest);
        CalendarAlarmSync.EXECUTOR.submit(() -> { }).get();

        final AlarmInstance snoozed = AlarmInstance.getInstance(mCr, instance.mId);
        assertEquals(AlarmInstance.SNOOZE_STATE, snoozed.mAlarmState);
        final long until = snoozed.getAlarmTime().getTimeInMillis();
        final long expected = before + TimeUnit.MINUTES.toMillis(longest);
        assertTrue("snoozed until " + until, Math.abs(until - expected) < TimeUnit.MINUTES.toMillis(1));
        assertEquals(until, row().end);
        // The user is told, also in the event log.
        boolean logged = false;
        for (com.best.deskclock.provider.EventLogStore.EventLogEntry entry
                : com.best.deskclock.provider.EventLogStore.getEvents(mCr)) {
            logged |= "CALENDAR_EVENT_EXTENDED".equals(entry.eventType);
        }
        assertTrue(logged);
    }

    @Test
    public void snoozedAlarmAtTheEndSurvivesAMovedEnd() throws Exception {
        final long endAlarmId = row().endAlarmId;
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, endAlarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        final long before = System.currentTimeMillis();
        AlarmStateManager.setSnoozeState(mContext, AlarmInstance.getInstance(mCr, instance.mId), false);
        final long until = checkSnoozed(instance.mId, before);

        CalendarAlarmSyncTest.ENDS.put(1L, mBegin + TimeUnit.HOURS.toMillis(3));
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(until, checkSnoozed(instance.mId, before));
        assertEquals(endAlarmId, row().endAlarmId);
    }

    @Test
    public void snoozedStartAlarmRingsOnceAfterTheEventEnded() throws Exception {
        // The start alarm of a short event without an alarm at the end is snoozed past the end.
        final long now = System.currentTimeMillis();
        CalendarAlarmSyncTest.clearEvents();
        mProfile.ringAtEnd = false;
        mProfile.updateProfile(mCr);
        CalendarAlarmSyncTest.addEvent(3, 7, now + TimeUnit.MINUTES.toMillis(3), "Alarm: Short");
        CalendarAlarmSyncTest.ENDS.put(3L, now + TimeUnit.MINUTES.toMillis(20));
        CalendarAlarmSync.syncAll(mContext);
        final long startAlarmId = row().startAlarmId;
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, startAlarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        AlarmStateManager.setSnoozeState(mContext, AlarmInstance.getInstance(mCr, instance.mId), false);

        // The event is over when the snooze ends: it was snoozed on purpose, so it still rings.
        final CalendarAlarmEvent event = row();
        event.begin = now - TimeUnit.MINUTES.toMillis(30);
        event.end = now - TimeUnit.MINUTES.toMillis(1);
        event.save(mCr);
        assertTrue(CalendarAlarmSlots.isOverAfterSnooze(mContext,
            AlarmInstance.getInstance(mCr, instance.mId), now));
        AlarmStateManager.setFiredState(mContext, AlarmInstance.getInstance(mCr, instance.mId));
        assertEquals(AlarmInstance.FIRED_STATE, AlarmInstance.getInstance(mCr, instance.mId).mAlarmState);
    }

    @Test
    public void alarmAtTheEndReplacesASnoozedStartAlarm() throws Exception {
        final CalendarAlarmEvent event = row();
        final AlarmInstance start = AlarmInstance.getInstancesByAlarmId(mCr, event.startAlarmId).get(0);
        AlarmStateManager.setFiredState(mContext, start);
        AlarmStateManager.setSnoozeState(mContext, AlarmInstance.getInstance(mCr, start.mId), false);
        assertEquals(AlarmInstance.SNOOZE_STATE, AlarmInstance.getInstance(mCr, start.mId).mAlarmState);

        final AlarmInstance end = AlarmInstance.getInstancesByAlarmId(mCr, event.endAlarmId).get(0);
        AlarmStateManager.setFiredState(mContext, end);
        assertEquals(AlarmInstance.FIRED_STATE, AlarmInstance.getInstance(mCr, end.mId).mAlarmState);
        // The snoozed start alarm won't ring again after the end alarm.
        org.junit.Assert.assertNull(AlarmInstance.getInstance(mCr, start.mId));
    }

    @Test
    public void alarmAtTheStartReplacesASnoozedEarlyAlarm() throws Exception {
        final CalendarAlarmEvent event = row();
        final AlarmInstance early = AlarmInstance.getInstancesByAlarmId(mCr, event.alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, early);
        AlarmStateManager.setSnoozeState(mContext, AlarmInstance.getInstance(mCr, early.mId), false);

        final AlarmInstance start = AlarmInstance.getInstancesByAlarmId(mCr, event.startAlarmId).get(0);
        AlarmStateManager.setFiredState(mContext, start);
        assertEquals(AlarmInstance.FIRED_STATE, AlarmInstance.getInstance(mCr, start.mId).mAlarmState);
        org.junit.Assert.assertNull(AlarmInstance.getInstance(mCr, early.mId));
        // The event itself still has its alarm at the end.
        assertTrue(row().endAlarmId >= 0);
    }
}

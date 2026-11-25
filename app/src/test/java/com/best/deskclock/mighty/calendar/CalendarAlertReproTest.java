// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import static org.junit.Assert.assertEquals;

import android.Manifest;
import android.app.Application;
import android.content.ContentResolver;
import android.net.Uri;
import android.provider.CalendarContract;

import androidx.test.core.app.ApplicationProvider;

import com.best.deskclock.DeskClock;
import com.best.deskclock.alarms.AlarmActivity;
import com.best.deskclock.alarms.AlarmStateManager;
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
import org.robolectric.shadows.ShadowLooper;

import java.util.Calendar;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CalendarAlertReproTest {

    private Application mContext;
    private ContentResolver mCr;
    private CalendarProfile mProfile;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mCr = mContext.getContentResolver();
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.READ_CALENDAR);
        Robolectric.setupContentProvider(CalendarAlertSyncTest.FakeCalendarProvider.class, CalendarContract.AUTHORITY);
        mProfile = new CalendarProfile();
        mProfile.name = "🔕 Profil";
        mProfile.calendarId = 7;
        mProfile.calendarName = "denis";
        mProfile.accountName = "denis@example.com";
        mProfile.titleRegex = "🔕";
        mProfile.addProfile(mCr);
        final Calendar c = Calendar.getInstance();
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.HOUR_OF_DAY, 2);
        CalendarAlertSyncTest.clearEvents();
        CalendarAlertSyncTest.addEvent(1, 7, c.getTimeInMillis(), "🔕 Aufstehen %s 100%");
        CalendarAlertSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    @Test
    public void openEventsScreen() {
        Robolectric.buildActivity(CalendarProfileEventsActivity.class,
            CalendarProfileEventsActivity.createIntent(mContext, mProfile.id)).setup();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void openEditScreenAndSave() {
        final CalendarProfileEditActivity activity = Robolectric.buildActivity(CalendarProfileEditActivity.class,
            CalendarProfileEditActivity.createIntent(mContext, mProfile.id)).setup().get();
        activity.findViewById(com.best.deskclock.R.id.save_button).performClick();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void openMainScreen() {
        com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences(mContext).edit()
            .putBoolean(com.best.deskclock.settings.PreferencesKeys.KEY_IS_FIRST_LAUNCH, false)
            .putBoolean(com.best.deskclock.settings.PreferencesKeys.KEY_ESSENTIAL_PERMISSIONS_GRANTED, true)
            .commit();
        Robolectric.buildActivity(DeskClock.class).setup();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void fireSnoozeDismiss() {
        final long alarmId = CalendarAlertEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setSnoozeState(mContext, instance, false);
        instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.deleteInstanceAndUpdateParent(mContext, instance, false);
        org.junit.Assert.assertNull(Alarm.getAlarm(mCr, alarmId));
        CalendarAlertSync.syncAll(mContext);
        assertEquals(0, Alarm.getAlarms(mCr, null).size());
    }

    @org.junit.Ignore("AlarmActivity animations keep the looper busy under Robolectric")
    @Test
    public void fireAlarm() {
        final long alarmId = CalendarAlertEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        Robolectric.buildActivity(AlarmActivity.class,
            new android.content.Intent(mContext, AlarmActivity.class)
                .setData(AlarmInstance.getContentUri(instance.mId))).setup();
        ShadowLooper.idleMainLooper();
    }
}

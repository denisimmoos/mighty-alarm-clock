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
        CalendarAlertSyncTest.CALENDARS.put(7L, "denis@example.com");
        CalendarAlertSyncTest.addEvent(1, 7, c.getTimeInMillis(), "🔕 Aufstehen %s 100%");
        CalendarAlertSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    /** Lets background syncs started by the screens finish before the next test starts. */
    @org.junit.After
    public void drainBackgroundWork() throws Exception {
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
        ShadowLooper.idleMainLooper();
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
    }

    @Test
    public void openEventsScreen() {
        Robolectric.buildActivity(CalendarProfileEventsActivity.class,
            CalendarProfileEventsActivity.createIntent(mContext, mProfile.id)).setup();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void tappingAnEventOpensTheCalendarEntry() throws Exception {
        final CalendarProfileEventsActivity activity = Robolectric.buildActivity(CalendarProfileEventsActivity.class,
            CalendarProfileEventsActivity.createIntent(mContext, mProfile.id)).setup().get();
        for (int i = 0; i < 5; i++) {
            com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
            ShadowLooper.idleMainLooper();
        }
        final androidx.recyclerview.widget.RecyclerView list = activity.findViewById(com.best.deskclock.R.id.events_list);
        list.measure(1080, 1920);
        list.layout(0, 0, 1080, 1920);
        org.junit.Assert.assertTrue(list.getChildCount() > 0);
        list.getChildAt(0).performClick();
        final android.content.Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        org.junit.Assert.assertNotNull(started);
        assertEquals(android.content.Intent.ACTION_VIEW, started.getAction());
        assertEquals(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 1),
            started.getData());
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
    public void reopenWithCalendarTabSelected() {
        com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences(mContext).edit()
            .putBoolean(com.best.deskclock.settings.PreferencesKeys.KEY_IS_FIRST_LAUNCH, false)
            .putBoolean(com.best.deskclock.settings.PreferencesKeys.KEY_ESSENTIAL_PERMISSIONS_GRANTED, true)
            .putInt("selected_tab", com.best.deskclock.uidata.UiDataModel.Tab.CALENDAR_ALERT.ordinal())
            .commit();
        org.robolectric.android.controller.ActivityController<DeskClock> c =
            Robolectric.buildActivity(DeskClock.class).setup();
        ShadowLooper.idleMainLooper();
        c.pause().stop().destroy();
        org.robolectric.shadows.ShadowLog.stream = System.out;
        Robolectric.buildActivity(DeskClock.class).setup();
        for (int i = 0; i < 20; i++) {
            ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
        }
    }

    @Test
    public void openWithCalendarTabToDisplay() {
        com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences(mContext).edit()
            .putBoolean(com.best.deskclock.settings.PreferencesKeys.KEY_IS_FIRST_LAUNCH, false)
            .putBoolean(com.best.deskclock.settings.PreferencesKeys.KEY_ESSENTIAL_PERMISSIONS_GRANTED, true)
            .putString(com.best.deskclock.settings.PreferencesKeys.KEY_TAB_TO_DISPLAY, "4")
            .commit();
        Robolectric.buildActivity(DeskClock.class).setup();
        ShadowLooper.idleMainLooper();
    }

    private void syncInBackground() throws Exception {
        final Throwable[] error = new Throwable[1];
        final Thread t = new Thread(() -> {
            try {
                CalendarAlertSync.syncAll(mContext);
            } catch (Throwable e) {
                error[0] = e;
            }
        });
        t.setUncaughtExceptionHandler((thread, e) -> error[0] = e);
        t.start();
        t.join();
        if (error[0] != null) {
            throw new AssertionError(error[0]);
        }
    }

    /** Sync runs in the background on a phone: creating and updating alarms there must work. */
    @Test
    public void syncOffMainThread() throws Exception {
        final long alarmId = CalendarAlertEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        AlarmStateManager.deleteAllInstances(mContext, alarmId);
        Alarm.deleteAlarm(mCr, alarmId);
        CalendarAlertEvent.deleteEventsForProfile(mCr, mProfile.id);
        org.robolectric.shadows.ShadowLog.clear();

        syncInBackground();
        final long newId = CalendarAlertEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        org.junit.Assert.assertNotNull(Alarm.getAlarm(mCr, newId));
        assertEquals(1, AlarmInstance.getInstancesByAlarmId(mCr, newId).size());

        // An existing alarm is checked again: this used to crash the app in the background.
        AlarmStateManager.deleteAllInstances(mContext, newId);
        syncInBackground();
        assertEquals(1, AlarmInstance.getInstancesByAlarmId(mCr, newId).size());

        for (org.robolectric.shadows.ShadowLog.LogItem item : org.robolectric.shadows.ShadowLog.getLogs()) {
            org.junit.Assert.assertFalse(String.valueOf(item.msg), item.type >= android.util.Log.ERROR
                && String.valueOf(item.msg).contains("CalendarAlert"));
        }
    }

    /** Moving an event is checked in the background on a phone: the old alert must go away. */
    @Test
    public void movedEventOffMainThreadLeavesOneAlert() throws Exception {
        org.robolectric.shadows.ShadowLog.stream = System.out;
        CalendarAlertSyncTest.clearEvents();
        CalendarAlertSyncTest.CALENDARS.put(7L, "denis@example.com");
        final Calendar c = Calendar.getInstance();
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.HOUR_OF_DAY, 5);
        CalendarAlertSyncTest.addEvent(1, 7, c.getTimeInMillis(), "🔕 Aufstehen %s 100%");
        syncInBackground();
        final java.util.List<CalendarAlertEvent> rows = CalendarAlertEvent.getEventsForProfile(mCr, mProfile.id);
        assertEquals(1, rows.size());
        assertEquals(c.getTimeInMillis(), rows.get(0).begin);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
        assertEquals(c.getTimeInMillis(), AlarmInstance.getInstancesByAlarmId(mCr, rows.get(0).alarmId)
            .get(0).getAlarmTime().getTimeInMillis());
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

    private void openMainPrefs() {
        com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences(mContext).edit()
            .putBoolean(com.best.deskclock.settings.PreferencesKeys.KEY_IS_FIRST_LAUNCH, false)
            .putBoolean(com.best.deskclock.settings.PreferencesKeys.KEY_ESSENTIAL_PERMISSIONS_GRANTED, true)
            .putInt("selected_tab", com.best.deskclock.uidata.UiDataModel.Tab.CALENDAR_ALERT.ordinal())
            .commit();
    }

    @Test
    public void reopenAfterDismiss() {
        final long alarmId = CalendarAlertEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.deleteInstanceAndUpdateParent(mContext, instance, false);
        CalendarAlertSync.syncAll(mContext);
        openMainPrefs();
        Robolectric.buildActivity(DeskClock.class).setup();
        ShadowLooper.idleMainLooper();
        Robolectric.buildActivity(CalendarProfileEventsActivity.class,
            CalendarProfileEventsActivity.createIntent(mContext, mProfile.id)).setup();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void reopenWhileFiredAndMissed() {
        final long alarmId = CalendarAlertEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        openMainPrefs();
        Robolectric.buildActivity(DeskClock.class).setup();
        ShadowLooper.idleMainLooper();
        instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setMissedState(mContext, instance);
        CalendarAlertSync.syncAll(mContext);
        Robolectric.buildActivity(DeskClock.class).setup();
        ShadowLooper.idleMainLooper();
    }

    @Test(timeout = 60000)
    public void fireAlarmCreateOnly() {
        final long alarmId = CalendarAlertEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        final AlarmActivity activity = Robolectric.buildActivity(AlarmActivity.class,
            new android.content.Intent(mContext, AlarmActivity.class)
                .setData(AlarmInstance.getContentUri(instance.mId))).create().start().postCreate(null).resume().get();
        // Calendar alarms say "Dismiss", not "Delete".
        final String delete = mContext.getString(com.best.deskclock.R.string.delete);
        for (int id : new int[] {com.best.deskclock.R.id.dismiss_button, com.best.deskclock.R.id.dismiss_only_button,
            com.best.deskclock.R.id.dismiss_text}) {
            final android.view.View v = activity.findViewById(id);
            if (v instanceof android.widget.TextView) {
                org.junit.Assert.assertNotEquals(delete, ((android.widget.TextView) v).getText().toString());
            }
        }
    }

    @Test
    public void notificationSaysDismiss() {
        final long alarmId = CalendarAlertEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        com.best.deskclock.alarms.AlarmNotifications.showUpcomingNotification(mContext, instance);
        final android.app.NotificationManager nm = mContext.getSystemService(android.app.NotificationManager.class);
        final java.util.List<android.app.Notification> all = Shadows.shadowOf(nm).getAllNotifications();
        org.junit.Assert.assertFalse(all.isEmpty());
        boolean found = false;
        for (android.app.Notification n : all) {
            if (n.actions == null) continue;
            for (android.app.Notification.Action a : n.actions) {
                org.junit.Assert.assertNotEquals(mContext.getString(com.best.deskclock.R.string.alarm_alert_dismiss_and_delete_text), a.title.toString());
                if (a.title.toString().equals(mContext.getString(com.best.deskclock.R.string.alarm_alert_dismiss_text))) found = true;
            }
        }
        org.junit.Assert.assertTrue(found);
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

    @Test(timeout = 60000)
    public void alarmShowsDescriptionAndOpensLocationInMaps() throws Exception {
        CalendarAlertSyncTest.EVENT_DETAILS.put(1L, new String[]{"Bring the <b>insurance card</b><br>Room 3",
            "Bahnhofstrasse 1, Zürich"});
        final long alarmId = CalendarAlertEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        final AlarmActivity activity = Robolectric.buildActivity(AlarmActivity.class,
            new android.content.Intent(mContext, AlarmActivity.class)
                .setData(AlarmInstance.getContentUri(instance.mId))).create().start().postCreate(null).resume().get();
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
        ShadowLooper.idleMainLooper();

        final android.widget.TextView description = activity.findViewById(com.best.deskclock.R.id.calendar_description);
        final android.widget.TextView location = activity.findViewById(com.best.deskclock.R.id.calendar_location);
        assertEquals(android.view.View.VISIBLE, description.getVisibility());
        assertEquals("Bring the insurance card\nRoom 3", description.getText().toString());
        assertEquals(android.view.View.VISIBLE, location.getVisibility());
        assertEquals("Bahnhofstrasse 1, Zürich", location.getText().toString());

        location.performClick();
        final android.content.Intent maps = Shadows.shadowOf(activity).getNextStartedActivity();
        org.junit.Assert.assertNotNull(maps);
        assertEquals("com.google.android.apps.maps", maps.getPackage());
        assertEquals("geo:0,0?q=Bahnhofstrasse%201%2C%20Z%C3%BCrich", maps.getDataString());
    }

    @Test(timeout = 60000)
    public void alarmWithoutDescriptionOrLocationShowsNothingExtra() throws Exception {
        final long alarmId = CalendarAlertEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        final AlarmActivity activity = Robolectric.buildActivity(AlarmActivity.class,
            new android.content.Intent(mContext, AlarmActivity.class)
                .setData(AlarmInstance.getContentUri(instance.mId))).create().start().postCreate(null).resume().get();
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
        ShadowLooper.idleMainLooper();
        assertEquals(android.view.View.GONE,
            activity.findViewById(com.best.deskclock.R.id.calendar_info).getVisibility());
    }

    @Test
    public void gearInTheTitleBarOpensTheProfileSettings() {
        final CalendarProfileEventsActivity activity = Robolectric.buildActivity(CalendarProfileEventsActivity.class,
            CalendarProfileEventsActivity.createIntent(mContext, mProfile.id)).setup().get();
        ShadowLooper.idleMainLooper();
        org.junit.Assert.assertNull(activity.findViewById(
            activity.getResources().getIdentifier("edit_profile_button", "id", activity.getPackageName())));
        final androidx.appcompat.widget.Toolbar toolbar = activity.findViewById(com.best.deskclock.R.id.action_bar);
        final android.view.MenuItem gear = toolbar.getMenu().getItem(0);
        assertEquals(mContext.getString(com.best.deskclock.R.string.calendar_alert_edit_profile), gear.getTitle().toString());
        org.junit.Assert.assertTrue(activity.onOptionsItemSelected(gear));
        final android.content.Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(CalendarProfileEditActivity.class.getName(), started.getComponent().getClassName());
    }
}

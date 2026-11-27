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
import com.best.deskclock.provider.CalendarAlarmEvent;
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
public class CalendarAlarmReproTest {

    private Application mContext;
    private ContentResolver mCr;
    private CalendarProfile mProfile;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mCr = mContext.getContentResolver();
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.READ_CALENDAR);
        Robolectric.setupContentProvider(CalendarAlarmSyncTest.FakeCalendarProvider.class, CalendarContract.AUTHORITY);
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
        CalendarAlarmSyncTest.clearEvents();
        CalendarAlarmSyncTest.CALENDARS.put(7L, "denis@example.com");
        CalendarAlarmSyncTest.addEvent(1, 7, c.getTimeInMillis(), "🔕 Aufstehen %s 100%");
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
    }

    /** Lets background syncs started by the screens finish before the next test starts. */
    @org.junit.After
    public void drainBackgroundWork() throws Exception {
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
        // Calendar checks started by a screen run on their own thread; let them finish so they
        // can't change the next test's database.
        CalendarAlarmSync.EXECUTOR.submit(() -> { }).get();
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
            .putInt("selected_tab", com.best.deskclock.uidata.UiDataModel.Tab.CALENDAR_ALARM.ordinal())
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
                CalendarAlarmSync.syncAll(mContext);
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
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        AlarmStateManager.deleteAllInstances(mContext, alarmId);
        Alarm.deleteAlarm(mCr, alarmId);
        CalendarAlarmEvent.deleteEventsForProfile(mCr, mProfile.id);
        org.robolectric.shadows.ShadowLog.clear();

        syncInBackground();
        final long newId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        org.junit.Assert.assertNotNull(Alarm.getAlarm(mCr, newId));
        assertEquals(1, AlarmInstance.getInstancesByAlarmId(mCr, newId).size());

        // An existing alarm is checked again: this used to crash the app in the background.
        AlarmStateManager.deleteAllInstances(mContext, newId);
        syncInBackground();
        assertEquals(1, AlarmInstance.getInstancesByAlarmId(mCr, newId).size());

        for (org.robolectric.shadows.ShadowLog.LogItem item : org.robolectric.shadows.ShadowLog.getLogs()) {
            org.junit.Assert.assertFalse(String.valueOf(item.msg), item.type >= android.util.Log.ERROR
                && String.valueOf(item.msg).contains("CalendarAlarm"));
        }
    }

    /** Moving an event is checked in the background on a phone: the old alarm must go away. */
    @Test
    public void movedEventOffMainThreadLeavesOneAlert() throws Exception {
        org.robolectric.shadows.ShadowLog.stream = System.out;
        CalendarAlarmSyncTest.clearEvents();
        CalendarAlarmSyncTest.CALENDARS.put(7L, "denis@example.com");
        final Calendar c = Calendar.getInstance();
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.HOUR_OF_DAY, 5);
        CalendarAlarmSyncTest.addEvent(1, 7, c.getTimeInMillis(), "🔕 Aufstehen %s 100%");
        syncInBackground();
        final java.util.List<CalendarAlarmEvent> rows = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id);
        assertEquals(1, rows.size());
        assertEquals(c.getTimeInMillis(), rows.get(0).begin);
        assertEquals(1, Alarm.getAlarms(mCr, null).size());
        assertEquals(c.getTimeInMillis(), AlarmInstance.getInstancesByAlarmId(mCr, rows.get(0).alarmId)
            .get(0).getAlarmTime().getTimeInMillis());
    }

    @Test
    public void fireSnoozeDismiss() {
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setSnoozeState(mContext, instance, false);
        instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.deleteInstanceAndUpdateParent(mContext, instance, false);
        org.junit.Assert.assertNull(Alarm.getAlarm(mCr, alarmId));
        CalendarAlarmSync.syncAll(mContext);
        assertEquals(0, Alarm.getAlarms(mCr, null).size());
    }

    private void openMainPrefs() {
        com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences(mContext).edit()
            .putBoolean(com.best.deskclock.settings.PreferencesKeys.KEY_IS_FIRST_LAUNCH, false)
            .putBoolean(com.best.deskclock.settings.PreferencesKeys.KEY_ESSENTIAL_PERMISSIONS_GRANTED, true)
            .putInt("selected_tab", com.best.deskclock.uidata.UiDataModel.Tab.CALENDAR_ALARM.ordinal())
            .commit();
    }

    @Test
    public void reopenAfterDismiss() {
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.deleteInstanceAndUpdateParent(mContext, instance, false);
        CalendarAlarmSync.syncAll(mContext);
        openMainPrefs();
        Robolectric.buildActivity(DeskClock.class).setup();
        ShadowLooper.idleMainLooper();
        Robolectric.buildActivity(CalendarProfileEventsActivity.class,
            CalendarProfileEventsActivity.createIntent(mContext, mProfile.id)).setup();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void reopenWhileFiredAndMissed() {
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        openMainPrefs();
        Robolectric.buildActivity(DeskClock.class).setup();
        ShadowLooper.idleMainLooper();
        instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setMissedState(mContext, instance);
        CalendarAlarmSync.syncAll(mContext);
        Robolectric.buildActivity(DeskClock.class).setup();
        ShadowLooper.idleMainLooper();
    }

    @Test(timeout = 60000)
    public void fireAlarmCreateOnly() {
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
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

    @Test(timeout = 60000)
    public void alarmScreenSnoozesOnlyWithTheThreeLengthButtons() {
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        final AlarmActivity activity = Robolectric.buildActivity(AlarmActivity.class,
            new android.content.Intent(mContext, AlarmActivity.class)
                .setData(AlarmInstance.getContentUri(instance.mId))).create().start().postCreate(null).resume().get();
        assertEquals(android.view.View.VISIBLE,
            activity.findViewById(com.best.deskclock.R.id.calendar_snooze_choices).getVisibility());
        final int[] ids = {com.best.deskclock.R.id.calendar_snooze_choice_1,
            com.best.deskclock.R.id.calendar_snooze_choice_2, com.best.deskclock.R.id.calendar_snooze_choice_3};
        final String[] labels = {"+5", "+10", "+20"};
        for (int i = 0; i < ids.length; i++) {
            assertEquals(labels[i], ((android.widget.TextView) activity.findViewById(ids[i])).getText().toString());
        }
        // No other way to snooze: no snooze picker, no snooze button, and the slider only dismisses.
        assertEquals(android.view.View.GONE,
            activity.findViewById(com.best.deskclock.R.id.snooze_selector_layout).getVisibility());
        assertEquals(android.view.View.GONE,
            activity.findViewById(com.best.deskclock.R.id.snooze_button).getVisibility());
        final String dismiss = mContext.getString(com.best.deskclock.R.string.button_action_dismiss);
        if (activity.findViewById(com.best.deskclock.R.id.slide_zone_layout).getVisibility() == android.view.View.VISIBLE) {
            assertEquals(dismiss, ((android.widget.TextView) activity.findViewById(
                com.best.deskclock.R.id.snooze_text)).getText().toString());
        } else {
            assertEquals(dismiss, ((android.widget.TextView) activity.findViewById(
                com.best.deskclock.R.id.dismiss_only_button)).getText().toString());
        }

        final long before = System.currentTimeMillis();
        activity.findViewById(com.best.deskclock.R.id.calendar_snooze_choice_3).performClick();
        final AlarmInstance snoozed = AlarmInstance.getInstance(mCr, instance.mId);
        assertEquals(AlarmInstance.SNOOZE_STATE, snoozed.mAlarmState);
        final long expected = before + java.util.concurrent.TimeUnit.MINUTES.toMillis(20);
        org.junit.Assert.assertTrue(Math.abs(snoozed.getAlarmTime().getTimeInMillis() - expected) < 61_000);
    }

    @Test
    public void notificationOffersTheThreeSnoozeLengths() throws Exception {
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        AlarmStateManager.setFiredState(mContext, instance);
        final java.lang.reflect.Method show = com.best.deskclock.alarms.AlarmNotifications.class
            .getDeclaredMethod("showAlarmNotification", android.content.Context.class, AlarmInstance.class);
        show.setAccessible(true);
        show.invoke(null, mContext, AlarmInstance.getInstance(mCr, instance.mId));
        final android.app.NotificationManager nm = mContext.getSystemService(android.app.NotificationManager.class);
        android.app.Notification ringing = null;
        for (android.app.Notification n : Shadows.shadowOf(nm).getAllNotifications()) {
            if (android.app.Notification.CATEGORY_ALARM.equals(n.category) && n.fullScreenIntent != null) {
                ringing = n;
            }
        }
        org.junit.Assert.assertNotNull(ringing);
        // The lengths are buttons in the small and the large view; the actions only dismiss.
        org.junit.Assert.assertNotNull(ringing.headsUpContentView);
        org.junit.Assert.assertNotNull(ringing.contentView);
        for (android.app.Notification.Action a : ringing.actions) {
            org.junit.Assert.assertNotEquals(mContext.getString(com.best.deskclock.R.string.alarm_alert_snooze_text),
                a.title.toString());
        }

        // A tap on "+10 min" snoozes for 10 minutes.
        final android.content.Intent snooze = AlarmStateManager.createStateChangeIntent(mContext,
            AlarmStateManager.ALARM_SNOOZE_TAG, instance, AlarmInstance.SNOOZE_STATE);
        snooze.putExtra(AlarmStateManager.SNOOZE_MINUTES_EXTRA, 10);
        final long before = System.currentTimeMillis();
        AlarmStateManager.handleIntent(mContext, snooze);
        final AlarmInstance snoozed = AlarmInstance.getInstance(mCr, instance.mId);
        assertEquals(AlarmInstance.SNOOZE_STATE, snoozed.mAlarmState);
        final long expected = before + java.util.concurrent.TimeUnit.MINUTES.toMillis(10);
        org.junit.Assert.assertTrue(Math.abs(snoozed.getAlarmTime().getTimeInMillis() - expected) < 61_000);
    }

    @Test
    public void notificationSaysDismiss() {
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
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
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        Robolectric.buildActivity(AlarmActivity.class,
            new android.content.Intent(mContext, AlarmActivity.class)
                .setData(AlarmInstance.getContentUri(instance.mId))).setup();
        ShadowLooper.idleMainLooper();
    }

    @Test(timeout = 60000)
    public void alarmShowsDescriptionAndOpensLocationInMaps() throws Exception {
        CalendarAlarmSyncTest.EVENT_DETAILS.put(1L, new String[]{"Bring the <b>insurance card</b><br>Room 3",
            "Bahnhofstrasse 1, Zürich"});
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
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
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
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
    public void earlyAlarmShowsHowLongUntilTheEvent() throws Exception {
        mProfile.offsetEnabled = true;
        mProfile.offsetMinutes = 10;
        mProfile.updateProfile(mCr);
        CalendarAlarmSync.syncAll(mContext);
        final CalendarAlarmEvent event = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0);
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, event.alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        final AlarmActivity activity = Robolectric.buildActivity(AlarmActivity.class,
            new android.content.Intent(mContext, AlarmActivity.class)
                .setData(AlarmInstance.getContentUri(instance.mId))).create().start().postCreate(null).resume().get();
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
        ShadowLooper.idleMainLooper();
        final android.widget.TextView countdown = activity.findViewById(com.best.deskclock.R.id.calendar_countdown);
        assertEquals(android.view.View.VISIBLE, countdown.getVisibility());
        final String expected = mContext.getString(com.best.deskclock.R.string.calendar_alarm_event_in,
            CalendarEventText.formatTimeUntil(mContext, event.begin, System.currentTimeMillis()));
        assertEquals(expected, countdown.getText().toString());
        assertEquals(android.view.View.VISIBLE,
            activity.findViewById(com.best.deskclock.R.id.calendar_info).getVisibility());
        activity.finish();
    }

    @Test
    public void gearInTheTitleBarOpensTheProfileSettings() {
        final CalendarProfileEventsActivity activity = Robolectric.buildActivity(CalendarProfileEventsActivity.class,
            CalendarProfileEventsActivity.createIntent(mContext, mProfile.id)).setup().get();
        ShadowLooper.idleMainLooper();
        org.junit.Assert.assertNull(activity.findViewById(
            activity.getResources().getIdentifier("edit_profile_button", "id", activity.getPackageName())));
        final android.view.View gear = activity.findViewById(com.best.deskclock.R.id.calendar_profile_settings_gear);
        org.junit.Assert.assertNotNull(gear);
        gear.performClick();
        final android.content.Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(CalendarProfileEditActivity.class.getName(), started.getComponent().getClassName());
    }

    @Test(timeout = 60000)
    public void deleteButtonDeletesTheProfileAndItsAlarms() throws Exception {
        // Even a ringing alarm goes away with its profile.
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        AlarmStateManager.setFiredState(mContext, AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0));
        final CalendarProfileEditActivity activity = Robolectric.buildActivity(CalendarProfileEditActivity.class,
            CalendarProfileEditActivity.createIntent(mContext, mProfile.id)).setup().get();
        ShadowLooper.idleMainLooper();
        activity.findViewById(com.best.deskclock.R.id.delete_button).performClick();
        ShadowLooper.idleMainLooper();
        final android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        org.junit.Assert.assertNotNull(dialog);
        ((androidx.appcompat.app.AlertDialog) dialog).getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
        ShadowLooper.idleMainLooper();
        CalendarAlarmSync.EXECUTOR.submit(() -> { }).get();
        ShadowLooper.idleMainLooper();

        org.junit.Assert.assertTrue(activity.isFinishing());
        org.junit.Assert.assertTrue(AlarmInstance.getInstances(mCr, null).isEmpty());
        org.junit.Assert.assertNull(CalendarProfile.getProfile(mCr, mProfile.id));
        org.junit.Assert.assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
        org.junit.Assert.assertTrue(CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).isEmpty());
    }

    @Test(timeout = 60000)
    public void hiddenDetailsKeepTheTitleOffTheLockScreenUntilUnlock() throws Exception {
        mProfile.showDetailsLocked = false;
        mProfile.updateProfile(mCr);
        CalendarAlarmSyncTest.EVENT_DETAILS.put(1L, new String[]{"Private note", "Somewhere"});
        final android.app.KeyguardManager keyguard = mContext.getSystemService(android.app.KeyguardManager.class);
        Shadows.shadowOf(keyguard).setKeyguardLocked(true);

        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        AlarmStateManager.setFiredState(mContext, instance);
        final AlarmActivity activity = Robolectric.buildActivity(AlarmActivity.class,
            new android.content.Intent(mContext, AlarmActivity.class)
                .setData(AlarmInstance.getContentUri(instance.mId))).create().start().postCreate(null).resume().get();
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
        ShadowLooper.idleMainLooper();

        final android.widget.TextView title = activity.findViewById(com.best.deskclock.R.id.alarm_title);
        assertEquals(mContext.getString(com.best.deskclock.R.string.calendar_alarm_hidden_title), title.getText().toString());
        assertEquals(android.view.View.GONE,
            activity.findViewById(com.best.deskclock.R.id.calendar_info).getVisibility());

        // Unlocking shows everything.
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);
        mContext.sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_USER_PRESENT));
        ShadowLooper.idleMainLooper();
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
        ShadowLooper.idleMainLooper();
        assertEquals("🔕 Aufstehen %s 100%", title.getText().toString());
        assertEquals(android.view.View.VISIBLE,
            activity.findViewById(com.best.deskclock.R.id.calendar_info).getVisibility());
    }

    @Test
    public void hiddenDetailsKeepTheTitleOutOfTheLockScreenNotification() {
        mProfile.showDetailsLocked = false;
        mProfile.updateProfile(mCr);
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        final AlarmInstance instance = AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        com.best.deskclock.alarms.AlarmNotifications.showUpcomingNotification(mContext, instance);
        final android.app.NotificationManager nm = mContext.getSystemService(android.app.NotificationManager.class);
        boolean found = false;
        for (android.app.Notification n : Shadows.shadowOf(nm).getAllNotifications()) {
            if (n.publicVersion == null) continue;
            found = true;
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, n.visibility);
            assertEquals(mContext.getString(com.best.deskclock.R.string.calendar_alarm_hidden_title),
                n.publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString());
        }
        org.junit.Assert.assertTrue(found);
    }
}

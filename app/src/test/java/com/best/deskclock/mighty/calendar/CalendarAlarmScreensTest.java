// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.best.deskclock.R;
import com.best.deskclock.provider.CalendarProfile;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * Opens the calendar alarm screens to catch layout and setup errors.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CalendarAlarmScreensTest {

    @org.junit.After
    public void drainBackgroundWork() throws Exception {
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
        // Calendar checks started by a screen run on their own thread; let them finish so they
        // can't change the next test's database.
        CalendarAlarmSync.EXECUTOR.submit(() -> { }).get();
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
    }


    @Test
    public void editScreenOpensForNewAndExistingProfile() {
        final Application context = ApplicationProvider.getApplicationContext();

        try (ActivityController<CalendarProfileEditActivity> controller = Robolectric.buildActivity(
            CalendarProfileEditActivity.class,
            CalendarProfileEditActivity.createIntent(context, CalendarProfile.INVALID_ID)).setup()) {
            assertNotNull(controller.get().findViewById(R.id.save_button));
        }

        final CalendarProfile profile = new CalendarProfile();
        profile.name = "Work";
        profile.calendarId = 3;
        profile.calendarName = "Work";
        profile.accountName = "someone@example.com";
        profile.addProfile(context.getContentResolver());

        try (ActivityController<CalendarProfileEditActivity> controller = Robolectric.buildActivity(
            CalendarProfileEditActivity.class,
            CalendarProfileEditActivity.createIntent(context, profile.id)).setup()) {
            final TextView name = controller.get().findViewById(R.id.name_input);
            assertEquals("Work", name.getText().toString());
        }
    }

    @Test
    public void cloneKeepsTheSettingsWithAnEmptyPatternAndSavesAsNewProfile() throws Exception {
        final Application context = ApplicationProvider.getApplicationContext();
        final CalendarProfile profile = new CalendarProfile();
        profile.name = "Work";
        profile.calendarId = 3;
        profile.calendarName = "Work";
        profile.accountName = "someone@example.com";
        profile.titleRegex = "^Alarm:";
        profile.ringAtEnd = true;
        profile.offsetEnabled = true;
        profile.snoozeDuration = 7;
        profile.addProfile(context.getContentResolver());

        try (ActivityController<CalendarProfileEditActivity> controller = Robolectric.buildActivity(
            CalendarProfileEditActivity.class,
            CalendarProfileEditActivity.createCloneIntent(context, profile.id)).setup()) {
            final CalendarProfileEditActivity activity = controller.get();
            final TextView name = activity.findViewById(R.id.name_input);
            final TextView regex = activity.findViewById(R.id.regex_input);
            assertEquals("Work (clone)", name.getText().toString());
            assertEquals("", regex.getText().toString());
            org.junit.Assert.assertTrue(regex.hasFocus());

            regex.setText("^Meeting:");
            activity.findViewById(R.id.save_button).performClick();
            CalendarAlarmSync.EXECUTOR.submit(() -> { }).get();
        }

        final java.util.List<CalendarProfile> profiles = CalendarProfile.getProfiles(context.getContentResolver());
        assertEquals(2, profiles.size());
        final CalendarProfile original = CalendarProfile.getProfile(context.getContentResolver(), profile.id);
        assertEquals("^Alarm:", original.titleRegex);
        final CalendarProfile clone = profiles.get(0).id == profile.id ? profiles.get(1) : profiles.get(0);
        assertEquals("Work (clone)", clone.name);
        assertEquals("^Meeting:", clone.titleRegex);
        assertEquals(3, clone.calendarId);
        org.junit.Assert.assertTrue(clone.ringAtEnd);
        org.junit.Assert.assertTrue(clone.offsetEnabled);
        assertEquals(7, clone.snoozeDuration);
    }

    @Test
    public void eventsScreenOpens() {
        final Application context = ApplicationProvider.getApplicationContext();
        final CalendarProfile profile = new CalendarProfile();
        profile.name = "Work";
        profile.calendarId = 3;
        profile.addProfile(context.getContentResolver());

        try (ActivityController<CalendarProfileEventsActivity> controller = Robolectric.buildActivity(
            CalendarProfileEventsActivity.class,
            CalendarProfileEventsActivity.createIntent(context, profile.id)).setup()) {
            ShadowLooper.idleMainLooper();
            assertNotNull(controller.get().findViewById(R.id.events_list));
        }
    }

    @Test
    public void tabFragmentOpens() {
        try (ActivityController<androidx.fragment.app.FragmentActivity> controller =
                 Robolectric.buildActivity(androidx.fragment.app.FragmentActivity.class).setup()) {
            controller.get().setTheme(R.style.Theme_DeskClock);
            final CalendarAlarmFragment fragment = new CalendarAlarmFragment();
            controller.get().getSupportFragmentManager().beginTransaction()
                .add(android.R.id.content, fragment).commitNow();
            assertNotNull(fragment.getView());
        }
    }

    @Test
    public void permissionsScreenListsCalendarAccess() {
        try (ActivityController<com.best.deskclock.settings.PermissionsManagementActivity> controller =
                 Robolectric.buildActivity(com.best.deskclock.settings.PermissionsManagementActivity.class).setup()) {
            ShadowLooper.idleMainLooper();
            final androidx.preference.PreferenceFragmentCompat fragment = (androidx.preference.PreferenceFragmentCompat)
                controller.get().getSupportFragmentManager().findFragmentById(R.id.content_frame);
            assertNotNull(fragment);
            final androidx.preference.Preference pref = fragment.findPreference(
                com.best.deskclock.settings.PreferencesKeys.KEY_CALENDAR_PERMISSION);
            assertNotNull(pref);
            org.junit.Assert.assertTrue(pref.isVisible());
        }
    }
}

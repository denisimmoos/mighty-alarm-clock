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
 * Opens the calendar alert screens to catch layout and setup errors.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CalendarAlertScreensTest {

    @org.junit.After
    public void drainBackgroundWork() throws Exception {
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
            final CalendarAlertFragment fragment = new CalendarAlertFragment();
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

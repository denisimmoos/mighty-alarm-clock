// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.TypedValue;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.R;
import com.best.deskclock.DeskClockApplication;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** A fresh install opens with the Blue accent color; debug builds (which the tests use) with Orange. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DefaultAccentColorTest {

    @Test
    public void freshInstallHasTheDefaultAccentColor() {
        final Application app = ApplicationProvider.getApplicationContext();
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(app);
        assertFalse(prefs.contains("key_accent_color"));
        final CalendarProfileEditActivity activity = Robolectric.buildActivity(CalendarProfileEditActivity.class,
            CalendarProfileEditActivity.createIntent(app, CalendarProfile.INVALID_ID)).setup().get();
        final TypedValue value = new TypedValue();
        activity.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true);
        assertEquals(ContextCompat.getColor(activity, com.best.deskclock.BuildConfig.IS_DEBUG_BUILD ? R.color.orangeColorPrimary : R.color.blueColorPrimary), value.data);
    }

    @Test
    public void olderDebugDataWithBlueIsSwitchedToOrangeOnce() {
        final Application app = ApplicationProvider.getApplicationContext();
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(app);
        prefs.edit().clear().putString("key_accent_color", "11").putString("key_night_accent_color", "7").commit();
        DeskClockApplication.migrateDebugAccentToOrange(prefs, true);
        assertEquals("5", prefs.getString("key_accent_color", null));
        assertEquals("7", prefs.getString("key_night_accent_color", null));
        // A colour picked afterwards is kept.
        prefs.edit().putString("key_accent_color", "11").commit();
        DeskClockApplication.migrateDebugAccentToOrange(prefs, true);
        assertEquals("11", prefs.getString("key_accent_color", null));
    }

    @Test
    public void releaseBuildsKeepBlue() {
        final Application app = ApplicationProvider.getApplicationContext();
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(app);
        prefs.edit().clear().putString("key_accent_color", "11").commit();
        DeskClockApplication.migrateDebugAccentToOrange(prefs, false);
        assertEquals("11", prefs.getString("key_accent_color", null));
    }
}

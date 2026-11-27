// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;

import androidx.test.core.app.ApplicationProvider;

import com.best.deskclock.mighty.calendar.CalendarProfileEditActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The launcher offers "Create new profile" next to the timer, stopwatch and screensaver. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NewProfileShortcutTest {

    @Test
    public void newProfileShortcutOpensTheProfileEditor() {
        final Application app = ApplicationProvider.getApplicationContext();
        new ShortcutController().updateShortcuts();
        ShortcutInfo found = null;
        for (ShortcutInfo info : app.getSystemService(ShortcutManager.class).getDynamicShortcuts()) {
            if ("Create new profile".contentEquals(info.getLongLabel())) {
                found = info;
            }
        }
        assertNotNull(found);
        assertEquals(0, found.getRank());
        final Intent[] intents = found.getIntents();
        assertEquals(CalendarProfileEditActivity.class.getName(),
            intents[intents.length - 1].getComponent().getClassName());
    }
}

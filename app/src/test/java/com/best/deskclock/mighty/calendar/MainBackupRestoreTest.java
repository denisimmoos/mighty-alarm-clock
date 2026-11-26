// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.utils.BackupAndRestoreUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * The backup and restore in the app's main settings also covers calendar alert profiles.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MainBackupRestoreTest {

    @Test(timeout = 120000)
    public void mainBackupAndRestoreBringsProfilesBack() throws Exception {
        final Application context = ApplicationProvider.getApplicationContext();
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        final CalendarProfile profile = new CalendarProfile();
        profile.name = "Work";
        profile.calendarId = 3;
        profile.calendarName = "Work";
        profile.accountName = "someone@example.com";
        profile.titleRegex = "^Alarm:";
        profile.vibrateOnly = true;
        profile.allDayMinutes = 9 * 60;
        profile.addProfile(context.getContentResolver());

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupAndRestoreUtils.settingsToJsonStream(context, prefs, prefs.getAll(), out);

        // Lost and replaced by another profile before restoring.
        CalendarProfile.deleteProfile(context.getContentResolver(), profile.id);
        final CalendarProfile other = new CalendarProfile();
        other.name = "Other";
        other.addProfile(context.getContentResolver());

        BackupAndRestoreUtils.readJson(context, prefs, new ByteArrayInputStream(out.toByteArray()));

        final List<CalendarProfile> profiles = CalendarProfile.getProfiles(context.getContentResolver());
        assertEquals(1, profiles.size());
        assertEquals("Work", profiles.get(0).name);
        assertEquals("^Alarm:", profiles.get(0).titleRegex);
        assertTrue(profiles.get(0).vibrateOnly);
        assertEquals(9 * 60, profiles.get(0).allDayMinutes);
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
    }

    @Test(timeout = 120000)
    public void oldMainBackupKeepsExistingProfiles() throws Exception {
        final Application context = ApplicationProvider.getApplicationContext();
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupAndRestoreUtils.settingsToJsonStream(context, prefs, prefs.getAll(), out);
        final org.json.JSONObject json = new org.json.JSONObject(out.toString("UTF-8"));
        json.remove("Calendar alert profiles");

        final CalendarProfile profile = new CalendarProfile();
        profile.name = "Keep me";
        profile.addProfile(context.getContentResolver());
        BackupAndRestoreUtils.readJson(context, prefs, new ByteArrayInputStream(json.toString().getBytes("UTF-8")));
        assertEquals(1, CalendarProfile.getProfiles(context.getContentResolver()).size());
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
    }

    @Test(timeout = 120000)
    public void mainBackupKeepsTagsAndMightyAlarmOptions() throws Exception {
        final Application context = ApplicationProvider.getApplicationContext();
        final android.content.ContentResolver cr = context.getContentResolver();
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        final com.best.deskclock.provider.Tag tag = new com.best.deskclock.provider.Tag("Work", 0xFF336699);
        tag.addTag(cr);
        final com.best.deskclock.provider.Alarm alarm = new com.best.deskclock.provider.Alarm();
        alarm.hour = 6;
        alarm.minutes = 45;
        alarm.label = "Gym";
        alarm.repeatIntervalMinutes = 10;
        alarm.repeatMaxCount = 3;
        alarm.wifiRuleEnabled = true;
        alarm.wifiSsid = "Home";
        alarm.addAlarm(cr);
        com.best.deskclock.provider.Tag.addTagToAlarm(cr, alarm.id, tag.id);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupAndRestoreUtils.settingsToJsonStream(context, prefs, prefs.getAll(), out);
        com.best.deskclock.provider.Tag.deleteTag(cr, tag.id);

        BackupAndRestoreUtils.readJson(context, prefs, new ByteArrayInputStream(out.toByteArray()));

        final List<com.best.deskclock.provider.Alarm> alarms = com.best.deskclock.provider.Alarm.getUserAlarms(cr);
        assertEquals(1, alarms.size());
        final com.best.deskclock.provider.Alarm restored = alarms.get(0);
        assertEquals("Gym", restored.label);
        assertEquals(10, restored.repeatIntervalMinutes);
        assertEquals(3, restored.repeatMaxCount);
        assertTrue(restored.wifiRuleEnabled);
        assertEquals("Home", restored.wifiSsid);
        final List<com.best.deskclock.provider.Tag> tags = com.best.deskclock.provider.Tag.getTagsForAlarm(cr, restored.id);
        assertEquals(1, tags.size());
        assertEquals("Work", tags.get(0).name);
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
    }

    @Test(timeout = 120000)
    public void automaticBackupIsTheSameFullZipAsTheMainBackup() throws Exception {
        final Application context = ApplicationProvider.getApplicationContext();
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        final CalendarProfile profile = new CalendarProfile();
        profile.name = "Zipped";
        profile.addProfile(context.getContentResolver());

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupAndRestoreUtils.writeFullBackup(context, prefs, out);

        String json = null;
        try (java.util.zip.ZipInputStream zip =
                 new java.util.zip.ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(BackupAndRestoreUtils.BACKUP_JSON_FILE_NAME)) {
                    json = new String(readAll(zip), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(json != null && json.contains("Zipped"));
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }
}

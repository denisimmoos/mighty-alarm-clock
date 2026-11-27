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
 * The backup and restore in the app's main settings also covers calendar alarm profiles.
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

    @Test(timeout = 120000)
    public void everyCalendarProfileFieldSurvivesTheMainBackup() throws Exception {
        final Application context = ApplicationProvider.getApplicationContext();
        final android.content.ContentResolver cr = context.getContentResolver();
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        final CalendarProfile profile = new CalendarProfile();
        profile.name = "All";
        profile.enabled = false;
        profile.calendarId = 3;
        profile.calendarName = "Work";
        profile.accountName = "someone@example.com";
        profile.titleRegex = "^Wake";
        // A sound the restoring phone can play; unreadable ones fall back to the default sound.
        profile.ringtone = android.net.Uri.parse("android.resource://com.best.deskclock/raw/alarm_sound");
        profile.alarmVolume = 3;
        profile.vibrate = false;
        profile.autoSilenceDuration = 600;
        profile.snoozeDuration = 7;
        profile.maxSnoozeCount = 4;
        profile.dismissMethod = CalendarProfile.DISMISS_METHOD_SLIDE;
        profile.lookaheadDays = 21;
        profile.flash = true;
        profile.vibrateOnly = false;
        profile.vibrationStrength = 40;
        profile.flashOnly = false;
        profile.ignoreCase = true;
        profile.skipDeclined = false;
        profile.skipTentative = true;
        profile.allDayMinutes = 7 * 60;
        profile.showDetailsLocked = false;
        profile.offsetEnabled = true;
        profile.offsetMinutes = 95;
        profile.escalation = true;
        profile.ringAtStart = false;
        profile.wifiCheck = true;
        profile.wifiOffRings = true;
        profile.ringAtEnd = true;
        profile.extendOnSnooze = true;
        profile.wifiNetworks = "[{\"ssid\":\"Home\",\"bssid\":\"aa:bb:cc:dd:ee:ff\"}]";
        profile.addProfile(cr);
        final android.content.ContentValues before = CalendarProfile.getProfile(cr, profile.id).createContentValues();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupAndRestoreUtils.settingsToJsonStream(context, prefs, prefs.getAll(), out);
        CalendarProfile.deleteProfile(cr, profile.id);
        BackupAndRestoreUtils.readJson(context, prefs, new ByteArrayInputStream(out.toByteArray()));

        final List<CalendarProfile> profiles = CalendarProfile.getProfiles(cr);
        assertEquals(1, profiles.size());
        final android.content.ContentValues after = profiles.get(0).createContentValues();
        for (String key : before.keySet()) {
            if (key.equals("_id") || key.equals(CalendarProfile.CALENDAR_ID) || key.equals(CalendarProfile.UPDATED_AT)) {
                // The calendar is looked up again on the restoring phone, which also updates the profile.
                continue;
            }
            assertEquals(key, before.getAsString(key), after.getAsString(key));
        }
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
    }

    @Test(timeout = 120000)
    public void backupAndExchangeSettingsSurviveTheMainBackup() throws Exception {
        final Application context = ApplicationProvider.getApplicationContext();
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        com.best.deskclock.mighty.backup.JsonBackupManager.setScheduleEnabled(context, true);
        com.best.deskclock.mighty.backup.JsonBackupManager.setIntervalHours(context, 72);
        com.best.deskclock.mighty.exchange.ExchangeManager.setDeviceName(context, "Kitchen phone");
        // A normal setting too.
        prefs.edit().putBoolean("key_test_backup_flag", true).putInt("key_test_backup_number", 42).apply();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupAndRestoreUtils.settingsToJsonStream(context, prefs, prefs.getAll(), out);
        final org.json.JSONObject json = new org.json.JSONObject(out.toString("UTF-8"));
        // A folder this phone has no access to is not taken over.
        json.getJSONObject("Mighty settings").getJSONObject("exchange").getJSONArray("folders")
            .put(new org.json.JSONObject().put("name", "Gone").put("treeUri", "content://nowhere/tree/x")
                .put("importPolicy", "ASK"));

        com.best.deskclock.mighty.backup.JsonBackupManager.setScheduleEnabled(context, false);
        com.best.deskclock.mighty.backup.JsonBackupManager.setIntervalHours(context, 24);
        com.best.deskclock.mighty.exchange.ExchangeManager.setDeviceName(context, "Other");
        prefs.edit().remove("key_test_backup_flag").remove("key_test_backup_number").apply();

        BackupAndRestoreUtils.readJson(context, prefs, new ByteArrayInputStream(json.toString().getBytes("UTF-8")));

        assertTrue(com.best.deskclock.mighty.backup.JsonBackupManager.isScheduleEnabled(context));
        assertEquals(72, com.best.deskclock.mighty.backup.JsonBackupManager.getIntervalHours(context));
        assertEquals("Kitchen phone", com.best.deskclock.mighty.exchange.ExchangeManager.getDeviceName(context));
        assertTrue(com.best.deskclock.mighty.exchange.ExchangeManager.getFolders(context).isEmpty());
        assertTrue(prefs.getBoolean("key_test_backup_flag", false));
        assertEquals(42, prefs.getInt("key_test_backup_number", 0));
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
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

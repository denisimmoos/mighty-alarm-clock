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
    public void backupLeavesOutNormalAlarmsAndOldBackupsImportWithoutThem() throws Exception {
        final Application context = ApplicationProvider.getApplicationContext();
        final android.content.ContentResolver cr = context.getContentResolver();
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        final CalendarProfile profile = new CalendarProfile();
        profile.name = "Meetings";
        profile.addProfile(cr);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupAndRestoreUtils.settingsToJsonStream(context, prefs, prefs.getAll(), out);
        final org.json.JSONObject json = new org.json.JSONObject(out.toString("UTF-8"));
        // A new backup has no normal alarms, tags or exchange settings.
        assertTrue(!json.has("Alarms") && !json.has("Alarms with specified date") && !json.has("Tags"));
        assertTrue(!json.getJSONObject("Mighty settings").has("exchange"));

        // A backup from the old app with normal alarms, tags and their settings.
        final org.json.JSONObject alarm = new org.json.JSONObject().put("id", 7).put("enabled", true)
            .put("hour", 6).put("minutes", 45).put("label", "Gym").put("daysOfWeek", 0);
        json.put("Alarms", new org.json.JSONArray().put(alarm));
        json.put("Alarms with specified date", new org.json.JSONArray());
        json.put("Tags", new org.json.JSONArray().put(new org.json.JSONObject().put("id", 1).put("name", "Work")));
        json.getJSONObject("Boolean settings").put("key_display_enabled_alarms_first", true)
            .put("key_display_screensaver_battery", true);
        json.getJSONObject("Integer settings").put("key_screensaver_brightness", 70);
        json.getJSONObject("String settings").put("key_sort_alarm", "2").put("key_tab_to_display", "0");
        json.put("Visible tabs", new org.json.JSONArray().put("alarm").put("clock").put("calendar_alarm"));

        BackupAndRestoreUtils.readJson(context, prefs, new ByteArrayInputStream(json.toString().getBytes("UTF-8")));

        assertTrue(com.best.deskclock.provider.Alarm.getUserAlarms(cr).isEmpty());
        assertTrue(!prefs.contains("key_display_enabled_alarms_first"));
        assertTrue(!prefs.contains("key_sort_alarm"));
        // Settings of the removed screensaver are skipped too.
        assertTrue(!prefs.contains("key_screensaver_brightness"));
        assertTrue(!prefs.contains("key_display_screensaver_battery"));
        final java.util.Set<String> tabs = com.best.deskclock.data.SettingsDAO.getVisibleTabs(prefs);
        assertTrue(!tabs.contains("alarm"));
        assertTrue(tabs.contains(com.best.deskclock.settings.PreferencesDefaultValues.VISIBLE_TAB_CALENDAR_ALARM));
        assertTrue(com.best.deskclock.data.SettingsDAO.isClockTabVisible(prefs));
        // The removed Alarm tab as start tab opens the Calendar tab.
        assertEquals(com.best.deskclock.uidata.UiDataModel.Tab.CALENDAR_ALARM,
            com.best.deskclock.uidata.UiDataModel.Tab.fromStoredId(
                com.best.deskclock.data.SettingsDAO.getTabToDisplay(prefs)));
        boolean found = false;
        for (CalendarProfile restored : CalendarProfile.getProfiles(cr)) {
            found |= "Meetings".equals(restored.name);
        }
        assertTrue(found);
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
        profile.volumePercent = 40;
        profile.vibrate = false;
        profile.autoSilenceDuration = 600;
        profile.snoozeDuration = 7;
        profile.maxSnoozeCount = 4;
        profile.dismissMethod = CalendarProfile.DISMISS_METHOD_BUTTON;
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
        profile.snoozeMinutes2 = 12;
        profile.snoozeMinutes3 = 33;
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
    public void backupSettingsSurviveTheMainBackup() throws Exception {
        final Application context = ApplicationProvider.getApplicationContext();
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        com.best.deskclock.mighty.backup.JsonBackupManager.setScheduleEnabled(context, true);
        com.best.deskclock.mighty.backup.JsonBackupManager.setIntervalHours(context, 72);
        // A normal setting too.
        prefs.edit().putBoolean("key_test_backup_flag", true).putInt("key_test_backup_number", 42).apply();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupAndRestoreUtils.settingsToJsonStream(context, prefs, prefs.getAll(), out);
        final org.json.JSONObject json = new org.json.JSONObject(out.toString("UTF-8"));

        com.best.deskclock.mighty.backup.JsonBackupManager.setScheduleEnabled(context, false);
        com.best.deskclock.mighty.backup.JsonBackupManager.setIntervalHours(context, 24);
        prefs.edit().remove("key_test_backup_flag").remove("key_test_backup_number").apply();

        BackupAndRestoreUtils.readJson(context, prefs, new ByteArrayInputStream(json.toString().getBytes("UTF-8")));

        assertTrue(com.best.deskclock.mighty.backup.JsonBackupManager.isScheduleEnabled(context));
        assertEquals(72, com.best.deskclock.mighty.backup.JsonBackupManager.getIntervalHours(context));
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

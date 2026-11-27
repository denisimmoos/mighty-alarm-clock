/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.utils;

import static com.best.deskclock.data.CustomRingtoneDAO.NEXT_RINGTONE_ID;
import static com.best.deskclock.data.CustomRingtoneDAO.RINGTONE_IDS;
import static com.best.deskclock.data.CustomRingtoneDAO.RINGTONE_TITLE;
import static com.best.deskclock.data.CustomRingtoneDAO.RINGTONE_URI;
import static com.best.deskclock.data.SettingsDAO.KEY_SELECTED_ALARM_RINGTONE_URI;
import static com.best.deskclock.data.TimerDAO.STATE;
import static com.best.deskclock.data.TimerDAO.TIMER_IDS;
import static com.best.deskclock.settings.PreferencesKeys.*;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.format.DateFormat;

import com.best.deskclock.BuildConfig;
import com.best.deskclock.R;
import com.best.deskclock.alarms.AlarmStateManager;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.data.Timer;
import com.best.deskclock.provider.Alarm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class lists all settings that can be backed up or restored.
 */
public class BackupAndRestoreUtils {

    public static boolean isRestoringBackupOrIsResettingApp = false;

    /** Key of the calendar alarm profiles in the backup's settings.json. */
    // Name used in backup files, kept so backups made before the rename can still be restored.
    private static final String KEY_CALENDAR_ALARM_PROFILES = "Calendar alert profiles";
    private static final String KEY_MIGHTY_SETTINGS = "Mighty settings";

    public static boolean appNeedsRestart = false;

    /**
     * Settings of removed features: the normal alarms (list, editor, per-alarm defaults, exchange)
     * and the screensaver. Older
     * backups still contain them; they are skipped when restoring.
     */
    private static final Set<String> REMOVED_NORMAL_ALARM_SETTINGS = Set.of(
        // The screensaver was removed too.
        "key_screensaver_settings",
        "key_screensaver_clock_style",
        "key_screensaver_clock_dial",
        "key_screensaver_clock_dial_material",
        "key_screensaver_digital_clock_font",
        "key_screensaver_analog_clock_size",
        "key_display_screensaver_clock_seconds",
        "key_screensaver_clock_second_hand",
        "key_display_screensaver_battery",
        "key_screensaver_clock_dynamic_colors",
        "key_screensaver_clock_color_picker",
        "key_screensaver_battery_color_picker",
        "key_screensaver_date_color_picker",
        "key_screensaver_next_alarm_color_picker",
        "key_screensaver_brightness",
        "key_screensaver_digital_clock_font_size",
        "key_screensaver_display_text_uppercase",
        "key_screensaver_digital_clock_in_bold",
        "key_screensaver_digital_clock_in_italic",
        "key_screensaver_battery_in_bold",
        "key_screensaver_battery_in_italic",
        "key_screensaver_date_in_bold",
        "key_screensaver_date_in_italic",
        "key_screensaver_next_alarm_in_bold",
        "key_screensaver_next_alarm_in_italic",
        "key_screensaver_keep_screen_on",
        "key_screensaver_background_image",
        "key_enable_screensaver_blur_effect",
        "key_screensaver_blur_intensity",
        "key_calendar_alert_tab_added",
        "key_swipe_action",
        "key_display_snooze_selector",
        "key_slide_zone_color",
        "key_alarm_button_color",
        "key_snooze_button_color",
        "key_dismiss_title_color",
        "key_snooze_minus_button_color",
        "key_snooze_plus_button_color",
        "key_snooze_minus_symbol_color",
        "key_snooze_plus_symbol_color",
        "key_sample_alarms_seeded",
        "key_material_time_picker_style",
        "key_material_date_picker_style",
        "key_enable_per_alarm_auto_silence",
        "key_auto_silence_duration",
        "key_enable_per_alarm_snooze_duration",
        "key_alarm_snooze_duration",
        "key_enable_per_alarm_missed_repeat_limit",
        "key_repeat_missed_alarm",
        "key_enable_per_alarm_volume_crescendo_duration",
        "key_alarm_volume_crescendo_duration",
        "key_enable_per_alarm_volume",
        "key_sort_alarm",
        "key_display_enabled_alarms_first",
        "key_enable_alarm_fab_long_press",
        "key_week_start",
        "key_display_dismiss_button",
        "key_enable_per_alarm_vibration_pattern",
        "key_enable_alarm_vibrations_by_default",
        "key_turn_on_back_flash_for_triggered_alarm",
        "key_enable_delete_occasional_alarm_by_default",
        "key_mighty_exchange_nudge_syncthing",
        "key_alarm_list_filter_enabled",
        "key_alarm_list_filter_repeating",
        "key_alarm_list_filter_one_shot",
        "key_alarm_list_extra_sort",
        "key_alarm_list_filter_tag_id"
    );

    /**
     * Read and export values in SharedPreferences to a file.
     */
    public static void settingsToJsonStream(Context context, SharedPreferences prefs, Map<String, ?> settings, OutputStream out)
        throws JSONException, IOException {

        Map<String, Boolean> booleans = new HashMap<>();
        Map<String, String> strings = new HashMap<>();
        Map<String, Integer> ints = new HashMap<>();
        Map<String, Long> longs = new HashMap<>();
        Set<String> timerIds = prefs.getStringSet(TIMER_IDS, Collections.emptySet());
        Set<String> visibleTabs = SettingsDAO.getVisibleTabs(prefs);

        for (Map.Entry<String, ?> entry : settings.entrySet()) {
            if (entry.getKey() != null) {
                String key = entry.getKey();
                // Exclude keys from custom ringtones and the selected alarm ringtone as this causes bugs when restoring.
                // Also, exclude keys related to images and fonts from the JSON backup. The physical files for these preferences are saved
                // directly within the ZIP archive.
                // Finally, exclude the essential permissions key, as it reflects the current system state and should not be saved,
                // restored, or reset like other preferences.
                if (REMOVED_NORMAL_ALARM_SETTINGS.contains(key)
                    || key.equals(RINGTONE_IDS)
                    || key.equals(KEY_GENERAL_FONT)
                    || key.startsWith(RINGTONE_URI)
                    || key.equals(NEXT_RINGTONE_ID)
                    || key.startsWith(RINGTONE_TITLE)
                    || key.equals(KEY_SELECTED_ALARM_RINGTONE_URI)
                    || key.equals(KEY_ALARM_FONT)
                    || key.equals(KEY_ALARM_BACKGROUND_IMAGE)
                    || key.equals(KEY_TIMER_DURATION_FONT)
                    || key.equals(KEY_TIMER_BACKGROUND_IMAGE)
                    || key.equals(KEY_SW_FONT)
                    || key.equals(KEY_DIGITAL_CLOCK_FONT)
                    || key.equals(KEY_ESSENTIAL_PERMISSIONS_GRANTED)) {
                    continue;
                }

                if (entry.getValue() instanceof Boolean) {
                    booleans.put(entry.getKey(), prefs.getBoolean(entry.getKey(), (Boolean) entry.getValue()));
                } else if (entry.getValue() instanceof String) {
                    // Exclude these keys if the URI does not match a system ringtone
                    if (KEY_TIMER_RINGTONE.equals(key) || KEY_DEFAULT_ALARM_RINGTONE.equals(key)) {
                        String value = prefs.getString(key, (String) entry.getValue());
                        Uri uri = Uri.parse(value);
                        if (!RingtoneUtils.isSystemRingtone(uri)) {
                            continue;
                        }
                    }

                    strings.put(entry.getKey(), prefs.getString(entry.getKey(), (String) entry.getValue()));
                } else if (entry.getValue() instanceof Integer) {
                    int value = prefs.getInt(entry.getKey(), (Integer) entry.getValue());
                    // Always save the timers in the Reset state
                    if (key.startsWith(STATE)) {
                        value = Timer.State.RESET.getValue();
                    }

                    ints.put(entry.getKey(), value);
                } else if (entry.getValue() instanceof Long) {
                    longs.put(entry.getKey(), prefs.getLong(entry.getKey(), (Long) entry.getValue()));
                }
            }
        }

        JSONObject jsonObject = new JSONObject();

        // Header
        JSONObject header = new JSONObject();

        header.put("packageName", context.getPackageName());
        header.put("versionName", BuildConfig.VERSION_NAME);
        header.put("versionCode", BuildConfig.VERSION_CODE);
        header.put("backupDate", DateFormat.format("yyyy_MM_dd_HH-mm-ss", new Date()).toString());

        jsonObject.put("Header", header);

        // Convert the Map of booleans to a JSONObject
        jsonObject.put("Boolean settings", convertMapToJsonObject(booleans));

        // Convert the Map of strings to a JSONObject
        jsonObject.put("String settings", convertMapToJsonObject(strings));

        // Convert the Map of integers to a JSONObject
        jsonObject.put("Integer settings", convertMapToJsonObject(ints));

        // Convert the Map of longs to a JSONObject
        jsonObject.put("Long settings", convertMapToJsonObject(longs));

        // Convert the Map of visible tabs to a JSONArray
        jsonObject.put("Visible tabs", new JSONArray(visibleTabs));

        // Convert the Map of timers IDs to a JSONArray
        jsonObject.put("Timers IDs", new JSONArray(timerIds));

        // Normal alarms were removed from the app: alarms come only from calendar profiles, which
        // are saved below and rebuilt from the calendar after a restore.

        // Mighty settings kept outside the main settings: automatic backup.
        final JSONObject mighty = new JSONObject();
        mighty.put("backup", com.best.deskclock.mighty.backup.JsonBackupManager.exportSettings(context));
        jsonObject.put(KEY_MIGHTY_SETTINGS, mighty);

        // Calendar alarm profiles; their alarms are rebuilt from the calendar after a restore.
        jsonObject.put(KEY_CALENDAR_ALARM_PROFILES,
            com.best.deskclock.mighty.calendar.CalendarProfileRestore.exportProfiles(context.getContentResolver()));

        out.write(jsonObject.toString(4).getBytes(StandardCharsets.UTF_8));

        out.flush();
    }

    /**
     * Helper method to convert a Map to JSONObject.
     */
    private static JSONObject convertMapToJsonObject(Map<String, ?> map) throws JSONException {
        JSONObject jsonObject = new JSONObject();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                jsonObject.put(entry.getKey(), value);
            } else if (value instanceof String) {
                jsonObject.put(entry.getKey(), value);
            } else if (value instanceof Integer) {
                jsonObject.put(entry.getKey(), value);
            } else if (value instanceof Long) {
                jsonObject.put(entry.getKey(), value);
            } else if (value instanceof Set<?> set) {
                if (!set.isEmpty() && set.iterator().next() instanceof String) {
                    jsonObject.put(entry.getKey(), new JSONArray(set));
                } else {
                    LogUtils.w("Expected Set<String>, but got: " + set.getClass().getName());
                }
            }
        }
        return jsonObject;
    }

    /**
     * Read and apply values to restore in SharedPreferences.
     */
    @SuppressLint("ApplySharedPref")
    public static void readJson(Context context, SharedPreferences prefs, InputStream inputStream)
        throws IOException, JSONException {

        SharedPreferences.Editor editor = prefs.edit();

        // Exclude keys corresponding to custom ringtones and the selected alarm ringtone, as this causes bugs for alarms.
        // Next, exclude keys related to images and fonts because they are not stored in the JSON. They will be updated dynamically later
        // if the corresponding files are found in the ZIP archive.
        // Finally, exclude the essential permissions key, as it reflects the current system state and should not be saved, restored,
        // or reset like other preferences.
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();

            if (!key.equals(KEY_GENERAL_FONT)
                && !key.startsWith(RINGTONE_URI)
                && !key.equals(RINGTONE_IDS)
                && !key.equals(NEXT_RINGTONE_ID)
                && !key.startsWith(RINGTONE_TITLE)
                && !key.equals(KEY_SELECTED_ALARM_RINGTONE_URI)
                && !key.equals(KEY_ALARM_FONT)
                && !key.equals(KEY_ALARM_BACKGROUND_IMAGE)
                && !key.equals(KEY_TIMER_DURATION_FONT)
                && !key.equals(KEY_TIMER_BACKGROUND_IMAGE)
                && !key.equals(KEY_SW_FONT)
                && !key.equals(KEY_DIGITAL_CLOCK_FONT)
                && !key.equals(KEY_ESSENTIAL_PERMISSIONS_GRANTED)
            ) {
                editor.remove(key);
            }
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder jsonBuilder = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            jsonBuilder.append(line);
        }

        JSONObject jsonObject = new JSONObject(jsonBuilder.toString());

        JSONObject booleans = jsonObject.getJSONObject("Boolean settings");
        for (Iterator<String> it = booleans.keys(); it.hasNext(); ) {
            String key = it.next();
            if (REMOVED_NORMAL_ALARM_SETTINGS.contains(key)) {
                continue;
            }
            boolean value = booleans.getBoolean(key);
            editor.putBoolean(key, value);
        }

        JSONObject strings = jsonObject.getJSONObject("String settings");
        for (Iterator<String> it = strings.keys(); it.hasNext(); ) {
            String key = it.next();
            if (REMOVED_NORMAL_ALARM_SETTINGS.contains(key)) {
                continue;
            }
            String value = strings.getString(key);

            if (isRingtoneKey(key)) {
                if (!isRingtoneAvailable(context, value)) {
                    if (KEY_TIMER_RINGTONE.equals(key)) {
                        editor.putString(key, RingtoneUtils.getResourceUri(context, R.raw.timer_expire).toString());
                    } else if (KEY_DEFAULT_ALARM_RINGTONE.equals(key)) {
                        editor.putString(key, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString());
                    }
                } else {
                    editor.putString(key, value);
                }
            } else {
                editor.putString(key, value);
            }
        }

        JSONObject integers = jsonObject.getJSONObject("Integer settings");
        for (Iterator<String> it = integers.keys(); it.hasNext(); ) {
            String key = it.next();
            if (REMOVED_NORMAL_ALARM_SETTINGS.contains(key)) {
                continue;
            }
            int value = integers.getInt(key);
            editor.putInt(key, value);
        }

        JSONObject longs = jsonObject.getJSONObject("Long settings");
        for (Iterator<String> it = longs.keys(); it.hasNext(); ) {
            String key = it.next();
            if (REMOVED_NORMAL_ALARM_SETTINGS.contains(key)) {
                continue;
            }
            long value = longs.getLong(key);
            editor.putLong(key, value);
        }

        if (jsonObject.has("Visible tabs")) {
            JSONArray visibleTabsArray = jsonObject.getJSONArray("Visible tabs");
            Set<String> visibleTabs = new HashSet<>();
            for (int i = 0; i < visibleTabsArray.length(); i++) {
                visibleTabs.add(visibleTabsArray.getString(i));
            }
            editor.putStringSet(KEY_VISIBLE_TABS, visibleTabs);
        }

        if (jsonObject.has("Timers IDs")) {
            JSONArray timerIdsArray = jsonObject.getJSONArray("Timers IDs");
            Set<String> timerIds = new HashSet<>();
            for (int i = 0; i < timerIdsArray.length(); i++) {
                timerIds.add(timerIdsArray.getString(i));
            }
            editor.putStringSet(TIMER_IDS, timerIds);
        }

        // These describe the calendar checks on the phone the backup came from, not this one.
        com.best.deskclock.mighty.calendar.CalendarAlarmStatus.clearDeviceState(editor);

        editor.commit();

        final ContentResolver contentResolver = context.getContentResolver();
        // Remove any alarm that wasn't made from a calendar profile
        final List<Alarm> alarms = Alarm.getUserAlarms(contentResolver);
        for (Alarm alarm : alarms) {
            AlarmStateManager.deleteAllInstances(context, alarm.id);
            Alarm.deleteAlarm(contentResolver, alarm.id);
        }

        // Alarms, alarm tags and the alarm exchange in older backups belonged to the normal alarms,
        // which were removed from the app, so they are ignored.

        // Mighty settings kept outside the main settings. Older backups leave them as they are.
        final JSONObject mighty = jsonObject.optJSONObject(KEY_MIGHTY_SETTINGS);
        if (mighty != null) {
            try {
                com.best.deskclock.mighty.backup.JsonBackupManager.restoreSettings(context, mighty.optJSONObject("backup"));
            } catch (Throwable e) {
                LogUtils.e("Could not restore the automatic backup settings", e);
            }
        }

        // Calendar alarm profiles. Backups made before they were included leave them as they are.
        try {
            com.best.deskclock.mighty.calendar.CalendarProfileRestore.replaceAll(context,
                jsonObject.optJSONArray(KEY_CALENDAR_ALARM_PROFILES));
        } catch (Throwable e) {
            // Never let the profiles stop the rest of the restore.
            LogUtils.e("Could not restore calendar alarm profiles", e);
        }

        AlarmStateManager.updateNextAlarm(context);
    }

    /**
     * @return {@code true} if a key matches a ringtone key. {@code false} otherwise.
     */
    private static boolean isRingtoneKey(String key) {
        return KEY_TIMER_RINGTONE.equals(key) || KEY_DEFAULT_ALARM_RINGTONE.equals(key);
    }

    /**
     * @return {@code true} if a ringtone is available in the device. {@code false} otherwise.
     * Useful when restoring between different devices.
     */
    private static boolean isRingtoneAvailable(Context context, String ringtoneUriString) {
        Uri ringtoneUri = Uri.parse(ringtoneUriString);

        // Check if the URI is of type "content" or "file"
        if ("content".equals(ringtoneUri.getScheme()) || "file".equals(ringtoneUri.getScheme())) {
            try {
                if ("content".equals(ringtoneUri.getScheme())) {
                    // For URI content:// (managed by ContentResolver)
                    try (Cursor cursor = context.getContentResolver().query(ringtoneUri, null, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int columnIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);

                            if (columnIndex != -1) {
                                String filePath = cursor.getString(columnIndex);
                                File file = new File(filePath);
                                return file.exists();
                            }
                        }
                    }
                } else if ("file".equals(ringtoneUri.getScheme())) {
                    // For URI file:// (local files on the system)
                    String path = ringtoneUri.getPath();
                    if (path != null) {
                        File file = new File(path);
                        return file.exists();
                    }
                }
            } catch (Exception e) {
                LogUtils.e("Error checking ringtone availability", e);
            }
        }

        return false;
    }


    /** Name of the settings file inside a full backup. */
    public static final String BACKUP_JSON_FILE_NAME = "settings.json";

    /**
     * Writes a full backup (settings, alarms, tags, calendar alarm profiles, fonts and images) as
     * a ZIP archive. Used by the manual backup in the settings and by the automatic backup, so
     * both can be restored the same way.
     */
    public static void writeFullBackup(Context context, SharedPreferences prefs, OutputStream outputStream)
        throws IOException, JSONException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(outputStream)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(BACKUP_JSON_FILE_NAME));
            settingsToJsonStream(context, prefs, prefs.getAll(), zip);
            zip.closeEntry();

            appendFileToZip(zip, prefs.getString(KEY_GENERAL_FONT, null));
            appendFileToZip(zip, prefs.getString(KEY_ALARM_FONT, null));
            appendFileToZip(zip, prefs.getString(KEY_ALARM_BACKGROUND_IMAGE, null));
            appendFileToZip(zip, prefs.getString(KEY_TIMER_DURATION_FONT, null));
            appendFileToZip(zip, prefs.getString(KEY_TIMER_BACKGROUND_IMAGE, null));
            appendFileToZip(zip, prefs.getString(KEY_SW_FONT, null));
            appendFileToZip(zip, prefs.getString(KEY_DIGITAL_CLOCK_FONT, null));
        }
    }

    private static void appendFileToZip(java.util.zip.ZipOutputStream zip, String filePath) throws IOException {
        if (filePath == null) {
            return;
        }
        final File file = new File(filePath);
        if (!file.exists()) {
            return;
        }
        zip.putNextEntry(new java.util.zip.ZipEntry(file.getName()));
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            final byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) > 0) {
                zip.write(buffer, 0, length);
            }
        }
        zip.closeEntry();
    }
}

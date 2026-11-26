// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.best.deskclock.provider.CalendarProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Writes calendar alert profiles into the app's backups and restores them from there.
 *
 * <p>The alarms themselves are not in the backup; they are rebuilt from the calendar right after
 * the restore.</p>
 */
public final class CalendarProfileRestore {

    /** The JSON key the backup stores the profiles under. */
    public static final String KEY_PROFILES = "calendar_profiles";

    private CalendarProfileRestore() {
    }

    /**
     * @return all calendar alert profiles as JSON, as stored in backups.
     */
    public static JSONArray exportProfiles(ContentResolver cr) throws JSONException {
        final JSONArray profiles = new JSONArray();
        for (CalendarProfile profile : CalendarProfile.getProfiles(cr)) {
            final ContentValues values = profile.createContentValues();
            final JSONObject json = new JSONObject();
            json.put("id", profile.id);
            for (String key : values.keySet()) {
                final Object value = values.get(key);
                json.put(key, value == null ? JSONObject.NULL : value);
            }
            profiles.put(json);
        }
        return profiles;
    }

    /**
     * Replaces all calendar alert profiles with the ones in a full app backup, the way the app's
     * main restore replaces all alarms. Must not run on the main thread.
     *
     * @param profiles the profiles from the backup, or {@code null} for a backup made before
     *                 profiles were included; existing profiles are then kept.
     */
    public static void replaceAll(Context context, @Nullable JSONArray profiles) {
        if (profiles == null) {
            return;
        }
        final ContentResolver cr = context.getContentResolver();
        for (CalendarProfile profile : CalendarProfile.getProfiles(cr)) {
            // Their alarms are removed by the calendar check below.
            CalendarProfile.deleteProfile(cr, profile.id);
        }
        insertAll(context, profiles);
        CalendarAlertSync.syncAll(context);
        CalendarAlertWorker.ensureScheduled(context);
    }

    /**
     * Adds the profiles of a backup. Does not start a calendar check.
     */
    private static void insertAll(Context context, JSONArray profiles) {
        final ContentResolver cr = context.getContentResolver();
        final List<DeviceCalendars.CalendarInfo> calendars = DeviceCalendars.hasPermission(context)
            ? DeviceCalendars.getCalendars(context) : null;

        for (int i = 0; i < profiles.length(); i++) {
            final JSONObject json = profiles.optJSONObject(i);
            if (json == null) {
                continue;
            }

            // Start from the defaults so a backup from an older version gets sensible values for
            // options it didn't have yet. Keys this version doesn't know are ignored.
            final ContentValues values = new CalendarProfile().createContentValues();
            for (String key : values.keySet().toArray(new String[0])) {
                if (!json.has(key)) {
                    continue;
                }
                final Object value = json.opt(key);
                if (value == null || value == JSONObject.NULL) {
                    values.putNull(key);
                } else if (value instanceof Number number) {
                    values.put(key, number.longValue());
                } else if (value instanceof Boolean bool) {
                    values.put(key, bool ? 1 : 0);
                } else {
                    values.put(key, value.toString());
                }
            }

            // Calendar ids differ between phones. Find the same calendar by account and name.
            final String account = values.getAsString(CalendarProfile.ACCOUNT_NAME);
            final String calendarName = values.getAsString(CalendarProfile.CALENDAR_NAME);
            final Long storedId = values.getAsLong(CalendarProfile.CALENDAR_ID);
            final DeviceCalendars.CalendarInfo calendar = findCalendar(calendars,
                storedId == null ? -1 : storedId, account, calendarName);
            if (calendar != null) {
                values.put(CalendarProfile.CALENDAR_ID, calendar.id);
            } else if (calendars != null) {
                // Not on this phone: the profile shows "Calendar not available" until one is chosen.
                values.put(CalendarProfile.CALENDAR_ID, -1L);
            }

            // A custom sound from the old phone may not exist here; fall back to the default.
            final String ringtone = values.getAsString(CalendarProfile.RINGTONE);
            if (ringtone != null && !isReadable(cr, Uri.parse(ringtone))) {
                values.putNull(CalendarProfile.RINGTONE);
            }

            final Uri uri = cr.insert(CalendarProfile.CONTENT_URI, values);
            if (uri == null) {
                continue;
            }
            // Read back and save once more so out-of-range numbers are brought into range.
            final CalendarProfile profile = CalendarProfile.getProfile(cr, ContentUris.parseId(uri));
            if (profile != null) {
                profile.updateProfile(cr);
            }
        }
    }

    @Nullable
    private static DeviceCalendars.CalendarInfo findCalendar(@Nullable List<DeviceCalendars.CalendarInfo> calendars,
                                                             long storedId, String account, String calendarName) {
        if (calendars == null) {
            return null;
        }
        // Same phone: the id still points to the same account's calendar.
        for (DeviceCalendars.CalendarInfo info : calendars) {
            if (info.id == storedId && info.accountName.equals(account) && info.displayName.equals(calendarName)) {
                return info;
            }
        }
        for (DeviceCalendars.CalendarInfo info : calendars) {
            if (info.accountName.equals(account) && info.displayName.equals(calendarName)) {
                return info;
            }
        }
        return null;
    }

    private static boolean isReadable(ContentResolver cr, Uri uri) {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
            && !ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            // Built-in sounds such as the silent ringtone.
            return true;
        }
        try (AssetFileDescriptor fd = cr.openAssetFileDescriptor(uri, "r")) {
            return fd != null;
        } catch (Exception e) {
            return false;
        }
    }
}

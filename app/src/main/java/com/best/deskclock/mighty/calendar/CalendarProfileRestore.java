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
import com.best.deskclock.utils.LogUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Restores calendar alert profiles from a JSON backup file written by
 * {@link com.best.deskclock.mighty.backup.JsonBackupManager}.
 *
 * <p>A profile that already exists (same name, calendar, account and title pattern) gets the
 * settings from the backup instead of a second copy. The alarms themselves are not in the
 * backup; they are rebuilt from the calendar right after the restore.</p>
 */
public final class CalendarProfileRestore {

    /** The JSON key the backup stores the profiles under. */
    public static final String KEY_PROFILES = "calendar_profiles";

    /** Largest file accepted; real backups are a few kilobytes. */
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    /**
     * What a restore did.
     *
     * @param restored profiles added
     * @param skipped  profiles that already existed and got the backup's settings again
     * @param calendarMissing restored profiles whose calendar is not on this phone; they need a
     *                        calendar chosen before they ring
     */
    public record Result(int restored, int skipped, int calendarMissing) {
    }

    /** The file is a backup, but holds no calendar alert profiles. */
    public static final Result NO_PROFILES = new Result(0, 0, 0);

    private CalendarProfileRestore() {
    }

    /**
     * Reads the backup file and adds its profiles. Must not run on the main thread.
     *
     * @return the result, {@link #NO_PROFILES} if the file is a backup without calendar alert
     * profiles (made before they were backed up), or {@code null} if the file can't be read.
     */
    @Nullable
    public static Result restore(Context context, Uri file) {
        final ContentResolver cr = context.getContentResolver();
        try (InputStream in = cr.openInputStream(file)) {
            if (in == null) {
                return null;
            }
            final Result result = restore(context, new JSONObject(readAll(in)));
            if (result != null && result != NO_PROFILES) {
                CalendarAlertSync.syncAll(context);
                CalendarAlertWorker.ensureScheduled(context);
            }
            return result;
        } catch (IOException | JSONException | SecurityException e) {
            LogUtils.e("CalendarProfileRestore: could not read " + file, e);
            return null;
        }
    }

    /**
     * Adds the profiles of an already parsed backup. Does not start a calendar check.
     */
    @Nullable
    static Result restore(Context context, JSONObject backup) {
        final JSONArray profiles = backup.optJSONArray(KEY_PROFILES);
        if (profiles == null || profiles.length() == 0) {
            // A backup of this app, but from before profiles were included, or without any.
            return backup.has("alarms") || backup.has("metadata") ? NO_PROFILES : null;
        }

        final ContentResolver cr = context.getContentResolver();
        final List<CalendarProfile> existing = CalendarProfile.getProfiles(cr);
        final List<DeviceCalendars.CalendarInfo> calendars = DeviceCalendars.hasPermission(context)
            ? DeviceCalendars.getCalendars(context) : null;

        int restored = 0;
        int skipped = 0;
        int calendarMissing = 0;
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

            final String name = values.getAsString(CalendarProfile.NAME);
            final String account = values.getAsString(CalendarProfile.ACCOUNT_NAME);
            final String calendarName = values.getAsString(CalendarProfile.CALENDAR_NAME);
            final String regex = values.getAsString(CalendarProfile.TITLE_REGEX);
            final CalendarProfile match = findExisting(existing, name, account, calendarName, regex);

            // Calendar ids differ between phones. Find the same calendar by account and name.
            final Long storedId = values.getAsLong(CalendarProfile.CALENDAR_ID);
            final DeviceCalendars.CalendarInfo calendar = findCalendar(calendars,
                storedId == null ? -1 : storedId, account, calendarName);
            if (calendar != null) {
                values.put(CalendarProfile.CALENDAR_ID, calendar.id);
            } else if (calendars != null) {
                // Not on this phone: the profile shows "Calendar not available" until one is chosen.
                values.put(CalendarProfile.CALENDAR_ID, -1L);
                calendarMissing++;
            }

            // A custom sound from the old phone may not exist here; fall back to the default.
            final String ringtone = values.getAsString(CalendarProfile.RINGTONE);
            if (ringtone != null && !isReadable(cr, Uri.parse(ringtone))) {
                values.putNull(CalendarProfile.RINGTONE);
            }

            final long profileId;
            if (match != null) {
                // Bring back the backup's settings, but keep a calendar the user chose since.
                if (calendar == null && match.calendarId >= 0) {
                    values.put(CalendarProfile.CALENDAR_ID, match.calendarId);
                    if (calendars != null) {
                        calendarMissing--;
                    }
                }
                values.remove(CalendarProfile.CREATED_AT);
                cr.update(CalendarProfile.getContentUri(match.id), values, null, null);
                profileId = match.id;
                skipped++;
            } else {
                final Uri uri = cr.insert(CalendarProfile.CONTENT_URI, values);
                if (uri == null) {
                    continue;
                }
                profileId = ContentUris.parseId(uri);
                restored++;
            }
            // Read back and save once more so out-of-range numbers are brought into range.
            final CalendarProfile profile = CalendarProfile.getProfile(cr, profileId);
            if (profile != null) {
                profile.updateProfile(cr);
                if (match == null) {
                    existing.add(profile);
                }
            }
        }
        return new Result(restored, skipped, calendarMissing);
    }

    @Nullable
    private static CalendarProfile findExisting(List<CalendarProfile> existing, String name, String account,
                                                String calendarName, String regex) {
        for (CalendarProfile profile : existing) {
            if (Objects.equals(profile.name, name)
                && Objects.equals(profile.accountName, account)
                && Objects.equals(profile.calendarName, calendarName)
                && Objects.equals(profile.titleRegex, regex)) {
                return profile;
            }
        }
        return null;
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

    private static String readAll(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_FILE_BYTES) {
                throw new IOException("Backup file too large");
            }
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}

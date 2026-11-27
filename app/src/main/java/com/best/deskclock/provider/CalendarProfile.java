// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.provider;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A calendar alarm profile: it watches one device calendar and turns every upcoming event whose
 * title matches {@link #titleRegex} into a fullscreen alarm with the profile's sound settings.
 */
public final class CalendarProfile implements ClockContract.CalendarProfilesColumns {

    public static final long INVALID_ID = -1;

    /** Auto silence value meaning "ring until the user dismisses the alarm". */
    public static final int RING_UNTIL_DISMISSED = -1;

    /** Snooze duration value meaning "snooze is disabled". */
    public static final int SNOOZE_DISABLED = -1;

    public static final int DEFAULT_SNOOZE_MINUTES = 5;
    public static final int DEFAULT_SNOOZE_MINUTES_2 = 10;
    public static final int DEFAULT_SNOOZE_MINUTES_3 = 20;
    public static final int MAX_SNOOZE_MINUTES = 999;

    public static final int DEFAULT_ALARM_VOLUME = 5;

    public static final int MIN_LOOKAHEAD_DAYS = 7;
    public static final int MAX_LOOKAHEAD_DAYS = 30;
    public static final int DEFAULT_LOOKAHEAD_DAYS = 7;

    public static final int MIN_VIBRATION_STRENGTH = 10;
    public static final int MAX_VIBRATION_STRENGTH = 100;
    public static final int DEFAULT_VIBRATION_STRENGTH = 100;

    public static final int MIN_OFFSET_MINUTES = 1;
    public static final int MAX_OFFSET_MINUTES = 12 * 60;
    public static final int DEFAULT_OFFSET_MINUTES = 10;

    /** {@link #allDayMinutes} value meaning all-day events get no alarm. */
    public static final int ALL_DAY_IGNORED = -1;

    /** Titles are cut to this length before matching, which keeps a bad regex from taking long. */
    private static final int MAX_MATCHED_TITLE_LENGTH = 500;

    /** Matching one title may take at most this long. */
    private static final long MATCH_TIMEOUT_MS = 200;

    private static final String[] QUERY_COLUMNS = {
        _ID,
        NAME,
        ENABLED,
        CALENDAR_ID,
        ACCOUNT_NAME,
        CALENDAR_NAME,
        TITLE_REGEX,
        RINGTONE,
        ALARM_VOLUME,
        VIBRATE,
        AUTO_SILENCE_DURATION,
        SNOOZE_DURATION,
        MAX_SNOOZE_COUNT,
        DISMISS_METHOD,
        LOOKAHEAD_DAYS,
        FLASH,
        VIBRATE_ONLY,
        VIBRATION_STRENGTH,
        FLASH_ONLY,
        IGNORE_CASE,
        SKIP_DECLINED,
        SKIP_TENTATIVE,
        ALL_DAY_MINUTES,
        CREATED_AT,
        UPDATED_AT,
        SHOW_DETAILS_LOCKED,
        OFFSET_ENABLED,
        OFFSET_MINUTES,
        ESCALATION,
        RING_AT_START,
        WIFI_NETWORKS,
        WIFI_CHECK,
        WIFI_OFF_RINGS,
        RING_AT_END,
        EXTEND_ON_SNOOZE,
        SNOOZE_MINUTES_2,
        SNOOZE_MINUTES_3
    };

    // These must be kept in sync with QUERY_COLUMNS above.
    private static final int ID_INDEX = 0;
    private static final int NAME_INDEX = 1;
    private static final int ENABLED_INDEX = 2;
    private static final int CALENDAR_ID_INDEX = 3;
    private static final int ACCOUNT_NAME_INDEX = 4;
    private static final int CALENDAR_NAME_INDEX = 5;
    private static final int TITLE_REGEX_INDEX = 6;
    private static final int RINGTONE_INDEX = 7;
    private static final int ALARM_VOLUME_INDEX = 8;
    private static final int VIBRATE_INDEX = 9;
    private static final int AUTO_SILENCE_DURATION_INDEX = 10;
    private static final int SNOOZE_DURATION_INDEX = 11;
    private static final int MAX_SNOOZE_COUNT_INDEX = 12;
    private static final int DISMISS_METHOD_INDEX = 13;
    private static final int LOOKAHEAD_DAYS_INDEX = 14;
    private static final int FLASH_INDEX = 15;
    private static final int VIBRATE_ONLY_INDEX = 16;
    private static final int VIBRATION_STRENGTH_INDEX = 17;
    private static final int FLASH_ONLY_INDEX = 18;
    private static final int IGNORE_CASE_INDEX = 19;
    private static final int SKIP_DECLINED_INDEX = 20;
    private static final int SKIP_TENTATIVE_INDEX = 21;
    private static final int ALL_DAY_MINUTES_INDEX = 22;
    private static final int CREATED_AT_INDEX = 23;
    private static final int UPDATED_AT_INDEX = 24;
    private static final int SHOW_DETAILS_LOCKED_INDEX = 25;
    private static final int OFFSET_ENABLED_INDEX = 26;
    private static final int OFFSET_MINUTES_INDEX = 27;
    private static final int ESCALATION_INDEX = 28;
    private static final int RING_AT_START_INDEX = 29;
    private static final int WIFI_NETWORKS_INDEX = 30;
    private static final int WIFI_CHECK_INDEX = 31;
    private static final int WIFI_OFF_RINGS_INDEX = 32;
    private static final int RING_AT_END_INDEX = 33;
    private static final int EXTEND_ON_SNOOZE_INDEX = 34;
    private static final int SNOOZE_MINUTES_2_INDEX = 35;
    private static final int SNOOZE_MINUTES_3_INDEX = 36;

    public long id;
    public String name;
    public boolean enabled;
    public long calendarId;
    public String accountName;
    public String calendarName;
    public String titleRegex;
    /** Alarm sound, or {@code null} for the app's default alarm sound. */
    @Nullable
    public Uri ringtone;
    public int alarmVolume;
    public boolean vibrate;
    /** Maximum ring time in seconds, or {@link #RING_UNTIL_DISMISSED}. */
    public int autoSilenceDuration;
    /** Snooze length in minutes, or {@link #SNOOZE_DISABLED}. */
    public int snoozeDuration;
    /** Maximum number of snoozes, 0 for unlimited. */
    public int maxSnoozeCount;
    public String dismissMethod;
    /** How many days ahead to look for events, {@link #MIN_LOOKAHEAD_DAYS} to {@link #MAX_LOOKAHEAD_DAYS}. */
    public int lookaheadDays;
    /** Blink the camera flashlight while ringing. Off by default. */
    public boolean flash;
    /** Only vibrate, without any sound. Off by default. */
    public boolean vibrateOnly;
    /** Vibration strength in percent, {@link #MIN_VIBRATION_STRENGTH} to {@link #MAX_VIBRATION_STRENGTH}. */
    public int vibrationStrength;
    /** Only blink the flashlight, without sound or vibration. Off by default. */
    public boolean flashOnly;
    /** Match the title regex regardless of upper and lower case. */
    public boolean ignoreCase;
    /** Give no alarm for events the user declined. On by default. */
    public boolean skipDeclined;
    /** Give no alarm for events the user answered "maybe" to. */
    public boolean skipTentative;
    /** Shows the event's description and place on the alarm screen also while the phone is locked. */
    public boolean showDetailsLocked;
    /** Rings {@link #offsetMinutes} before the event starts. Off by default. */
    public boolean offsetEnabled;
    /** Minutes before the event, {@link #MIN_OFFSET_MINUTES} to {@link #MAX_OFFSET_MINUTES}. */
    public int offsetMinutes;
    /** Starts quietly, then gets louder and vibrates stronger. Off by default. */
    public boolean escalation;
    /** With an offset, also rings again when the event starts. On by default. */
    public boolean ringAtStart;
    /**
     * Wi-Fi networks to ring near, see {@link com.best.deskclock.mighty.calendar.CalendarWifi}.
     * Empty to ring anywhere.
     */
    public String wifiNetworks;
    /** Only rings near one of the {@link #wifiNetworks}. Off by default. */
    public boolean wifiCheck;
    /** With the Wi-Fi check, rings anyway while the phone's Wi-Fi is turned off. Off by default. */
    public boolean wifiOffRings;
    /** Also rings when the event ends. Off by default. */
    public boolean ringAtEnd;
    /** Snoozing the alarm at the end extends the calendar event. Off by default. */
    public boolean extendOnSnooze;
    /** Second and third snooze length in minutes; the first is {@link #snoozeDuration}. */
    public int snoozeMinutes2;
    public int snoozeMinutes3;
    /** Time of day for all-day events in minutes after midnight, or {@link #ALL_DAY_IGNORED}. */
    public int allDayMinutes;
    public long createdAt;
    public long updatedAt;

    public CalendarProfile() {
        id = INVALID_ID;
        name = "";
        enabled = true;
        calendarId = -1;
        accountName = "";
        calendarName = "";
        titleRegex = "^Alarm:";
        ringtone = null;
        alarmVolume = DEFAULT_ALARM_VOLUME;
        vibrate = true;
        autoSilenceDuration = RING_UNTIL_DISMISSED;
        snoozeDuration = DEFAULT_SNOOZE_MINUTES;
        maxSnoozeCount = 0;
        dismissMethod = DISMISS_METHOD_DEFAULT;
        lookaheadDays = DEFAULT_LOOKAHEAD_DAYS;
        flash = false;
        vibrateOnly = false;
        vibrationStrength = DEFAULT_VIBRATION_STRENGTH;
        flashOnly = false;
        ignoreCase = false;
        skipDeclined = true;
        skipTentative = false;
        allDayMinutes = ALL_DAY_IGNORED;
        showDetailsLocked = true;
        offsetEnabled = false;
        offsetMinutes = DEFAULT_OFFSET_MINUTES;
        escalation = false;
        ringAtStart = true;
        wifiNetworks = "";
        wifiCheck = false;
        wifiOffRings = false;
        ringAtEnd = false;
        extendOnSnooze = false;
        snoozeMinutes2 = DEFAULT_SNOOZE_MINUTES_2;
        snoozeMinutes3 = DEFAULT_SNOOZE_MINUTES_3;
        final long now = System.currentTimeMillis();
        createdAt = now;
        updatedAt = now;
    }

    public CalendarProfile(Cursor c) {
        id = c.getLong(ID_INDEX);
        name = c.getString(NAME_INDEX);
        enabled = c.getInt(ENABLED_INDEX) == 1;
        calendarId = c.getLong(CALENDAR_ID_INDEX);
        accountName = c.getString(ACCOUNT_NAME_INDEX);
        calendarName = c.getString(CALENDAR_NAME_INDEX);
        titleRegex = c.getString(TITLE_REGEX_INDEX);
        ringtone = c.isNull(RINGTONE_INDEX) ? null : Uri.parse(c.getString(RINGTONE_INDEX));
        alarmVolume = c.getInt(ALARM_VOLUME_INDEX);
        vibrate = c.getInt(VIBRATE_INDEX) == 1;
        autoSilenceDuration = c.getInt(AUTO_SILENCE_DURATION_INDEX);
        snoozeDuration = c.getInt(SNOOZE_DURATION_INDEX);
        maxSnoozeCount = c.getInt(MAX_SNOOZE_COUNT_INDEX);
        dismissMethod = c.getString(DISMISS_METHOD_INDEX);
        lookaheadDays = clampLookaheadDays(c.getInt(LOOKAHEAD_DAYS_INDEX));
        flash = c.getInt(FLASH_INDEX) == 1;
        vibrateOnly = c.getInt(VIBRATE_ONLY_INDEX) == 1;
        vibrationStrength = clampVibrationStrength(c.getInt(VIBRATION_STRENGTH_INDEX));
        flashOnly = c.getInt(FLASH_ONLY_INDEX) == 1;
        ignoreCase = c.getInt(IGNORE_CASE_INDEX) == 1;
        skipDeclined = c.getInt(SKIP_DECLINED_INDEX) == 1;
        skipTentative = c.getInt(SKIP_TENTATIVE_INDEX) == 1;
        allDayMinutes = c.getInt(ALL_DAY_MINUTES_INDEX);
        createdAt = c.getLong(CREATED_AT_INDEX);
        updatedAt = c.getLong(UPDATED_AT_INDEX);
        showDetailsLocked = c.getInt(SHOW_DETAILS_LOCKED_INDEX) == 1;
        offsetEnabled = c.getInt(OFFSET_ENABLED_INDEX) == 1;
        offsetMinutes = clampOffsetMinutes(c.getInt(OFFSET_MINUTES_INDEX));
        escalation = c.getInt(ESCALATION_INDEX) == 1;
        ringAtStart = c.getInt(RING_AT_START_INDEX) == 1;
        wifiNetworks = c.isNull(WIFI_NETWORKS_INDEX) ? "" : c.getString(WIFI_NETWORKS_INDEX);
        wifiCheck = c.getInt(WIFI_CHECK_INDEX) == 1;
        wifiOffRings = c.getInt(WIFI_OFF_RINGS_INDEX) == 1;
        ringAtEnd = c.getInt(RING_AT_END_INDEX) == 1;
        extendOnSnooze = c.getInt(EXTEND_ON_SNOOZE_INDEX) == 1;
        snoozeMinutes2 = c.getInt(SNOOZE_MINUTES_2_INDEX);
        snoozeMinutes3 = c.getInt(SNOOZE_MINUTES_3_INDEX);
    }

    public ContentValues createContentValues() {
        final ContentValues values = new ContentValues(QUERY_COLUMNS.length);
        if (id != INVALID_ID) {
            values.put(_ID, id);
        }
        values.put(NAME, name == null ? "" : name);
        values.put(ENABLED, enabled ? 1 : 0);
        values.put(CALENDAR_ID, calendarId);
        values.put(ACCOUNT_NAME, accountName == null ? "" : accountName);
        values.put(CALENDAR_NAME, calendarName == null ? "" : calendarName);
        values.put(TITLE_REGEX, titleRegex == null ? "" : titleRegex);
        if (ringtone == null) {
            values.putNull(RINGTONE);
        } else {
            values.put(RINGTONE, ringtone.toString());
        }
        values.put(ALARM_VOLUME, alarmVolume);
        values.put(VIBRATE, vibrate ? 1 : 0);
        values.put(AUTO_SILENCE_DURATION, autoSilenceDuration);
        values.put(SNOOZE_DURATION, snoozeDuration);
        values.put(MAX_SNOOZE_COUNT, maxSnoozeCount);
        values.put(DISMISS_METHOD, dismissMethod == null ? DISMISS_METHOD_DEFAULT : dismissMethod);
        values.put(LOOKAHEAD_DAYS, clampLookaheadDays(lookaheadDays));
        values.put(FLASH, flash ? 1 : 0);
        values.put(VIBRATE_ONLY, vibrateOnly ? 1 : 0);
        values.put(VIBRATION_STRENGTH, clampVibrationStrength(vibrationStrength));
        values.put(FLASH_ONLY, flashOnly ? 1 : 0);
        values.put(IGNORE_CASE, ignoreCase ? 1 : 0);
        values.put(SKIP_DECLINED, skipDeclined ? 1 : 0);
        values.put(SKIP_TENTATIVE, skipTentative ? 1 : 0);
        values.put(ALL_DAY_MINUTES, allDayMinutes);
        values.put(SHOW_DETAILS_LOCKED, showDetailsLocked ? 1 : 0);
        values.put(OFFSET_ENABLED, offsetEnabled ? 1 : 0);
        values.put(OFFSET_MINUTES, clampOffsetMinutes(offsetMinutes));
        values.put(ESCALATION, escalation ? 1 : 0);
        values.put(RING_AT_START, ringAtStart ? 1 : 0);
        values.put(WIFI_NETWORKS, wifiNetworks == null ? "" : wifiNetworks);
        values.put(WIFI_CHECK, wifiCheck ? 1 : 0);
        values.put(WIFI_OFF_RINGS, wifiOffRings ? 1 : 0);
        values.put(RING_AT_END, ringAtEnd ? 1 : 0);
        values.put(EXTEND_ON_SNOOZE, extendOnSnooze ? 1 : 0);
        values.put(SNOOZE_MINUTES_2, snoozeMinutes2);
        values.put(SNOOZE_MINUTES_3, snoozeMinutes3);
        values.put(CREATED_AT, createdAt);
        values.put(UPDATED_AT, updatedAt);
        return values;
    }

    /**
     * @return the compiled title pattern, or {@code null} if the regex is empty or invalid.
     */
    @Nullable
    public Pattern compileTitlePattern() {
        return compileTitlePattern(titleRegex, ignoreCase);
    }

    @Nullable
    public static Pattern compileTitlePattern(String regex) {
        return compileTitlePattern(regex, false);
    }

    @Nullable
    public static Pattern compileTitlePattern(String regex, boolean ignoreCase) {
        if (TextUtils.isEmpty(regex)) {
            return null;
        }
        try {
            return ignoreCase
                ? Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                : Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /**
     * @return {@code true} if the event title matches this profile's regex. A regex that takes too
     * long on a title (catastrophic backtracking) counts as no match instead of blocking the app.
     */
    public static boolean titleMatches(@Nullable Pattern pattern, @Nullable String title) {
        if (pattern == null || title == null) {
            return false;
        }
        final String cut = title.length() > MAX_MATCHED_TITLE_LENGTH
            ? title.substring(0, MAX_MATCHED_TITLE_LENGTH) : title;
        try {
            return pattern.matcher(new DeadlineCharSequence(cut, System.currentTimeMillis() + MATCH_TIMEOUT_MS)).find();
        } catch (MatchTimeoutException e) {
            return false;
        }
    }

    private static final class MatchTimeoutException extends RuntimeException {
    }

    /**
     * Gives up once the deadline has passed. The regex engine reads characters all the time, so
     * this stops even a runaway match.
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private final CharSequence mText;
        private final long mDeadline;
        private int mReads;

        DeadlineCharSequence(CharSequence text, long deadline) {
            mText = text;
            mDeadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if (++mReads % 1024 == 0 && System.currentTimeMillis() > mDeadline) {
                throw new MatchTimeoutException();
            }
            return mText.charAt(index);
        }

        @Override
        public int length() {
            return mText.length();
        }

        @NonNull
        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(mText.subSequence(start, end), mDeadline);
        }

        @NonNull
        @Override
        public String toString() {
            return mText.toString();
        }
    }

    public static int clampVibrationStrength(int percent) {
        return Math.max(MIN_VIBRATION_STRENGTH, Math.min(MAX_VIBRATION_STRENGTH, percent));
    }

    public static int clampOffsetMinutes(int minutes) {
        return Math.max(MIN_OFFSET_MINUTES, Math.min(MAX_OFFSET_MINUTES, minutes));
    }

    /**
     * @return {@code true} if the profile rings before the event and again at its start.
     */
    public boolean ringsEarlyAndAtStart() {
        return offsetEnabled && ringAtStart;
    }

    /**
     * @return the three snooze lengths in minutes, each longer than the one before, or an empty
     * array if the profile doesn't snooze. Lengths stored out of order (for example from before
     * there were three) are moved up so the order holds.
     */
    public int[] getSnoozeChoices() {
        if (snoozeDuration == SNOOZE_DISABLED || snoozeDuration <= 0) {
            return new int[0];
        }
        final int first = Math.min(snoozeDuration, MAX_SNOOZE_MINUTES - 2);
        int second = snoozeMinutes2 > first ? snoozeMinutes2 : first * 2;
        second = Math.max(first + 1, Math.min(second, MAX_SNOOZE_MINUTES - 1));
        int third = snoozeMinutes3 > second ? snoozeMinutes3 : second * 2;
        third = Math.max(second + 1, Math.min(third, MAX_SNOOZE_MINUTES));
        return new int[]{first, second, third};
    }

    /**
     * @return {@code true} if the three snooze lengths are each longer than the one before.
     */
    public static boolean isSnoozeOrderValid(int first, int second, int third) {
        return first > 0 && first < second && second < third && third <= MAX_SNOOZE_MINUTES;
    }

    /**
     * @return how many milliseconds before the event the alarm rings, 0 without an offset.
     */
    public long getOffsetMillis() {
        return offsetEnabled ? clampOffsetMinutes(offsetMinutes) * 60_000L : 0L;
    }

    public static int clampLookaheadDays(int days) {
        return Math.max(MIN_LOOKAHEAD_DAYS, Math.min(MAX_LOOKAHEAD_DAYS, days));
    }

    public static Uri getContentUri(long profileId) {
        return ContentUris.withAppendedId(CONTENT_URI, profileId);
    }

    @Nullable
    public static CalendarProfile getProfile(ContentResolver cr, long profileId) {
        try (Cursor cursor = cr.query(getContentUri(profileId), QUERY_COLUMNS, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return new CalendarProfile(cursor);
            }
        }
        return null;
    }

    public static List<CalendarProfile> getProfiles(ContentResolver cr) {
        final List<CalendarProfile> result = new ArrayList<>();
        try (Cursor cursor = cr.query(CONTENT_URI, QUERY_COLUMNS, null, null, _ID + " ASC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    result.add(new CalendarProfile(cursor));
                }
            }
        }
        return result;
    }

    public CalendarProfile addProfile(ContentResolver cr) {
        final Uri uri = cr.insert(CONTENT_URI, createContentValues());
        id = ContentUris.parseId(uri);
        return this;
    }

    public void updateProfile(ContentResolver cr) {
        if (id == INVALID_ID) {
            return;
        }
        updatedAt = System.currentTimeMillis();
        cr.update(getContentUri(id), createContentValues(), null, null);
    }

    public static void deleteProfile(ContentResolver cr, long profileId) {
        if (profileId != INVALID_ID) {
            cr.delete(getContentUri(profileId), null, null);
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "CalendarProfile{id=" + id + ", name='" + name + "', enabled=" + enabled
            + ", calendarId=" + calendarId + ", titleRegex='" + titleRegex + "'}";
    }
}

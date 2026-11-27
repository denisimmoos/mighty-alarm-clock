/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.provider;

import android.net.Uri;
import android.provider.BaseColumns;

import com.best.deskclock.BuildConfig;

/**
 * <p>
 * The contract between the clock provider and desk clock. Contains
 * definitions for the supported URIs and data columns.
 * </p>
 * <h3>Overview</h3>
 * <p>
 * ClockContract defines the data model of clock related information.
 * This data is stored in a number of tables:
 * </p>
 * <ul>
 * <li>The {@link AlarmsColumns} table holds the user created alarms</li>
 * <li>The {@link InstancesColumns} table holds the current state of each
 * alarm in the AlarmsColumn table.
 * </li>
 * </ul>
 */
public final class ClockContract {

    /**
     * This authority is used for writing to or querying from the clock
     * provider.
     */
    public static final String AUTHORITY = BuildConfig.APPLICATION_ID;

    /**
     * This utility class cannot be instantiated
     */
    private ClockContract() {
    }

    /**
     * Constants for tables with AlarmSettings.
     */
    private interface AlarmSettingColumns extends BaseColumns {

        /**
         * True if alarm should vibrate
         * <p>Type: BOOLEAN</p>
         */
        String VIBRATE = "vibrate";

        /**
         * Alarm vibration pattern.
         *
         * <p>Type: STRING</p>
         */
        String VIBRATION_PATTERN = "vibrationPattern";

        /**
         * True if flash should turn on
         * <p>Type: BOOLEAN</p>
         */
        String FLASH = "flash";

        /**
         * Alarm label.
         *
         * <p>Type: STRING</p>
         */
        String LABEL = "label";

        /**
         * True if alarms sharing the same label should be enabled or disabled together.
         *
         * <p>Type: BOOLEAN</p>
         */
        String SYNC_BY_LABEL = "syncByLabel";

        /**
         * Audio alert to play when alarm triggers. Null entry
         * means use system default and entry that equal
         * Uri.EMPTY.toString() means no ringtone.
         *
         * <p>Type: STRING</p>
         */
        String RINGTONE = "ringtone";

        /**
         * Alarm auto silence duration.
         * <p>Type: INTEGER</p>
         */
        String AUTO_SILENCE_DURATION = "autoSilenceDuration";

        /**
         * Alarm snooze duration.
         * <p>Type: INTEGER</p>
         */
        String SNOOZE_DURATION = "snoozeDuration";

        /**
         * Missed alarm repeat limit
         * <p>Type: INTEGER</p>
         */
        String MISSED_ALARM_REPEAT_LIMIT = "missed_alarm_repeat_limit";

        /**
         * Alarm crescendo duration.
         * <p>Type: INTEGER</p>
         */
        String CRESCENDO_DURATION = "crescendoDuration";

        /**
         * Alarm crescendo duration.
         * <p>Type: INTEGER</p>
         */
        String ALARM_VOLUME = "alarmVolume";

        /**
         * Manuel sort order.
         * <p>Type: INTEGER</p>
         */
        String MANUAL_SORT_ORDER = "manualSortOrder";

        /**
         * Start of the pause.
         * <p>Type: LONG</p>
         */
        String PAUSE_START_DATE = "pauseStartDate";

        /**
         * End of the pause.
         * <p>Type: LONG</p>
         */
        String PAUSE_END_DATE = "pauseEndDate";
    }

    /**
     * Column names shared by tables that track creation/update timestamps.
     */
    private interface TimestampColumns {

        /**
         * Timestamp (ms since epoch) when the row was created.
         * <p>Type: LONG</p>
         */
        String CREATED_AT = "created_at";

        /**
         * Timestamp (ms since epoch) when the row was last updated.
         * <p>Type: LONG</p>
         */
        String UPDATED_AT = "updated_at";
    }

    /**
     * Constants for the Alarms table, which contains the user created alarms.
     */
    protected interface AlarmsColumns extends AlarmSettingColumns, TimestampColumns, BaseColumns {

        /**
         * The content:// style URL for this table.
         */
        Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/alarms");

        /**
         * The content:// style URL for the alarms with instance tables, which is used to get the
         * next firing instance and the current state of an alarm.
         */
        Uri ALARMS_WITH_INSTANCES_URI = Uri.parse("content://" + AUTHORITY + "/alarms_with_instances");

        /**
         * Alarm year.
         *
         * <p>Type: INTEGER</p>
         */
        String YEAR = "year";

        /**
         * Alarm month in year.
         *
         * <p>Type: INTEGER</p>
         */
        String MONTH = "month";

        /**
         * Alarm day in month.
         *
         * <p>Type: INTEGER</p>
         */
        String DAY = "day";

        /**
         * Hour in 24-hour localtime 0 - 23.
         * <p>Type: INTEGER</p>
         */
        String HOUR = "hour";

        /**
         * Minutes in localtime 0 - 59.
         * <p>Type: INTEGER</p>
         */
        String MINUTES = "minutes";

        /**
         * Days of the week encoded as a bit set.
         * <p>Type: INTEGER</p>
         * <p>
         * {@link com.best.deskclock.data.Weekdays}
         */
        String DAYS_OF_WEEK = "daysofweek";

        /**
         * True if alarm is active.
         * <p>Type: BOOLEAN</p>
         */
        String ENABLED = "enabled";

        /**
         * Determine if alarm is deleted after it has been used.
         * <p>Type: INTEGER</p>
         */
        String DELETE_AFTER_USE = "delete_after_use";

        /**
         * Stable unique identifier for the alarm that survives id reuse/renumbering.
         * <p>Type: TEXT</p>
         */
        String STABLE_UUID = "stable_uuid";

        /**
         * Number of times this alarm has rung.
         * <p>Type: INTEGER</p>
         */
        String RING_COUNT = "ring_count";

        /**
         * True if the snooze duration should be progressively extended on repeated snoozes.
         * <p>Type: BOOLEAN</p>
         */
        String SNOOZE_EXTEND_ENABLED = "snooze_extend_enabled";

        /**
         * Number of minutes to extend the snooze duration by on each successive snooze.
         * <p>Type: INTEGER</p>
         */
        String SNOOZE_EXTEND_MINUTES = "snooze_extend_minutes";

        /**
         * Maximum number of minutes the snooze duration is allowed to be extended to.
         * <p>Type: INTEGER</p>
         */
        String SNOOZE_EXTEND_MAX_MINUTES = "snooze_extend_max_minutes";

        /**
         * True if a Wi-Fi based rule should control this alarm.
         * <p>Type: BOOLEAN</p>
         */
        String WIFI_RULE_ENABLED = "wifi_rule_enabled";

        /**
         * SSID of the Wi-Fi network used by the Wi-Fi rule.
         * <p>Type: STRING</p>
         */
        String WIFI_SSID = "wifi_ssid";

        /**
         * Condition of the Wi-Fi rule. One of {@link #WIFI_CONDITION_PRESENT} or
         * {@link #WIFI_CONDITION_ABSENT}.
         * <p>Type: STRING</p>
         */
        String WIFI_CONDITION = "wifi_condition";

        /**
         * Action of the Wi-Fi rule. One of {@link #WIFI_ACTION_ENABLE} or
         * {@link #WIFI_ACTION_DISABLE}.
         * <p>Type: STRING</p>
         */
        String WIFI_ACTION = "wifi_action";

        /**
         * {@link #WIFI_CONDITION} value meaning the Wi-Fi network is in range/connected.
         */
        String WIFI_CONDITION_PRESENT = "PRESENT";

        /**
         * {@link #WIFI_CONDITION} value meaning the Wi-Fi network is out of range/disconnected.
         */
        String WIFI_CONDITION_ABSENT = "ABSENT";

        /**
         * {@link #WIFI_ACTION} value meaning the alarm should be enabled when the Wi-Fi
         * condition is met.
         */
        String WIFI_ACTION_ENABLE = "ENABLE";

        /**
         * {@link #WIFI_ACTION} value meaning the alarm should be disabled when the Wi-Fi
         * condition is met.
         */
        String WIFI_ACTION_DISABLE = "DISABLE";

        /**
         * Interval in minutes between successive firings when interval repeat is enabled.
         * 0 means interval repeat is off (weekday or one-shot mode applies instead).
         * <p>Type: INTEGER</p>
         */
        String REPEAT_INTERVAL_MINUTES = "repeat_interval_minutes";

        /**
         * Maximum number of interval firings before the alarm is disabled.
         * 0 means unlimited.
         * <p>Type: INTEGER</p>
         */
        String REPEAT_MAX_COUNT = "repeat_max_count";

        /**
         * Number of times this interval alarm has fired since the counter was last reset.
         * <p>Type: INTEGER</p>
         */
        String INTERVAL_FIRE_COUNT = "interval_fire_count";
    }

    /**
     * Constants for the Instance table, which contains the state of each alarm.
     */
    protected interface InstancesColumns extends AlarmSettingColumns, BaseColumns {

        /**
         * The content:// style URL for this table.
         */
        Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/instances");

        /**
         * Alarm state when to show no notification.
         * <p>
         * Can transitions to:
         * NOTIFICATION_STATE
         */
        int SILENT_STATE = 0;

        /**
         * Alarm state to show alarm notification.
         * <p>
         * Can transitions to:
         * DISMISSED_STATE
         * FIRED_STATE
         */
        int NOTIFICATION_STATE = 1;

        /**
         * Alarm state when alarm is in snooze.
         * <p>
         * Can transitions to:
         * DISMISSED_STATE
         * FIRED_STATE
         */
        int SNOOZE_STATE = 2;

        /**
         * Alarm state when alarm is being fired.
         * <p>
         * Can transitions to:
         * DISMISSED_STATE
         * SNOOZED_STATE
         * MISSED_STATE
         */
        int FIRED_STATE = 3;

        /**
         * Alarm state when alarm has been missed.
         * <p>
         * Can transitions to:
         * DISMISSED_STATE
         */
        int MISSED_STATE = 4;

        /**
         * Alarm state when alarm is done.
         */
        int DISMISSED_STATE = 5;

        /**
         * Alarm state when alarm has been dismissed before its intended firing time.
         */
        int PREDISMISSED_STATE = 6;

        /**
         * Alarm year.
         *
         * <p>Type: INTEGER</p>
         */
        String YEAR = "year";

        /**
         * Alarm month in year.
         *
         * <p>Type: INTEGER</p>
         */
        String MONTH = "month";

        /**
         * Alarm day in month.
         *
         * <p>Type: INTEGER</p>
         */
        String DAY = "day";

        /**
         * Alarm hour in 24-hour localtime 0 - 23.
         * <p>Type: INTEGER</p>
         */
        String HOUR = "hour";

        /**
         * Alarm minutes in localtime 0 - 59
         * <p>Type: INTEGER</p>
         */
        String MINUTES = "minutes";

        /**
         * Foreign key to Alarms table
         * <p>Type: INTEGER (long)</p>
         */
        String ALARM_ID = "alarm_id";

        /**
         * Alarm state
         * <p>Type: INTEGER</p>
         */
        String ALARM_STATE = "alarm_state";

        /**
         * Missed alarm repeat count
         * <p>Type: INTEGER</p>
         */
        String MISSED_ALARM_REPEAT_COUNT = "missed_alarm_repeat_count";

        /**
         * Number of times this alarm instance has been snoozed.
         * <p>Type: INTEGER</p>
         */
        String SNOOZE_COUNT = "snooze_count";
    }

    /**
     * Constants for the Tags table, which contains user-defined alarm tags.
     */
    protected interface TagsColumns extends TimestampColumns, BaseColumns {

        /**
         * The content:// style URL for this table.
         */
        Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/tags");

        /**
         * Tag name.
         * <p>Type: TEXT</p>
         */
        String NAME = "name";

        /**
         * Tag color.
         * <p>Type: INTEGER</p>
         */
        String COLOR = "color";

        /**
         * Optional ringtone associated with the tag.
         * <p>Type: TEXT</p>
         */
        String RINGTONE_URI = "ringtone_uri";
    }

    /**
     * Constants for the AlarmTags table, which maps alarms to tags (many-to-many).
     */
    protected interface AlarmTagsColumns {

        /**
         * The content:// style URL for this table.
         */
        Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/alarm_tags");

        /**
         * Foreign key to the Alarms table.
         * <p>Type: INTEGER (long)</p>
         */
        String ALARM_ID = "alarm_id";

        /**
         * Foreign key to the Tags table.
         * <p>Type: INTEGER (long)</p>
         */
        String TAG_ID = "tag_id";
    }

    /**
     * Constants for the EventLog table, which contains a history of alarm-related events.
     */
    protected interface EventLogColumns extends BaseColumns {

        /**
         * The content:// style URL for this table.
         */
        Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/event_log");

        /**
         * Timestamp (ms since epoch) when the event occurred.
         * <p>Type: LONG</p>
         */
        String TIMESTAMP = "timestamp";

        /**
         * Type of event, e.g. "ALARM_FIRED", "ALARM_DISMISSED", "ALARM_SNOOZED".
         * <p>Type: TEXT</p>
         */
        String EVENT_TYPE = "event_type";

        /**
         * Stable UUID of the alarm the event refers to, if any.
         * <p>Type: TEXT</p>
         */
        String ALARM_UUID = "alarm_uuid";

        /**
         * Label of the alarm at the time the event occurred, if any.
         * <p>Type: TEXT</p>
         */
        String ALARM_LABEL = "alarm_label";

        /**
         * Free-form details about the event.
         * <p>Type: TEXT</p>
         */
        String DETAILS = "details";
    }

    /**
     * Constants for the calendar alarm profiles table. A profile watches one device calendar
     * and turns every upcoming event whose title matches its regex into an alarm.
     */
    protected interface CalendarProfilesColumns extends TimestampColumns, BaseColumns {

        /**
         * The content:// style URL for this table.
         */
        Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/calendar_profiles");

        /**
         * Display name of the profile.
         * <p>Type: TEXT</p>
         */
        String NAME = "name";

        /**
         * True if the profile creates alarms.
         * <p>Type: BOOLEAN</p>
         */
        String ENABLED = "enabled";

        /**
         * Id of the calendar in the system calendar provider ({@code CalendarContract.Calendars._ID}).
         * <p>Type: INTEGER (long)</p>
         */
        String CALENDAR_ID = "calendar_id";

        /**
         * Account the calendar belongs to, kept for display when the calendar is unavailable.
         * <p>Type: TEXT</p>
         */
        String ACCOUNT_NAME = "account_name";

        /**
         * Display name of the calendar, kept for display when the calendar is unavailable.
         * <p>Type: TEXT</p>
         */
        String CALENDAR_NAME = "calendar_name";

        /**
         * Regular expression that an event title must match (find semantics).
         * <p>Type: TEXT</p>
         */
        String TITLE_REGEX = "title_regex";

        /**
         * Alarm sound uri.
         * <p>Type: TEXT</p>
         */
        String RINGTONE = "ringtone";

        /**
         * Alarm volume in stream steps, same meaning as the alarm volume column.
         * <p>Type: INTEGER</p>
         */
        String ALARM_VOLUME = "alarm_volume";

        /**
         * True if the alarm should vibrate.
         * <p>Type: BOOLEAN</p>
         */
        String VIBRATE = "vibrate";

        /**
         * Maximum ring time in seconds, -1 to ring until dismissed.
         * <p>Type: INTEGER</p>
         */
        String AUTO_SILENCE_DURATION = "auto_silence_duration";

        /**
         * Snooze duration in minutes, -1 to disable snooze.
         * <p>Type: INTEGER</p>
         */
        String SNOOZE_DURATION = "snooze_duration";

        /**
         * Maximum number of snoozes per alarm, 0 for unlimited.
         * <p>Type: INTEGER</p>
         */
        String MAX_SNOOZE_COUNT = "max_snooze_count";

        /**
         * How the fullscreen alarm is dismissed: "default", "slide" or "button".
         * <p>Type: TEXT</p>
         */
        String DISMISS_METHOD = "dismiss_method";

        /**
         * How many days ahead the profile looks for matching events (7 to 30).
         * <p>Type: INTEGER</p>
         */
        String LOOKAHEAD_DAYS = "lookahead_days";

        /**
         * True if the camera flashlight should blink while the alarm rings.
         * <p>Type: BOOLEAN</p>
         */
        String FLASH = "flash";

        /**
         * True if the alarm should only vibrate, without any sound.
         * <p>Type: BOOLEAN</p>
         */
        String VIBRATE_ONLY = "vibrate_only";

        /**
         * Vibration strength in percent, 10 to 100.
         * <p>Type: INTEGER</p>
         */
        String VIBRATION_STRENGTH = "vibration_strength";

        /**
         * True if the alarm should only blink the flashlight, without sound or vibration.
         * <p>Type: BOOLEAN</p>
         */
        String FLASH_ONLY = "flash_only";

        /**
         * True if the title regex ignores upper and lower case.
         * <p>Type: BOOLEAN</p>
         */
        String IGNORE_CASE = "ignore_case";

        /**
         * True if events the user declined get no alarm.
         * <p>Type: BOOLEAN</p>
         */
        String SKIP_DECLINED = "skip_declined";

        /**
         * True if events the user answered "maybe" to get no alarm.
         * <p>Type: BOOLEAN</p>
         */
        String SKIP_TENTATIVE = "skip_tentative";

        /**
         * True if the event's description and place are shown on the alarm screen while the
         * phone is locked. If false, they are shown once the phone is unlocked.
         * <p>Type: BOOLEAN</p>
         */
        String SHOW_DETAILS_LOCKED = "show_details_locked";

        /**
         * True if the alarm rings {@link #OFFSET_MINUTES} before the event starts.
         * <p>Type: BOOLEAN</p>
         */
        String OFFSET_ENABLED = "offset_enabled";

        /**
         * How many minutes before the event the alarm rings, if {@link #OFFSET_ENABLED}.
         * <p>Type: INTEGER</p>
         */
        String OFFSET_MINUTES = "offset_minutes";

        /**
         * True if the alarm starts quietly and gets louder and vibrates stronger over time.
         * <p>Type: BOOLEAN</p>
         */
        String ESCALATION = "escalation";

        /**
         * True if a profile with an offset also rings again when the event starts.
         * <p>Type: BOOLEAN</p>
         */
        String RING_AT_START = "ring_at_start";

        /**
         * Wi-Fi networks the profile only rings near, as a JSON array of objects with "ssid" and
         * "bssid". Empty to ring anywhere.
         * <p>Type: TEXT</p>
         */
        String WIFI_NETWORKS = "wifi_networks";

        /**
         * True if the profile only rings near one of its {@link #WIFI_NETWORKS}. Off by default.
         * <p>Type: BOOLEAN</p>
         */
        String WIFI_CHECK = "wifi_check";

        /**
         * True if an alarm with the Wi-Fi check rings anyway while the phone's Wi-Fi is turned
         * off. Off by default.
         * <p>Type: BOOLEAN</p>
         */
        String WIFI_OFF_RINGS = "wifi_off_rings";

        /**
         * True if the profile also rings when the event ends. Off by default.
         * <p>Type: BOOLEAN</p>
         */
        String RING_AT_END = "ring_at_end";

        /**
         * True if snoozing the alarm at the end of an event extends the event until the alarm
         * rings again. Off by default.
         * <p>Type: BOOLEAN</p>
         */
        String EXTEND_ON_SNOOZE = "extend_on_snooze";

        /**
         * Time of day for all-day events in minutes after midnight, or -1 to ignore them.
         * <p>Type: INTEGER</p>
         */
        String ALL_DAY_MINUTES = "all_day_minutes";

        String DISMISS_METHOD_DEFAULT = "default";
        String DISMISS_METHOD_SLIDE = "slide";
        String DISMISS_METHOD_BUTTON = "button";
    }

    /**
     * Constants for the calendar alarm events table. Each row is one upcoming calendar event
     * matched by a profile, linked to the alarm created for it.
     */
    protected interface CalendarAlarmEventsColumns extends BaseColumns {

        /**
         * The content:// style URL for this table.
         */
        Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/calendar_alarm_events");

        /**
         * Foreign key to the calendar profiles table.
         * <p>Type: INTEGER (long)</p>
         */
        String PROFILE_ID = "profile_id";

        /**
         * Stable key of the event occurrence: event id and original start time.
         * <p>Type: TEXT</p>
         */
        String EVENT_KEY = "event_key";

        /**
         * Id of the event in the system calendar provider.
         * <p>Type: INTEGER (long)</p>
         */
        String EVENT_ID = "event_id";

        /**
         * Event title.
         * <p>Type: TEXT</p>
         */
        String TITLE = "title";

        /**
         * Start time of the occurrence in ms since epoch.
         * <p>Type: LONG</p>
         */
        String BEGIN = "begin";

        /**
         * False if the user switched off the alarm for this single event.
         * <p>Type: BOOLEAN</p>
         */
        String USER_ENABLED = "user_enabled";

        /**
         * Id of the alarm created for this event, or -1 if there is none.
         * <p>Type: INTEGER (long)</p>
         */
        String ALARM_ID = "alarm_id";

        /**
         * Id of the second alarm at the start of the event, when the profile rings early and
         * again at the start, -1 if there is none, or -2 once it was dismissed.
         * <p>Type: INTEGER (long)</p>
         */
        String START_ALARM_ID = "start_alarm_id";

        /**
         * When the event ends, 0 if unknown or for all-day events.
         * <p>Type: INTEGER (long)</p>
         */
        String END = "event_end";

        /**
         * Id of the alarm at the end of the event, -1 if there is none, or -2 once it was dismissed.
         * <p>Type: INTEGER (long)</p>
         */
        String END_ALARM_ID = "end_alarm_id";
    }
}

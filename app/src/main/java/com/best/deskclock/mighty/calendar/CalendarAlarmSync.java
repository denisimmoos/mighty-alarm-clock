// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.best.deskclock.alarms.AlarmStateManager;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.Weekdays;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.CalendarAlarmEvent;
import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.RingtoneUtils;

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Turns matching calendar events into alarms.
 *
 * <p>For every profile, the events of its calendar in the next {@link CalendarProfile#lookaheadDays} days are
 * matched against the profile's title regex. Each match gets a row in the calendar alarm events
 * table and, while the profile and the event are switched on, a one-time alarm at the event's
 * start time. The alarm goes through the app's normal alarm pipeline, so the fullscreen alarm,
 * snooze and auto silence all work as for any other alarm.</p>
 *
 * <p>Alarms that are ringing or snoozed are never changed or removed by a sync.</p>
 */
public final class CalendarAlarmSync {

    /** How long rows of past events are kept, so a missed alarm notification stays visible. */
    private static final long PAST_EVENT_RETENTION_MS = TimeUnit.HOURS.toMillis(12);

    private static final Object LOCK = new Object();

    /** Length of the escalation from quiet to full strength, in seconds. */
    public static final int ESCALATION_SECONDS = 60;
    /** Shortest escalation, used when the maximum ring time is very short. */
    public static final int MIN_ESCALATION_SECONDS = 10;

    /** When the calendar was last read successfully, in milliseconds since the epoch. */
    // Stored name from before the feature was called "Calendar Alarm"; kept so existing data still works.
    public static final String KEY_LAST_CHECK = "key_calendar_alert_last_check";

    /**
     * Calendar checks run on their own thread, so a slow calendar or a bad regex never holds up
     * the rest of the app.
     */
    public static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private CalendarAlarmSync() {
    }

    /**
     * Brings the calendar alarms of every profile up to date. Must not run on the main thread.
     * Never throws: a failure is logged so a background sync can never crash the app.
     */
    public static void syncAll(Context context) {
        synchronized (LOCK) {
            try {
                syncAllLocked(context.getApplicationContext());
            } catch (Throwable e) {
                LogUtils.e("CalendarAlarmSync: sync failed", e);
            }
        }
    }

    private static void syncAllLocked(Context appContext) {
        final ContentResolver cr = appContext.getContentResolver();
        final long now = System.currentTimeMillis();
        final boolean canReadCalendar = DeviceCalendars.hasPermission(appContext);

        final List<CalendarProfile> profiles = CalendarProfile.getProfiles(cr);
        final Set<Long> profileIds = new HashSet<>();
        // An event matched by several profiles gets one alarm, from the first profile in the list.
        final Set<String> claimedKeys = new HashSet<>();
        for (CalendarProfile profile : profiles) {
            profileIds.add(profile.id);
            try {
                syncProfile(appContext, profile, now, canReadCalendar, claimedKeys);
            } catch (Throwable e) {
                LogUtils.e("CalendarAlarmSync: failed to sync profile " + profile.id, e);
            }
        }

        CalendarAlarmStatus.update(appContext, canReadCalendar, !profiles.isEmpty(), now);

        // Clean up alarms and rows left behind by deleted profiles, ringing ones included.
        for (CalendarAlarmEvent event : CalendarAlarmEvent.getAllEvents(cr)) {
            if (!profileIds.contains(event.profileId)) {
                deleteEventAndAlarm(appContext, event);
            }
        }
    }

    /**
     * Deletes the event row and its alarm, stopping the alarm if it is ringing. A failure to stop
     * the alarm never keeps the row, so a deleted profile cannot come back.
     */
    private static void deleteEventAndAlarm(Context context, CalendarAlarmEvent event) {
        final ContentResolver cr = context.getContentResolver();
        for (long alarmId : new long[]{event.alarmId, event.startAlarmId}) {
            try {
                if (alarmExists(cr, alarmId)) {
                    deleteAlarm(context, alarmId);
                }
            } catch (Throwable e) {
                LogUtils.e("CalendarAlarmSync: could not stop alarm " + alarmId, e);
                try {
                    // At least remove the alarm itself so it can't ring any more.
                    for (AlarmInstance instance : AlarmInstance.getInstancesByAlarmId(cr, alarmId)) {
                        AlarmInstance.deleteInstance(cr, instance.mId);
                    }
                    Alarm.deleteAlarm(cr, alarmId);
                } catch (Throwable ignored) {
                    // Nothing more can be done here.
                }
            }
        }
        event.delete(cr);
    }

    /**
     * Brings the calendar alarms of one profile up to date. Must not run on the main thread.
     */
    public static void syncProfile(Context context, long profileId) {
        // Profiles can share events, so one profile's change can affect another. Check them all.
        syncAll(context);
    }

    /**
     * Deletes a profile together with its alarms. Must not run on the main thread.
     */
    public static void deleteProfile(Context context, long profileId) {
        synchronized (LOCK) {
            try {
                final Context appContext = context.getApplicationContext();
                final ContentResolver cr = appContext.getContentResolver();
                // The profile goes first, so it is gone even if stopping an alarm fails.
                CalendarProfile.deleteProfile(cr, profileId);
                for (CalendarAlarmEvent event : CalendarAlarmEvent.getEventsForProfile(cr, profileId)) {
                    // Also stops an alarm that is ringing or snoozed right now.
                    deleteEventAndAlarm(appContext, event);
                }
                // Another profile may match the same events and now gets their alarms.
                syncAllLocked(appContext);
            } catch (Throwable e) {
                LogUtils.e("CalendarAlarmSync: failed to delete profile " + profileId, e);
            }
        }
    }

    /**
     * Forgets all profiles and their events, for the app reset. The alarms themselves are deleted
     * by the caller. Must not run on the main thread.
     */
    public static void deleteAllProfiles(Context context) {
        synchronized (LOCK) {
            final ContentResolver cr = context.getContentResolver();
            final Set<Long> profileIds = new HashSet<>();
            for (CalendarProfile profile : CalendarProfile.getProfiles(cr)) {
                profileIds.add(profile.id);
            }
            for (CalendarAlarmEvent event : CalendarAlarmEvent.getAllEvents(cr)) {
                profileIds.add(event.profileId);
            }
            for (Long profileId : profileIds) {
                // Events first, so deleting the alarms afterwards doesn't mark them as dismissed.
                CalendarAlarmEvent.deleteEventsForProfile(cr, profileId);
                CalendarProfile.deleteProfile(cr, profileId);
            }
        }
    }

    /**
     * Switches the alarm of a single event on or off. Must not run on the main thread.
     */
    public static void setEventEnabled(Context context, long eventRowId, boolean enabled) {
        synchronized (LOCK) {
            try {
                final Context appContext = context.getApplicationContext();
                final ContentResolver cr = appContext.getContentResolver();
                final CalendarAlarmEvent event = CalendarAlarmEvent.getEvent(cr, eventRowId);
                if (event == null) {
                    return;
                }
                final CalendarProfile profile = CalendarProfile.getProfile(cr, event.profileId);
                if (profile == null) {
                    return;
                }
                event.userEnabled = enabled;
                // Switching an alarm off also stops it if it is ringing or snoozed right now.
                reconcileAlarms(appContext, profile, event, profile.enabled && enabled, true, !enabled);
                event.save(cr);
            } catch (Throwable e) {
                LogUtils.e("CalendarAlarmSync: failed to switch event " + eventRowId, e);
            }
        }
    }

    private static void syncProfile(Context context, CalendarProfile profile, long now, boolean canReadCalendar,
                                    Set<String> claimedKeys) {
        final ContentResolver cr = context.getContentResolver();
        final List<CalendarAlarmEvent> existing = CalendarAlarmEvent.getEventsForProfile(cr, profile.id);

        if (!profile.enabled) {
            silenceProfile(context, existing, now);
        }

        if (!canReadCalendar) {
            // Without calendar access, nothing can be checked. Keep what is already scheduled.
            LogUtils.w("CalendarAlarmSync: no calendar permission, profile " + profile.id + " not synced");
            keepClaims(existing, profile, claimedKeys);
            return;
        }

        final Map<String, CalendarAlarmEvent> existingByKey = new HashMap<>();
        for (CalendarAlarmEvent event : existing) {
            existingByKey.put(event.eventKey, event);
        }

        final Pattern pattern = profile.compileTitlePattern();
        final long until = now + TimeUnit.DAYS.toMillis(CalendarProfile.clampLookaheadDays(profile.lookaheadDays));
        // A removed calendar, or a new calendar that got the number of a removed one, has no
        // events for this profile.
        final DeviceCalendars.CalendarInfo calendar = DeviceCalendars.findProfileCalendar(context,
            profile.calendarId, profile.accountName, profile.calendarName);
        if (calendar != null && calendar.id >= 0
            && (calendar.id != profile.calendarId || !calendar.displayName.equals(profile.calendarName))) {
            // The calendar got another number (for example after a restore) or was renamed.
            LogUtils.i("CalendarAlarmSync: profile " + profile.id + " now watches calendar " + calendar.id);
            profile.calendarId = calendar.id;
            profile.calendarName = calendar.displayName;
            profile.updateProfile(context.getContentResolver());
        }
        final List<DeviceCalendars.EventInstance> instances = calendar != null && calendar.id < 0
            ? new java.util.ArrayList<>()
            : DeviceCalendars.getEventInstances(context, profile.calendarId, now, until, profile.allDayMinutes);
        if (calendar == null || instances == null) {
            // The calendar could not be read right now. Keep what is scheduled and try again later.
            LogUtils.w("CalendarAlarmSync: calendar of profile " + profile.id + " could not be read");
            keepClaims(existing, profile, claimedKeys);
            return;
        }

        final Set<String> matchedKeys = new HashSet<>();
        final List<DeviceCalendars.EventInstance> matched = new java.util.ArrayList<>();
        final Map<Long, Integer> matchesPerEvent = new HashMap<>();
        for (DeviceCalendars.EventInstance instance : instances) {
            if (!CalendarProfile.titleMatches(pattern, instance.title) || isSkipped(profile, instance)) {
                continue;
            }
            final String key = CalendarAlarmEvent.buildKey(instance.eventId, instance.begin);
            if (claimedKeys.contains(key)) {
                // An earlier profile already rings for this event.
                continue;
            }
            matchedKeys.add(key);
            matched.add(instance);
            final Integer count = matchesPerEvent.get(instance.eventId);
            matchesPerEvent.put(instance.eventId, count == null ? 1 : count + 1);
        }

        // Remember alarms the user switched off, so they stay off if their event is moved.
        final Set<Long> switchedOffEvents = new HashSet<>();
        for (CalendarAlarmEvent event : existing) {
            if (!event.userEnabled && !matchedKeys.contains(event.eventKey)) {
                switchedOffEvents.add(event.eventId);
            }
        }

        // Remove stale rows first, so one failing event can never leave an old alarm behind.
        for (CalendarAlarmEvent event : existing) {
            if (matchedKeys.contains(event.eventKey)) {
                continue;
            }
            try {
                if (event.begin > now) {
                    // The event was deleted, moved, renamed so it no longer matches, or the profile
                    // now points to another calendar or regex.
                    removeEvent(context, event, false);
                } else if (event.begin < now - PAST_EVENT_RETENTION_MS
                    || (!alarmExists(cr, event.alarmId) && !alarmExists(cr, event.startAlarmId))) {
                    removeEvent(context, event, false);
                }
            } catch (Throwable e) {
                LogUtils.e("CalendarAlarmSync: failed to remove " + event, e);
            }
        }

        for (DeviceCalendars.EventInstance instance : matched) {
            try {
                final String key = CalendarAlarmEvent.buildKey(instance.eventId, instance.begin);
                CalendarAlarmEvent event = existingByKey.get(key);
                if (event == null) {
                    event = new CalendarAlarmEvent(profile.id, instance.eventId, instance.begin, instance.title);
                    // A moved event that is not repeating keeps its switched off alarm.
                    if (switchedOffEvents.contains(instance.eventId)
                        && Integer.valueOf(1).equals(matchesPerEvent.get(instance.eventId))) {
                        event.userEnabled = false;
                    }
                } else {
                    event.title = instance.title;
                }

                reconcileAlarms(context, profile, event, profile.enabled && event.userEnabled, false, false);
                event.save(cr);
                if (profile.enabled && event.userEnabled) {
                    claimedKeys.add(key);
                }
            } catch (Throwable e) {
                LogUtils.e("CalendarAlarmSync: failed to update event " + instance.eventId, e);
            }
        }
    }

    private static void keepClaims(List<CalendarAlarmEvent> existing, CalendarProfile profile, Set<String> claimedKeys) {
        if (profile.enabled) {
            for (CalendarAlarmEvent event : existing) {
                if (event.userEnabled && (event.alarmId >= 0 || event.startAlarmId >= 0)) {
                    claimedKeys.add(event.eventKey);
                }
            }
        }
    }

    /**
     * @return {@code true} if the profile leaves out this event because of the user's answer to it.
     */
    private static boolean isSkipped(CalendarProfile profile, DeviceCalendars.EventInstance instance) {
        return (profile.skipDeclined
            && instance.selfAttendeeStatus == android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)
            || (profile.skipTentative
            && instance.selfAttendeeStatus == android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE);
    }

    /**
     * A switched off profile has no alarms at all: this also stops one that is ringing or snoozed
     * right now (sound, vibration and flashlight). Future events keep their row, so their alarms
     * come back when the profile is switched on again.
     */
    private static void silenceProfile(Context context, List<CalendarAlarmEvent> existing, long now) {
        final ContentResolver cr = context.getContentResolver();
        for (CalendarAlarmEvent event : existing) {
            try {
                final boolean early = alarmExists(cr, event.alarmId);
                final boolean start = alarmExists(cr, event.startAlarmId);
                if (!early && !start) {
                    // Nothing scheduled. A dismissed event stays dismissed.
                    continue;
                }
                if (early) {
                    deleteAlarm(context, event.alarmId);
                    LogUtils.i("CalendarAlarmSync: profile off, removed alarm " + event.alarmId);
                    event.alarmId = Alarm.INVALID_ID;
                }
                if (start) {
                    deleteAlarm(context, event.startAlarmId);
                    LogUtils.i("CalendarAlarmSync: profile off, removed alarm " + event.startAlarmId);
                    event.startAlarmId = Alarm.INVALID_ID;
                }
                if (event.begin <= now) {
                    event.delete(cr);
                } else {
                    event.save(cr);
                }
            } catch (Throwable e) {
                LogUtils.e("CalendarAlarmSync: failed to silence " + event, e);
            }
        }
    }

    /**
     * Makes the alarm of the event match the profile.
     *
     * @return the id of the event's alarm afterwards, or {@link Alarm#INVALID_ID} if it has none
     */
    /**
     * Brings both alarms of an event up to date: the alarm at the ring time (the event start, or
     * the offset before it), and with an offset that also rings at the start, a second alarm then.
     *
     * @param rearmConsumed {@code true} to create new alarms even if the event's alarms were already
     *                      used up (dismissed early from the notification, or rang and were dismissed)
     * @param stopRinging   {@code true} to also remove alarms that are ringing or snoozed, when they
     *                      should no longer be armed
     */
    private static void reconcileAlarms(Context context, CalendarProfile profile, CalendarAlarmEvent event,
                                        boolean shouldBeArmed, boolean rearmConsumed, boolean stopRinging) {
        final long now = System.currentTimeMillis();
        final long ringTime = ringTimeFor(profile, event.begin, now);
        final long startTime = alarmTimeFor(event.begin, now);
        final boolean[] armed = planAlarms(profile, event.begin, now, rearmConsumed);
        event.alarmId = reconcileAlarm(context, profile, event, event.alarmId, ringTime,
            shouldBeArmed && armed[0], rearmConsumed, stopRinging);
        final long startAlarmId = reconcileAlarm(context, profile, event, event.startAlarmId, startTime,
            shouldBeArmed && armed[1], rearmConsumed, stopRinging);
        // Without a second alarm wanted, forget that one was dismissed, so it can come back
        // when the setting is switched on again.
        event.startAlarmId = !profile.ringsEarlyAndAtStart() && startAlarmId == CalendarAlarmEvent.ALARM_CONSUMED
            ? Alarm.INVALID_ID : startAlarmId;
    }

    /**
     * @return which alarms an event should have: {@code [early, start]}. The early alarm is the
     * one at the ring time, which is the start itself without an offset.
     * <ul>
     *     <li>With a second alarm at the start, that one is always wanted, also in the last minute
     *     before the event when the early ring time and the start fall together. Then the early
     *     alarm is not needed.</li>
     *     <li>Switching an event on again, after its early ring time has passed, only arms the
     *     alarm at the start instead of ringing early right away.</li>
     * </ul>
     */
    static boolean[] planAlarms(CalendarProfile profile, long begin, long now, boolean rearm) {
        if (!profile.ringsEarlyAndAtStart()) {
            return new boolean[]{true, false};
        }
        final boolean earlyPassed = begin - profile.getOffsetMillis() <= now;
        final boolean early = ringTimeFor(profile, begin, now) < alarmTimeFor(begin, now)
            && !(rearm && earlyPassed);
        return new boolean[]{early, true};
    }

    /**
     * Brings one alarm of an event up to date.
     *
     * @param alarmId  the alarm's current id, {@link Alarm#INVALID_ID} or
     *                 {@link CalendarAlarmEvent#ALARM_CONSUMED}
     * @param ringTime when the alarm rings
     * @return the alarm's new id, {@link Alarm#INVALID_ID} or {@link CalendarAlarmEvent#ALARM_CONSUMED}
     */
    private static long reconcileAlarm(Context context, CalendarProfile profile, CalendarAlarmEvent event,
                                       long alarmId, long ringTime, boolean shouldBeArmed,
                                       boolean rearmConsumed, boolean stopRinging) {
        final ContentResolver cr = context.getContentResolver();
        Alarm alarm = alarmId >= 0 ? Alarm.getAlarm(cr, alarmId) : null;

        if (alarm == null && alarmId != Alarm.INVALID_ID && !rearmConsumed) {
            // The alarm existed and is gone: the user already dismissed it. Do not bring it back,
            // also not after the profile or the event was switched off and on again.
            return CalendarAlarmEvent.ALARM_CONSUMED;
        }

        if (alarm != null && !(stopRinging && !shouldBeArmed) && isRingingOrSnoozed(cr, alarm.id)) {
            // Never interrupt an alarm the user is dealing with right now.
            return alarm.id;
        }

        if (!shouldBeArmed || event.begin <= System.currentTimeMillis()) {
            if (alarm != null) {
                deleteAlarm(context, alarm.id);
            }
            return Alarm.INVALID_ID;
        }

        final Calendar start = Calendar.getInstance();
        start.setTimeInMillis(ringTime);

        if (alarm == null) {
            alarm = new Alarm(start.get(Calendar.YEAR), start.get(Calendar.MONTH),
                start.get(Calendar.DAY_OF_MONTH), start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE));
            applyProfile(context, profile, event, alarm);
            alarm.enabled = true;
            alarm.addAlarm(cr);
            scheduleInstance(context, alarm);
            LogUtils.i("CalendarAlarmSync: created alarm " + alarm.id + " for " + event);
            return alarm.id;
        }

        final Alarm before = new Alarm(alarm);
        alarm.year = start.get(Calendar.YEAR);
        alarm.month = start.get(Calendar.MONTH);
        alarm.day = start.get(Calendar.DAY_OF_MONTH);
        alarm.hour = start.get(Calendar.HOUR_OF_DAY);
        alarm.minutes = start.get(Calendar.MINUTE);
        applyProfile(context, profile, event, alarm);

        final boolean hasInstance = !AlarmInstance.getInstancesByAlarmId(cr, alarm.id).isEmpty();
        if (!alarm.enabled || !hasInstance || hasChanged(before, alarm)) {
            alarm.enabled = true;
            alarm.updateAlarm(cr);
            AlarmStateManager.deleteAllInstances(context, alarm.id);
            scheduleInstance(context, alarm);
            LogUtils.i("CalendarAlarmSync: updated alarm " + alarm.id + " for " + event);
        }
        return alarm.id;
    }

    /**
     * Alarms ring on the minute. An event that starts at, say, 10:00:45 rings at 10:00, unless
     * that minute has already begun, then it rings at 10:01 instead of never.
     */
    static long alarmTimeFor(long begin, long now) {
        final long minute = TimeUnit.MINUTES.toMillis(1);
        final long floor = begin - Math.floorMod(begin, minute);
        return floor > now ? floor : floor + minute;
    }

    /**
     * @return when the alarm for an event starting at {@code begin} rings: at the start, or the
     * profile's offset before it. If that time has already passed while the event is still to
     * come, for example after turning on the offset, the alarm rings at the next minute.
     */
    public static long ringTimeFor(CalendarProfile profile, long begin, long now) {
        return alarmTimeFor(Math.max(begin - profile.getOffsetMillis(), now), now);
    }

    /**
     * @return the volume ramp in seconds for an escalating alarm, 0 without escalation. The ramp
     * is {@link #ESCALATION_SECONDS} long, shorter if the maximum ring time would cut it off, so
     * the alarm still reaches full strength before it stops.
     */
    static int escalationSecondsFor(CalendarProfile profile) {
        if (!profile.escalation) {
            return 0;
        }
        if (profile.autoSilenceDuration > 0) {
            return Math.max(MIN_ESCALATION_SECONDS,
                Math.min(ESCALATION_SECONDS, profile.autoSilenceDuration / 2));
        }
        return ESCALATION_SECONDS;
    }

    private static void applyProfile(Context context, CalendarProfile profile, CalendarAlarmEvent event, Alarm alarm) {
        alarm.label = event.title;
        alarm.daysOfWeek = Weekdays.NONE;
        if (profile.flashOnly) {
            alarm.alert = RingtoneUtils.RINGTONE_SILENT;
            alarm.vibrate = false;
            alarm.flash = true;
        } else {
            alarm.alert = profile.vibrateOnly
                ? RingtoneUtils.RINGTONE_SILENT
                : resolveRingtone(context, profile.ringtone);
            alarm.vibrate = profile.vibrate || profile.vibrateOnly;
            alarm.flash = profile.flash;
        }
        alarm.autoSilenceDuration = profile.autoSilenceDuration;
        alarm.snoozeDuration = profile.snoozeDuration;
        alarm.alarmVolume = profile.alarmVolume;
        alarm.crescendoDuration = escalationSecondsFor(profile);
        alarm.deleteAfterUse = true;
        alarm.syncByLabel = false;
        alarm.snoozeExtendEnabled = false;
        alarm.repeatIntervalMinutes = 0;
        alarm.repeatMaxCount = 0;
        alarm.wifiRuleEnabled = false;
        // When the max alarm duration runs out, the alarm stops and counts as missed instead of
        // snoozing and ringing again forever.
        alarm.missedAlarmRepeatLimit = 0;
    }

    private static Uri resolveRingtone(Context context, @Nullable Uri ringtone) {
        return ringtone != null ? ringtone : DataModel.readAlarmRingtoneUriFromSettings(context);
    }

    private static boolean hasChanged(Alarm a, Alarm b) {
        return a.year != b.year || a.month != b.month || a.day != b.day
            || a.hour != b.hour || a.minutes != b.minutes
            || !Objects.equals(a.label, b.label)
            || !Objects.equals(a.alert, b.alert)
            || a.vibrate != b.vibrate
            || a.flash != b.flash
            || a.autoSilenceDuration != b.autoSilenceDuration
            || a.snoozeDuration != b.snoozeDuration
            || a.alarmVolume != b.alarmVolume
            || a.crescendoDuration != b.crescendoDuration
            || a.missedAlarmRepeatLimit != b.missedAlarmRepeatLimit
            || a.deleteAfterUse != b.deleteAfterUse;
    }

    private static void scheduleInstance(Context context, Alarm alarm) {
        final AlarmInstance instance = alarm.createInstanceAfter(Calendar.getInstance());
        instance.addInstance(context.getContentResolver());
        AlarmStateManager.registerInstance(context, instance, true);
    }

    private static void removeEvent(Context context, CalendarAlarmEvent event, boolean force) {
        final ContentResolver cr = context.getContentResolver();
        final long[] alarmIds = {event.alarmId, event.startAlarmId};
        for (long alarmId : alarmIds) {
            if (alarmExists(cr, alarmId) && isRingingOrSnoozed(cr, alarmId)) {
                // Leave a ringing or snoozed alarm alone. It deletes itself once dismissed.
                if (force) {
                    event.delete(cr);
                }
                return;
            }
        }
        for (long alarmId : alarmIds) {
            if (alarmExists(cr, alarmId)) {
                deleteAlarm(context, alarmId);
            }
        }
        event.delete(cr);
    }

    private static void deleteAlarm(Context context, long alarmId) {
        AlarmStateManager.deleteAllInstances(context, alarmId);
        Alarm.deleteAlarm(context.getContentResolver(), alarmId);
    }

    private static boolean alarmExists(ContentResolver cr, long alarmId) {
        return alarmId >= 0 && Alarm.getAlarm(cr, alarmId) != null;
    }

    private static boolean isRingingOrSnoozed(ContentResolver cr, long alarmId) {
        for (AlarmInstance instance : AlarmInstance.getInstancesByAlarmId(cr, alarmId)) {
            if (instance.mAlarmState == AlarmInstance.FIRED_STATE
                || instance.mAlarmState == AlarmInstance.SNOOZE_STATE) {
                return true;
            }
        }
        return false;
    }
}

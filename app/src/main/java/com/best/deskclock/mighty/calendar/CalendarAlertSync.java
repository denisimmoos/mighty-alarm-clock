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
import com.best.deskclock.provider.CalendarAlertEvent;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Turns matching calendar events into alarms.
 *
 * <p>For every profile, the events of its calendar in the next {@link CalendarProfile#lookaheadDays} days are
 * matched against the profile's title regex. Each match gets a row in the calendar alert events
 * table and, while the profile and the event are switched on, a one-time alarm at the event's
 * start time. The alarm goes through the app's normal alarm pipeline, so the fullscreen alarm,
 * snooze and auto silence all work as for any other alarm.</p>
 *
 * <p>Alarms that are ringing or snoozed are never changed or removed by a sync.</p>
 */
public final class CalendarAlertSync {

    /** How long rows of past events are kept, so a missed alarm notification stays visible. */
    private static final long PAST_EVENT_RETENTION_MS = TimeUnit.HOURS.toMillis(12);

    private static final Object LOCK = new Object();

    private CalendarAlertSync() {
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
                LogUtils.e("CalendarAlertSync: sync failed", e);
            }
        }
    }

    private static void syncAllLocked(Context appContext) {
        final ContentResolver cr = appContext.getContentResolver();
        final long now = System.currentTimeMillis();
        final boolean canReadCalendar = DeviceCalendars.hasPermission(appContext);

        final List<CalendarProfile> profiles = CalendarProfile.getProfiles(cr);
        final Set<Long> profileIds = new HashSet<>();
        for (CalendarProfile profile : profiles) {
            profileIds.add(profile.id);
            try {
                syncProfile(appContext, profile, now, canReadCalendar);
            } catch (Throwable e) {
                LogUtils.e("CalendarAlertSync: failed to sync profile " + profile.id, e);
            }
        }

        // Clean up rows left behind by deleted profiles.
        for (CalendarAlertEvent event : CalendarAlertEvent.getAllEvents(cr)) {
            if (!profileIds.contains(event.profileId)) {
                removeEvent(appContext, event, true);
            }
        }
    }

    /**
     * Brings the calendar alarms of one profile up to date. Must not run on the main thread.
     */
    public static void syncProfile(Context context, long profileId) {
        synchronized (LOCK) {
            try {
                final Context appContext = context.getApplicationContext();
                final CalendarProfile profile = CalendarProfile.getProfile(appContext.getContentResolver(), profileId);
                if (profile != null) {
                    syncProfile(appContext, profile, System.currentTimeMillis(),
                        DeviceCalendars.hasPermission(appContext));
                }
            } catch (Throwable e) {
                LogUtils.e("CalendarAlertSync: failed to sync profile " + profileId, e);
            }
        }
    }

    /**
     * Deletes a profile together with its alarms. Must not run on the main thread.
     */
    public static void deleteProfile(Context context, long profileId) {
        synchronized (LOCK) {
            try {
                final Context appContext = context.getApplicationContext();
                final ContentResolver cr = appContext.getContentResolver();
                for (CalendarAlertEvent event : CalendarAlertEvent.getEventsForProfile(cr, profileId)) {
                    removeEvent(appContext, event, true);
                }
                CalendarProfile.deleteProfile(cr, profileId);
            } catch (Throwable e) {
                LogUtils.e("CalendarAlertSync: failed to delete profile " + profileId, e);
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
                final CalendarAlertEvent event = CalendarAlertEvent.getEvent(cr, eventRowId);
                if (event == null) {
                    return;
                }
                final CalendarProfile profile = CalendarProfile.getProfile(cr, event.profileId);
                if (profile == null) {
                    return;
                }
                event.userEnabled = enabled;
                event.alarmId = reconcileAlarm(appContext, profile, event, profile.enabled && enabled, true);
                event.save(cr);
            } catch (Throwable e) {
                LogUtils.e("CalendarAlertSync: failed to switch event " + eventRowId, e);
            }
        }
    }

    private static void syncProfile(Context context, CalendarProfile profile, long now, boolean canReadCalendar) {
        final ContentResolver cr = context.getContentResolver();
        final List<CalendarAlertEvent> existing = CalendarAlertEvent.getEventsForProfile(cr, profile.id);

        if (!profile.enabled) {
            silenceProfile(context, existing, now);
        }

        if (!canReadCalendar) {
            // Without calendar access, nothing can be checked. Keep what is already scheduled.
            LogUtils.w("CalendarAlertSync: no calendar permission, profile " + profile.id + " not synced");
            return;
        }

        final Map<String, CalendarAlertEvent> existingByKey = new HashMap<>();
        for (CalendarAlertEvent event : existing) {
            existingByKey.put(event.eventKey, event);
        }

        final Pattern pattern = profile.compileTitlePattern();
        final long until = now + TimeUnit.DAYS.toMillis(CalendarProfile.clampLookaheadDays(profile.lookaheadDays));
        final List<DeviceCalendars.EventInstance> instances =
            DeviceCalendars.getEventInstances(context, profile.calendarId, now, until);

        final Set<String> matchedKeys = new HashSet<>();
        final List<DeviceCalendars.EventInstance> matched = new java.util.ArrayList<>();
        for (DeviceCalendars.EventInstance instance : instances) {
            if (CalendarProfile.titleMatches(pattern, instance.title)) {
                matchedKeys.add(CalendarAlertEvent.buildKey(instance.eventId, instance.begin));
                matched.add(instance);
            }
        }

        // Remove stale rows first, so one failing event can never leave an old alert behind.
        for (CalendarAlertEvent event : existing) {
            if (matchedKeys.contains(event.eventKey)) {
                continue;
            }
            try {
                if (event.begin > now) {
                    // The event was deleted, moved, renamed so it no longer matches, or the profile
                    // now points to another calendar or regex.
                    removeEvent(context, event, false);
                } else if (event.begin < now - PAST_EVENT_RETENTION_MS || !alarmExists(cr, event.alarmId)) {
                    removeEvent(context, event, false);
                }
            } catch (Throwable e) {
                LogUtils.e("CalendarAlertSync: failed to remove " + event, e);
            }
        }

        for (DeviceCalendars.EventInstance instance : matched) {
            try {
                final String key = CalendarAlertEvent.buildKey(instance.eventId, instance.begin);
                CalendarAlertEvent event = existingByKey.get(key);
                if (event == null) {
                    event = new CalendarAlertEvent(profile.id, instance.eventId, instance.begin, instance.title);
                } else {
                    event.title = instance.title;
                }

                event.alarmId = reconcileAlarm(context, profile, event, profile.enabled && event.userEnabled);
                event.save(cr);
            } catch (Throwable e) {
                LogUtils.e("CalendarAlertSync: failed to update event " + instance.eventId, e);
            }
        }
    }

    /**
     * A switched off profile has no alarms at all: this also stops one that is ringing or snoozed
     * right now (sound, vibration and flashlight). Future events keep their row, so their alarms
     * come back when the profile is switched on again.
     */
    private static void silenceProfile(Context context, List<CalendarAlertEvent> existing, long now) {
        final ContentResolver cr = context.getContentResolver();
        for (CalendarAlertEvent event : existing) {
            try {
                if (!alarmExists(cr, event.alarmId)) {
                    // Nothing scheduled. A dismissed event stays dismissed.
                    continue;
                }
                deleteAlarm(context, event.alarmId);
                LogUtils.i("CalendarAlertSync: profile off, removed alarm " + event.alarmId);
                if (event.begin <= now) {
                    event.delete(cr);
                } else {
                    event.alarmId = Alarm.INVALID_ID;
                    event.save(cr);
                }
            } catch (Throwable e) {
                LogUtils.e("CalendarAlertSync: failed to silence " + event, e);
            }
        }
    }

    /**
     * Makes the alarm of the event match the profile.
     *
     * @return the id of the event's alarm afterwards, or {@link Alarm#INVALID_ID} if it has none
     */
    private static long reconcileAlarm(Context context, CalendarProfile profile, CalendarAlertEvent event,
                                       boolean shouldBeArmed) {
        return reconcileAlarm(context, profile, event, shouldBeArmed, false);
    }

    /**
     * @param rearmConsumed {@code true} to create a new alarm even if the event's alarm was already
     *                      used up (dismissed early from the notification, or rang and was dismissed)
     */
    private static long reconcileAlarm(Context context, CalendarProfile profile, CalendarAlertEvent event,
                                       boolean shouldBeArmed, boolean rearmConsumed) {
        final ContentResolver cr = context.getContentResolver();
        Alarm alarm = event.alarmId != Alarm.INVALID_ID ? Alarm.getAlarm(cr, event.alarmId) : null;

        if (alarm == null && event.alarmId != Alarm.INVALID_ID && !rearmConsumed) {
            // The alarm existed and is gone: the user already dismissed it. Do not bring it back.
            return shouldBeArmed ? CalendarAlertEvent.ALARM_CONSUMED : Alarm.INVALID_ID;
        }

        if (alarm != null && isRingingOrSnoozed(cr, alarm.id)) {
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
        start.setTimeInMillis(event.begin);

        if (alarm == null) {
            alarm = new Alarm(start.get(Calendar.YEAR), start.get(Calendar.MONTH),
                start.get(Calendar.DAY_OF_MONTH), start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE));
            applyProfile(context, profile, event, alarm);
            alarm.enabled = true;
            alarm.addAlarm(cr);
            scheduleInstance(context, alarm);
            LogUtils.i("CalendarAlertSync: created alarm " + alarm.id + " for " + event);
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
            LogUtils.i("CalendarAlertSync: updated alarm " + alarm.id + " for " + event);
        }
        return alarm.id;
    }

    private static void applyProfile(Context context, CalendarProfile profile, CalendarAlertEvent event, Alarm alarm) {
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
        alarm.deleteAfterUse = true;
        alarm.syncByLabel = false;
        alarm.snoozeExtendEnabled = false;
        alarm.repeatIntervalMinutes = 0;
        alarm.repeatMaxCount = 0;
        alarm.wifiRuleEnabled = false;
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
            || a.deleteAfterUse != b.deleteAfterUse;
    }

    private static void scheduleInstance(Context context, Alarm alarm) {
        final AlarmInstance instance = alarm.createInstanceAfter(Calendar.getInstance());
        instance.addInstance(context.getContentResolver());
        AlarmStateManager.registerInstance(context, instance, true);
    }

    private static void removeEvent(Context context, CalendarAlertEvent event, boolean force) {
        final ContentResolver cr = context.getContentResolver();
        if (event.alarmId != Alarm.INVALID_ID && alarmExists(cr, event.alarmId)) {
            if (isRingingOrSnoozed(cr, event.alarmId)) {
                // Leave a ringing or snoozed alarm alone. It deletes itself once dismissed.
                if (force) {
                    event.delete(cr);
                }
                return;
            }
            deleteAlarm(context, event.alarmId);
        }
        event.delete(cr);
    }

    private static void deleteAlarm(Context context, long alarmId) {
        AlarmStateManager.deleteAllInstances(context, alarmId);
        Alarm.deleteAlarm(context.getContentResolver(), alarmId);
    }

    private static boolean alarmExists(ContentResolver cr, long alarmId) {
        return alarmId != Alarm.INVALID_ID && Alarm.getAlarm(cr, alarmId) != null;
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

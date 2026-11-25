// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.content.ContentResolver;

import androidx.annotation.Nullable;

import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.CalendarAlertEvent;
import com.best.deskclock.provider.CalendarProfile;

/**
 * Profile rules that apply while a calendar alarm rings: the snooze limit and the dismiss method.
 */
public final class CalendarAlertRules {

    private CalendarAlertRules() {
    }

    /**
     * @return the profile that created the alarm, or {@code null} for an alarm the user created.
     */
    @Nullable
    public static CalendarProfile getProfileForAlarm(ContentResolver cr, @Nullable Long alarmId) {
        if (alarmId == null) {
            return null;
        }
        final CalendarAlertEvent event = CalendarAlertEvent.getEventForAlarm(cr, alarmId);
        return event == null ? null : CalendarProfile.getProfile(cr, event.profileId);
    }

    /**
     * @return {@code true} if the instance was already snoozed as often as its profile allows.
     */
    public static boolean isSnoozeLimitReached(@Nullable CalendarProfile profile, AlarmInstance instance) {
        return profile != null && profile.maxSnoozeCount > 0 && instance.mSnoozeCount >= profile.maxSnoozeCount;
    }

    /**
     * @return {@code true} if the instance belongs to a calendar alarm that may not be snoozed again.
     */
    public static boolean isSnoozeLimitReached(ContentResolver cr, AlarmInstance instance) {
        return isSnoozeLimitReached(getProfileForAlarm(cr, instance.mAlarmId), instance);
    }
}

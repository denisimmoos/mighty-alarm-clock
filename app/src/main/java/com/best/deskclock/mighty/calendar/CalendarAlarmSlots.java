// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.content.ContentResolver;
import android.content.Context;

import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.CalendarAlarmEvent;
import com.best.deskclock.utils.LogUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the alarms of one calendar event in order: the early alarm, the one at the start and the
 * one at the end. A snoozed alarm must not ring after a later alarm of the same event took over,
 * or after the event is over.
 */
public final class CalendarAlarmSlots {

    private CalendarAlarmSlots() {
    }

    /**
     * @return {@code true} if the instance is a snoozed early or start alarm whose event has
     * already ended. Ringing then would be for an event that is over.
     */
    public static boolean isOverAfterSnooze(Context context, AlarmInstance instance, long now) {
        if (instance.mAlarmId == null || instance.mAlarmState != AlarmInstance.SNOOZE_STATE) {
            return false;
        }
        try {
            final CalendarAlarmEvent event = CalendarAlarmEvent.getEventForAlarm(
                context.getContentResolver(), instance.mAlarmId);
            if (event == null || event.endAlarmId == instance.mAlarmId) {
                return false;
            }
            return event.end > event.begin && now >= event.end;
        } catch (Exception e) {
            LogUtils.e("CalendarAlarmSlots: could not read the event of alarm " + instance.mAlarmId, e);
            return false;
        }
    }

    /**
     * @return the snoozed instances of the event's earlier alarms, which the alarm of the given
     * instance replaces: the start alarm replaces a snoozed early alarm, the end alarm both.
     */
    public static List<AlarmInstance> getReplacedSnoozes(Context context, AlarmInstance instance) {
        final List<AlarmInstance> replaced = new ArrayList<>();
        if (instance.mAlarmId == null) {
            return replaced;
        }
        try {
            final ContentResolver cr = context.getContentResolver();
            final CalendarAlarmEvent event = CalendarAlarmEvent.getEventForAlarm(cr, instance.mAlarmId);
            if (event == null) {
                return replaced;
            }
            final long[] earlier;
            if (event.endAlarmId == instance.mAlarmId) {
                earlier = new long[]{event.alarmId, event.startAlarmId};
            } else if (event.startAlarmId == instance.mAlarmId) {
                earlier = new long[]{event.alarmId};
            } else {
                return replaced;
            }
            for (long alarmId : earlier) {
                if (alarmId < 0 || alarmId == instance.mAlarmId) {
                    continue;
                }
                for (AlarmInstance other : AlarmInstance.getInstancesByAlarmId(cr, alarmId)) {
                    if (other.mAlarmState == AlarmInstance.SNOOZE_STATE) {
                        replaced.add(other);
                    }
                }
            }
        } catch (Exception e) {
            LogUtils.e("CalendarAlarmSlots: could not read the alarms of alarm " + instance.mAlarmId, e);
        }
        return replaced;
    }
}

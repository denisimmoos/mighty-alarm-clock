// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.annotation.Nullable;

import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.SdkUtils;

/**
 * Vibration strength of calendar alarms. The strength is a percentage that maps to the vibration
 * amplitude; phones without amplitude control always vibrate at their one fixed strength.
 */
public final class CalendarVibration {

    private static final long PREVIEW_MS = 250;

    private CalendarVibration() {
    }

    /**
     * @return {@code true} if the phone can vibrate at different strengths.
     */
    public static boolean hasAmplitudeControl(Context context) {
        final Vibrator vibrator = context.getSystemService(Vibrator.class);
        return vibrator != null && SdkUtils.isAtLeastAndroid8() && vibrator.hasAmplitudeControl();
    }

    /**
     * @return the amplitude (1 to 255) for a strength in percent.
     */
    public static int toAmplitude(int percent) {
        final int clamped = CalendarProfile.clampVibrationStrength(percent);
        return Math.max(1, Math.min(255, Math.round(255f * clamped / 100f)));
    }

    /**
     * @return the vibration strength of the calendar profile that created the alarm, or
     * {@code null} for an alarm the user created.
     */
    @Nullable
    public static Integer getStrengthForAlarm(ContentResolver cr, @Nullable Long alarmId) {
        try {
            final CalendarProfile profile = CalendarAlarmRules.getProfileForAlarm(cr, alarmId);
            return profile == null ? null : profile.vibrationStrength;
        } catch (Exception e) {
            LogUtils.e("CalendarVibration: could not read the profile of alarm " + alarmId, e);
            return null;
        }
    }

    /**
     * @return a repeating waveform of the pattern at the given strength, or {@code null} if the
     * phone cannot change the strength.
     */
    @Nullable
    @SuppressLint("NewApi")
    public static VibrationEffect createWaveform(Context context, long[] pattern, int percent) {
        if (!hasAmplitudeControl(context)) {
            return null;
        }
        final int amplitude = toAmplitude(percent);
        final int[] amplitudes = new int[pattern.length];
        for (int i = 0; i < pattern.length; i++) {
            // Patterns start with a pause, then alternate between vibrating and pausing.
            amplitudes[i] = i % 2 == 0 ? 0 : amplitude;
        }
        return VibrationEffect.createWaveform(pattern, amplitudes, 0);
    }

    /**
     * Vibrates briefly at the given strength so the user can feel it.
     */
    @SuppressLint("NewApi")
    public static void preview(Context context, int percent) {
        final Vibrator vibrator = context.getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        vibrator.cancel();
        if (SdkUtils.isAtLeastAndroid8()) {
            final int amplitude = vibrator.hasAmplitudeControl()
                ? toAmplitude(percent) : VibrationEffect.DEFAULT_AMPLITUDE;
            vibrator.vibrate(VibrationEffect.createOneShot(PREVIEW_MS, amplitude));
        } else {
            vibrator.vibrate(PREVIEW_MS);
        }
    }
}

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
        final CalendarProfile profile = getProfileForAlarm(cr, alarmId);
        return profile == null ? null : profile.vibrationStrength;
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
     * @return the calendar profile that created the alarm, or {@code null} for an alarm the
     * user created.
     */
    @Nullable
    public static CalendarProfile getProfileForAlarm(ContentResolver cr, @Nullable Long alarmId) {
        try {
            return CalendarAlarmRules.getProfileForAlarm(cr, alarmId);
        } catch (Exception e) {
            LogUtils.e("CalendarVibration: could not read the profile of alarm " + alarmId, e);
            return null;
        }
    }

    /**
     * Steps of an escalating vibration while the ringtone plays: nothing for the first third of
     * the ramp, half strength for the second third, then full strength.
     */
    static final float[] ESCALATION_STEPS_WITH_SOUND = {0.5f, 1f};

    /**
     * Steps of an escalating vibration without sound, where the vibration is the only signal:
     * a third, two thirds, then full strength, each for a third of the ramp.
     */
    static final float[] ESCALATION_STEPS_SILENT = {0.33f, 0.66f, 1f};

    /**
     * How an escalating calendar alarm vibrates.
     */
    public static final class Escalation {
        /** Time before the vibration starts, in milliseconds. */
        public final long delayMs;
        /** Strength steps as fractions of the profile's strength; the last one repeats. */
        public final float[] steps;
        /** Length of each step except the last, in milliseconds. */
        public final long stepMs;

        Escalation(long delayMs, float[] steps, long stepMs) {
            this.delayMs = delayMs;
            this.steps = steps;
            this.stepMs = stepMs;
        }
    }

    /**
     * @param rampMs            length of the volume ramp in milliseconds
     * @param silent            {@code true} if no ringtone plays
     * @param amplitudeControl  {@code true} if the phone can vibrate at different strengths
     * @param baseDelayMs       the vibration delay from the app's settings
     * @return how the vibration escalates. Phones that cannot change the strength vibrate
     * normally, starting later while the ringtone gets louder.
     */
    public static Escalation planEscalation(long rampMs, boolean silent, boolean amplitudeControl,
                                            long baseDelayMs) {
        final long third = Math.max(1, rampMs / 3);
        if (silent) {
            return amplitudeControl
                ? new Escalation(0, ESCALATION_STEPS_SILENT, third)
                : new Escalation(0, new float[]{1f}, third);
        }
        return amplitudeControl
            ? new Escalation(Math.max(baseDelayMs, third), ESCALATION_STEPS_WITH_SOUND, third)
            : new Escalation(Math.max(baseDelayMs, 2 * third), new float[]{1f}, third);
    }

    /**
     * Timings, amplitudes and repeat index of an escalating waveform.
     */
    static final class Waveform {
        final long[] timings;
        final int[] amplitudes;
        final int repeat;

        Waveform(long[] timings, int[] amplitudes, int repeat) {
            this.timings = timings;
            this.amplitudes = amplitudes;
            this.repeat = repeat;
        }
    }

    /**
     * Builds the pattern at rising strength: each step but the last repeats the pattern for about
     * {@code stepMs}, and the last step, at full strength, repeats until the alarm stops.
     */
    static Waveform buildEscalatingWaveform(long[] pattern, int percent, float[] steps, long stepMs) {
        long cycleMs = 0;
        for (long t : pattern) {
            cycleMs += Math.max(0, t);
        }
        final int[] repeats = new int[steps.length];
        int total = 0;
        for (int s = 0; s < steps.length; s++) {
            final boolean last = s == steps.length - 1;
            repeats[s] = last || cycleMs <= 0 ? 1 : (int) Math.max(1, (stepMs + cycleMs - 1) / cycleMs);
            total += repeats[s] * pattern.length;
        }
        final long[] timings = new long[total];
        final int[] amplitudes = new int[total];
        final int full = toAmplitude(percent);
        int i = 0;
        int repeat = 0;
        for (int s = 0; s < steps.length; s++) {
            if (s == steps.length - 1) {
                repeat = i;
            }
            final int amplitude = Math.max(1, Math.min(255, Math.round(full * steps[s])));
            for (int r = 0; r < repeats[s]; r++) {
                for (int p = 0; p < pattern.length; p++) {
                    timings[i] = pattern[p];
                    // Patterns start with a pause, then alternate between vibrating and pausing.
                    amplitudes[i] = p % 2 == 0 ? 0 : amplitude;
                    i++;
                }
            }
        }
        return new Waveform(timings, amplitudes, repeat);
    }

    /**
     * @return a waveform that starts softly and gets stronger, or {@code null} if the phone
     * cannot change the strength or there is only one step.
     */
    @Nullable
    @SuppressLint("NewApi")
    public static VibrationEffect createEscalatingWaveform(Context context, long[] pattern, int percent,
                                                           Escalation escalation) {
        if (!hasAmplitudeControl(context)) {
            return null;
        }
        if (escalation.steps.length <= 1) {
            return createWaveform(context, pattern, percent);
        }
        final Waveform w = buildEscalatingWaveform(pattern, percent, escalation.steps, escalation.stepMs);
        return VibrationEffect.createWaveform(w.timings, w.amplitudes, w.repeat);
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

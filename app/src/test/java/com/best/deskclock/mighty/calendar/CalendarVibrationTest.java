package com.best.deskclock.mighty.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.os.Vibrator;

import androidx.test.core.app.ApplicationProvider;

import com.best.deskclock.provider.CalendarProfile;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CalendarVibrationTest {

    @Test
    public void strengthMapsToAmplitude() {
        assertEquals(255, CalendarVibration.toAmplitude(100));
        assertEquals(128, CalendarVibration.toAmplitude(50));
        assertEquals(26, CalendarVibration.toAmplitude(10));
        // Out of range values are clamped.
        assertEquals(26, CalendarVibration.toAmplitude(0));
        assertEquals(255, CalendarVibration.toAmplitude(400));
    }

    @Test
    public void strengthIsSavedPerProfile() {
        final Context context = ApplicationProvider.getApplicationContext();
        final CalendarProfile profile = new CalendarProfile();
        assertEquals(CalendarProfile.DEFAULT_VIBRATION_STRENGTH, profile.vibrationStrength);
        profile.vibrationStrength = 40;
        profile.addProfile(context.getContentResolver());
        assertEquals(40, CalendarProfile.getProfile(context.getContentResolver(), profile.id).vibrationStrength);
    }

    @Test
    public void waveformOnlyWhenThePhoneCanChangeStrength() {
        final Context context = ApplicationProvider.getApplicationContext();
        final Vibrator vibrator = context.getSystemService(Vibrator.class);
        Shadows.shadowOf(vibrator).setHasAmplitudeControl(false);
        assertNull(CalendarVibration.createWaveform(context, new long[]{500, 500}, 50));
        Shadows.shadowOf(vibrator).setHasAmplitudeControl(true);
        assertNotNull(CalendarVibration.createWaveform(context, new long[]{500, 500}, 50));
        // Preview must not throw either way.
        CalendarVibration.preview(context, 50);
        Shadows.shadowOf(vibrator).setHasAmplitudeControl(false);
        CalendarVibration.preview(context, 50);
    }

    @Test
    public void escalationWithSoundStartsLaterAndSofter() {
        final CalendarVibration.Escalation e = CalendarVibration.planEscalation(60_000, false, true, 300);
        assertEquals(20_000, e.delayMs);
        assertEquals(2, e.steps.length);
        assertEquals(0.5f, e.steps[0], 0.001f);
        assertEquals(1f, e.steps[1], 0.001f);
        // A longer delay from the settings is kept.
        assertEquals(30_000, CalendarVibration.planEscalation(60_000, false, true, 30_000).delayMs);
        // Without strength control, vibration starts once the sound is loud.
        final CalendarVibration.Escalation fixed = CalendarVibration.planEscalation(60_000, false, false, 300);
        assertEquals(40_000, fixed.delayMs);
        assertEquals(1, fixed.steps.length);
    }

    @Test
    public void escalationWithoutSoundVibratesAtOnceInThreeSteps() {
        final CalendarVibration.Escalation e = CalendarVibration.planEscalation(60_000, true, true, 0);
        assertEquals(0, e.delayMs);
        assertEquals(3, e.steps.length);
        assertEquals(20_000, e.stepMs);
        // Without strength control, the vibration is the only signal and starts right away.
        assertEquals(0, CalendarVibration.planEscalation(60_000, true, false, 0).delayMs);
    }

    @Test
    public void escalatingWaveformRisesAndRepeatsAtFullStrength() {
        final long[] pattern = {500, 500};
        final CalendarVibration.Waveform w = CalendarVibration.buildEscalatingWaveform(
            pattern, 100, new float[]{0.33f, 0.66f, 1f}, 2_000);
        // 2 seconds per step is 2 cycles of 1 second, then 1 repeating cycle at full strength.
        assertEquals(10, w.timings.length);
        assertEquals(8, w.repeat);
        assertEquals(0, w.amplitudes[0]);
        assertEquals(Math.round(255 * 0.33f), w.amplitudes[1]);
        assertEquals(Math.round(255 * 0.66f), w.amplitudes[5]);
        assertEquals(255, w.amplitudes[9]);
        assertEquals(0, w.amplitudes[8]);

        // The profile's strength is the top.
        final CalendarVibration.Waveform half = CalendarVibration.buildEscalatingWaveform(
            pattern, 50, new float[]{0.5f, 1f}, 1_000);
        assertEquals(CalendarVibration.toAmplitude(50), half.amplitudes[half.amplitudes.length - 1]);

        final Context context = ApplicationProvider.getApplicationContext();
        Shadows.shadowOf(context.getSystemService(Vibrator.class)).setHasAmplitudeControl(true);
        assertNotNull(CalendarVibration.createEscalatingWaveform(context, pattern, 50,
            CalendarVibration.planEscalation(60_000, true, true, 0)));
        Shadows.shadowOf(context.getSystemService(Vibrator.class)).setHasAmplitudeControl(false);
        assertNull(CalendarVibration.createEscalatingWaveform(context, pattern, 50,
            CalendarVibration.planEscalation(60_000, true, true, 0)));
    }
}

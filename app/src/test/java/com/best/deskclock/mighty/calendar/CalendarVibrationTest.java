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
}

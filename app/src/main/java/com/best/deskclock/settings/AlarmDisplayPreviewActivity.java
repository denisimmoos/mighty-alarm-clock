/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.settings;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static androidx.core.util.TypedValueCompat.dpToPx;
import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;
import static com.best.deskclock.settings.PreferencesDefaultValues.AMOLED_DARK_MODE;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.view.WindowCompat;

import com.best.deskclock.R;
import com.best.deskclock.base.BaseActivity;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.databinding.AlarmActivityBinding;
import com.best.deskclock.utils.AlarmUtils;
import com.best.deskclock.utils.ClockUtils;
import com.best.deskclock.utils.FormattedTextUtils;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.SdkUtils;
import com.best.deskclock.utils.ThemeUtils;

import java.io.File;

public class AlarmDisplayPreviewActivity extends BaseActivity {

    private static final int ALERT_REVEAL_DURATION_MILLIS = 500;
    private static final int ALERT_DISMISS_DELAY_MILLIS = 2500;

    private AlarmActivityBinding mBinding;

    private SharedPreferences mPrefs;
    private Typeface mGeneralBoldTypeface;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private float mAlarmTitleFontSize;
    private int mAlarmTitleColor;

    private Vibrator mVibrator;
    private boolean mAreSnoozedOrDismissedAlarmVibrationsEnabled;
    private boolean mIsFadeTransitionsEnabled;
    private boolean mIsTextShadowDisplayed;
    private int mShadowColor;
    private int mShadowOffset;
    private float mShadowRadius;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mBinding = AlarmActivityBinding.inflate(getLayoutInflater());

        mPrefs = getDefaultSharedPreferences(this);
        mGeneralBoldTypeface = ThemeUtils.boldTypeface(SettingsDAO.getGeneralFont(mPrefs));
        mVibrator = getSystemService(Vibrator.class);
        mAreSnoozedOrDismissedAlarmVibrationsEnabled = SettingsDAO.areSnoozedOrDismissedAlarmVibrationsEnabled(mPrefs);

        // Honor rotation on tablets; fix the orientation on phones.
        if (ThemeUtils.isPortrait()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);
        }

        // To manually manage insets
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(mBinding.getRoot());

        initAlarmBackground();

        mIsFadeTransitionsEnabled = SettingsDAO.isFadeTransitionsEnabled(mPrefs);
        mAlarmTitleFontSize = SettingsDAO.getAlarmTitleFontSize(mPrefs);
        mAlarmTitleColor = SettingsDAO.getAlarmTitleColor(mPrefs);
        mIsTextShadowDisplayed = SettingsDAO.isAlarmTextShadowDisplayed(mPrefs);
        mShadowColor = SettingsDAO.getAlarmShadowColor(mPrefs);
        mShadowOffset = SettingsDAO.getAlarmShadowOffset(mPrefs);
        mShadowRadius = mShadowOffset * 0.5f;

        initAlarmClock();

        initAlarmTitle();

        initCalendarActions();

        initRingtoneTitle();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishActivity();
            }
        });

        AlarmUtils.hideSystemBarsOfTriggeredAlarms(getWindow(), getWindow().getDecorView());
    }

    @Override
    protected void onDestroy() {
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }

        mVibrator = null;

        mGeneralBoldTypeface = null;

        mBinding = null;

        super.onDestroy();
    }

    /**
     * Initializes the background.
     */
    private void initAlarmBackground() {
        final String getDarkMode = SettingsDAO.getDarkMode(mPrefs);
        final boolean isAmoledMode = ThemeUtils.isNight(getResources()) && getDarkMode.equals(AMOLED_DARK_MODE);
        int alarmBackgroundColor = isAmoledMode
            ? SettingsDAO.getAlarmBackgroundAmoledColor(mPrefs)
            : SettingsDAO.getAlarmBackgroundColor(mPrefs);
        final String imagePath = SettingsDAO.getAlarmBackgroundImage(mPrefs);

        // Apply a background image and a blur effect.
        if (imagePath != null) {
            mBinding.alarmBackgroundImage.setVisibility(View.VISIBLE);

            File imageFile = new File(imagePath);
            if (imageFile.exists()) {
                Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
                if (bitmap != null) {
                    mBinding.alarmBackgroundImage.setImageBitmap(bitmap);
                    mBinding.alarmBackgroundImage.setColorFilter(alarmBackgroundColor);

                    if (SdkUtils.isAtLeastAndroid12() && SettingsDAO.isAlarmBlurEffectEnabled(mPrefs)) {
                        float intensity = SettingsDAO.getAlarmBlurIntensity(mPrefs);
                        RenderEffect blur = RenderEffect.createBlurEffect(intensity, intensity, Shader.TileMode.CLAMP);
                        mBinding.alarmBackgroundImage.setRenderEffect(blur);
                    }
                } else {
                    LogUtils.e("Bitmap null for path: " + imagePath);
                    getWindow().setBackgroundDrawable(new ColorDrawable(alarmBackgroundColor));
                }
            } else {
                LogUtils.e("Image file not found: " + imagePath);
                getWindow().setBackgroundDrawable(new ColorDrawable(alarmBackgroundColor));
            }
        } else {
            getWindow().setBackgroundDrawable(new ColorDrawable(alarmBackgroundColor));
        }
    }

    /**
     * Initializes the digital or analog clock.
     */
    private void initAlarmClock() {
        final DataModel.ClockStyle alarmClockStyle = SettingsDAO.getAlarmClockStyle(mPrefs);
        final boolean isAlarmSecondHandDisplayed = SettingsDAO.isAlarmSecondHandDisplayed(mPrefs);
        int alarmClockColor = SettingsDAO.getAlarmClockColor(mPrefs);
        float alarmDigitalClockFontSize = SettingsDAO.getAlarmDigitalClockFontSize(mPrefs);

        ClockUtils.setClockStyle(alarmClockStyle, mBinding.digitalClock, mBinding.analogClock);

        if (alarmClockStyle == DataModel.ClockStyle.DIGITAL) {
            ClockUtils.setDigitalClockFont(mBinding.digitalClock, SettingsDAO.getAlarmFont(mPrefs));
            ClockUtils.setDigitalClockTimeFormat(mBinding.digitalClock, 0.4f, false, true, false, false);
            mBinding.digitalClock.applyUserPreferredTextSizeSp(alarmDigitalClockFontSize);
            mBinding.digitalClock.setTextColor(alarmClockColor);

            // Display a shadow if enabled in the settings
            if (mIsTextShadowDisplayed) {
                mBinding.digitalClock.setShadowLayer(mShadowRadius, mShadowOffset, mShadowOffset, mShadowColor);
            }
        } else {
            ClockUtils.adjustAnalogClockSize(mBinding.analogClock, SettingsDAO.getAlarmAnalogClockSize(mPrefs));
            ClockUtils.setAnalogClockSecondsEnabled(alarmClockStyle, mBinding.analogClock, isAlarmSecondHandDisplayed);
        }
    }

    /**
     * Initializes the alarm title.
     */
    private void initAlarmTitle() {
        mBinding.alarmTitle.setText(R.string.app_label);
        mBinding.alarmTitle.setTypeface(mGeneralBoldTypeface);
        mBinding.alarmTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, mAlarmTitleFontSize);
        mBinding.alarmTitle.setTextColor(mAlarmTitleColor);
        // Allow text scrolling (all other attributes are indicated in the "alarm_activity.xml" file)
        mBinding.alarmTitle.setSelected(true);

        // Display a shadow if enabled in the settings
        if (mIsTextShadowDisplayed) {
            mBinding.alarmTitle.setShadowLayer(mShadowRadius, mShadowOffset, mShadowOffset, mShadowColor);
        }
    }

    /**
     * Initializes the ringtone title.
     */
    private void initRingtoneTitle() {
        if (SettingsDAO.isRingtoneTitleDisplayed(mPrefs)) {
            mBinding.ringtoneLayout.setVisibility(VISIBLE);

            displayRingtoneTitle();
        } else {
            mBinding.ringtoneLayout.setVisibility(GONE);
        }
    }

    private String buildTimeString(int totalMinutes) {
        int hour = totalMinutes / 60;
        int minute = totalMinutes % 60;

        if (hour > 0) {
            return FormattedTextUtils.getNumberFormattedQuantityString(this, R.plurals.hours_short, hour);
        } else {
            return FormattedTextUtils.getNumberFormattedQuantityString(this, R.plurals.minutes_short, minute);
        }
    }

    /**
     * Shows the calendar alarm screen as it rings: a big Dismiss bubble and three snooze bubbles
     * with the default snooze lengths of a new profile.
     */
    private void initCalendarActions() {
        final com.google.android.material.button.MaterialButton dismiss = mBinding.calendarDismissButton;
        dismiss.setBackgroundColor(SettingsDAO.getDismissButtonColor(mPrefs, this));
        dismiss.setTypeface(mGeneralBoldTypeface);
        dismiss.setContentDescription(getString(R.string.description_dismiss_button));
        dismiss.setOnClickListener(v -> dismiss());
        mBinding.calendarActions.setVisibility(VISIBLE);

        final int snoozeZoneColor = SettingsDAO.getSnoozeZoneColor(mPrefs);
        final int snoozeTextColor = SettingsDAO.getSnoozeSelectorTextColor(mPrefs);
        final int[] minutes = {
            com.best.deskclock.provider.CalendarProfile.DEFAULT_SNOOZE_MINUTES,
            com.best.deskclock.provider.CalendarProfile.DEFAULT_SNOOZE_MINUTES_2,
            com.best.deskclock.provider.CalendarProfile.DEFAULT_SNOOZE_MINUTES_3
        };
        final android.widget.TextView[] buttons = {
            mBinding.calendarSnoozeChoice1, mBinding.calendarSnoozeChoice2, mBinding.calendarSnoozeChoice3
        };
        for (int i = 0; i < buttons.length; i++) {
            final int length = minutes[i];
            final android.widget.TextView button = buttons[i];
            button.setText(getString(R.string.calendar_alarm_snooze_button, length));
            button.setContentDescription(getResources().getQuantityString(R.plurals.alarm_alert_snooze_set, length, length));
            button.setBackground(ThemeUtils.pillRippleDrawable(this, snoozeZoneColor));
            button.setTypeface(mGeneralBoldTypeface);
            button.setTextColor(snoozeTextColor);
            button.setOnClickListener(v -> snooze(length));
        }
        mBinding.calendarSnoozeLabel.setTypeface(mGeneralBoldTypeface);
        mBinding.calendarSnoozeLabel.setTextColor(SettingsDAO.getSnoozeTitleColor(mPrefs));
        if (mIsTextShadowDisplayed) {
            mBinding.calendarSnoozeLabel.setShadowLayer(mShadowRadius, mShadowOffset, mShadowOffset, mShadowColor);
        }
        mBinding.calendarSnoozeChoices.setVisibility(VISIBLE);
    }

    /**
     * Perform snooze animation.
     */
    private void snooze(int minutes) {
        if (mAreSnoozedOrDismissedAlarmVibrationsEnabled) {
            performDoubleVibration();
        }

        displayAlarmActionMessage(R.string.alarm_alert_snoozed_text, buildTimeString(minutes));
    }

    /**
     * Perform dismiss animation.
     */
    private void dismiss() {
        if (mAreSnoozedOrDismissedAlarmVibrationsEnabled) {
            performSingleVibration();
        }

        displayAlarmActionMessage(R.string.alarm_alert_off_text, null);
    }

    /**
     * Perform single vibration if alarm is dismissed.
     */
    private void performSingleVibration() {
        if (SdkUtils.isAtLeastAndroid8()) {
            mVibrator.vibrate(VibrationEffect.createWaveform(new long[]{700, 500}, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            mVibrator.vibrate(new long[]{700, 500}, -1);
        }
    }

    /**
     * Perform double vibration if alarm is snoozed.
     */
    private void performDoubleVibration() {
        if (SdkUtils.isAtLeastAndroid8()) {
            mVibrator.vibrate(VibrationEffect.createWaveform(new long[]{700, 200, 100, 500}, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            mVibrator.vibrate(new long[]{700, 200, 100, 500}, -1);
        }
    }

    /**
     * Display ringtone title if enabled in <i>"Customize alarm display"</i> settings.
     */
    private void displayRingtoneTitle() {
        final Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        final Ringtone ringtone = RingtoneManager.getRingtone(this, ringtoneUri);
        final Drawable musicIcon = AppCompatResources.getDrawable(this, R.drawable.ic_music_note);
        int iconSize = (int) dpToPx(24, getResources().getDisplayMetrics());
        final int ringtoneTitleColor = SettingsDAO.getRingtoneTitleColor(mPrefs);

        if (musicIcon != null) {
            musicIcon.setTint(ringtoneTitleColor);

            if (mIsTextShadowDisplayed) {
                // Convert the drawable to a bitmap
                Bitmap iconBitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);
                Canvas iconCanvas = new Canvas(iconBitmap);
                musicIcon.setBounds(0, 0, iconSize, iconSize);
                musicIcon.draw(iconCanvas);

                // Create the alpha mask for the shadow
                Bitmap shadowBitmap = iconBitmap.extractAlpha();
                Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                shadowPaint.setColor(mShadowColor);
                shadowPaint.setMaskFilter(new BlurMaskFilter(mShadowRadius * 1.5f, BlurMaskFilter.Blur.NORMAL));

                // Create the final bitmap with space for the shadow
                int finalWidth = iconSize + mShadowOffset;
                int finalHeight = iconSize + mShadowOffset;
                Bitmap finalBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888);
                Canvas finalCanvas = new Canvas(finalBitmap);

                // Draw the blurred shadow with an offset
                finalCanvas.drawBitmap(shadowBitmap, mShadowOffset, mShadowOffset, shadowPaint);

                // Draw the normal icon on top
                finalCanvas.drawBitmap(iconBitmap, 0, 0, null);

                // Apply the result to the ImageView
                mBinding.ringtoneIcon.setImageBitmap(finalBitmap);

                mBinding.ringtoneTitle.setShadowLayer(mShadowRadius, mShadowOffset, mShadowOffset, mShadowColor);
            } else {
                mBinding.ringtoneIcon.setImageDrawable(musicIcon);
            }
        }

        mBinding.ringtoneTitle.setText(ringtone.getTitle(this));
        mBinding.ringtoneTitle.setTypeface(mGeneralBoldTypeface);
        mBinding.ringtoneTitle.setTextColor(ringtoneTitleColor);
        // Allow text scrolling (all other attributes are indicated in the "alarm_activity.xml" file)
        mBinding.ringtoneTitle.setSelected(true);
    }

    /**
     * Display a message after snoozing or dismissing the alarm.
     */
    private void displayAlarmActionMessage(final int titleResId, final String descriptionText) {
        if (SettingsDAO.isAlarmActionMessageHidden(mPrefs)) {
            finishActivity();
            return;
        }

        mBinding.contentView.setVisibility(GONE);

        mBinding.actionMessageView.setVisibility(VISIBLE);

        mBinding.actionTitle.setText(titleResId);
        mBinding.actionTitle.setTypeface(mGeneralBoldTypeface);
        mBinding.actionTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, mAlarmTitleFontSize);
        mBinding.actionTitle.setTextColor(mAlarmTitleColor);

        if (descriptionText != null) {
            mBinding.actionDescription.setVisibility(VISIBLE);
            mBinding.actionDescription.setText(descriptionText);
            mBinding.actionDescription.setTypeface(mGeneralBoldTypeface);
            mBinding.actionDescription.setTextSize(TypedValue.COMPLEX_UNIT_SP, mAlarmTitleFontSize);
            mBinding.actionDescription.setTextColor(mAlarmTitleColor);
        }

        mBinding.actionMessageView.setAlpha(0f);
        mBinding.actionMessageView.animate()
            .alpha(1f)
            .setDuration(ALERT_REVEAL_DURATION_MILLIS)
            .withEndAction(() -> mHandler.postDelayed(this::finishActivity, ALERT_DISMISS_DELAY_MILLIS))
            .start();
    }

    private void finishActivity() {
        finish();
        if (mIsFadeTransitionsEnabled) {
            if (SdkUtils.isAtLeastAndroid14()) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.fade_in, R.anim.fade_out);
            } else {
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
            }
        } else {
            if (SdkUtils.isAtLeastAndroid14()) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.activity_slide_from_left, R.anim.activity_slide_to_right);
            } else {
                overridePendingTransition(R.anim.activity_slide_from_left, R.anim.activity_slide_to_right);
            }
        }
    }

}

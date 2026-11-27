/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.alarms;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static androidx.core.util.TypedValueCompat.dpToPx;
import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;
import static com.best.deskclock.settings.PreferencesDefaultValues.ALARM_SNOOZE_DURATION_DISABLED;
import static com.best.deskclock.settings.PreferencesDefaultValues.AMOLED_DARK_MODE;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
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
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.view.WindowCompat;

import com.best.deskclock.R;
import com.best.deskclock.base.BaseActivity;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.DataModel.PowerButtonBehavior;
import com.best.deskclock.data.DataModel.VolumeButtonBehavior;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.databinding.AlarmActivityBinding;
import com.best.deskclock.events.Events;
import com.best.deskclock.mighty.calendar.CalendarAlarmRules;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.utils.AlarmUtils;
import com.best.deskclock.utils.ClockUtils;
import com.best.deskclock.utils.FormattedTextUtils;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.RingtoneUtils;
import com.best.deskclock.utils.SdkUtils;
import com.best.deskclock.utils.ThemeUtils;
import com.best.deskclock.utils.Utils;

import java.io.File;

public class AlarmActivity extends BaseActivity {

    private static final LogUtils.Logger LOGGER = new LogUtils.Logger("AlarmActivity");

    private static final long POWER_BUTTON_ACTIVATION_DELAY = 1500;
    private static final int ALERT_REVEAL_DURATION_MILLIS = 500;
    private static final int ALERT_DISMISS_DELAY_MILLIS = 2500;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            LOGGER.i("Finished binding to AlarmService");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            LOGGER.i("Disconnected from AlarmService");
        }
    };

    private AlarmActivityBinding mBinding;
    private SharedPreferences mPrefs;
    private Typeface mGeneralBoldTypeface;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Alarm mAlarm;
    private AlarmInstance mAlarmInstance;
    private boolean mAlarmHandled;
    private VolumeButtonBehavior mVolumeBehavior;
    private PowerButtonBehavior mPowerBehavior;
    private long mActivityStartTime;
    private float mAlarmTitleFontSize;
    private int mAlarmTitleColor;
    private boolean mIsTextShadowDisplayed;
    private int mShadowColor;
    private int mShadowOffset;
    private float mShadowRadius;
    private boolean mReceiverRegistered = false;
    private boolean mPowerBtnReceiverRegistered = false;
    private boolean mServiceBound;
    private int mDefaultSnoozeMinutes;


    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            LOGGER.v("Received broadcast: %s", action);

            if (!mAlarmHandled) {
                if (action != null) {
                    switch (action) {
                        case AlarmService.ALARM_SNOOZE_ACTION -> snooze();
                        case AlarmService.ALARM_DISMISS_ACTION -> dismiss();
                        case AlarmService.ALARM_DISABLE_ACTION -> dismiss();
                        case AlarmService.ALARM_DONE_ACTION -> finish();
                        default -> LOGGER.i("Unknown broadcast: %s", action);
                    }
                }
            } else {
                LOGGER.v("Ignored broadcast: %s", action);
            }
        }
    };

    private final BroadcastReceiver PowerBtnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            LOGGER.v("Received broadcast: %s", action);

            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // Ignore immediate screen-off events to prevent the proximity sensor from instantly dismissing
                // the alarm if the device wakes up in a pocket or face down.
                if (SystemClock.elapsedRealtime() - mActivityStartTime < POWER_BUTTON_ACTIVATION_DELAY) {
                    LOGGER.v("Ignored ACTION_SCREEN_OFF due to grace period.");
                    return;
                }

                // Power keys dismiss the alarm.
                if (!mAlarmHandled && !isFinishing()) {
                    if (mPowerBehavior == PowerButtonBehavior.SNOOZE) {
                        snooze();
                    } else if (mPowerBehavior == PowerButtonBehavior.DISMISS) {
                        dismiss();
                    } else if (mPowerBehavior == PowerButtonBehavior.DISABLE) {
                        dismiss();
                    }
                }
            }
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mActivityStartTime = SystemClock.elapsedRealtime();

        mBinding = AlarmActivityBinding.inflate(getLayoutInflater());

        Context storageContext = Utils.getSafeStorageContext(this);

        mPrefs = getDefaultSharedPreferences(storageContext);
        mGeneralBoldTypeface = ThemeUtils.boldTypeface(SettingsDAO.getGeneralFont(mPrefs));

        setVolumeControlStream(AudioManager.STREAM_ALARM);

        initAlarmAndInstance();

        initDefaultSnoozeValue();

        LOGGER.i("Displaying alarm for instance: %s", mAlarmInstance);

        // Get the volume/camera button behavior setting
        mVolumeBehavior = SettingsDAO.getAlarmVolumeButtonBehavior(mPrefs);

        // Get the power button behavior setting
        mPowerBehavior = SettingsDAO.getAlarmPowerButtonBehavior(mPrefs);

        // Register Power button (screen off) intent receiver
        if (mPowerBehavior != PowerButtonBehavior.NOTHING) {
            IntentFilter powerFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if (SdkUtils.isAtLeastAndroid13()) {
                registerReceiver(PowerBtnReceiver, powerFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(PowerBtnReceiver, powerFilter);
            }

            mPowerBtnReceiverRegistered = true;
        }

        // To manually manage insets
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        if (SdkUtils.isAtLeastAndroid81()) {
            setTurnScreenOn(true);
            setShowWhenLocked(true);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);
        }

        // Honor rotation on tablets; fix the orientation on phones.
        if (ThemeUtils.isPortrait()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);
        }

        setContentView(mBinding.getRoot());

        initAlarmBackground();

        applyCalendarProfileRules();
        mAlarmTitleFontSize = SettingsDAO.getAlarmTitleFontSize(mPrefs);
        mAlarmTitleColor = SettingsDAO.getAlarmTitleColor(mPrefs);
        mIsTextShadowDisplayed = SettingsDAO.isAlarmTextShadowDisplayed(mPrefs);
        mShadowColor = SettingsDAO.getAlarmShadowColor(mPrefs);
        mShadowOffset = SettingsDAO.getAlarmShadowOffset(mPrefs);
        mShadowRadius = mShadowOffset * 0.5f;

        initAlarmClock();

        initAlarmTitle();

        loadCalendarEventDetails();

        initCalendarActionsUI();

        // A snooze by button, flip, shake or the notification uses the profile's first length.
        mAlarmInstance.mSnoozeDuration = mDefaultSnoozeMinutes;
        mAlarmInstance.updateInstance(getContentResolver());

        initCalendarSnoozeChoices();

        initRingtoneTitle();

        AlarmUtils.hideSystemBarsOfTriggeredAlarms(getWindow(), getWindow().getDecorView());
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();

        // Re-query for AlarmInstance in case the state has changed externally
        final long instanceId = AlarmInstance.getId(getIntent().getData());
        mAlarmInstance = AlarmInstance.getInstance(getContentResolver(), instanceId);

        if (mAlarmInstance == null) {
            LOGGER.i("No alarm instance for instanceId: %d", instanceId);
            finish();
            return;
        }

        // Verify that the alarm is still firing before showing the activity
        if (mAlarmInstance.mAlarmState != AlarmInstance.FIRED_STATE) {
            LOGGER.i("Skip displaying alarm for instance: %s", mAlarmInstance);
            finish();
            return;
        }

        if (!mReceiverRegistered) {
            // Register to get the alarm done/snooze/dismiss intent.
            final IntentFilter filter = new IntentFilter(AlarmService.ALARM_DONE_ACTION);
            filter.addAction(AlarmService.ALARM_SNOOZE_ACTION);
            filter.addAction(AlarmService.ALARM_DISMISS_ACTION);
            filter.addAction(AlarmService.ALARM_DISABLE_ACTION);
            if (SdkUtils.isAtLeastAndroid13()) {
                registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(mReceiver, filter);
            }
            mReceiverRegistered = true;
        }

        bindAlarmService();
    }

    @Override
    protected void onPause() {
        super.onPause();

        unbindAlarmService();

        // Skip if register didn't happen to avoid IllegalArgumentException
        if (mReceiverRegistered) {
            unregisterReceiver(mReceiver);
            mReceiverRegistered = false;
        }
    }

    @Override
    protected void onDestroy() {
        if (mBinding != null) {
            mBinding.calendarCountdown.removeCallbacks(mCountdownUpdater);
        }
        unregisterUnlockReceiver();

        if (mPowerBtnReceiverRegistered) {
            unregisterReceiver(PowerBtnReceiver);
            mPowerBtnReceiverRegistered = false;
        }

        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }

        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent keyEvent) {
        // Do this in dispatch to intercept a few of the system keys.
        LOGGER.v("dispatchKeyEvent: %s", keyEvent);

        final int keyCode = keyEvent.getKeyCode();
        switch (keyCode) {
            // Volume keys and camera keys dismiss the alarm.
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_FOCUS:
                if (!mAlarmHandled) {
                    switch (mVolumeBehavior) {
                        case DO_NOTHING -> {
                            return keyEvent.getAction() != KeyEvent.ACTION_UP;
                        }
                        case SNOOZE_ALARM -> {
                            if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                                snooze();
                            }
                            return true;
                        }
                        case DISMISS_ALARM -> {
                            if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                                dismiss();
                            }
                            return true;
                        }
                        case DISABLE_ALARM -> {
                            if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                                dismiss();
                            }
                            return true;
                        }
                    }
                }
        }
        return super.dispatchKeyEvent(keyEvent);
    }

    private void initAlarmAndInstance() {
        final long instanceId = AlarmInstance.getId(getIntent().getData());
        mAlarmInstance = AlarmInstance.getInstance(getContentResolver(), instanceId);
        if (mAlarmInstance == null) {
            // The alarm was deleted before the activity got created, so just finish()
            LOGGER.e("Error displaying alarm for intent: %s", getIntent());
            finish();
            return;
        } else if (mAlarmInstance.mAlarmState != AlarmInstance.FIRED_STATE) {
            LOGGER.i("Skip displaying alarm for instance: %s", mAlarmInstance);
            finish();
            return;
        }

        mAlarm = Alarm.getAlarm(getContentResolver(), mAlarmInstance.mAlarmId);
        if (mAlarm == null) {
            LogUtils.wtf("Failed to retrieve alarm with ID: %d", mAlarmInstance.mAlarmId);
        }
    }

    /** The snooze lengths of the calendar profile, shown as buttons; empty if none. */
    private int[] mCalendarSnoozeChoices = new int[0];
    /** The profile keeps the event title, description and place off the lock screen. */
    private boolean mHideCalendarDetailsLocked;

    /**
     * Applies the snooze lengths, snooze limit and lock screen privacy of the calendar profile
     * that created this alarm.
     */
    private void applyCalendarProfileRules() {
        if (mAlarm == null || mAlarmInstance == null) {
            return;
        }

        final CalendarProfile profile = CalendarAlarmRules.getProfileForAlarm(getContentResolver(), mAlarm.id);
        if (profile == null) {
            return;
        }

        mHideCalendarDetailsLocked = !profile.showDetailsLocked;

        // The alarm screen has a big Dismiss bubble and the profile's snooze lengths below it.
        if (CalendarAlarmRules.isSnoozeLimitReached(profile, mAlarmInstance)) {
            mDefaultSnoozeMinutes = ALARM_SNOOZE_DURATION_DISABLED;
            mAlarmInstance.mSnoozeDuration = ALARM_SNOOZE_DURATION_DISABLED;
        } else {
            mCalendarSnoozeChoices = profile.getSnoozeChoices();
        }
    }

    /**
     * Shows the big Dismiss bubble. The snooze bubbles below it are set up by {@link #initCalendarSnoozeChoices()}.
     */
    private void initCalendarActionsUI() {
        final com.google.android.material.button.MaterialButton dismiss = mBinding.calendarDismissButton;
        dismiss.setBackgroundColor(SettingsDAO.getDismissButtonColor(mPrefs, this));
        dismiss.setTypeface(mGeneralBoldTypeface);
        dismiss.setContentDescription(getString(R.string.description_dismiss_button));
        dismiss.setOnClickListener(v -> {
            if (!mAlarmHandled) {
                dismiss();
            }
        });
        mBinding.calendarActions.setVisibility(VISIBLE);
    }

    /**
     * Shows a button for each snooze length of the calendar profile. Tapping one snoozes for that
     * long; if the profile extends the event on snooze, it is extended by the same time.
     */
    private void initCalendarSnoozeChoices() {
        if (mCalendarSnoozeChoices.length != 3) {
            mBinding.calendarSnoozeChoices.setVisibility(GONE);
            return;
        }
        final int snoozeZoneColor = SettingsDAO.getSnoozeZoneColor(mPrefs);
        final int snoozeTextColor = SettingsDAO.getSnoozeSelectorTextColor(mPrefs);
        final TextView[] buttons = {
            mBinding.calendarSnoozeChoice1, mBinding.calendarSnoozeChoice2, mBinding.calendarSnoozeChoice3
        };
        for (int i = 0; i < buttons.length; i++) {
            final int minutes = mCalendarSnoozeChoices[i];
            final TextView button = buttons[i];
            button.setText(getString(R.string.calendar_alarm_snooze_button, minutes));
            button.setContentDescription(getResources().getQuantityString(
                R.plurals.alarm_alert_snooze_set, minutes, minutes));
            button.setBackground(ThemeUtils.pillRippleDrawable(this, snoozeZoneColor));
            button.setTypeface(mGeneralBoldTypeface);
            button.setTextColor(snoozeTextColor);
            button.setOnClickListener(v -> snoozeWithExplicitDuration(minutes));
        }
        mBinding.calendarSnoozeLabel.setTypeface(mGeneralBoldTypeface);
        mBinding.calendarSnoozeLabel.setTextColor(SettingsDAO.getSnoozeTitleColor(mPrefs));
        if (mIsTextShadowDisplayed) {
            mBinding.calendarSnoozeLabel.setShadowLayer(mShadowRadius, mShadowOffset, mShadowOffset, mShadowColor);
        }
        mBinding.calendarSnoozeChoices.setVisibility(VISIBLE);
    }

    /**
     * Shows the description and place of the calendar event under the alarm title. They are read
     * from the calendar when the alarm rings, so later edits of the event are shown too.
     */
    private void loadCalendarEventDetails() {
        final android.content.Context appContext = getApplicationContext();
        final long alarmId = mAlarm.id;
        com.best.deskclock.base.AppExecutors.getDiskIO().execute(() -> {
            final com.best.deskclock.provider.CalendarAlarmEvent event =
                com.best.deskclock.provider.CalendarAlarmEvent.getEventForAlarm(appContext.getContentResolver(), alarmId);
            if (event == null) {
                return;
            }
            final com.best.deskclock.provider.CalendarProfile profile =
                com.best.deskclock.provider.CalendarProfile.getProfile(appContext.getContentResolver(), event.profileId);
            if (event.endAlarmId == alarmId) {
                // The alarm at the end of the event says so, also on the lock screen, and how long
                // a snooze extends the event, if the profile does that.
                final AlarmInstance instance = mAlarmInstance;
                final int snoozeMinutes = instance == null ? 0 : instance.mSnoozeDuration;
                final boolean extends_ = profile != null && profile.extendOnSnooze && snoozeMinutes > 0
                    && instance != null
                    && !com.best.deskclock.mighty.calendar.CalendarAlarmRules.isSnoozeLimitReached(profile, instance)
                    && com.best.deskclock.mighty.calendar.CalendarEventExtender.hasPermission(appContext);
                com.best.deskclock.base.AppExecutors.getMainThread().post(() -> showCalendarEventEnds(extends_));
            } else if (event.end > event.begin && System.currentTimeMillis() >= event.end) {
                // A snoozed early or start alarm ringing after its event ended says when it ended.
                final long end = event.end;
                com.best.deskclock.base.AppExecutors.getMainThread().post(() -> showCalendarEventEnded(end));
            } else if (profile != null && profile.offsetEnabled && event.alarmId == alarmId) {
                // The early alarm says how long until the event. This is not private, so it
                // shows also on the lock screen.
                final long begin = event.begin;
                com.best.deskclock.base.AppExecutors.getMainThread().post(() -> showCalendarCountdown(begin));
            }
            final android.app.KeyguardManager keyguard = appContext.getSystemService(android.app.KeyguardManager.class);
            if (profile != null && !profile.showDetailsLocked && keyguard != null && keyguard.isKeyguardLocked()) {
                // The profile keeps the details private on the lock screen. Show them after unlocking.
                com.best.deskclock.base.AppExecutors.getMainThread().post(this::showCalendarEventDetailsAfterUnlock);
                return;
            }
            final com.best.deskclock.mighty.calendar.DeviceCalendars.EventDetails details =
                com.best.deskclock.mighty.calendar.DeviceCalendars.getEventDetails(appContext, event.eventId);
            if (details == null) {
                return;
            }
            com.best.deskclock.base.AppExecutors.getMainThread().post(() -> showCalendarEventDetails(details));
        });
    }

    private long mCalendarEventBegin;

    /** Keeps "Event in …" up to date while the alarm screen is shown. */
    private final Runnable mCountdownUpdater = this::updateCalendarCountdown;

    /**
     * Shows how long until the event starts, for an alarm that rings before the event.
     */
    private void showCalendarCountdown(long begin) {
        if (isFinishing() || isDestroyed() || mBinding == null) {
            return;
        }
        mCalendarEventBegin = begin;
        if (mIsTextShadowDisplayed) {
            mBinding.calendarCountdown.setShadowLayer(mShadowRadius, mShadowOffset, mShadowOffset, mShadowColor);
        }
        mBinding.calendarCountdown.setTextColor(mAlarmTitleColor);
        updateCalendarCountdown();
    }

    /**
     * Shows that this alarm rings because the event ends.
     */
    private void showCalendarEventEnds(boolean extendsOnSnooze) {
        if (isFinishing() || isDestroyed() || mBinding == null) {
            return;
        }
        if (mIsTextShadowDisplayed) {
            mBinding.calendarCountdown.setShadowLayer(mShadowRadius, mShadowOffset, mShadowOffset, mShadowColor);
        }
        mBinding.calendarCountdown.setTextColor(mAlarmTitleColor);
        final String ends = getString(R.string.calendar_alarm_event_ends);
        mBinding.calendarCountdown.setText(ends);
        // Below the snooze bubbles: the event is extended by the length picked.
        if (extendsOnSnooze && mBinding.calendarSnoozeChoices.getVisibility() == VISIBLE) {
            mBinding.calendarExtendHint.setTextColor(SettingsDAO.getSnoozeTitleColor(mPrefs));
            if (mIsTextShadowDisplayed) {
                mBinding.calendarExtendHint.setShadowLayer(mShadowRadius, mShadowOffset, mShadowOffset, mShadowColor);
            }
            mBinding.calendarExtendHint.setVisibility(VISIBLE);
        }
        mBinding.calendarCountdown.setVisibility(VISIBLE);
        mBinding.calendarInfo.setVisibility(VISIBLE);
    }

    /**
     * Shows when the event ended, for a snoozed alarm that rings after its event.
     */
    private void showCalendarEventEnded(long end) {
        if (isFinishing() || isDestroyed() || mBinding == null) {
            return;
        }
        if (mIsTextShadowDisplayed) {
            mBinding.calendarCountdown.setShadowLayer(mShadowRadius, mShadowOffset, mShadowOffset, mShadowColor);
        }
        mBinding.calendarCountdown.setTextColor(mAlarmTitleColor);
        mBinding.calendarCountdown.setText(getString(R.string.calendar_alarm_event_ended,
            android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(end))));
        mBinding.calendarCountdown.setVisibility(VISIBLE);
        mBinding.calendarInfo.setVisibility(VISIBLE);
    }

    private void updateCalendarCountdown() {
        if (isFinishing() || isDestroyed() || mBinding == null) {
            return;
        }
        final String timeLeft = com.best.deskclock.mighty.calendar.CalendarEventText.formatTimeUntil(
            this, mCalendarEventBegin, System.currentTimeMillis());
        mBinding.calendarCountdown.removeCallbacks(mCountdownUpdater);
        if (timeLeft == null) {
            mBinding.calendarCountdown.setVisibility(GONE);
            return;
        }
        mBinding.calendarCountdown.setText(getString(R.string.calendar_alarm_event_in, timeLeft));
        mBinding.calendarCountdown.setVisibility(VISIBLE);
        mBinding.calendarInfo.setVisibility(VISIBLE);
        mBinding.calendarCountdown.postDelayed(mCountdownUpdater, 15_000);
    }

    private final android.content.BroadcastReceiver mUnlockReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            unregisterUnlockReceiver();
            if (mBinding != null && mAlarmInstance != null) {
                mBinding.alarmTitle.setText(mAlarmInstance.getLabelOrDefault(AlarmActivity.this));
            }
            loadCalendarEventDetails();
        }
    };
    private boolean mUnlockReceiverRegistered;

    private boolean isCalendarTitleHidden() {
        if (!mHideCalendarDetailsLocked) {
            return false;
        }
        final android.app.KeyguardManager keyguard = getSystemService(android.app.KeyguardManager.class);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    private void showCalendarEventDetailsAfterUnlock() {
        if (isFinishing() || isDestroyed() || mUnlockReceiverRegistered) {
            return;
        }
        final IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        if (SdkUtils.isAtLeastAndroid13()) {
            registerReceiver(mUnlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mUnlockReceiver, filter);
        }
        mUnlockReceiverRegistered = true;
    }

    private void unregisterUnlockReceiver() {
        if (mUnlockReceiverRegistered) {
            unregisterReceiver(mUnlockReceiver);
            mUnlockReceiverRegistered = false;
        }
    }

    private void showCalendarEventDetails(com.best.deskclock.mighty.calendar.DeviceCalendars.EventDetails details) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        final boolean hasDescription = !details.description().isEmpty();
        final boolean hasLocation = !details.location().isEmpty();
        if (!hasDescription && !hasLocation) {
            return;
        }

        if (hasDescription) {
            mBinding.calendarDescription.setText(
                com.best.deskclock.mighty.calendar.CalendarEventText.toDisplayText(details.description()));
            mBinding.calendarDescription.setMovementMethod(new android.text.method.ScrollingMovementMethod());
            mBinding.calendarDescription.setTextColor(mAlarmTitleColor);
            mBinding.calendarDescription.setVisibility(VISIBLE);
        }
        if (hasLocation) {
            final android.text.SpannableString place = new android.text.SpannableString(details.location());
            place.setSpan(new android.text.style.UnderlineSpan(), 0, place.length(), 0);
            mBinding.calendarLocation.setText(place);
            mBinding.calendarLocation.setTextColor(mAlarmTitleColor);
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(mBinding.calendarLocation,
                android.content.res.ColorStateList.valueOf(mAlarmTitleColor));
            mBinding.calendarLocation.setContentDescription(
                getString(R.string.calendar_alarm_open_location, details.location()));
            mBinding.calendarLocation.setOnClickListener(v -> openLocation(details.location()));
            mBinding.calendarLocation.setVisibility(VISIBLE);
        }
        if (mIsTextShadowDisplayed) {
            mBinding.calendarDescription.setShadowLayer(mShadowRadius, mShadowOffset, mShadowOffset, mShadowColor);
            mBinding.calendarLocation.setShadowLayer(mShadowRadius, mShadowOffset, mShadowOffset, mShadowColor);
        }
        mBinding.calendarInfo.setVisibility(VISIBLE);
    }

    /**
     * Opens the place in Google Maps, or in another maps app if Google Maps is not installed. On
     * the lock screen the phone is unlocked first. The alarm keeps ringing until it is dismissed.
     */
    private void openLocation(String location) {
        final Runnable open = () -> {
            for (Intent intent : com.best.deskclock.mighty.calendar.CalendarEventText.createMapIntents(location)) {
                try {
                    startActivity(intent);
                    return;
                } catch (android.content.ActivityNotFoundException ignored) {
                    // Try the next way to show the place.
                }
            }
            com.best.deskclock.uicomponents.toast.CustomToast.showLong(this, getString(R.string.calendar_alarm_no_maps_app));
        };

        final android.app.KeyguardManager keyguard = getSystemService(android.app.KeyguardManager.class);
        if (SdkUtils.isAtLeastAndroid8() && keyguard != null && keyguard.isKeyguardLocked()) {
            keyguard.requestDismissKeyguard(this, new android.app.KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    open.run();
                }
            });
        } else {
            if (!SdkUtils.isAtLeastAndroid8() && keyguard != null && keyguard.isKeyguardLocked()) {
                // Ask Android 6 and 7 to drop a non-secure lock screen, or to ask for the PIN,
                // so the map isn't opened behind the lock screen.
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
            }
            open.run();
        }
    }

    private void initDefaultSnoozeValue() {
        mDefaultSnoozeMinutes = mAlarm.snoozeDuration;
    }

    /**
     * Initializes the background.
     */
    private void initAlarmBackground() {
        final String darkMode = SettingsDAO.getDarkMode(mPrefs);
        final boolean isAmoledMode = ThemeUtils.isNight(getResources()) && darkMode.equals(AMOLED_DARK_MODE);
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
        final int alarmClockColor = SettingsDAO.getAlarmClockColor(mPrefs);
        final float alarmDigitalClockFontSize = SettingsDAO.getAlarmDigitalClockFontSize(mPrefs);

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
        if (isCalendarTitleHidden()) {
            // Until the phone is unlocked; see mUnlockReceiver.
            mBinding.alarmTitle.setText(R.string.calendar_alarm_hidden_title);
        } else {
            mBinding.alarmTitle.setText(mAlarmInstance.getLabelOrDefault(this));
        }
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

        if (hour > 0 && minute > 0) {
            String hourString = FormattedTextUtils.getNumberFormattedQuantityString(this, R.plurals.hours_short, hour);
            String minuteString = FormattedTextUtils.getNumberFormattedQuantityString(this, R.plurals.minutes_short, minute);
            return String.format("%s %s", hourString, minuteString);
        } else if (hour > 0) {
            return FormattedTextUtils.getNumberFormattedQuantityString(this, R.plurals.hours_short, hour);
        } else {
            return FormattedTextUtils.getNumberFormattedQuantityString(this, R.plurals.minutes_short, minute);
        }
    }

    /**
     * Perform snooze animation and send dismiss intent if snooze duration has been set to "None";
     * otherwise, send snooze intent.
     */
    private void snooze() {
        mAlarmHandled = true;
        LOGGER.v("Snoozed: %s", mAlarmInstance);

        // If snooze duration has been set to "None", simply dismiss the alarm.
        if (isSnoozeDisabledForAlarmInstance()) {
            displayAlarmActionMessage(R.string.alarm_alert_off_text, null, getString(R.string.alarm_alert_off_text));

            AlarmStateManager.deleteInstanceAndUpdateParent(this, mAlarmInstance, false);

            Events.sendAlarmEvent(R.string.action_dismiss, R.string.label_deskclock);
        } else {
            int snoozeDuration = AlarmStateManager.computeNextSnoozeMinutes(this, mAlarm, mAlarmInstance);
            if (snoozeDuration == ALARM_SNOOZE_DURATION_DISABLED) {
                snoozeDuration = mAlarmInstance.mSnoozeDuration;
            }
            final String descriptionText = buildTimeString(snoozeDuration);
            final String accessibilityText = getResources().getQuantityString(
                R.plurals.alarm_alert_snooze_set, snoozeDuration, snoozeDuration);

            displayAlarmActionMessage(R.string.alarm_alert_snoozed_text, descriptionText, accessibilityText);

            AlarmStateManager.setSnoozeState(this, mAlarmInstance, false);

            Events.sendAlarmEvent(R.string.action_snooze, R.string.label_deskclock);
        }

        // Unbind here, otherwise alarm will keep ringing until activity finishes.
        unbindAlarmService();
    }

    /**
     * Snoozes the alarm using an explicit user-chosen duration, bypassing the progressive snooze
     * computation. Triggered by a long-press on the snooze button.
     */
    private void snoozeWithExplicitDuration(int minutes) {
        if (mAlarmHandled) {
            return;
        }
        mAlarmHandled = true;
        LOGGER.v("Snoozed with explicit duration (%d min): %s", minutes, mAlarmInstance);

        final String descriptionText = buildTimeString(minutes);
        final String accessibilityText = getResources().getQuantityString(R.plurals.alarm_alert_snooze_set, minutes, minutes);

        displayAlarmActionMessage(R.string.alarm_alert_snoozed_text, descriptionText, accessibilityText);

        AlarmStateManager.setSnoozeState(this, mAlarmInstance, false, minutes);

        Events.sendAlarmEvent(R.string.action_snooze, R.string.label_deskclock);

        unbindAlarmService();
    }

    /**
     * Perform dismiss animation and send dismiss intent.
     */
    private void dismiss() {
        mAlarmHandled = true;
        LOGGER.v("Dismissed: %s", mAlarmInstance);

        displayAlarmActionMessage(R.string.alarm_alert_off_text, null, getString(R.string.alarm_alert_off_text));

        AlarmStateManager.deleteInstanceAndUpdateParent(this, mAlarmInstance, false);

        Events.sendAlarmEvent(R.string.action_dismiss, R.string.label_deskclock);

        // Unbind here, otherwise alarm will keep ringing until activity finishes.
        unbindAlarmService();
    }

    /**
     * @return {@code true} if snooze is disabled for this alarm instance. {@code false} otherwise.
     *
     * <p>Useful to know what the alarm should do when it sounds.</p>
     */
    private boolean isSnoozeDisabledForAlarmInstance() {
        return mAlarmInstance.mSnoozeDuration == ALARM_SNOOZE_DURATION_DISABLED;
    }

    /**
     * Bind AlarmService if not yet bound.
     */
    private void bindAlarmService() {
        if (!mServiceBound) {
            final Intent intent = new Intent(this, AlarmService.class);
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
            mServiceBound = true;
        }
    }

    /**
     * Unbind AlarmService if bound.
     */
    private void unbindAlarmService() {
        if (mServiceBound) {
            unbindService(mConnection);
            mServiceBound = false;
        }
    }

    /**
     * Display ringtone title if enabled in <i>"Customize alarm display"</i> settings.
     */
    private void displayRingtoneTitle() {
        final boolean silent = RingtoneUtils.RINGTONE_SILENT.equals(mAlarmInstance.mRingtone);
        final String title = DataModel.getDataModel().getRingtoneTitle(mAlarmInstance.mRingtone);
        final Drawable musicIcon = silent
            ? AppCompatResources.getDrawable(this, R.drawable.ic_ringtone_silent)
            : AppCompatResources.getDrawable(this, R.drawable.ic_music_note);
        int ringtoneIconSize = (int) dpToPx(24, getResources().getDisplayMetrics());
        final int ringtoneTitleColor = SettingsDAO.getRingtoneTitleColor(mPrefs);

        if (musicIcon != null) {
            musicIcon.setTint(ringtoneTitleColor);

            if (mIsTextShadowDisplayed) {
                // Convert the drawable to a bitmap
                Bitmap iconBitmap = Bitmap.createBitmap(ringtoneIconSize, ringtoneIconSize, Bitmap.Config.ARGB_8888);
                Canvas iconCanvas = new Canvas(iconBitmap);
                musicIcon.setBounds(0, 0, ringtoneIconSize, ringtoneIconSize);
                musicIcon.draw(iconCanvas);

                // Create the alpha mask for the shadow
                Bitmap shadowBitmap = iconBitmap.extractAlpha();
                Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                shadowPaint.setColor(mShadowColor);
                shadowPaint.setMaskFilter(new BlurMaskFilter(mShadowRadius * 1.5f, BlurMaskFilter.Blur.NORMAL));

                // Create the final bitmap with space for the shadow
                int finalWidth = ringtoneIconSize + mShadowOffset;
                int finalHeight = ringtoneIconSize + mShadowOffset;
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

        mBinding.ringtoneTitle.setText(title);
        mBinding.ringtoneTitle.setTypeface(mGeneralBoldTypeface);
        mBinding.ringtoneTitle.setTextColor(ringtoneTitleColor);
        // Allow text scrolling (all other attributes are indicated in the "alarm_activity.xml" file)
        mBinding.ringtoneTitle.setSelected(true);
    }

    /**
     * Display a message after snoozing or dismissing the alarm.
     */
    private void displayAlarmActionMessage(final int titleResId, final String descriptionText, final String accessibilityText) {
        if (SettingsDAO.isAlarmActionMessageHidden(mPrefs)) {
            finish();
            return;
        }

        mBinding.contentView.setVisibility(View.GONE);

        mBinding.actionMessageView.setVisibility(View.VISIBLE);

        mBinding.actionTitle.setText(titleResId);
        mBinding.actionTitle.setTypeface(mGeneralBoldTypeface);
        mBinding.actionTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, mAlarmTitleFontSize);
        mBinding.actionTitle.setTextColor(mAlarmTitleColor);

        if (descriptionText != null) {
            mBinding.actionDescription.setVisibility(View.VISIBLE);
            mBinding.actionDescription.setTypeface(mGeneralBoldTypeface);
            mBinding.actionDescription.setText(descriptionText);
            mBinding.actionDescription.setTextSize(TypedValue.COMPLEX_UNIT_SP, mAlarmTitleFontSize);
            mBinding.actionDescription.setTextColor(mAlarmTitleColor);
        }

        mBinding.actionMessageView.setAlpha(0f);
        mBinding.actionMessageView.animate()
            .alpha(1f)
            .setDuration(ALERT_REVEAL_DURATION_MILLIS)
            .withEndAction(() -> {
                mBinding.actionMessageView.announceForAccessibility(accessibilityText);
                mHandler.postDelayed(this::finish, ALERT_DISMISS_DELAY_MILLIS);
            })
            .start();
    }

}

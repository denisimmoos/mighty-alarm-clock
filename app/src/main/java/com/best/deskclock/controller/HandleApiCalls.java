/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.controller;

import static android.text.format.DateUtils.SECOND_IN_MILLIS;
import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;
import static com.best.deskclock.settings.PreferencesDefaultValues.VISIBLE_TAB_CALENDAR_ALARM;
import static com.best.deskclock.settings.PreferencesDefaultValues.VISIBLE_TAB_TIMER;
import static com.best.deskclock.settings.PreferencesKeys.KEY_VISIBLE_TABS;
import static com.best.deskclock.uidata.UiDataModel.Tab.TIMERS;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.text.TextUtils;

import com.best.deskclock.DeskClock;
import com.best.deskclock.R;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.data.Timer;
import com.best.deskclock.events.Events;
import com.best.deskclock.timer.TimerFragment;
import com.best.deskclock.timer.TimerService;
import com.best.deskclock.uidata.UiDataModel;
import com.best.deskclock.utils.LogUtils;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * This activity is never visible. It processes all public intents defined by {@link AlarmClock}
 * that apply to timers (and opens the Calendar tab for SHOW_ALARMS). Its definition in AndroidManifest.xml requires callers to hold
 * the com.android.alarm.permission.SET_ALARM permission to complete the requested action.
 */
public class HandleApiCalls extends Activity {

    private static final LogUtils.Logger LOGGER = new LogUtils.Logger("HandleApiCalls");

    private Context mAppContext;
    private SharedPreferences mPrefs;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mAppContext = getApplicationContext();
        mPrefs = getDefaultSharedPreferences(mAppContext);

        try {
            final Intent intent = getIntent();
            final String action = intent == null ? null : intent.getAction();
            if (action == null) {
                return;
            }
            LOGGER.i("onCreate: " + intent);

            switch (action) {
                case AlarmClock.ACTION_SHOW_ALARMS -> {
                    // Normal alarms were removed: alarms are shown in the Calendar tab.
                    ensureTabIsVisible(VISIBLE_TAB_CALENDAR_ALARM);
                    handleShowAlarms();
                }
                case AlarmClock.ACTION_SET_TIMER -> {
                    ensureTabIsVisible(VISIBLE_TAB_TIMER);
                    handleSetTimer(intent);
                }
                case AlarmClock.ACTION_SHOW_TIMERS -> {
                    ensureTabIsVisible(VISIBLE_TAB_TIMER);
                    handleShowTimers();
                }
                case AlarmClock.ACTION_DISMISS_TIMER -> {
                    ensureTabIsVisible(VISIBLE_TAB_TIMER);
                    handleDismissTimer(intent);
                }
            }
        } catch (Exception e) {
            LOGGER.wtf(e);
        } finally {
            finish();
        }
    }

    private void ensureTabIsVisible(String requiredTabStr) {
        Set<String> visibleTabs = SettingsDAO.getVisibleTabs(mPrefs);

        if (!visibleTabs.contains(requiredTabStr)) {
            // The requested tab is hidden; re-enable it
            Set<String> newTabs = new HashSet<>(visibleTabs);
            newTabs.add(requiredTabStr);
            mPrefs.edit().putStringSet(KEY_VISIBLE_TABS, newTabs).apply();

            // Update shortcuts in the background
            Controller.getController().updateShortcuts();
        }
    }

    private void handleDismissTimer(Intent intent) {
        final Uri dataUri = intent.getData();
        if (dataUri != null) {
            final Timer selectedTimer = getSelectedTimer(dataUri);
            if (selectedTimer != null) {
                DataModel.getDataModel().resetOrDeleteTimer(selectedTimer, R.string.label_intent);
                Controller.getController().notifyVoiceSuccess(
                    this, getResources().getQuantityString(R.plurals.expired_timers_dismissed, 1));
                LOGGER.i("Timer dismissed: " + selectedTimer);
            } else {
                Controller.getController().notifyVoiceFailure(this, getString(R.string.invalid_timer));
                LOGGER.e("Could not dismiss timer: invalid URI");
            }
        } else {
            final List<Timer> expiredTimers = DataModel.getDataModel().getExpiredTimers();
            if (!expiredTimers.isEmpty()) {
                for (Timer timer : expiredTimers) {
                    DataModel.getDataModel().resetOrDeleteTimer(timer, R.string.label_intent);
                }
                final int numberOfTimers = expiredTimers.size();
                final String timersDismissedMessage = getResources().getQuantityString(
                    R.plurals.expired_timers_dismissed, numberOfTimers, numberOfTimers);
                Controller.getController().notifyVoiceSuccess(this, timersDismissedMessage);
                LOGGER.i(timersDismissedMessage);
            } else {
                Controller.getController().notifyVoiceFailure(this, getString(R.string.no_expired_timers));
                LOGGER.e("Could not dismiss timer: no expired timers");
            }
        }
    }

    private Timer getSelectedTimer(Uri dataUri) {
        try {
            final int timerId = (int) ContentUris.parseId(dataUri);
            return DataModel.getDataModel().getTimer(timerId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void handleShowAlarms() {
        // Open the app on the Calendar tab, where the alarms are.
        UiDataModel.getUiDataModel().setSelectedTab(UiDataModel.Tab.CALENDAR_ALARM);

        startActivity(new Intent(this, DeskClock.class));
    }

    private void handleShowTimers() {
        Events.sendTimerEvent(R.string.action_show, R.string.label_intent);

        final Intent showTimersIntent = new Intent(this, DeskClock.class);

        final List<Timer> timers = DataModel.getDataModel().getTimers();
        if (!timers.isEmpty()) {
            final Timer newestTimer = timers.get(timers.size() - 1);
            showTimersIntent.putExtra(TimerService.EXTRA_TIMER_ID, newestTimer.getId());
        }

        // Open DeskClock positioned on the timers tab.
        UiDataModel.getUiDataModel().setSelectedTab(TIMERS);
        startActivity(showTimersIntent);
    }

    private void handleSetTimer(Intent intent) {
        // If no length is supplied, show the timer setup view.
        if (!intent.hasExtra(AlarmClock.EXTRA_LENGTH)) {
            // Change to the timers tab.
            UiDataModel.getUiDataModel().setSelectedTab(TIMERS);

            // Open DeskClock which is now positioned on the timers tab and show the timer setup.
            startActivity(TimerFragment.createTimerSetupIntent(this));
            LOGGER.i("Showing timer setup");
            return;
        }

        // Verify that the timer length is between one second and one day.
        final long lengthMillis = SECOND_IN_MILLIS * intent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0);
        if (lengthMillis < Timer.MIN_LENGTH) {
            final String voiceMessage = getString(R.string.invalid_timer_length);
            Controller.getController().notifyVoiceFailure(this, voiceMessage);
            LOGGER.i("Invalid timer length requested: " + lengthMillis);
            return;
        }

        final String label = getLabelFromIntent(intent, "");
        final boolean skipUi = intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false);

        // Attempt to reuse an existing timer that is Reset with the same length and label.
        Timer timer = null;
        for (Timer t : DataModel.getDataModel().getTimers()) {
            if (!t.isReset()) {
                continue;
            }
            if (t.getLength() != lengthMillis) {
                continue;
            }
            if (!TextUtils.equals(label, t.getLabel())) {
                continue;
            }

            timer = t;
            break;
        }

        // Create a new timer if one could not be reused.
        if (timer == null) {
            String defaultTimeToAddToTimer = String.valueOf(SettingsDAO.getDefaultTimeToAddToTimer(getDefaultSharedPreferences(mAppContext)));
            timer = DataModel.getDataModel().addTimer(lengthMillis, label, defaultTimeToAddToTimer, skipUi);
            Events.sendTimerEvent(R.string.action_create, R.string.label_intent);
        }

        // Start the selected timer.
        DataModel.getDataModel().startTimer(timer);
        Events.sendTimerEvent(R.string.action_start, R.string.label_intent);
        Controller.getController().notifyVoiceSuccess(this, getString(R.string.timer_created));

        // If not instructed to skip the UI, display the running timer.
        if (!skipUi) {
            // Change to the timers tab.
            UiDataModel.getUiDataModel().setSelectedTab(TIMERS);

            // Open DeskClock which is now positioned on the timers tab.
            startActivity(new Intent(this, DeskClock.class).putExtra(TimerService.EXTRA_TIMER_ID, timer.getId()));
        }
    }

    private static String getLabelFromIntent(Intent intent, String defaultLabel) {
        final String message = Objects.requireNonNull(intent.getExtras()).getString(AlarmClock.EXTRA_MESSAGE, defaultLabel);
        return message == null ? "" : message;
    }

}

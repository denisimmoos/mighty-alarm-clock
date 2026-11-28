/*
 * Copyright (C) 2015 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock;

import static com.best.deskclock.settings.PreferencesDefaultValues.DARK_THEME;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_FLIP_ACTION;
import static com.best.deskclock.settings.PreferencesDefaultValues.LIGHT_THEME;
import static com.best.deskclock.settings.PreferencesDefaultValues.SYSTEM_THEME;
import static com.best.deskclock.settings.PreferencesDefaultValues.BLUE_ACCENT_COLOR;
import static com.best.deskclock.settings.PreferencesDefaultValues.ORANGE_ACCENT_COLOR;
import static com.best.deskclock.settings.PreferencesKeys.KEY_ACCENT_COLOR;
import static com.best.deskclock.settings.PreferencesKeys.KEY_FLIP_ACTION;
import static com.best.deskclock.settings.PreferencesKeys.KEY_NIGHT_ACCENT_COLOR;
import static com.best.deskclock.settings.PreferencesKeys.KEY_DEBUG_ORANGE_ACCENT_APPLIED;
import static com.best.deskclock.settings.PreferencesKeys.KEY_FLIP_SNOOZE_DEFAULT_APPLIED;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import com.best.deskclock.controller.Controller;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.events.LogEventTracker;
import com.best.deskclock.mighty.backup.BackupWorker;
import com.best.deskclock.mighty.calendar.CalendarAlarmWorker;
import com.best.deskclock.mighty.backup.JsonBackupManager;
import com.best.deskclock.uidata.UiDataModel;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.NotificationUtils;
import com.best.deskclock.utils.SdkUtils;

import java.io.File;
import java.util.Objects;

public class DeskClockApplication extends Application implements Application.ActivityLifecycleCallbacks {

    private static DeskClockApplication sInstance;

    private int mStartedActivities = 0;
    private boolean mIsChangingConfiguration = false;

    @Override
    public void onCreate() {
        super.onCreate();

        sInstance = this;

        migrateFlipActionDefaultToSnooze();
        migrateDebugAccentToOrange(getDefaultSharedPreferences(this), BuildConfig.IS_DEBUG_BUILD);

        String theme = SettingsDAO.getTheme(getDefaultSharedPreferences(this));
        applySystemNightMode(theme);

        DataModel.getDataModel().init();
        UiDataModel.getUiDataModel().init();
        Controller.getController().init();
        Controller.getController().addEventTracker(new LogEventTracker());
        Controller.getController().updateShortcuts();

        if (SdkUtils.isAtLeastAndroid8()) {
            NotificationUtils.updateNotificationChannels(this);
        }

        registerActivityLifecycleCallbacks(this);

        initMightyFeatures();
    }

    /**
     * Schedules background work for the Mighty fork features (scheduled JSON backup and
     * calendar alarm scanning) that needs to happen once per process start.
     */
    private void initMightyFeatures() {
        try {
            if (JsonBackupManager.isScheduleEnabled(this)) {
                BackupWorker.schedulePeriodic(this, JsonBackupManager.getIntervalHours(this));
            }
            CalendarAlarmWorker.ensureScheduled(this);
        } catch (Exception e) {
            LogUtils.e("DeskClockApplication: failed to schedule Mighty background work", e);
        }
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        if (mStartedActivities == 0 && !mIsChangingConfiguration) {
            DataModel.getDataModel().setApplicationInForeground(true);
        }

        mIsChangingConfiguration = false;
        mStartedActivities++;
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        mStartedActivities--;

        if (mStartedActivities == 0) {
            if (!activity.isChangingConfigurations()) {
                DataModel.getDataModel().setApplicationInForeground(false);
            } else {
                mIsChangingConfiguration = true;
            }
        }
    }

    @Override public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {}
    @Override public void onActivityResumed(@NonNull Activity activity) {}
    @Override public void onActivityPaused(@NonNull Activity activity) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}
    @Override public void onActivityDestroyed(@NonNull Activity activity) {}

    /**
     * Existing installs kept the previous default "nothing" for flip. Upgrade once to snooze
     * when the preference is still unset or still "0"; leave dismiss/disable choices alone.
     */
    private void migrateFlipActionDefaultToSnooze() {
        SharedPreferences prefs = getDefaultSharedPreferences(this);
        if (prefs.getBoolean(KEY_FLIP_SNOOZE_DEFAULT_APPLIED, false)) {
            return;
        }

        SharedPreferences.Editor editor = prefs.edit();
        final String current = prefs.getString(KEY_FLIP_ACTION, null);
        if (current == null || "0".equals(current)) {
            editor.putString(KEY_FLIP_ACTION, DEFAULT_FLIP_ACTION);
        }
        editor.putBoolean(KEY_FLIP_SNOOZE_DEFAULT_APPLIED, true).apply();
    }

    /**
     * Debug builds default to Orange so they can be told apart from the release app. Data that
     * was created by an older debug build still holds Blue ("11"), the old default, because the
     * settings screen stores its default when it is shown. Switch that over once; a colour the
     * user picks afterwards is kept.
     */
    public static void migrateDebugAccentToOrange(SharedPreferences prefs, boolean isDebugBuild) {
        if (!isDebugBuild) {
            return;
        }
        if (prefs.getBoolean(KEY_DEBUG_ORANGE_ACCENT_APPLIED, false)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : new String[]{KEY_ACCENT_COLOR, KEY_NIGHT_ACCENT_COLOR}) {
            if (BLUE_ACCENT_COLOR.equals(prefs.getString(key, null))) {
                editor.putString(key, ORANGE_ACCENT_COLOR);
            }
        }
        editor.putBoolean(KEY_DEBUG_ORANGE_ACCENT_APPLIED, true).apply();
    }

    private void applySystemNightMode(String theme) {
        switch (theme) {
            case SYSTEM_THEME -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            case LIGHT_THEME -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            case DARK_THEME -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }
    }

    public static Context getAppContext() {
        return sInstance;
    }

    /**
     * Returns the default {@link SharedPreferences} instance from the underlying storage context.
     */
    public static SharedPreferences getDefaultSharedPreferences(Context context) {
        final Context appContext = context.getApplicationContext();
        final Context storageContext;

        if (SdkUtils.isAtLeastAndroid7()) {
            // All N devices have split storage areas. Migrate the existing preferences into the new
            // device encrypted storage area if that has not yet occurred.
            storageContext = appContext.createDeviceProtectedStorageContext();
            final String name = appContext.getPackageName() + "_preferences";
            final String prefsFilename = storageContext.getDataDir() + "/shared_prefs/" + name + ".xml";
            final File prefs = new File(Objects.requireNonNull(Uri.parse(prefsFilename).getPath()));

            if (!prefs.exists()) {
                if (!storageContext.moveSharedPreferencesFrom(appContext, name)) {
                    LogUtils.wtf("Failed to migrate shared preferences");
                }
            }
        } else {
            storageContext = appContext;
        }

        return PreferenceManager.getDefaultSharedPreferences(storageContext);
    }

}

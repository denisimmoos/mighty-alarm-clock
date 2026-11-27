// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.settings;

import static android.app.Activity.RESULT_OK;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_VIBRATION_START_DELAY;
import static com.best.deskclock.settings.PreferencesKeys.*;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.HapticFeedbackConstantsCompat;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LifecycleOwner;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.best.deskclock.R;
import com.best.deskclock.alarms.AlarmStateManager;
import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.dialogfragment.AlarmNotificationReminderDialogFragment;
import com.best.deskclock.dialogfragment.VibrationPatternDialogFragment;
import com.best.deskclock.dialogfragment.VibrationStartDelayDialogFragment;
import com.best.deskclock.ringtone.RingtonePickerActivity;
import com.best.deskclock.settings.custompreference.AlarmNotificationReminderPreference;
import com.best.deskclock.settings.custompreference.AlarmVolumePreference;
import com.best.deskclock.settings.custompreference.CustomSliderPreference;
import com.best.deskclock.settings.custompreference.VibrationPatternPreference;
import com.best.deskclock.settings.custompreference.VibrationStartDelayPreference;
import com.best.deskclock.uicomponents.toast.CustomToast;
import com.best.deskclock.utils.DeviceUtils;
import com.best.deskclock.utils.RingtoneUtils;
import com.best.deskclock.utils.ThemeUtils;
import com.best.deskclock.utils.Utils;


public class AlarmSettingsFragment extends ScreenFragment
    implements Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener {

    private AudioManager mAudioManager;
    private AudioDeviceCallback mAudioDeviceCallback;

    private boolean mHasExternalAudioDeviceConnected;

    Preference mAlarmDisplayCustomizationPref;
    Preference mAlarmFontPref;
    Preference mAlarmRingtonePref;
    AlarmVolumePreference mAlarmVolumePref;
    SwitchPreferenceCompat mAdvancedAudioPlaybackPref;
    SwitchPreferenceCompat mAutoRoutingToExternalAudioDevicePref;
    SwitchPreferenceCompat mSystemMediaVolume;
    CustomSliderPreference mExternalAudioDeviceVolumePref;
    PreferenceCategory mAlarmVibrationCategory;
    VibrationPatternPreference mVibrationPatternPref;
    SwitchPreferenceCompat mEnableSnoozedOrDismissedAlarmVibrationsPref;
    ListPreference mVolumeButtonsPref;
    ListPreference mPowerButtonPref;
    ListPreference mFlipActionPref;
    ListPreference mShakeActionPref;
    CustomSliderPreference mShakeIntensityPref;

    private final ActivityResultLauncher<Intent> fontPickerLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK) {
                return;
            }

            Intent intent = result.getData();
            final Uri sourceUri = intent == null ? null : intent.getData();
            if (sourceUri == null) {
                return;
            }

            final Context appContext = requireContext().getApplicationContext();

            // Take persistent permission
            appContext.getContentResolver().takePersistableUriPermission(sourceUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

            String safeTitle = Utils.toSafeFileName(FILE_ALARM_FONT);
            String oldFontPath = mPrefs.getString(KEY_ALARM_FONT, null);

            AppExecutors.getDiskIO().execute(() -> {
                // Delete the old font if it exists
                clearFile(oldFontPath);

                // Clear the font cache
                ThemeUtils.removeFontFromCache(oldFontPath);

                // Copy the new font to the device's protected storage
                Uri copiedUri = Utils.copyFileToDeviceProtectedStorage(appContext, sourceUri, safeTitle);

                // Save the new path
                if (copiedUri != null) {
                    mPrefs.edit().putString(KEY_ALARM_FONT, copiedUri.getPath()).apply();
                }

                AppExecutors.getMainThread().post(() -> {
                    if (copiedUri != null) {
                        CustomToast.show(appContext, R.string.custom_font_toast_message_selected);
                    } else {
                        CustomToast.show(appContext, "Error importing font");
                    }

                    if (!isAdded() || mAlarmFontPref == null) {
                        return;
                    }

                    if (copiedUri != null) {
                        mAlarmFontPref.setTitle(getString(R.string.custom_font_title_variant));
                    }else {
                        mAlarmFontPref.setTitle(getString(R.string.custom_font_title));
                    }
                });
            });
        });

    @Override
    protected String getFragmentTitle() {
        return getString(R.string.alarm_settings);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAudioManager = requireContext().getApplicationContext().getSystemService(AudioManager.class);
        mHasExternalAudioDeviceConnected = RingtoneUtils.hasExternalAudioDeviceConnected(requireContext(), mPrefs);

        addPreferencesFromResource(R.xml.settings_alarm);

        mAlarmDisplayCustomizationPref = findPreference(KEY_ALARM_DISPLAY_CUSTOMIZATION);
        mAlarmFontPref = findPreference(KEY_ALARM_FONT);
        mAlarmRingtonePref = findPreference(KEY_DEFAULT_ALARM_RINGTONE);
        mAlarmVolumePref = findPreference(KEY_ALARM_VOLUME_SETTING);
        mAdvancedAudioPlaybackPref = findPreference(KEY_ADVANCED_AUDIO_PLAYBACK);
        mAutoRoutingToExternalAudioDevicePref = findPreference(KEY_AUTO_ROUTING_TO_EXTERNAL_AUDIO_DEVICE);
        mSystemMediaVolume = findPreference(KEY_SYSTEM_MEDIA_VOLUME);
        mExternalAudioDeviceVolumePref = findPreference(KEY_EXTERNAL_AUDIO_DEVICE_VOLUME);
        mAlarmVibrationCategory = findPreference(KEY_ALARM_VIBRATION_CATEGORY);
        mVibrationPatternPref = findPreference(KEY_VIBRATION_PATTERN);
        mEnableSnoozedOrDismissedAlarmVibrationsPref = findPreference(KEY_ENABLE_SNOOZED_OR_DISMISSED_ALARM_VIBRATIONS);
        mVolumeButtonsPref = findPreference(KEY_VOLUME_BUTTONS);
        mPowerButtonPref = findPreference(KEY_POWER_BUTTON);
        mFlipActionPref = findPreference(KEY_FLIP_ACTION);
        mShakeActionPref = findPreference(KEY_SHAKE_ACTION);
        mShakeIntensityPref = findPreference(KEY_SHAKE_INTENSITY);

        setupPreferences();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupFragmentResultListeners();
    }

    @Override
    public void onResume() {
        super.onResume();

        restoreCustomFileDialogIfNeeded(KEY_ALARM_FONT, mAlarmFontPref, fontPickerLauncher, null);

        updateRingtonePreferences();

        if (mHasExternalAudioDeviceConnected) {
            mAlarmVolumePref.setTitle(R.string.disconnect_external_audio_device_title);
            mExternalAudioDeviceVolumePref.setTitle(R.string.external_audio_device_volume_title);
        } else {
            mAlarmVolumePref.setTitle(R.string.alarm_volume_title);
            mExternalAudioDeviceVolumePref.setTitle(R.string.connect_external_audio_device_title);
        }

        if (mAudioDeviceCallback == null) {
            initAudioDeviceCallback();
        }
    }

    @Override
    public void onStop() {
        super.onStop();

        stopRingtonePreview();

        if (mAudioDeviceCallback != null) {
            mAudioManager.unregisterAudioDeviceCallback(mAudioDeviceCallback);
            mAudioDeviceCallback = null;
        }
    }

    @Override
    public void onDestroy() {
        nullifyPreferenceListeners(mAlarmDisplayCustomizationPref, mAlarmFontPref, mAlarmRingtonePref, mAlarmVolumePref,
            mAdvancedAudioPlaybackPref, mAutoRoutingToExternalAudioDevicePref, mSystemMediaVolume, mExternalAudioDeviceVolumePref,
            mAlarmVibrationCategory, mVibrationPatternPref, mEnableSnoozedOrDismissedAlarmVibrationsPref, mVolumeButtonsPref,
            mPowerButtonPref, mFlipActionPref, mShakeActionPref, mShakeIntensityPref);

        mAudioManager = null;

        nullifyAllPrefs();

        super.onDestroy();
    }

    @Override
    public boolean onPreferenceChange(Preference pref, Object newValue) {
        switch (pref.getKey()) {
            case KEY_ENABLE_SNOOZED_OR_DISMISSED_ALARM_VIBRATIONS ->
                Utils.performHapticFeedback(getView(), HapticFeedbackConstantsCompat.VIRTUAL_KEY);

            case KEY_ADVANCED_AUDIO_PLAYBACK -> {
                stopRingtonePreview();
                mAutoRoutingToExternalAudioDevicePref.setVisible((boolean) newValue);
                mSystemMediaVolume.setVisible((boolean) newValue && SettingsDAO.isAutoRoutingToExternalAudioDevice(mPrefs));
                mExternalAudioDeviceVolumePref.setVisible((boolean) newValue
                    && SettingsDAO.isAutoRoutingToExternalAudioDevice(mPrefs)
                    && SettingsDAO.shouldUseCustomMediaVolume(mPrefs));
                Utils.performHapticFeedback(getView(), HapticFeedbackConstantsCompat.VIRTUAL_KEY);
            }

            case KEY_AUTO_ROUTING_TO_EXTERNAL_AUDIO_DEVICE -> {
                stopRingtonePreview();
                mSystemMediaVolume.setVisible((boolean) newValue);
                mExternalAudioDeviceVolumePref.setVisible((boolean) newValue && SettingsDAO.shouldUseCustomMediaVolume(mPrefs));
                Utils.performHapticFeedback(getView(), HapticFeedbackConstantsCompat.VIRTUAL_KEY);
            }

            case KEY_SYSTEM_MEDIA_VOLUME -> {
                stopRingtonePreview();
                mExternalAudioDeviceVolumePref.setVisible(!(boolean) newValue);
                Utils.performHapticFeedback(getView(), HapticFeedbackConstantsCompat.VIRTUAL_KEY);
            }

            case KEY_VOLUME_BUTTONS, KEY_POWER_BUTTON, KEY_FLIP_ACTION, KEY_VIBRATION_PATTERN -> {
                final ListPreference preference = (ListPreference) pref;
                final int index = preference.findIndexOfValue((String) newValue);
                preference.setSummary(preference.getEntries()[index]);
            }

            case KEY_SHAKE_ACTION -> {
                final int index = mShakeActionPref.findIndexOfValue((String) newValue);
                mShakeActionPref.setSummary(mShakeActionPref.getEntries()[index]);
                // index == 2 --> Nothing
                mShakeIntensityPref.setVisible(index != 2);
            }
        }

        return true;
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference pref) {
        final Context context = getActivity();
        if (context == null) {
            return false;
        }

        switch (pref.getKey()) {
            case KEY_ALARM_DISPLAY_CUSTOMIZATION -> animateAndShowFragment(new AlarmDisplayCustomizationFragment());

            case KEY_ALARM_FONT -> selectCustomFile(mAlarmFontPref, fontPickerLauncher, SettingsDAO.getAlarmFont(mPrefs), KEY_ALARM_FONT,
                true, null);

            case KEY_DEFAULT_ALARM_RINGTONE -> startActivity(RingtonePickerActivity.createAlarmRingtonePickerIntentForSettings(context));
        }

        return true;
    }

    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference pref) {
        if (pref instanceof VibrationPatternPreference vibrationPatternPreference) {
            String currentValue = vibrationPatternPreference.getPattern();
            VibrationPatternDialogFragment dialogFragment = VibrationPatternDialogFragment.newInstance(pref.getKey(), currentValue);
            VibrationPatternDialogFragment.show(getParentFragmentManager(), dialogFragment);
        } else if (pref instanceof VibrationStartDelayPreference vibrationStartDelayPreference) {
            int currentValue = vibrationStartDelayPreference.getVibrationStartDelay();
            VibrationStartDelayDialogFragment dialogFragment = VibrationStartDelayDialogFragment.newInstance(pref.getKey(), currentValue,
                currentValue == DEFAULT_VIBRATION_START_DELAY);
            VibrationStartDelayDialogFragment.show(getParentFragmentManager(), dialogFragment);
        } else if (pref instanceof AlarmNotificationReminderPreference alarmNotificationReminderPreference) {
            int currentValue = alarmNotificationReminderPreference.getAlarmNotificationReminderTime();
            AlarmNotificationReminderDialogFragment dialogFragment =
                AlarmNotificationReminderDialogFragment.newInstance(pref.getKey(), currentValue);
            AlarmNotificationReminderDialogFragment.show(getParentFragmentManager(), dialogFragment);
        } else {
            super.onDisplayPreferenceDialog(pref);
        }
    }

    private void setupPreferences() {
        final boolean isAdvancedAudioPlaybackEnabled = SettingsDAO.isAdvancedAudioPlaybackEnabled(mPrefs);
        final boolean isAutoRoutingToExternalAudioDevice = SettingsDAO.isAutoRoutingToExternalAudioDevice(mPrefs);

        mAlarmDisplayCustomizationPref.setOnPreferenceClickListener(this);

        mAlarmFontPref.setTitle(getString(SettingsDAO.getAlarmFont(mPrefs) == null
            ? R.string.custom_font_title
            : R.string.custom_font_title_variant));
        mAlarmFontPref.setOnPreferenceClickListener(this);



        mAlarmRingtonePref.setOnPreferenceClickListener(this);






        if (mAlarmVolumePref.isVisible()) {
            mAlarmVolumePref.setEnabled(!mHasExternalAudioDeviceConnected);
        }



        mAdvancedAudioPlaybackPref.setOnPreferenceChangeListener(this);

        mAutoRoutingToExternalAudioDevicePref.setVisible(isAdvancedAudioPlaybackEnabled);
        mAutoRoutingToExternalAudioDevicePref.setOnPreferenceChangeListener(this);

        mSystemMediaVolume.setVisible(isAdvancedAudioPlaybackEnabled && isAutoRoutingToExternalAudioDevice);
        mSystemMediaVolume.setOnPreferenceChangeListener(this);

        mExternalAudioDeviceVolumePref.setVisible(isAdvancedAudioPlaybackEnabled
            && isAutoRoutingToExternalAudioDevice
            && SettingsDAO.shouldUseCustomMediaVolume(mPrefs));
        mExternalAudioDeviceVolumePref.setEnabled(mExternalAudioDeviceVolumePref.isVisible() && mHasExternalAudioDeviceConnected);

        mAlarmVibrationCategory.setVisible(DeviceUtils.hasVibrator(requireContext()));



        mEnableSnoozedOrDismissedAlarmVibrationsPref.setOnPreferenceChangeListener(this);

        mVolumeButtonsPref.setOnPreferenceChangeListener(this);
        mVolumeButtonsPref.setSummary(mVolumeButtonsPref.getEntry());

        mPowerButtonPref.setOnPreferenceChangeListener(this);
        mPowerButtonPref.setSummary(mPowerButtonPref.getEntry());

        SensorManager sensorManager = requireContext().getApplicationContext().getSystemService(SensorManager.class);
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) {
            mFlipActionPref.setValue("0");
            mShakeActionPref.setValue("0");
            mFlipActionPref.setVisible(false);
            mShakeActionPref.setVisible(false);
        } else {
            mFlipActionPref.setSummary(mFlipActionPref.getEntry());
            mFlipActionPref.setOnPreferenceChangeListener(this);
            mShakeActionPref.setSummary(mShakeActionPref.getEntry());
            mShakeActionPref.setOnPreferenceChangeListener(this);

            // shakeActionIndex == 2 --> Nothing
            final int shakeActionIndex = mShakeActionPref.findIndexOfValue(String.valueOf(SettingsDAO.getShakeAction(mPrefs)));
            mShakeIntensityPref.setVisible(shakeActionIndex != 2);
        }








    }

    private void setupFragmentResultListeners() {
        FragmentManager parentFragmentManager = getParentFragmentManager();
        LifecycleOwner viewLifecycleOwner = getViewLifecycleOwner();

        // Vibration pattern preference
        parentFragmentManager.setFragmentResultListener(VibrationPatternDialogFragment.REQUEST_KEY, viewLifecycleOwner,
            (requestKey, bundle) -> {
                String key = bundle.getString(VibrationPatternDialogFragment.RESULT_PREF_KEY);
                String newValue = bundle.getString(VibrationPatternDialogFragment.RESULT_PATTERN_KEY);

                if (key != null) {
                    VibrationPatternPreference pref = findPreference(key);
                    if (pref != null) {
                        pref.setPattern(newValue);
                    }
                }
            });

        // Vibration start delay preference
        parentFragmentManager.setFragmentResultListener(VibrationStartDelayDialogFragment.REQUEST_KEY, viewLifecycleOwner,
            (requestKey, bundle) -> {
                String key = bundle.getString(VibrationStartDelayDialogFragment.RESULT_PREF_KEY);
                int newValue = bundle.getInt(VibrationStartDelayDialogFragment.VIBRATION_DELAY_VALUE);

                if (key != null) {
                    VibrationStartDelayPreference pref = findPreference(key);
                    if (pref != null) {
                        pref.setVibrationStartDelay(newValue);
                    }
                }
            });

        // Notification reminder preference
        parentFragmentManager.setFragmentResultListener(AlarmNotificationReminderDialogFragment.REQUEST_KEY, viewLifecycleOwner,
            (requestKey, bundle) -> {
                String key = bundle.getString(AlarmNotificationReminderDialogFragment.RESULT_PREF_KEY);
                int newValue = bundle.getInt(AlarmNotificationReminderDialogFragment.ALARM_NOTIFICATION_REMINDER_VALUE);

                if (key != null) {
                    AlarmNotificationReminderPreference pref = findPreference(key);
                    if (pref != null) {
                        pref.setAlarmNotificationReminderTime(newValue);

                        // Reschedule the upcoming alarm notifications with the new reminder time.
                        final Context appContext = requireContext().getApplicationContext();
                        AppExecutors.getDiskIO().execute(() -> AlarmStateManager.fixAlarmInstances(appContext));
                    }
                }
            });
    }

    private void initAudioDeviceCallback() {
        if (mAudioDeviceCallback != null) {
            return;
        }

        mAudioDeviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                super.onAudioDevicesAdded(addedDevices);

                mAlarmVolumePref.stopRingtonePreview();

                for (AudioDeviceInfo device : addedDevices) {
                    if (RingtoneUtils.isExternalAudioDevice(device)) {
                        mAlarmVolumePref.setEnabled(false);
                        mAlarmVolumePref.setTitle(R.string.disconnect_external_audio_device_title);
                        mExternalAudioDeviceVolumePref.setEnabled(true);
                        mExternalAudioDeviceVolumePref.setTitle(R.string.external_audio_device_volume_title);
                    }
                }
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                mExternalAudioDeviceVolumePref.stopRingtonePreviewForExternalAudioDevices();

                for (AudioDeviceInfo device : removedDevices) {
                    if (RingtoneUtils.isExternalAudioDevice(device)) {
                        mAlarmVolumePref.setEnabled(true);
                        mAlarmVolumePref.setTitle(R.string.alarm_volume_title);
                        mExternalAudioDeviceVolumePref.setEnabled(false);
                        mExternalAudioDeviceVolumePref.setTitle(R.string.connect_external_audio_device_title);
                    }
                }
            }
        };

        mAudioManager.registerAudioDeviceCallback(mAudioDeviceCallback, new Handler(Looper.getMainLooper()));
    }

    private void stopRingtonePreview() {
        if (mHasExternalAudioDeviceConnected) {
            mExternalAudioDeviceVolumePref.stopRingtonePreviewForExternalAudioDevices();
        } else {
            mAlarmVolumePref.stopRingtonePreview();
        }
    }

    private void updateRingtonePreferences() {
        mAlarmRingtonePref.setSummary(DataModel.getDataModel().getAlarmRingtoneTitle());
        mAlarmRingtonePref.setIntent(RingtonePickerActivity.createAlarmRingtonePickerIntentForSettings(requireContext()));
    }

    private void nullifyAllPrefs() {
        mAlarmDisplayCustomizationPref = null;
        mAlarmFontPref = null;
        mAlarmRingtonePref = null;
        mAlarmVolumePref = null;
        mAdvancedAudioPlaybackPref = null;
        mAutoRoutingToExternalAudioDevicePref = null;
        mSystemMediaVolume = null;
        mExternalAudioDeviceVolumePref = null;
        mAlarmVibrationCategory = null;
        mVibrationPatternPref = null;
        mEnableSnoozedOrDismissedAlarmVibrationsPref = null;
        mVolumeButtonsPref = null;
        mPowerButtonPref = null;
        mFlipActionPref = null;
        mShakeActionPref = null;
        mShakeIntensityPref = null;
    }

}

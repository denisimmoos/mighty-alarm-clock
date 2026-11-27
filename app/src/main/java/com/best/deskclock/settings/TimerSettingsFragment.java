// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.settings;

import static android.app.Activity.RESULT_OK;
import static com.best.deskclock.settings.PreferencesKeys.*;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.view.HapticFeedbackConstantsCompat;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LifecycleOwner;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.best.deskclock.R;
import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.data.Timer;
import com.best.deskclock.dialogfragment.AutoSilenceDurationDialogFragment;
import com.best.deskclock.dialogfragment.TimerAddTimeButtonDialogFragment;
import com.best.deskclock.dialogfragment.VolumeCrescendoDurationDialogFragment;
import com.best.deskclock.ringtone.RingtonePickerActivity;
import com.best.deskclock.settings.custompreference.AutoSilenceDurationPreference;
import com.best.deskclock.settings.custompreference.CustomSliderPreference;
import com.best.deskclock.settings.custompreference.TimerAddTimeButtonValuePreference;
import com.best.deskclock.settings.custompreference.VolumeCrescendoDurationPreference;
import com.best.deskclock.uicomponents.CustomDialog;
import com.best.deskclock.uicomponents.toast.CustomToast;
import com.best.deskclock.utils.DeviceUtils;
import com.best.deskclock.utils.ThemeUtils;
import com.best.deskclock.utils.Utils;

import java.util.ArrayList;
import java.util.List;

public class TimerSettingsFragment extends ScreenFragment
    implements Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener {

    private static final String KEY_SHOW_SINGLE_TIMER_WARNING = "show_single_timer_warning";
    private static final String KEY_PENDING_SINGLE_MODE_VALUE = "pending_single_mode_value";

    private boolean mShowSingleTimerWarning = false;
    private boolean mPendingSingleModeValue = false;


    Preference mTimerDisplayCustomizationPref;
    Preference mTimerDurationFontPref;
    ListPreference mTimerCreationViewStylePref;
    Preference mTimerRingtonePref;
    SwitchPreferenceCompat mTimerVibratePref;
    SwitchPreferenceCompat mTimerVolumeButtonsActionPref;
    SwitchPreferenceCompat mTimerPowerButtonActionPref;
    SwitchPreferenceCompat mTimerFlipActionPref;
    SwitchPreferenceCompat mTimerShakeActionPref;
    CustomSliderPreference mTimerShakeIntensityPref;
    SwitchPreferenceCompat mSingleTimerModePref;
    ListPreference mSortTimerPref;
    SwitchPreferenceCompat mDisplayWarningBeforeDeletingTimerPref;
    SwitchPreferenceCompat mDisplayLowAlarmVolumeWarningPref;

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

            String safeTitle = Utils.toSafeFileName(FILE_TIMER_FONT);
            String oldFontPath = mPrefs.getString(KEY_TIMER_DURATION_FONT, null);

            AppExecutors.getDiskIO().execute(() -> {
                // Delete the old font if it exists
                clearFile(oldFontPath);

                // Clear the font cache
                ThemeUtils.removeFontFromCache(oldFontPath);

                // Copy the new font to the device's protected storage
                Uri copiedUri = Utils.copyFileToDeviceProtectedStorage(appContext, sourceUri, safeTitle);

                // Save the new path
                if (copiedUri != null) {
                    mPrefs.edit().putString(KEY_TIMER_DURATION_FONT, copiedUri.getPath()).apply();
                }

                AppExecutors.getMainThread().post(() -> {
                    if (copiedUri != null) {
                        CustomToast.show(appContext, R.string.custom_font_toast_message_selected);
                    } else {
                        CustomToast.show(appContext, "Error importing font");
                    }

                    if (!isAdded() || mTimerDurationFontPref == null) {
                        return;
                    }

                    if (copiedUri != null) {
                        mTimerDurationFontPref.setTitle(getString(R.string.custom_font_title_variant));
                    } else {
                        mTimerDurationFontPref.setTitle(getString(R.string.custom_font_title));
                    }
                });
            });
        });

    @Override
    protected String getFragmentTitle() {
        return getString(R.string.timer_settings);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.settings_timer);

        mTimerDisplayCustomizationPref = findPreference(KEY_TIMER_DISPLAY_CUSTOMIZATION);
        mTimerDurationFontPref = findPreference(KEY_TIMER_DURATION_FONT);
        mTimerCreationViewStylePref = findPreference(KEY_TIMER_CREATION_VIEW_STYLE);
        mTimerRingtonePref = findPreference(KEY_TIMER_RINGTONE);
        mTimerVibratePref = findPreference(KEY_TIMER_VIBRATE);
        mTimerVolumeButtonsActionPref = findPreference(KEY_TIMER_VOLUME_BUTTONS_ACTION);
        mTimerPowerButtonActionPref = findPreference(KEY_TIMER_POWER_BUTTON_ACTION);
        mTimerFlipActionPref = findPreference(KEY_TIMER_FLIP_ACTION);
        mTimerShakeActionPref = findPreference(KEY_TIMER_SHAKE_ACTION);
        mTimerShakeIntensityPref = findPreference(KEY_TIMER_SHAKE_INTENSITY);
        mSingleTimerModePref = findPreference(KEY_SINGLE_TIMER_MODE);
        mSortTimerPref = findPreference(KEY_SORT_TIMER);
        mDisplayWarningBeforeDeletingTimerPref = findPreference(KEY_DISPLAY_WARNING_BEFORE_DELETING_TIMER);
        mDisplayLowAlarmVolumeWarningPref = findPreference(KEY_DISPLAY_LOW_ALARM_VOLUME_WARNING);

        if (savedInstanceState != null) {
            mShowSingleTimerWarning = savedInstanceState.getBoolean(KEY_SHOW_SINGLE_TIMER_WARNING, false);
            mPendingSingleModeValue = savedInstanceState.getBoolean(KEY_PENDING_SINGLE_MODE_VALUE, false);
        }

        setupPreferences();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(KEY_SHOW_SINGLE_TIMER_WARNING, mShowSingleTimerWarning);
        outState.putBoolean(KEY_PENDING_SINGLE_MODE_VALUE, mPendingSingleModeValue);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupFragmentResultListeners();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mShowSingleTimerWarning && (mActiveDialog == null || !mActiveDialog.isShowing())) {
            mActiveDialog = singleModeWarningDialog(mPendingSingleModeValue);
            mActiveDialog.show();
        }

        restoreCustomFileDialogIfNeeded(KEY_TIMER_DURATION_FONT, mTimerDurationFontPref, fontPickerLauncher, null);

        updateRingtonePreferences();
    }

    @Override
    public void onStop() {
        super.onStop();

    }

    @Override
    public void onDestroy() {
        nullifyPreferenceListeners(mTimerDisplayCustomizationPref, mTimerDurationFontPref, mTimerCreationViewStylePref, mTimerRingtonePref,
            mTimerVibratePref, mTimerVolumeButtonsActionPref, mTimerPowerButtonActionPref,
            mTimerFlipActionPref, mTimerShakeActionPref, mTimerShakeIntensityPref, mSortTimerPref, mDisplayWarningBeforeDeletingTimerPref,
            mDisplayLowAlarmVolumeWarningPref);

        nullifyAllPrefs();

        super.onDestroy();
    }

    @Override
    public boolean onPreferenceChange(Preference pref, Object newValue) {
        switch (pref.getKey()) {
            case KEY_TIMER_CREATION_VIEW_STYLE, KEY_SORT_TIMER -> {
                final ListPreference preference = (ListPreference) pref;
                final int index = preference.findIndexOfValue((String) newValue);
                preference.setSummary(preference.getEntries()[index]);
            }

            case KEY_TIMER_RINGTONE -> mTimerRingtonePref.setSummary(DataModel.getDataModel().getTimerRingtoneTitle());

            case KEY_TIMER_SHAKE_ACTION -> {
                mTimerShakeIntensityPref.setVisible((boolean) newValue);

                Utils.performHapticFeedback(getView(), HapticFeedbackConstantsCompat.VIRTUAL_KEY);
            }

            case KEY_SINGLE_TIMER_MODE -> {
                Utils.performHapticFeedback(getView(), HapticFeedbackConstantsCompat.VIRTUAL_KEY);
                boolean newValueBool = (boolean) newValue;

                if (DataModel.getDataModel().getTimers().isEmpty()) {
                    mSortTimerPref.setVisible(!newValueBool);
                    mDisplayWarningBeforeDeletingTimerPref.setVisible(!newValueBool);
                } else {
                    mShowSingleTimerWarning = true;
                    mPendingSingleModeValue = newValueBool;

                    mActiveDialog = singleModeWarningDialog(newValueBool);
                    mActiveDialog.show();

                    return false;
                }
            }

            case KEY_TIMER_VIBRATE, KEY_TIMER_VOLUME_BUTTONS_ACTION, KEY_TIMER_POWER_BUTTON_ACTION, KEY_TIMER_FLIP_ACTION,
                 KEY_DISPLAY_WARNING_BEFORE_DELETING_TIMER, KEY_DISPLAY_LOW_ALARM_VOLUME_WARNING ->
                Utils.performHapticFeedback(getView(), HapticFeedbackConstantsCompat.VIRTUAL_KEY);
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
            case KEY_TIMER_DISPLAY_CUSTOMIZATION -> animateAndShowFragment(new TimerDisplayCustomizationFragment());

            case KEY_TIMER_DURATION_FONT -> selectCustomFile(mTimerDurationFontPref, fontPickerLauncher,
                SettingsDAO.getTimerDurationFont(mPrefs), KEY_TIMER_DURATION_FONT, true, null);

            case KEY_TIMER_RINGTONE -> startActivity(RingtonePickerActivity.createTimerRingtonePickerIntent(context));
        }

        return true;
    }

    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference pref) {
        if (pref instanceof AutoSilenceDurationPreference autoSilenceDurationPreference) {
            int currentValue = autoSilenceDurationPreference.getAutoSilenceDuration();
            AutoSilenceDurationDialogFragment dialogFragment = AutoSilenceDurationDialogFragment.newInstance(pref.getKey(), currentValue);
            AutoSilenceDurationDialogFragment.show(getParentFragmentManager(), dialogFragment);
        } else if (pref instanceof VolumeCrescendoDurationPreference volumeCrescendoDurationPreference) {
            int currentDelay = volumeCrescendoDurationPreference.getVolumeCrescendoDuration();
            VolumeCrescendoDurationDialogFragment dialogFragment =
                VolumeCrescendoDurationDialogFragment.newInstance(pref.getKey(), currentDelay);
            VolumeCrescendoDurationDialogFragment.show(getParentFragmentManager(), dialogFragment);
        } else if (pref instanceof TimerAddTimeButtonValuePreference timerAddTimeButtonValuePreference) {
            int currentValue = timerAddTimeButtonValuePreference.getAddTimeButtonValue();
            TimerAddTimeButtonDialogFragment dialogFragment = TimerAddTimeButtonDialogFragment.newInstance(pref.getKey(), currentValue);
            TimerAddTimeButtonDialogFragment.show(getParentFragmentManager(), dialogFragment);
        } else {
            super.onDisplayPreferenceDialog(pref);
        }
    }

    private void setupPreferences() {
        mTimerDisplayCustomizationPref.setOnPreferenceClickListener(this);

        mTimerDurationFontPref.setTitle(getString(SettingsDAO.getTimerDurationFont(mPrefs) == null
            ? R.string.custom_font_title
            : R.string.custom_font_title_variant));
        mTimerDurationFontPref.setOnPreferenceClickListener(this);

        mTimerCreationViewStylePref.setOnPreferenceChangeListener(this);
        mTimerCreationViewStylePref.setSummary(mTimerCreationViewStylePref.getEntry());

        mTimerRingtonePref.setOnPreferenceClickListener(this);

        mTimerVibratePref.setVisible(DeviceUtils.hasVibrator(requireContext()));
        mTimerVibratePref.setOnPreferenceChangeListener(this);

        mTimerVolumeButtonsActionPref.setOnPreferenceChangeListener(this);

        mTimerPowerButtonActionPref.setOnPreferenceChangeListener(this);

        SensorManager sensorManager = requireContext().getApplicationContext().getSystemService(SensorManager.class);
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) {
            mTimerFlipActionPref.setChecked(false);
            mTimerShakeActionPref.setChecked(false);
            mTimerFlipActionPref.setVisible(false);
            mTimerShakeActionPref.setVisible(false);
        } else {
            mTimerFlipActionPref.setOnPreferenceChangeListener(this);
            mTimerShakeActionPref.setOnPreferenceChangeListener(this);
            mTimerShakeIntensityPref.setVisible(SettingsDAO.isShakeActionForTimersEnabled(mPrefs));
        }

        mSingleTimerModePref.setOnPreferenceChangeListener(this);

        mSortTimerPref.setVisible(!SettingsDAO.isSingleTimerModeEnabled(mPrefs));
        mSortTimerPref.setOnPreferenceChangeListener(this);
        mSortTimerPref.setSummary(mSortTimerPref.getEntry());

        mDisplayWarningBeforeDeletingTimerPref.setVisible(!SettingsDAO.isSingleTimerModeEnabled(mPrefs));
        mDisplayWarningBeforeDeletingTimerPref.setOnPreferenceChangeListener(this);

        mDisplayLowAlarmVolumeWarningPref.setOnPreferenceChangeListener(this);
    }

    private void setupFragmentResultListeners() {
        FragmentManager parentFragmentManager = getParentFragmentManager();
        LifecycleOwner viewLifecycleOwner = getViewLifecycleOwner();

        // Timer auto silence duration preference
        parentFragmentManager.setFragmentResultListener(AutoSilenceDurationDialogFragment.REQUEST_KEY, viewLifecycleOwner,
            (requestKey, bundle) -> {
                String key = bundle.getString(AutoSilenceDurationDialogFragment.RESULT_PREF_KEY);
                int newValue = bundle.getInt(AutoSilenceDurationDialogFragment.AUTO_SILENCE_DURATION_VALUE);

                if (key != null) {
                    AutoSilenceDurationPreference pref = findPreference(key);
                    if (pref != null) {
                        pref.setAutoSilenceDuration(newValue);
                    }
                }
            });

        // Timer volume crescendo duration preference
        parentFragmentManager.setFragmentResultListener(VolumeCrescendoDurationDialogFragment.REQUEST_KEY, viewLifecycleOwner,
            (requestKey, bundle) -> {
                String key = bundle.getString(VolumeCrescendoDurationDialogFragment.RESULT_PREF_KEY);
                int newValue = bundle.getInt(VolumeCrescendoDurationDialogFragment.VOLUME_CRESCENDO_DURATION_VALUE);

                if (key != null) {
                    VolumeCrescendoDurationPreference pref = findPreference(key);
                    if (pref != null) {
                        pref.setVolumeCrescendoDuration(newValue);
                    }
                }
            });

        // Add time button value preference
        parentFragmentManager.setFragmentResultListener(TimerAddTimeButtonDialogFragment.REQUEST_KEY, viewLifecycleOwner,
            (requestKey, bundle) -> {
                String key = bundle.getString(TimerAddTimeButtonDialogFragment.RESULT_PREF_KEY);
                int newValue = bundle.getInt(TimerAddTimeButtonDialogFragment.ADD_TIME_BUTTON_VALUE);

                if (key != null) {
                    TimerAddTimeButtonValuePreference pref = findPreference(key);
                    if (pref != null) {
                        pref.setAddTimeButtonValue(newValue);
                    }
                }
            });
    }

    private AlertDialog singleModeWarningDialog(boolean newValue) {
        String confirmAction = getString(R.string.confirm_action_prompt);

        return CustomDialog.create(
            requireContext(),
            null,
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_error),
            getString(R.string.warning),
            getString(R.string.single_timer_mode_warning_message, confirmAction),
            null,
            getString(android.R.string.ok),
            (d, w) -> {
                List<Timer> timersToDelete = new ArrayList<>(DataModel.getDataModel().getTimers());

                for (Timer timer : timersToDelete) {
                    DataModel.getDataModel().removeTimer(timer);
                }

                mSortTimerPref.setVisible(!newValue);
                mDisplayWarningBeforeDeletingTimerPref.setVisible(!newValue);
                mPrefs.edit().putBoolean(KEY_SINGLE_TIMER_MODE, newValue).apply();
                mSingleTimerModePref.setChecked(newValue);

                mShowSingleTimerWarning = false;
            },
            getString(android.R.string.cancel),
            null,
            null,
            null,
            (alertDialog -> alertDialog.setOnDismissListener(d -> mShowSingleTimerWarning = false)),
            CustomDialog.SoftInputMode.NONE
        );
    }

    private void updateRingtonePreferences() {
        mTimerRingtonePref.setSummary(DataModel.getDataModel().getTimerRingtoneTitle());
        mTimerRingtonePref.setIntent(RingtonePickerActivity.createTimerRingtonePickerIntent(requireContext()));
    }

    private void nullifyAllPrefs() {
        mTimerDisplayCustomizationPref = null;
        mTimerDurationFontPref = null;
        mTimerCreationViewStylePref = null;
        mTimerRingtonePref = null;
        mTimerVibratePref = null;
        mTimerVolumeButtonsActionPref = null;
        mTimerPowerButtonActionPref = null;
        mTimerFlipActionPref = null;
        mTimerShakeActionPref = null;
        mTimerShakeIntensityPref = null;
        mSortTimerPref = null;
        mDisplayWarningBeforeDeletingTimerPref = null;
        mDisplayLowAlarmVolumeWarningPref = null;
    }

}

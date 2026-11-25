// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.best.deskclock.R;
import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.databinding.CalendarProfileEditActivityBinding;
import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.ringtone.RingtonePickerActivity;
import com.best.deskclock.uicomponents.CollapsingToolbarBaseActivity;
import com.best.deskclock.uicomponents.toast.CustomToast;
import com.best.deskclock.utils.RingtoneUtils;
import com.best.deskclock.utils.SdkUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * Creates or edits a calendar alert profile.
 */
public final class CalendarProfileEditActivity extends CollapsingToolbarBaseActivity {

    private static final String EXTRA_PROFILE_ID = "extra_profile_id";
    private static final String STATE_CALENDAR_ID = "state_calendar_id";
    private static final String STATE_ACCOUNT_NAME = "state_account_name";
    private static final String STATE_CALENDAR_NAME = "state_calendar_name";
    private static final String STATE_RINGTONE = "state_ringtone";

    private CalendarProfileEditActivityBinding mBinding;

    /** The profile being edited; its form fields are only copied in on save. */
    private CalendarProfile mProfile;

    private boolean mIsNew;

    private final ActivityResultLauncher<Intent> mRingtonePickerLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                final Uri uri = SdkUtils.isAtLeastAndroid13()
                    ? result.getData().getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri.class)
                    : result.getData().getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                mProfile.ringtone = uri != null ? uri : RingtoneUtils.RINGTONE_SILENT;
                bindRingtone();
            }
        });

    private final ActivityResultLauncher<String> mPermissionLauncher = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                chooseCalendar();
            } else {
                CustomToast.showLong(this, getString(R.string.calendar_alert_permission_denied));
            }
        });

    public static Intent createIntent(Context context, long profileId) {
        return new Intent(context, CalendarProfileEditActivity.class).putExtra(EXTRA_PROFILE_ID, profileId);
    }

    @Override
    protected String getActivityTitle() {
        return getString(mIsNew ? R.string.calendar_alert_new_profile : R.string.calendar_alert_edit_profile);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mBinding = CalendarProfileEditActivityBinding.inflate(getLayoutInflater(), mBaseBinding.contentFrame, true);

        final long profileId = getIntent().getLongExtra(EXTRA_PROFILE_ID, CalendarProfile.INVALID_ID);
        // Reading one row is quick; the form needs it before it can be shown.
        final CalendarProfile stored = profileId == CalendarProfile.INVALID_ID
            ? null
            : CalendarProfile.getProfile(getContentResolver(), profileId);
        mIsNew = stored == null;
        mProfile = stored != null ? stored : new CalendarProfile();

        if (savedInstanceState != null) {
            mProfile.calendarId = savedInstanceState.getLong(STATE_CALENDAR_ID, mProfile.calendarId);
            mProfile.accountName = savedInstanceState.getString(STATE_ACCOUNT_NAME, mProfile.accountName);
            mProfile.calendarName = savedInstanceState.getString(STATE_CALENDAR_NAME, mProfile.calendarName);
            final String ringtone = savedInstanceState.getString(STATE_RINGTONE);
            mProfile.ringtone = ringtone == null ? null : Uri.parse(ringtone);
        }

        bindForm(savedInstanceState == null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_CALENDAR_ID, mProfile.calendarId);
        outState.putString(STATE_ACCOUNT_NAME, mProfile.accountName);
        outState.putString(STATE_CALENDAR_NAME, mProfile.calendarName);
        outState.putString(STATE_RINGTONE, mProfile.ringtone == null ? null : mProfile.ringtone.toString());
    }

    @Override
    protected void onDestroy() {
        mBinding = null;
        super.onDestroy();
    }

    /**
     * @param fillInputs {@code false} after a configuration change, where the views restore their
     *                   own text and checked state
     */
    private void bindForm(boolean fillInputs) {
        if (fillInputs) {
            mBinding.nameInput.setText(mProfile.name);
            mBinding.enabledSwitch.setChecked(mProfile.enabled);
            mBinding.regexInput.setText(mProfile.titleRegex);
            mBinding.vibrateSwitch.setChecked(mProfile.vibrate);
            mBinding.maxDurationInput.setText(mProfile.autoSilenceDuration > 0
                ? String.valueOf(Math.max(1, mProfile.autoSilenceDuration / 60)) : "");

            final boolean snoozeAllowed = mProfile.snoozeDuration != CalendarProfile.SNOOZE_DISABLED;
            mBinding.snoozeSwitch.setChecked(snoozeAllowed);
            mBinding.snoozeMinutesInput.setText(String.valueOf(snoozeAllowed
                ? mProfile.snoozeDuration : CalendarProfile.DEFAULT_SNOOZE_MINUTES));
            mBinding.maxSnoozesInput.setText(mProfile.maxSnoozeCount > 0 ? String.valueOf(mProfile.maxSnoozeCount) : "");

            if (mProfile.isSlideDismissForced()) {
                mBinding.dismissGroup.check(mBinding.dismissSlide.getId());
            } else if (mProfile.isButtonDismissForced()) {
                mBinding.dismissGroup.check(mBinding.dismissButton.getId());
            } else {
                mBinding.dismissGroup.check(mBinding.dismissDefault.getId());
            }
        }

        // Volume
        final AudioManager audioManager = getSystemService(AudioManager.class);
        final int maxVolume = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM));
        mBinding.volumeSlider.setValueTo(maxVolume);
        if (fillInputs) {
            mBinding.volumeSlider.setValue(Math.max(0, Math.min(maxVolume, mProfile.alarmVolume)));
        }
        updateVolumeLabel(maxVolume);
        mBinding.volumeSlider.addOnChangeListener((slider, value, fromUser) -> updateVolumeLabel(maxVolume));

        // Lookahead window
        if (fillInputs) {
            mBinding.lookaheadSlider.setValue(CalendarProfile.clampLookaheadDays(mProfile.lookaheadDays));
        }
        updateLookaheadLabel();
        mBinding.lookaheadSlider.addOnChangeListener((slider, value, fromUser) -> updateLookaheadLabel());

        // Snooze fields follow the snooze switch
        mBinding.snoozeFields.setVisibility(mBinding.snoozeSwitch.isChecked() ? VISIBLE : GONE);
        mBinding.snoozeSwitch.setOnCheckedChangeListener((button, checked) ->
            mBinding.snoozeFields.setVisibility(checked ? VISIBLE : GONE));

        // Regex validation as the user types
        mBinding.regexInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                validateRegex();
            }
        });

        bindCalendar();
        bindRingtone();

        mBinding.calendarButton.setOnClickListener(v -> {
            if (DeviceCalendars.hasPermission(this)) {
                chooseCalendar();
            } else {
                mPermissionLauncher.launch(Manifest.permission.READ_CALENDAR);
            }
        });

        mBinding.ringtoneButton.setOnClickListener(v -> mRingtonePickerLauncher.launch(
            RingtonePickerActivity.createCalendarProfileRingtonePickerIntent(this, mProfile.ringtone)));

        mBinding.saveButton.setOnClickListener(v -> save());

        mBinding.deleteButton.setVisibility(mIsNew ? GONE : VISIBLE);
        mBinding.deleteButton.setOnClickListener(v -> confirmDelete());
    }

    private void updateLookaheadLabel() {
        mBinding.lookaheadLabel.setText(getString(R.string.calendar_alert_lookahead,
            (int) mBinding.lookaheadSlider.getValue()));
    }

    private void updateVolumeLabel(int maxVolume) {
        mBinding.volumeLabel.setText(getString(R.string.calendar_alert_volume,
            (int) mBinding.volumeSlider.getValue(), maxVolume));
    }

    private void bindCalendar() {
        if (mProfile.calendarId < 0) {
            mBinding.calendarButton.setText(R.string.calendar_alert_choose_calendar);
        } else if (TextUtils.isEmpty(mProfile.accountName) || mProfile.accountName.equals(mProfile.calendarName)) {
            mBinding.calendarButton.setText(mProfile.calendarName);
        } else {
            mBinding.calendarButton.setText(mProfile.calendarName + "\n" + mProfile.accountName);
        }
    }

    private void bindRingtone() {
        final Uri uri = mProfile.ringtone != null
            ? mProfile.ringtone
            : DataModel.getDataModel().getAlarmRingtoneUriFromSettings();
        mBinding.ringtoneButton.setText(DataModel.getDataModel().getRingtoneTitle(uri));
    }

    private boolean validateRegex() {
        final String regex = textOf(mBinding.regexInput.getText());
        final boolean valid = CalendarProfile.compileTitlePattern(regex) != null;
        mBinding.regexLayout.setError(valid ? null : getString(R.string.calendar_alert_title_regex_invalid));
        return valid;
    }

    private void chooseCalendar() {
        final Context appContext = getApplicationContext();
        AppExecutors.getDiskIO().execute(() -> {
            final List<DeviceCalendars.CalendarInfo> calendars = DeviceCalendars.getCalendars(appContext);
            AppExecutors.getMainThread().post(() -> {
                if (mBinding == null || isFinishing()) {
                    return;
                }
                if (calendars.isEmpty()) {
                    CustomToast.showLong(this, getString(R.string.calendar_alert_no_calendars));
                    return;
                }

                final CharSequence[] labels = new CharSequence[calendars.size()];
                int checked = -1;
                for (int i = 0; i < calendars.size(); i++) {
                    final DeviceCalendars.CalendarInfo info = calendars.get(i);
                    labels[i] = info.accountName.isEmpty() || info.accountName.equals(info.displayName)
                        ? info.displayName
                        : info.displayName + " (" + info.accountName + ")";
                    if (info.id == mProfile.calendarId) {
                        checked = i;
                    }
                }

                new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.calendar_alert_choose_calendar)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        final DeviceCalendars.CalendarInfo info = calendars.get(which);
                        mProfile.calendarId = info.id;
                        mProfile.accountName = info.accountName;
                        mProfile.calendarName = info.displayName;
                        bindCalendar();
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
        });
    }

    private void save() {
        if (mProfile.calendarId < 0) {
            CustomToast.showLong(this, getString(R.string.calendar_alert_error_no_calendar));
            return;
        }
        if (!validateRegex()) {
            mBinding.regexInput.requestFocus();
            return;
        }

        final Integer maxDurationMinutes = parseOptionalNumber(mBinding.maxDurationInput.getText());
        final Integer snoozeMinutes = parseOptionalNumber(mBinding.snoozeMinutesInput.getText());
        final Integer maxSnoozes = parseOptionalNumber(mBinding.maxSnoozesInput.getText());
        if (maxDurationMinutes == null || snoozeMinutes == null || maxSnoozes == null) {
            CustomToast.showLong(this, getString(R.string.calendar_alert_error_number));
            return;
        }

        final String name = textOf(mBinding.nameInput.getText()).trim();
        mProfile.name = name.isEmpty() ? mProfile.calendarName : name;
        mProfile.enabled = mBinding.enabledSwitch.isChecked();
        mProfile.titleRegex = textOf(mBinding.regexInput.getText());
        mProfile.alarmVolume = (int) mBinding.volumeSlider.getValue();
        mProfile.lookaheadDays = CalendarProfile.clampLookaheadDays((int) mBinding.lookaheadSlider.getValue());
        mProfile.vibrate = mBinding.vibrateSwitch.isChecked();
        mProfile.autoSilenceDuration = maxDurationMinutes > 0
            ? maxDurationMinutes * 60
            : CalendarProfile.RING_UNTIL_DISMISSED;

        if (mBinding.snoozeSwitch.isChecked()) {
            mProfile.snoozeDuration = snoozeMinutes > 0 ? snoozeMinutes : CalendarProfile.DEFAULT_SNOOZE_MINUTES;
            mProfile.maxSnoozeCount = Math.max(0, maxSnoozes);
        } else {
            mProfile.snoozeDuration = CalendarProfile.SNOOZE_DISABLED;
            mProfile.maxSnoozeCount = 0;
        }

        final int dismissId = mBinding.dismissGroup.getCheckedRadioButtonId();
        if (dismissId == mBinding.dismissSlide.getId()) {
            mProfile.dismissMethod = CalendarProfile.DISMISS_METHOD_SLIDE;
        } else if (dismissId == mBinding.dismissButton.getId()) {
            mProfile.dismissMethod = CalendarProfile.DISMISS_METHOD_BUTTON;
        } else {
            mProfile.dismissMethod = CalendarProfile.DISMISS_METHOD_DEFAULT;
        }

        final Context appContext = getApplicationContext();
        final CalendarProfile profile = mProfile;
        final boolean isNew = mIsNew;
        AppExecutors.getDiskIO().execute(() -> {
            if (isNew) {
                profile.addProfile(appContext.getContentResolver());
            } else {
                profile.updateProfile(appContext.getContentResolver());
            }
            CalendarAlertSync.syncProfile(appContext, profile.id);
            CalendarAlertWorker.ensureScheduled(appContext);
        });
        finish();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
            .setMessage(R.string.calendar_alert_delete_confirm)
            .setPositiveButton(R.string.calendar_alert_delete_profile, (dialog, which) -> {
                final Context appContext = getApplicationContext();
                final long profileId = mProfile.id;
                AppExecutors.getDiskIO().execute(() -> {
                    CalendarAlertSync.deleteProfile(appContext, profileId);
                    CalendarAlertWorker.ensureScheduled(appContext);
                });
                finish();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * @return the number, 0 for an empty field, or {@code null} if the text is not a number
     */
    @Nullable
    private static Integer parseOptionalNumber(@Nullable Editable text) {
        final String value = textOf(text).trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String textOf(@Nullable Editable text) {
        return text == null ? "" : text.toString();
    }
}

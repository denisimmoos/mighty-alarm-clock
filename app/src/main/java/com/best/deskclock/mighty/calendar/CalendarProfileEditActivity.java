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
 * Creates or edits a calendar alarm profile.
 */
public final class CalendarProfileEditActivity extends CollapsingToolbarBaseActivity {

    private static final String EXTRA_PROFILE_ID = "extra_profile_id";
    private static final String EXTRA_CLONE_FROM = "extra_clone_from";
    private static final String STATE_CALENDAR_ID = "state_calendar_id";
    private static final String STATE_ACCOUNT_NAME = "state_account_name";
    private static final String STATE_CALENDAR_NAME = "state_calendar_name";
    private static final String STATE_RINGTONE = "state_ringtone";
    private static final String STATE_WIFI_NETWORKS = "state_wifi_networks";
    private static final String STATE_ALL_DAY_MINUTES = "state_all_day_minutes";

    /** Default time for all-day events when they are first switched on: 8:00. */
    private static final int DEFAULT_ALL_DAY_MINUTES = 8 * 60;

    private CalendarProfileEditActivityBinding mBinding;

    /** The profile being edited; its form fields are only copied in on save. */
    private CalendarProfile mProfile;

    private boolean mIsNew;

    /** Set once Save or Delete was tapped, so a second tap does not save twice. */
    private boolean mFinishing;

    /** Time of day chosen for all-day events, kept even while the switch is off. */
    private int mAllDayMinutes = DEFAULT_ALL_DAY_MINUTES;

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

    /** Wi-Fi networks chosen in the editor, kept while the Wi-Fi switch is off. */
    private final List<CalendarWifi.Network> mWifiNetworks = new java.util.ArrayList<>();

    /** What to do after the location permission was granted. */
    private boolean mChooseWifiAfterPermission;

    private final ActivityResultLauncher<String[]> mLocationPermissionLauncher = registerForActivityResult(
        new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            if (CalendarWifi.hasForegroundPermission(this)) {
                if (mChooseWifiAfterPermission) {
                    mChooseWifiAfterPermission = false;
                    showNearbyNetworks();
                } else {
                    requestBackgroundLocation();
                }
            } else {
                mChooseWifiAfterPermission = false;
                CustomToast.showLong(this, getString(R.string.calendar_alarm_wifi_permission));
            }
            updateWifiPermission();
        });

    private final ActivityResultLauncher<String> mBackgroundLocationLauncher = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> updateWifiPermission());

    private final ActivityResultLauncher<String> mWriteCalendarLauncher = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            if (!granted && mBinding != null) {
                mBinding.extendOnSnoozeSwitch.setChecked(false);
                CustomToast.showLong(this, getString(R.string.calendar_alarm_extend_permission));
            }
        });

    private final ActivityResultLauncher<String> mPermissionLauncher = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                chooseCalendar();
            } else {
                CustomToast.showLong(this, getString(R.string.calendar_alarm_permission_denied));
            }
        });

    public static Intent createIntent(Context context, long profileId) {
        return new Intent(context, CalendarProfileEditActivity.class).putExtra(EXTRA_PROFILE_ID, profileId);
    }

    /**
     * @return an intent that opens a new profile with all settings of the given one, except its
     * title pattern, which the user enters next.
     */
    public static Intent createCloneIntent(Context context, long sourceProfileId) {
        return new Intent(context, CalendarProfileEditActivity.class).putExtra(EXTRA_CLONE_FROM, sourceProfileId);
    }

    @Override
    protected String getActivityTitle() {
        return getString(mIsNew ? R.string.calendar_alarm_new_profile : R.string.calendar_alarm_edit_profile);
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

        final long cloneFrom = getIntent().getLongExtra(EXTRA_CLONE_FROM, CalendarProfile.INVALID_ID);
        final CalendarProfile source = cloneFrom == CalendarProfile.INVALID_ID
            ? null : CalendarProfile.getProfile(getContentResolver(), cloneFrom);
        final boolean cloning = mIsNew && source != null;
        if (cloning) {
            // A copy with every setting, saved as a new profile. Only the title pattern is left
            // empty, so the copy matches other events.
            final long now = System.currentTimeMillis();
            source.id = CalendarProfile.INVALID_ID;
            source.name = getString(R.string.calendar_alarm_clone_name,
                source.name.isEmpty() ? source.calendarName : source.name);
            source.titleRegex = "";
            source.createdAt = now;
            source.updatedAt = now;
            mProfile = source;
        }

        if (savedInstanceState != null) {
            mProfile.calendarId = savedInstanceState.getLong(STATE_CALENDAR_ID, mProfile.calendarId);
            mProfile.accountName = savedInstanceState.getString(STATE_ACCOUNT_NAME, mProfile.accountName);
            mProfile.calendarName = savedInstanceState.getString(STATE_CALENDAR_NAME, mProfile.calendarName);
            final String ringtone = savedInstanceState.getString(STATE_RINGTONE);
            mProfile.ringtone = ringtone == null ? null : Uri.parse(ringtone);
        }
        if (mProfile.allDayMinutes >= 0) {
            mAllDayMinutes = mProfile.allDayMinutes;
        }
        mWifiNetworks.addAll(CalendarWifi.parse(savedInstanceState != null
            ? savedInstanceState.getString(STATE_WIFI_NETWORKS, mProfile.wifiNetworks) : mProfile.wifiNetworks));
        if (savedInstanceState != null) {
            mAllDayMinutes = savedInstanceState.getInt(STATE_ALL_DAY_MINUTES, mAllDayMinutes);
        }

        bindForm(savedInstanceState == null);
        if (cloning && savedInstanceState == null) {
            // Ready to type the new pattern.
            mBinding.regexInput.requestFocus();
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_CALENDAR_ID, mProfile.calendarId);
        outState.putString(STATE_ACCOUNT_NAME, mProfile.accountName);
        outState.putString(STATE_CALENDAR_NAME, mProfile.calendarName);
        outState.putString(STATE_RINGTONE, mProfile.ringtone == null ? null : mProfile.ringtone.toString());
        outState.putInt(STATE_ALL_DAY_MINUTES, mAllDayMinutes);
        outState.putString(STATE_WIFI_NETWORKS, CalendarWifi.toJson(mWifiNetworks));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The user may come back from the app settings with location allowed.
        if (mBinding != null) {
            updateWifiPermission();
        }
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
            mBinding.ignoreCaseSwitch.setChecked(mProfile.ignoreCase);
            mBinding.skipDeclinedSwitch.setChecked(mProfile.skipDeclined);
            mBinding.skipTentativeSwitch.setChecked(mProfile.skipTentative);
            mBinding.showDetailsLockedSwitch.setChecked(mProfile.showDetailsLocked);
            mBinding.offsetSwitch.setChecked(mProfile.offsetEnabled);
            final int offset = CalendarProfile.clampOffsetMinutes(mProfile.offsetMinutes);
            mBinding.offsetHoursInput.setText(offset >= 60 ? String.valueOf(offset / 60) : "");
            mBinding.offsetMinutesInput.setText(String.valueOf(offset % 60));
            mBinding.ringAtStartSwitch.setChecked(mProfile.ringAtStart);
            mBinding.ringAtEndSwitch.setChecked(mProfile.ringAtEnd);
            mBinding.extendOnSnoozeSwitch.setChecked(mProfile.extendOnSnooze);
            mBinding.escalationSwitch.setChecked(mProfile.escalation);
            mBinding.wifiSwitch.setChecked(mProfile.wifiCheck);
            mBinding.wifiOffRingsSwitch.setChecked(mProfile.wifiOffRings);
            mBinding.allDaySwitch.setChecked(mProfile.allDayMinutes >= 0);
            mBinding.vibrateSwitch.setChecked(mProfile.vibrate);
            mBinding.flashSwitch.setChecked(mProfile.flash);
            mBinding.vibrateOnlySwitch.setChecked(mProfile.vibrateOnly && !mProfile.flashOnly);
            mBinding.flashOnlySwitch.setChecked(mProfile.flashOnly);
            // The slider moves in steps of 10.
            mBinding.vibrationStrengthSlider.setValue(
                Math.round(CalendarProfile.clampVibrationStrength(mProfile.vibrationStrength) / 10f) * 10f);
            mBinding.maxDurationInput.setText(mProfile.autoSilenceDuration > 0
                ? String.valueOf(Math.max(1, mProfile.autoSilenceDuration / 60)) : "");

            final boolean snoozeAllowed = mProfile.snoozeDuration != CalendarProfile.SNOOZE_DISABLED;
            mBinding.snoozeSwitch.setChecked(snoozeAllowed);
            mBinding.snoozeMinutesInput.setText(String.valueOf(snoozeAllowed
                ? mProfile.snoozeDuration : CalendarProfile.DEFAULT_SNOOZE_MINUTES));
            final int[] choices = snoozeAllowed ? mProfile.getSnoozeChoices() : new int[0];
            if (choices.length == 3) {
                mBinding.snoozeMinutesInput.setText(String.valueOf(choices[0]));
                mBinding.snoozeMinutes2Input.setText(String.valueOf(choices[1]));
                mBinding.snoozeMinutes3Input.setText(String.valueOf(choices[2]));
            } else {
                mBinding.snoozeMinutes2Input.setText(String.valueOf(mProfile.snoozeMinutes2));
                mBinding.snoozeMinutes3Input.setText(String.valueOf(mProfile.snoozeMinutes3));
            }
            mBinding.maxSnoozesInput.setText(mProfile.maxSnoozeCount > 0 ? String.valueOf(mProfile.maxSnoozeCount) : "");
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

        // Vibrate only hides the sound settings and always vibrates
        updateVibrateOnly();
        mBinding.vibrateOnlySwitch.setOnCheckedChangeListener((button, checked) -> {
            // Vibrate only and flash only exclude each other.
            if (checked) {
                mBinding.flashOnlySwitch.setChecked(false);
            }
            updateVibrateOnly();
        });
        mBinding.flashOnlySwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                mBinding.vibrateOnlySwitch.setChecked(false);
            }
            updateVibrateOnly();
        });

        // Vibration strength: a short buzz lets the user feel each value
        updateVibrationStrength();
        mBinding.vibrateSwitch.setOnCheckedChangeListener((button, checked) -> updateVibrationStrength());
        mBinding.vibrationStrengthSlider.addOnChangeListener((slider, value, fromUser) -> {
            updateVibrationStrengthLabel();
            if (fromUser) {
                CalendarVibration.preview(this, (int) value);
            }
        });
        mBinding.vibrationStrengthHelp.setVisibility(CalendarVibration.hasAmplitudeControl(this) ? GONE : VISIBLE);

        // The offset minutes follow the offset switch
        mBinding.offsetFields.setVisibility(mBinding.offsetSwitch.isChecked() ? VISIBLE : GONE);
        mBinding.offsetSwitch.setOnCheckedChangeListener((button, checked) ->
            mBinding.offsetFields.setVisibility(checked ? VISIBLE : GONE));

        // Extending the event on snooze belongs to the alarm at the end, only makes sense when the
        // profile snoozes, and needs calendar write access.
        updateExtendOnSnoozeVisibility();
        mBinding.ringAtEndSwitch.setOnCheckedChangeListener((button, checked) -> updateExtendOnSnoozeVisibility());
        mBinding.extendOnSnoozeSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !CalendarEventExtender.hasPermission(this)) {
                mWriteCalendarLauncher.launch(Manifest.permission.WRITE_CALENDAR);
            }
        });

        // Wi-Fi: the networks and the permission hint follow the Wi-Fi switch
        mBinding.wifiFields.setVisibility(mBinding.wifiSwitch.isChecked() ? VISIBLE : GONE);
        mBinding.wifiSwitch.setOnCheckedChangeListener((button, checked) -> {
            mBinding.wifiFields.setVisibility(checked ? VISIBLE : GONE);
            updateWifiPermission();
        });
        mBinding.wifiChooseButton.setOnClickListener(v -> chooseNearbyNetwork());
        mBinding.wifiEnterButton.setOnClickListener(v -> enterNetwork());
        mBinding.wifiPermissionButton.setOnClickListener(v -> requestLocation(false));
        bindWifiNetworks();
        updateWifiPermission();

        // Snooze fields follow the snooze switch
        mBinding.snoozeFields.setVisibility(mBinding.snoozeSwitch.isChecked() ? VISIBLE : GONE);
        mBinding.snoozeSwitch.setOnCheckedChangeListener((button, checked) -> {
            mBinding.snoozeFields.setVisibility(checked ? VISIBLE : GONE);
            updateExtendOnSnoozeVisibility();
        });

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

        mBinding.ignoreCaseSwitch.setOnCheckedChangeListener((button, checked) -> validateRegex());
        mBinding.allDaySwitch.setOnCheckedChangeListener((button, checked) -> updateAllDay());
        mBinding.allDayTimeButton.setOnClickListener(v -> chooseAllDayTime());
        updateAllDay();

        mBinding.saveButton.setOnClickListener(v -> save());

        mBinding.deleteButton.setVisibility(mIsNew ? GONE : VISIBLE);
        mBinding.deleteButton.setOnClickListener(v -> confirmDelete());
    }

    private void updateLookaheadLabel() {
        mBinding.lookaheadLabel.setText(getString(R.string.calendar_alarm_lookahead,
            (int) mBinding.lookaheadSlider.getValue()));
    }

    private void updateVolumeLabel(int maxVolume) {
        mBinding.volumeLabel.setText(getString(R.string.calendar_alarm_volume,
            (int) mBinding.volumeSlider.getValue(), maxVolume));
    }

    private void bindCalendar() {
        if (mProfile.calendarId < 0) {
            mBinding.calendarButton.setText(R.string.calendar_alarm_choose_calendar);
        } else if (TextUtils.isEmpty(mProfile.accountName) || mProfile.accountName.equals(mProfile.calendarName)) {
            mBinding.calendarButton.setText(mProfile.calendarName);
        } else {
            mBinding.calendarButton.setText(mProfile.calendarName + "\n" + mProfile.accountName);
        }
    }

    /**
     * Vibrate only hides the sound settings and always vibrates. Flash only hides the sound and
     * vibration settings and always blinks the flashlight.
     */
    private void updateVibrateOnly() {
        final boolean vibrateOnly = mBinding.vibrateOnlySwitch.isChecked();
        final boolean flashOnly = mBinding.flashOnlySwitch.isChecked();
        mBinding.soundFields.setVisibility(vibrateOnly || flashOnly ? GONE : VISIBLE);
        if (vibrateOnly) {
            mBinding.vibrateSwitch.setChecked(true);
        }
        mBinding.vibrateSwitch.setVisibility(flashOnly ? GONE : VISIBLE);
        mBinding.vibrateSwitch.setEnabled(!vibrateOnly);
        if (flashOnly) {
            mBinding.flashSwitch.setChecked(true);
        }
        mBinding.flashSwitch.setEnabled(!flashOnly);
        // A flash only alarm has nothing that could get louder or stronger.
        mBinding.escalationSwitch.setVisibility(flashOnly ? GONE : VISIBLE);
        mBinding.escalationHelp.setVisibility(flashOnly ? GONE : VISIBLE);
        updateVibrationStrength();
    }

    private void updateVibrationStrength() {
        final boolean vibrates = !mBinding.flashOnlySwitch.isChecked()
            && (mBinding.vibrateSwitch.isChecked() || mBinding.vibrateOnlySwitch.isChecked());
        mBinding.vibrationStrengthFields.setVisibility(vibrates ? VISIBLE : GONE);
        updateVibrationStrengthLabel();
    }

    private void updateVibrationStrengthLabel() {
        mBinding.vibrationStrengthLabel.setText(getString(R.string.calendar_alarm_vibration_strength,
            (int) mBinding.vibrationStrengthSlider.getValue()));
    }

    // ---------------------------------------------------------------------------------------
    // Wi-Fi

    private void bindWifiNetworks() {
        final android.widget.LinearLayout container = mBinding.wifiNetworksContainer;
        container.removeAllViews();
        if (mWifiNetworks.isEmpty()) {
            final android.widget.TextView none = new android.widget.TextView(this);
            none.setText(R.string.calendar_alarm_wifi_none);
            none.setTextSize(15);
            none.setMinHeight(dp(40));
            none.setGravity(android.view.Gravity.CENTER_VERTICAL);
            container.addView(none);
            return;
        }
        for (CalendarWifi.Network network : new java.util.ArrayList<>(mWifiNetworks)) {
            final android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            final android.widget.TextView text = new android.widget.TextView(this);
            text.setText(describe(network));
            text.setTextSize(16);
            row.addView(text, new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final android.widget.ImageButton remove = new android.widget.ImageButton(this);
            remove.setImageResource(R.drawable.ic_delete);
            remove.setBackgroundResource(android.R.color.transparent);
            remove.setContentDescription(getString(R.string.calendar_alarm_wifi_remove, describe(network)));
            remove.setOnClickListener(v -> {
                mWifiNetworks.remove(network);
                bindWifiNetworks();
            });
            row.addView(remove, new android.widget.LinearLayout.LayoutParams(dp(48), dp(48)));
            container.addView(row);
        }
    }

    private String describe(CalendarWifi.Network network) {
        if (network.bssid.isEmpty()) {
            return getString(R.string.calendar_alarm_wifi_match_name, network.ssid);
        }
        if (network.ssid.isEmpty()) {
            return getString(R.string.calendar_alarm_wifi_match_mac, network.bssid);
        }
        return network.ssid + " · " + network.bssid;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void addNetwork(CalendarWifi.Network network) {
        if (!network.isEmpty() && !mWifiNetworks.contains(network)) {
            mWifiNetworks.add(network);
        }
        bindWifiNetworks();
    }

    private void updateWifiPermission() {
        if (mBinding == null) {
            return;
        }
        final boolean missing = !CalendarWifi.hasBackgroundPermission(this);
        final boolean locationOff = !missing && !CalendarWifi.isLocationEnabled(this);
        mBinding.wifiPermissionFields.setVisibility(mBinding.wifiSwitch.isChecked() && (missing || locationOff)
            ? VISIBLE : GONE);
        mBinding.wifiPermissionText.setText(missing
            ? R.string.calendar_alarm_wifi_permission : R.string.calendar_alarm_wifi_location_off);
        mBinding.wifiPermissionButton.setVisibility(missing ? VISIBLE : GONE);
    }

    /**
     * Asks for location: first while the app is open, then, on Android 10 and newer, all the time.
     */
    private void requestLocation(boolean thenChooseWifi) {
        if (!CalendarWifi.hasForegroundPermission(this)) {
            mChooseWifiAfterPermission = thenChooseWifi;
            mLocationPermissionLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        } else if (thenChooseWifi) {
            showNearbyNetworks();
        } else {
            requestBackgroundLocation();
        }
    }

    private void requestBackgroundLocation() {
        if (!SdkUtils.isAtLeastAndroid10() || CalendarWifi.hasBackgroundPermission(this)) {
            updateWifiPermission();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.calendar_alarm_wifi_background_title)
            .setMessage(R.string.calendar_alarm_wifi_background_message)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                if (SdkUtils.isAtLeastAndroid11()) {
                    // Android 11 and newer only offer "Allow all the time" in the app's settings.
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null)));
                        return;
                    } catch (android.content.ActivityNotFoundException ignored) {
                        // Fall back to asking directly below.
                    }
                }
                mBackgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void chooseNearbyNetwork() {
        if (!CalendarWifi.hasForegroundPermission(this)) {
            requestLocation(true);
        } else {
            showNearbyNetworks();
        }
    }

    private void showNearbyNetworks() {
        final Context appContext = getApplicationContext();
        AppExecutors.getDiskIO().execute(() -> {
            final List<CalendarWifi.Seen> seen = CalendarWifi.getNearbyNetworks(appContext);
            AppExecutors.getMainThread().post(() -> {
                if (mBinding == null || isFinishing()) {
                    return;
                }
                if (seen.isEmpty()) {
                    new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.calendar_alarm_wifi_list_title)
                        .setMessage(CalendarWifi.isLocationEnabled(this)
                            ? R.string.calendar_alarm_wifi_list_empty : R.string.calendar_alarm_wifi_location_off)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return;
                }
                final CharSequence[] items = new CharSequence[seen.size()];
                for (int i = 0; i < seen.size(); i++) {
                    final CalendarWifi.Seen network = seen.get(i);
                    final String name = network.ssid.isEmpty()
                        ? getString(R.string.calendar_alarm_wifi_hidden_name) : network.ssid;
                    items[i] = name + "\n" + network.bssid + " · " + network.level + " dBm"
                        + (network.connected ? " · " + getString(R.string.calendar_alarm_wifi_connected) : "");
                }
                new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.calendar_alarm_wifi_list_title)
                    .setItems(items, (dialog, which) -> chooseMatch(seen.get(which)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
        });
    }

    /**
     * Lets the user pick whether a network from the list is matched by name, MAC address, or both.
     */
    private void chooseMatch(CalendarWifi.Seen seen) {
        if (seen.ssid.isEmpty()) {
            // A hidden network only has its MAC address.
            addNetwork(new CalendarWifi.Network("", seen.bssid));
            return;
        }
        final CharSequence[] options = {
            getString(R.string.calendar_alarm_wifi_match_name, seen.ssid),
            getString(R.string.calendar_alarm_wifi_match_mac, seen.bssid),
            getString(R.string.calendar_alarm_wifi_match_both)
        };
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.calendar_alarm_wifi_match_title, seen.ssid))
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0 -> addNetwork(new CalendarWifi.Network(seen.ssid, ""));
                    case 1 -> addNetwork(new CalendarWifi.Network("", seen.bssid));
                    default -> addNetwork(new CalendarWifi.Network(seen.ssid, seen.bssid));
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void enterNetwork() {
        final android.widget.LinearLayout fields = new android.widget.LinearLayout(this);
        fields.setOrientation(android.widget.LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        final android.widget.EditText ssid = new android.widget.EditText(this);
        ssid.setHint(R.string.calendar_alarm_wifi_ssid_hint);
        ssid.setSingleLine(true);
        final android.widget.EditText bssid = new android.widget.EditText(this);
        bssid.setHint(R.string.calendar_alarm_wifi_bssid_hint);
        bssid.setSingleLine(true);
        fields.addView(ssid);
        fields.addView(bssid);
        final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.calendar_alarm_wifi_enter)
            .setView(fields)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                final CalendarWifi.Network network =
                    new CalendarWifi.Network(textOf(ssid.getText()), textOf(bssid.getText()));
                if (network.isEmpty() || !CalendarWifi.isValidBssid(network.bssid)) {
                    CustomToast.showLong(this, getString(R.string.calendar_alarm_wifi_error_entry));
                    return;
                }
                addNetwork(network);
                dialog.dismiss();
            }));
        dialog.show();
    }

    private void updateAllDay() {
        mBinding.allDayTimeButton.setVisibility(mBinding.allDaySwitch.isChecked() ? VISIBLE : GONE);
        final java.util.Calendar time = java.util.Calendar.getInstance();
        time.set(java.util.Calendar.HOUR_OF_DAY, mAllDayMinutes / 60);
        time.set(java.util.Calendar.MINUTE, mAllDayMinutes % 60);
        mBinding.allDayTimeButton.setText(getString(R.string.calendar_alarm_all_day_time,
            android.text.format.DateFormat.getTimeFormat(this).format(time.getTime())));
    }

    private void chooseAllDayTime() {
        new android.app.TimePickerDialog(this, (picker, hour, minute) -> {
            mAllDayMinutes = hour * 60 + minute;
            updateAllDay();
        }, mAllDayMinutes / 60, mAllDayMinutes % 60, android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    private void bindRingtone() {
        final Uri uri = mProfile.ringtone != null
            ? mProfile.ringtone
            : DataModel.getDataModel().getAlarmRingtoneUriFromSettings();
        mBinding.ringtoneButton.setText(DataModel.getDataModel().getRingtoneTitle(uri));
    }

    private boolean validateRegex() {
        final String regex = textOf(mBinding.regexInput.getText());
        final boolean valid = CalendarProfile.compileTitlePattern(regex, mBinding.ignoreCaseSwitch.isChecked()) != null;
        mBinding.regexLayout.setError(valid ? null : getString(R.string.calendar_alarm_title_regex_invalid));
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
                    CustomToast.showLong(this, getString(R.string.calendar_alarm_no_calendars));
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
                    .setTitle(R.string.calendar_alarm_choose_calendar)
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
        if (mFinishing) {
            return;
        }
        if (mProfile.calendarId < 0) {
            CustomToast.showLong(this, getString(R.string.calendar_alarm_error_no_calendar));
            return;
        }
        if (!validateRegex()) {
            mBinding.regexInput.requestFocus();
            return;
        }

        final Integer maxDurationMinutes = parseOptionalNumber(mBinding.maxDurationInput.getText());
        final Integer snoozeMinutes = parseOptionalNumber(mBinding.snoozeMinutesInput.getText());
        final Integer snoozeMinutes2 = parseOptionalNumber(mBinding.snoozeMinutes2Input.getText());
        final Integer snoozeMinutes3 = parseOptionalNumber(mBinding.snoozeMinutes3Input.getText());
        final Integer maxSnoozes = parseOptionalNumber(mBinding.maxSnoozesInput.getText());
        if (maxDurationMinutes == null || snoozeMinutes == null || snoozeMinutes2 == null
                || snoozeMinutes3 == null || maxSnoozes == null) {
            CustomToast.showLong(this, getString(R.string.calendar_alarm_error_number));
            return;
        }

        if (mBinding.wifiSwitch.isChecked() && mWifiNetworks.isEmpty()) {
            CustomToast.showLong(this, getString(R.string.calendar_alarm_wifi_error_no_network));
            return;
        }

        // Empty snooze fields fall back to 5, 10 and 20 minutes.
        final int snooze1 = snoozeMinutes > 0 ? snoozeMinutes : CalendarProfile.DEFAULT_SNOOZE_MINUTES;
        final int snooze2 = snoozeMinutes2 > 0 ? snoozeMinutes2 : CalendarProfile.DEFAULT_SNOOZE_MINUTES_2;
        final int snooze3 = snoozeMinutes3 > 0 ? snoozeMinutes3 : CalendarProfile.DEFAULT_SNOOZE_MINUTES_3;
        if (mBinding.snoozeSwitch.isChecked() && !CalendarProfile.isSnoozeOrderValid(snooze1, snooze2, snooze3)) {
            CustomToast.showLong(this, getString(R.string.calendar_alarm_error_snooze_order));
            mBinding.snoozeMinutes2Input.requestFocus();
            return;
        }

        final Integer offsetHours = parseOptionalNumber(mBinding.offsetHoursInput.getText());
        final Integer offsetRest = parseOptionalNumber(mBinding.offsetMinutesInput.getText());
        final Integer offsetMinutes = offsetHours == null || offsetRest == null
            ? null : offsetHours * 60 + offsetRest;
        final boolean offsetEnabled = mBinding.offsetSwitch.isChecked();
        if (offsetEnabled && (offsetMinutes == null || offsetMinutes < CalendarProfile.MIN_OFFSET_MINUTES
            || offsetMinutes > CalendarProfile.MAX_OFFSET_MINUTES)) {
            CustomToast.showLong(this, getString(R.string.calendar_alarm_error_offset));
            mBinding.offsetMinutesInput.requestFocus();
            return;
        }

        final String name = textOf(mBinding.nameInput.getText()).trim();
        mProfile.name = name.isEmpty() ? mProfile.calendarName : name;
        mProfile.enabled = mBinding.enabledSwitch.isChecked();
        mProfile.titleRegex = textOf(mBinding.regexInput.getText());
        mProfile.ignoreCase = mBinding.ignoreCaseSwitch.isChecked();
        mProfile.skipDeclined = mBinding.skipDeclinedSwitch.isChecked();
        mProfile.skipTentative = mBinding.skipTentativeSwitch.isChecked();
        mProfile.showDetailsLocked = mBinding.showDetailsLockedSwitch.isChecked();
        mProfile.offsetEnabled = offsetEnabled;
        if (offsetEnabled) {
            mProfile.offsetMinutes = offsetMinutes;
        }
        mProfile.ringAtStart = mBinding.ringAtStartSwitch.isChecked();
        mProfile.ringAtEnd = mBinding.ringAtEndSwitch.isChecked();
        // Without an alarm at the end or without snooze there is nothing to extend.
        mProfile.extendOnSnooze = mBinding.extendOnSnoozeSwitch.isChecked()
            && mBinding.ringAtEndSwitch.isChecked() && mBinding.snoozeSwitch.isChecked();
        mProfile.wifiCheck = mBinding.wifiSwitch.isChecked();
        mProfile.wifiOffRings = mBinding.wifiOffRingsSwitch.isChecked();
        mProfile.wifiNetworks = CalendarWifi.toJson(mWifiNetworks);
        // Flash only cannot escalate, see updateVibrateOnly().
        mProfile.escalation = mBinding.escalationSwitch.isChecked() && !mBinding.flashOnlySwitch.isChecked();
        mProfile.allDayMinutes = mBinding.allDaySwitch.isChecked() ? mAllDayMinutes : CalendarProfile.ALL_DAY_IGNORED;
        mProfile.alarmVolume = (int) mBinding.volumeSlider.getValue();
        mProfile.lookaheadDays = CalendarProfile.clampLookaheadDays((int) mBinding.lookaheadSlider.getValue());
        mProfile.vibrate = mBinding.vibrateSwitch.isChecked();
        mProfile.flash = mBinding.flashSwitch.isChecked();
        mProfile.vibrateOnly = mBinding.vibrateOnlySwitch.isChecked();
        mProfile.flashOnly = mBinding.flashOnlySwitch.isChecked();
        mProfile.vibrationStrength = (int) mBinding.vibrationStrengthSlider.getValue();
        mProfile.autoSilenceDuration = maxDurationMinutes > 0
            ? maxDurationMinutes * 60
            : CalendarProfile.RING_UNTIL_DISMISSED;

        if (mBinding.snoozeSwitch.isChecked()) {
            mProfile.snoozeDuration = snooze1;
            mProfile.snoozeMinutes2 = snooze2;
            mProfile.snoozeMinutes3 = snooze3;
            mProfile.maxSnoozeCount = Math.max(0, maxSnoozes);
        } else {
            mProfile.snoozeDuration = CalendarProfile.SNOOZE_DISABLED;
            mProfile.maxSnoozeCount = 0;
        }

        // A calendar alarm is always dismissed with its Dismiss button; there is no slider.
        mProfile.dismissMethod = CalendarProfile.DISMISS_METHOD_BUTTON;

        final Context appContext = getApplicationContext();
        final CalendarProfile profile = mProfile;
        final boolean isNew = mIsNew;
        mFinishing = true;
        CalendarAlarmSync.EXECUTOR.execute(() -> {
            if (isNew) {
                profile.addProfile(appContext.getContentResolver());
            } else {
                profile.updateProfile(appContext.getContentResolver());
            }
            CalendarAlarmSync.syncProfile(appContext, profile.id);
            CalendarAlarmWorker.ensureScheduled(appContext);
        });
        finish();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
            .setMessage(R.string.calendar_alarm_delete_confirm)
            .setPositiveButton(R.string.calendar_alarm_delete_profile, (dialog, which) -> {
                if (mFinishing) {
                    return;
                }
                mFinishing = true;
                final Context appContext = getApplicationContext();
                final long profileId = mProfile.id;
                // Remove the profile right away, so it is gone from every list even while a
                // calendar check is still running. Its alarms are cleaned up right after.
                AppExecutors.getDiskIO().execute(() -> {
                    CalendarProfile.deleteProfile(appContext.getContentResolver(), profileId);
                    AppExecutors.getMainThread().post(() -> {
                        CustomToast.show(appContext, appContext.getString(R.string.calendar_alarm_profile_deleted));
                        finish();
                    });
                    CalendarAlarmSync.EXECUTOR.execute(() -> {
                        CalendarAlarmSync.deleteProfile(appContext, profileId);
                        CalendarAlarmWorker.ensureScheduled(appContext);
                    });
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * @return the number, 0 for an empty field, or {@code null} if the text is not a number
     */
    @Nullable
    private void updateExtendOnSnoozeVisibility() {
        mBinding.extendOnSnoozeSwitch.setVisibility(
            mBinding.ringAtEndSwitch.isChecked() && mBinding.snoozeSwitch.isChecked() ? VISIBLE : GONE);
    }

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

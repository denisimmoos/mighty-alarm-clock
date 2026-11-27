// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.settings;

import static com.best.deskclock.settings.PreferencesKeys.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.best.deskclock.R;
import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.mighty.backup.BackupWorker;
import com.best.deskclock.mighty.backup.JsonBackupManager;
import com.best.deskclock.mighty.mute.GlobalMuteController;
import com.best.deskclock.mighty.stats.DeviceStats;
import com.best.deskclock.provider.EventLogStore;
import com.best.deskclock.uicomponents.toast.CustomToast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Date;
import java.util.List;

/**
 * Settings screen exposing the "Mighty" fork features that don't fit naturally into the existing
 * settings screens: device statistics, the event log, global mute, scheduled backup and
 * the clock timezone globe toggle.
 */
public class MightyFeaturesFragment extends ScreenFragment
    implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {

    Preference mDeviceStatsPref;
    Preference mViewEventLogPref;
    Preference mExportEventLogPref;
    Preference mGlobalMutePref;
    Preference mBackupFolderPref;
    SwitchPreferenceCompat mBackupScheduleEnabledPref;
    ListPreference mBackupIntervalPref;
    Preference mBackupNowPref;
    SwitchPreferenceCompat mDisplayTimezoneGlobePref;

    private final ActivityResultLauncher<Intent> mBackupFolderPicker = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                return;
            }
            final Uri treeUri = result.getData().getData();
            if (treeUri == null) {
                return;
            }
            requireContext().getContentResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            JsonBackupManager.setTreeUri(requireContext(), treeUri);
            refreshSummaries();
        });

    @Override
    protected String getFragmentTitle() {
        return getString(R.string.mighty_features_title);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.settings_mighty_features);

        mDeviceStatsPref = findPreference(KEY_MIGHTY_DEVICE_STATS);
        mViewEventLogPref = findPreference(KEY_MIGHTY_VIEW_EVENT_LOG);
        mExportEventLogPref = findPreference(KEY_MIGHTY_EXPORT_EVENT_LOG);
        mGlobalMutePref = findPreference(KEY_MIGHTY_GLOBAL_MUTE);
        mBackupFolderPref = findPreference(KEY_MIGHTY_BACKUP_FOLDER);
        mBackupScheduleEnabledPref = findPreference(KEY_MIGHTY_BACKUP_SCHEDULE_ENABLED);
        mBackupIntervalPref = findPreference(KEY_MIGHTY_BACKUP_INTERVAL_HOURS);
        mBackupNowPref = findPreference(KEY_MIGHTY_BACKUP_NOW);
        mDisplayTimezoneGlobePref = findPreference(KEY_DISPLAY_TIMEZONE_GLOBE);

        setupPreferences();
    }

    @Override
    public void onResume() {
        super.onResume();

        refreshSummaries();
    }

    @Override
    public void onDestroy() {
        nullifyPreferenceListeners(mDeviceStatsPref, mViewEventLogPref, mExportEventLogPref, mGlobalMutePref, mBackupFolderPref,
            mBackupScheduleEnabledPref, mBackupIntervalPref, mBackupNowPref, mDisplayTimezoneGlobePref);

        nullifyAllPrefs();

        super.onDestroy();
    }

    private void setupPreferences() {
        mDeviceStatsPref.setOnPreferenceClickListener(this);
        mViewEventLogPref.setOnPreferenceClickListener(this);
        mExportEventLogPref.setOnPreferenceClickListener(this);
        mGlobalMutePref.setOnPreferenceClickListener(this);
        mBackupFolderPref.setOnPreferenceClickListener(this);
        mBackupScheduleEnabledPref.setOnPreferenceChangeListener(this);
        mBackupIntervalPref.setOnPreferenceChangeListener(this);
        mBackupNowPref.setOnPreferenceClickListener(this);
        mDisplayTimezoneGlobePref.setOnPreferenceChangeListener(this);

        refreshSummaries();
    }

    private void refreshSummaries() {
        if (!isAdded()) {
            return;
        }
        final Context context = requireContext();

        mDeviceStatsPref.setSummary(getString(R.string.mighty_device_stats_summary,
            DeviceStats.getTotalRingCount(context), DeviceStats.getTotalSnoozeCount(context)));

        updateGlobalMuteSummary();

        final Uri backupUri = JsonBackupManager.getTreeUri(context);
        mBackupFolderPref.setSummary(backupUri != null ? backupUri.toString() : getString(R.string.mighty_backup_folder_not_set));
        mBackupScheduleEnabledPref.setChecked(JsonBackupManager.isScheduleEnabled(context));
        mBackupIntervalPref.setValue(String.valueOf(JsonBackupManager.getIntervalHours(context)));
        mBackupIntervalPref.setSummary(mBackupIntervalPref.getEntry());
    }

    private void updateGlobalMuteSummary() {
        final Context context = requireContext();
        if (GlobalMuteController.isActive(context)) {
            final CharSequence time = DateFormat.getTimeFormat(context).format(new Date(GlobalMuteController.getEndTime(context)));
            mGlobalMutePref.setSummary(getString(R.string.mighty_global_mute_active_summary, time));
        } else {
            mGlobalMutePref.setSummary(getString(R.string.mighty_global_mute_inactive_summary));
        }
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference pref) {
        final Context context = requireContext();

        switch (pref.getKey()) {
            case KEY_MIGHTY_DEVICE_STATS -> showDeviceStatsDialog();

            case KEY_MIGHTY_VIEW_EVENT_LOG -> showEventLogDialog();

            case KEY_MIGHTY_EXPORT_EVENT_LOG -> exportEventLog();

            case KEY_MIGHTY_GLOBAL_MUTE -> showGlobalMuteDialog();

            case KEY_MIGHTY_BACKUP_FOLDER -> mBackupFolderPicker.launch(createOpenTreeIntent());

            case KEY_MIGHTY_BACKUP_NOW -> {
                final Context appContext = context.getApplicationContext();
                AppExecutors.getDiskIO().execute(() -> {
                    final boolean success = JsonBackupManager.exportNow(appContext);
                    AppExecutors.getMainThread().post(() ->
                        CustomToast.show(context, success ? R.string.mighty_backup_now_success : R.string.mighty_backup_now_failure));
                });
            }
        }

        return true;
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference pref, Object newValue) {
        final Context context = requireContext();

        switch (pref.getKey()) {
            case KEY_MIGHTY_BACKUP_SCHEDULE_ENABLED -> {
                final boolean enabled = (boolean) newValue;
                JsonBackupManager.setScheduleEnabled(context, enabled);
                if (enabled) {
                    BackupWorker.schedulePeriodic(context, JsonBackupManager.getIntervalHours(context));
                } else {
                    BackupWorker.cancel(context);
                }
            }

            case KEY_MIGHTY_BACKUP_INTERVAL_HOURS -> {
                final int hours = Integer.parseInt((String) newValue);
                JsonBackupManager.setIntervalHours(context, hours);
                if (JsonBackupManager.isScheduleEnabled(context)) {
                    BackupWorker.schedulePeriodic(context, hours);
                }
                final int index = mBackupIntervalPref.findIndexOfValue((String) newValue);
                if (index >= 0) {
                    mBackupIntervalPref.setSummary(mBackupIntervalPref.getEntries()[index]);
                }
            }
        }

        return true;
    }

    private Intent createOpenTreeIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    }

    private void showDeviceStatsDialog() {
        final Context context = requireContext();
        final String message = getString(R.string.mighty_device_stats_dialog_message,
            DeviceStats.getTotalRingCount(context), DeviceStats.getTotalSnoozeCount(context));

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_device_stats_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.mighty_reset_stats, (dialog, which) -> {
                DeviceStats.reset(context);
                refreshSummaries();
            })
            .show();
    }

    private void showEventLogDialog() {
        final Context context = requireContext();
        final Context appContext = context.getApplicationContext();

        AppExecutors.getDiskIO().execute(() -> {
            final List<EventLogStore.EventLogEntry> events =
                EventLogStore.getEvents(appContext.getContentResolver());

            AppExecutors.getMainThread().post(() -> {
                if (!isAdded()) {
                    return;
                }

                if (events.isEmpty()) {
                    new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.mighty_view_event_log)
                        .setMessage(R.string.mighty_event_log_empty)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return;
                }

                final java.text.DateFormat dateFormat =
                    android.text.format.DateFormat.getMediumDateFormat(context);
                final java.text.DateFormat timeFormat =
                    android.text.format.DateFormat.getTimeFormat(context);
                final CharSequence[] items = new CharSequence[events.size()];

                for (int i = 0; i < events.size(); i++) {
                    final EventLogStore.EventLogEntry entry = events.get(i);
                    final Date date = new Date(entry.timestamp);
                    final String when = dateFormat.format(date) + " " + timeFormat.format(date);
                    final String label = entry.alarmLabel == null || entry.alarmLabel.isEmpty()
                        ? getString(R.string.mighty_event_log_untitled_alarm)
                        : entry.alarmLabel;
                    final String type = formatEventType(entry.eventType);
                    final String details = entry.details;

                    if (details == null || details.isEmpty()) {
                        items[i] = getString(R.string.mighty_event_log_entry_no_details, when, type, label);
                    } else {
                        items[i] = getString(R.string.mighty_event_log_entry, when, type, label, details);
                    }
                }

                new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.mighty_view_event_log)
                    .setItems(items, null)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.mighty_export_event_log, (dialog, which) -> exportEventLog())
                    .show();
            });
        });
    }

    private String formatEventType(String eventType) {
        if (eventType == null) {
            return "";
        }
        if (EventLogStore.EVENT_STOPWATCH_STOPPED.equals(eventType)) {
            return getString(R.string.mighty_event_log_stopwatch_stopped);
        }
        return eventType;
    }

    private void exportEventLog() {
        final Context context = requireContext();
        final Context appContext = context.getApplicationContext();

        AppExecutors.getDiskIO().execute(() -> {
            final String csv = EventLogStore.exportCsv(appContext.getContentResolver());

            AppExecutors.getMainThread().post(() -> {
                if (!isAdded()) {
                    return;
                }
                final Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "MightyCalendarAlarm event log");
                shareIntent.putExtra(Intent.EXTRA_TEXT, csv);
                startActivity(Intent.createChooser(shareIntent, getString(R.string.mighty_export_event_log)));
            });
        });
    }

    private void showGlobalMuteDialog() {
        final Context context = requireContext();

        final CharSequence[] options = {
            getString(R.string.mighty_mute_1h_silent),
            getString(R.string.mighty_mute_1h_vibrate),
            getString(R.string.mighty_mute_2h_silent),
            getString(R.string.mighty_mute_2h_vibrate),
            getString(R.string.mighty_mute_until_tomorrow_silent),
            getString(R.string.mighty_mute_until_tomorrow_vibrate),
            getString(R.string.mighty_mute_disable),
        };

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_global_mute_title)
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0 -> GlobalMuteController.muteForOneHour(context, GlobalMuteController.Mode.SILENT);
                    case 1 -> GlobalMuteController.muteForOneHour(context, GlobalMuteController.Mode.VIBRATE);
                    case 2 -> GlobalMuteController.muteForTwoHours(context, GlobalMuteController.Mode.SILENT);
                    case 3 -> GlobalMuteController.muteForTwoHours(context, GlobalMuteController.Mode.VIBRATE);
                    case 4 -> GlobalMuteController.muteUntilTomorrowMorning(context, GlobalMuteController.Mode.SILENT);
                    case 5 -> GlobalMuteController.muteUntilTomorrowMorning(context, GlobalMuteController.Mode.VIBRATE);
                    case 6 -> GlobalMuteController.disable(context);
                    default -> {
                        // No-op.
                    }
                }
                updateGlobalMuteSummary();
            })
            .show();
    }

    private void nullifyAllPrefs() {
        mDeviceStatsPref = null;
        mViewEventLogPref = null;
        mExportEventLogPref = null;
        mGlobalMutePref = null;
        mBackupFolderPref = null;
        mBackupScheduleEnabledPref = null;
        mBackupIntervalPref = null;
        mBackupNowPref = null;
        mDisplayTimezoneGlobePref = null;
    }
}

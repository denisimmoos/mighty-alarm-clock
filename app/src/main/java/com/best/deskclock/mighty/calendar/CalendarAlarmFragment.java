// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static com.best.deskclock.uidata.UiDataModel.Tab.CALENDAR_ALARM;

import android.Manifest;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.best.deskclock.R;
import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.base.DeskClockFragment;
import com.best.deskclock.databinding.CalendarAlarmFragmentBinding;
import com.best.deskclock.databinding.CalendarProfileItemBinding;
import com.best.deskclock.provider.CalendarAlarmEvent;
import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.uicomponents.CustomTooltip;
import com.best.deskclock.uicomponents.toast.CustomToast;

import java.util.ArrayList;
import java.util.List;

/**
 * The Calendar Alarm tab: lists the calendar profiles. Each profile turns matching calendar
 * events into fullscreen alarms.
 */
public final class CalendarAlarmFragment extends DeskClockFragment {

    private CalendarAlarmFragmentBinding mBinding;
    private ProfileAdapter mAdapter;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            // Sync writes many rows at once; reload once they are done.
            mHandler.removeCallbacks(mReload);
            mHandler.postDelayed(mReload, 300);
        }
    };

    private final Runnable mReload = this::reload;

    /** Updates the Wi-Fi line when the phone's Wi-Fi is turned on or off or connects. */
    private final android.content.BroadcastReceiver mWifiReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            mHandler.removeCallbacks(mReload);
            mHandler.postDelayed(mReload, 300);
        }
    };

    private static android.content.IntentFilter wifiFilter() {
        final android.content.IntentFilter filter = new android.content.IntentFilter(
            android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION);
        return filter;
    }

    private final ActivityResultLauncher<String> mPermissionLauncher = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                syncAndReload();
                CalendarAlarmWorker.ensureScheduled(requireContext());
            } else if (getContext() != null) {
                CustomToast.showLong(requireContext(), getString(R.string.calendar_alarm_permission_denied));
            }
            updatePermissionCard();
        });

    /**
     * The public no-arg constructor required by all fragments.
     */
    public CalendarAlarmFragment() {
        super(CALENDAR_ALARM);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mBinding = CalendarAlarmFragmentBinding.inflate(inflater, container, false);

        mAdapter = new ProfileAdapter();
        mBinding.profilesList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mBinding.profilesList.setAdapter(mAdapter);

        mBinding.permissionButton.setOnClickListener(v ->
            mPermissionLauncher.launch(Manifest.permission.READ_CALENDAR));

        return mBinding.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        requireContext().getContentResolver()
            .registerContentObserver(CalendarProfile.CONTENT_URI, true, mObserver);
        requireContext().getContentResolver()
            .registerContentObserver(CalendarAlarmEvent.CONTENT_URI, true, mObserver);
        androidx.core.content.ContextCompat.registerReceiver(requireContext(), mWifiReceiver, wifiFilter(),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePermissionCard();
        syncAndReload();
    }

    @Override
    public void onStop() {
        requireContext().getContentResolver().unregisterContentObserver(mObserver);
        requireContext().unregisterReceiver(mWifiReceiver);
        mHandler.removeCallbacks(mReload);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        mBinding = null;
        super.onDestroyView();
    }

    private void updatePermissionCard() {
        if (mBinding != null) {
            mBinding.permissionCard.setVisibility(DeviceCalendars.hasPermission(requireContext()) ? GONE : VISIBLE);
            updateStatus();
        }
    }

    /**
     * Shows when the calendar was last read, and a hint if battery saving may delay the checks.
     */
    private void updateStatus() {
        if (mBinding == null || getContext() == null) {
            return;
        }
        final android.content.Context context = requireContext();
        final long lastCheck = CalendarAlarmStatus.getLastCheck(context);
        final StringBuilder status = new StringBuilder(lastCheck > 0
            ? getString(R.string.calendar_alarm_last_check, DateUtils.formatDateTime(context, lastCheck,
                DateUtils.isToday(lastCheck) ? DateUtils.FORMAT_SHOW_TIME
                    : DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH))
            : getString(R.string.calendar_alarm_never_checked));
        final boolean batteryOptimized = CalendarAlarmStatus.isBatteryOptimized(context);
        if (batteryOptimized) {
            status.append('\n').append(getString(R.string.calendar_alarm_battery_hint));
        }
        mBinding.statusText.setText(status);
        mBinding.statusText.setClickable(batteryOptimized);
        mBinding.statusText.setOnClickListener(batteryOptimized ? v -> startActivity(
            new Intent(context, com.best.deskclock.settings.PermissionsManagementActivity.class)) : null);
    }

    /**
     * Shows the stored state right away, then checks the calendars and shows the result.
     */
    private void syncAndReload() {
        reload();
        final android.content.Context appContext = requireContext().getApplicationContext();
        CalendarAlarmSync.EXECUTOR.execute(() -> CalendarAlarmSync.syncAll(appContext));
    }

    private void reload() {
        if (getContext() == null) {
            return;
        }
        final android.content.Context appContext = requireContext().getApplicationContext();
        AppExecutors.getDiskIO().execute(() -> {
            final List<ProfileRow> rows = new ArrayList<>();
            final long now = System.currentTimeMillis();
            final List<DeviceCalendars.CalendarInfo> calendars = DeviceCalendars.hasPermission(appContext)
                ? DeviceCalendars.getCalendars(appContext) : null;
            for (CalendarProfile profile : CalendarProfile.getProfiles(appContext.getContentResolver())) {
                int count = 0;
                long next = -1;
                for (CalendarAlarmEvent event : CalendarAlarmEvent.getEventsForProfile(
                    appContext.getContentResolver(), profile.id)) {
                    // A switched off profile rings for nothing.
                    if (profile.enabled && event.begin > now && event.userEnabled
                        && !event.isDismissed()) {
                        count++;
                        if (next < 0) {
                            next = event.begin;
                        }
                    }
                }
                rows.add(new ProfileRow(profile, count, next, isCalendarMissing(profile, calendars),
                    CalendarWifiGate.status(appContext, profile, false)));
            }
            AppExecutors.getMainThread().post(() -> {
                if (mBinding == null) {
                    return;
                }
                mAdapter.setRows(rows);
                updateStatus();
                mBinding.emptyView.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
            });
        });
    }

    private void openProfileEvents(CalendarProfile profile) {
        startActivity(CalendarProfileEventsActivity.createIntent(requireContext(), profile.id));
    }

    private void openProfileEditor(long profileId) {
        startActivity(CalendarProfileEditActivity.createIntent(requireContext(), profileId));
    }

    private void setProfileEnabled(CalendarProfile profile, boolean enabled) {
        final android.content.Context appContext = requireContext().getApplicationContext();
        final long profileId = profile.id;
        CalendarAlarmSync.EXECUTOR.execute(() -> {
            // Change only the switch. The list's copy may be older than what the profile settings
            // or the calendar check saved since; saving that copy would undo their changes.
            final CalendarProfile current = CalendarProfile.getProfile(appContext.getContentResolver(), profileId);
            if (current == null) {
                return;
            }
            current.enabled = enabled;
            current.updateProfile(appContext.getContentResolver());
            CalendarAlarmSync.syncProfile(appContext, profileId);
            CalendarAlarmWorker.ensureScheduled(appContext);
        });
    }

    @Override
    public void onFabClick() {
        openProfileEditor(CalendarProfile.INVALID_ID);
    }

    @Override
    public void onUpdateFab(@NonNull ImageView fab) {
        fab.setVisibility(VISIBLE);
        fab.setImageResource(R.drawable.ic_add);
        fab.setContentDescription(getString(R.string.calendar_alarm_add_profile));
        fab.setOnLongClickListener(v -> {
            CustomTooltip.showAbove(v, fab.getContentDescription().toString(), true);
            return true;
        });
    }

    @Override
    public void onUpdateFabButtons(@NonNull ImageView left, @NonNull ImageView right) {
        left.setVisibility(INVISIBLE);
        right.setVisibility(INVISIBLE);
    }

    private record ProfileRow(CalendarProfile profile, int upcomingCount, long nextBegin, boolean calendarMissing,
                              CalendarWifiGate.Status wifiStatus) {
    }

    /**
     * @return {@code true} if the profile's calendar was removed or now belongs to another account.
     */
    private static boolean isCalendarMissing(CalendarProfile profile, List<DeviceCalendars.CalendarInfo> calendars) {
        if (profile.calendarId < 0) {
            return true;
        }
        if (calendars == null || calendars.isEmpty()) {
            // Without access nothing can be said; the permission card covers that case.
            return false;
        }
        for (DeviceCalendars.CalendarInfo info : calendars) {
            if (info.id == profile.calendarId) {
                return !profile.accountName.isEmpty() && !profile.accountName.equals(info.accountName);
            }
        }
        return true;
    }

    private final class ProfileAdapter extends RecyclerView.Adapter<ProfileViewHolder> {

        private final List<ProfileRow> mRows = new ArrayList<>();

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        void setRows(List<ProfileRow> rows) {
            mRows.clear();
            mRows.addAll(rows);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ProfileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ProfileViewHolder(CalendarProfileItemBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ProfileViewHolder holder, int position) {
            holder.bind(mRows.get(position));
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }
    }

    private final class ProfileViewHolder extends RecyclerView.ViewHolder {

        private final CalendarProfileItemBinding mItem;

        ProfileViewHolder(CalendarProfileItemBinding binding) {
            super(binding.getRoot());
            mItem = binding;
        }

        void bind(ProfileRow row) {
            final CalendarProfile profile = row.profile();
            final android.content.Context context = itemView.getContext();

            mItem.profileName.setText(profile.name.isEmpty() ? profile.calendarName : profile.name);
            mItem.profileEventCount.setText(context.getResources().getQuantityString(
                R.plurals.calendar_alarm_event_count, row.upcomingCount(), row.upcomingCount(),
                CalendarProfile.clampLookaheadDays(profile.lookaheadDays)));

            if (row.nextBegin() > 0) {
                final String when = DateUtils.formatDateTime(context, row.nextBegin(),
                    DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY
                        | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH
                        | DateUtils.FORMAT_SHOW_TIME);
                mItem.profileNext.setText(context.getString(R.string.calendar_alarm_next, when));
            } else {
                mItem.profileNext.setText(R.string.calendar_alarm_no_next);
            }

            final StringBuilder source = new StringBuilder();
            if (row.calendarMissing()) {
                source.append(context.getString(R.string.calendar_alarm_calendar_missing));
            } else {
                source.append(profile.accountName);
                if (!profile.calendarName.isEmpty() && !profile.calendarName.equals(profile.accountName)) {
                    source.append(" · ").append(profile.calendarName);
                }
            }
            source.append(" · ").append(profile.titleRegex);
            mItem.profileCalendar.setText(source);

            final String wifi = CalendarWifiGate.describe(context, row.wifiStatus());
            CalendarWifiGate.showStatus(mItem.profileWifi, row.wifiStatus(), wifi);

            mItem.profileSwitch.setOnCheckedChangeListener(null);
            mItem.profileSwitch.setChecked(profile.enabled);
            mItem.profileSwitch.setOnCheckedChangeListener((button, checked) -> setProfileEnabled(profile, checked));

            mItem.getRoot().setOnClickListener(v -> openProfileEvents(profile));
            mItem.profileSettings.setOnClickListener(v -> openProfileEditor(profile.id));

            itemView.setAlpha(profile.enabled ? 1f : 0.6f);
        }
    }
}

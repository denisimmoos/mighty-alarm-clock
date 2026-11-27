// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.best.deskclock.R;
import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.databinding.CalendarEventItemBinding;
import com.best.deskclock.databinding.CalendarProfileEventsActivityBinding;
import com.best.deskclock.provider.CalendarAlarmEvent;
import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.uicomponents.CollapsingToolbarBaseActivity;
import com.best.deskclock.uicomponents.toast.CustomToast;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists the events of one calendar profile within its lookahead window, each with its own on/off switch.
 */
public final class CalendarProfileEventsActivity extends CollapsingToolbarBaseActivity {

    private static final String EXTRA_PROFILE_ID = "extra_profile_id";

    private CalendarProfileEventsActivityBinding mBinding;
    private EventAdapter mAdapter;
    private long mProfileId;
    private String mTitle = "";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
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
    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            mHandler.removeCallbacks(mReload);
            mHandler.postDelayed(mReload, 300);
        }
    };

    public static Intent createIntent(Context context, long profileId) {
        return new Intent(context, CalendarProfileEventsActivity.class).putExtra(EXTRA_PROFILE_ID, profileId);
    }

    @Override
    protected String getActivityTitle() {
        return mTitle;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mProfileId = getIntent().getLongExtra(EXTRA_PROFILE_ID, CalendarProfile.INVALID_ID);
        mBinding = CalendarProfileEventsActivityBinding.inflate(getLayoutInflater(), mBaseBinding.contentFrame, true);

        mAdapter = new EventAdapter();
        mBinding.eventsList.setLayoutManager(new LinearLayoutManager(this));
        mBinding.eventsList.setAdapter(mAdapter);

        addSettingsGear();
    }

    /**
     * Puts a gear on the same line as the large title, at its right end. It opens the profile's
     * settings and moves into the top bar together with the title when the list is scrolled.
     */
    private void addSettingsGear() {
        final com.google.android.material.appbar.CollapsingToolbarLayout collapsing = mBaseBinding.collapsingToolbar;
        final float density = getResources().getDisplayMetrics().density;
        final int size = Math.round(48 * density);

        final android.widget.ImageButton gear = new androidx.appcompat.widget.AppCompatImageButton(this);
        gear.setId(R.id.calendar_profile_settings_gear);
        gear.setImageResource(R.drawable.ic_settings);
        final android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
        gear.setBackgroundResource(value.resourceId);
        gear.setContentDescription(getString(R.string.calendar_alarm_edit_profile));
        androidx.appcompat.widget.TooltipCompat.setTooltipText(gear, getString(R.string.calendar_alarm_edit_profile));
        gear.setOnClickListener(v -> startActivity(CalendarProfileEditActivity.createIntent(this, mProfileId)));

        // Line the gear up with the middle of the large title.
        final android.graphics.Paint paint = new android.graphics.Paint();
        paint.setTextSize(collapsing.getExpandedTitleTextSize());
        final android.graphics.Paint.FontMetrics metrics = paint.getFontMetrics();
        final float titleHeight = metrics.descent - metrics.ascent;
        // The font's ascent includes room for accents above capitals; the visible letters sit lower.
        final int expandedBottom = Math.round(collapsing.getExpandedTitleMarginBottom() + titleHeight / 2f
            - size / 2f - metrics.descent / 2f - 2 * density);
        // In the collapsed bar the gear sits in the middle of the toolbar instead.
        final int collapsedBottom = Math.round((mBaseBinding.actionBar.getLayoutParams().height - size) / 2f);

        final com.google.android.material.appbar.CollapsingToolbarLayout.LayoutParams params =
            new com.google.android.material.appbar.CollapsingToolbarLayout.LayoutParams(size, size,
                android.view.Gravity.BOTTOM | android.view.Gravity.END);
        params.setCollapseMode(com.google.android.material.appbar.CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_OFF);
        params.setMarginEnd(Math.round(12 * density));
        params.bottomMargin = expandedBottom;
        collapsing.addView(gear, params);
        // Keep the title clear of the gear.
        collapsing.setExpandedTitleMarginEnd(Math.round(72 * density));
        // Also in the collapsed bar, where a long title would otherwise run under it.
        final androidx.appcompat.widget.Toolbar toolbar = mBaseBinding.actionBar;
        toolbar.setContentInsetsRelative(toolbar.getContentInsetStart(), Math.round(64 * density));

        mBaseBinding.appBar.addOnOffsetChangedListener((appBar, offset) -> {
            final int range = appBar.getTotalScrollRange();
            final float collapsed = range == 0 ? 0f : -offset / (float) range;
            gear.setTranslationY(collapsed * (expandedBottom - collapsedBottom));
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        getContentResolver().registerContentObserver(CalendarProfile.CONTENT_URI, true, mObserver);
        getContentResolver().registerContentObserver(CalendarAlarmEvent.CONTENT_URI, true, mObserver);
        androidx.core.content.ContextCompat.registerReceiver(this, mWifiReceiver, wifiFilter(),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        final Context appContext = getApplicationContext();
        final long profileId = mProfileId;
        CalendarAlarmSync.EXECUTOR.execute(() -> {
            // Also fetch the latest changes others made (shared calendars). When they arrive, the
            // calendar change check updates the list.
            final com.best.deskclock.provider.CalendarProfile profile =
                com.best.deskclock.provider.CalendarProfile.getProfile(appContext.getContentResolver(), profileId);
            if (profile != null) {
                DeviceCalendars.requestSync(appContext, profile.calendarId);
            }
            CalendarAlarmSync.syncProfile(appContext, profileId);
        });
    }

    @Override
    protected void onStop() {
        getContentResolver().unregisterContentObserver(mObserver);
        this.unregisterReceiver(mWifiReceiver);
        mHandler.removeCallbacks(mReload);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mBinding = null;
        super.onDestroy();
    }

    private void reload() {
        final Context appContext = getApplicationContext();
        final long profileId = mProfileId;
        AppExecutors.getDiskIO().execute(() -> {
            final CalendarProfile profile = CalendarProfile.getProfile(appContext.getContentResolver(), profileId);
            final List<CalendarAlarmEvent> events = new ArrayList<>();
            final CalendarWifiGate.Status wifiStatus = CalendarWifiGate.status(appContext, profile, false);
            if (profile != null) {
                final long now = System.currentTimeMillis();
                for (CalendarAlarmEvent event : CalendarAlarmEvent.getEventsForProfile(
                    appContext.getContentResolver(), profileId)) {
                    if (event.isUpcoming(now)) {
                        events.add(event);
                    }
                }
            }

            AppExecutors.getMainThread().post(() -> {
                if (mBinding == null) {
                    return;
                }
                if (profile == null) {
                    // The profile was deleted from the editor.
                    finish();
                    return;
                }
                mTitle = profile.name.isEmpty() ? profile.calendarName : profile.name;
                mBaseBinding.collapsingToolbar.setTitle(mTitle);
                mBinding.eventsSubtitle.setText(getString(R.string.calendar_alarm_events_subtitle,
                    profile.calendarName.isEmpty() ? profile.accountName : profile.calendarName,
                    profile.titleRegex));
                final String wifi = CalendarWifiGate.describe(this, wifiStatus);
                CalendarWifiGate.showStatus(mBinding.eventsWifi, wifiStatus, wifi);
                mAdapter.setEvents(events, profile);
                mBinding.eventsEmpty.setText(getString(R.string.calendar_alarm_no_events,
                    CalendarProfile.clampLookaheadDays(profile.lookaheadDays)));
                mBinding.eventsEmpty.setVisibility(events.isEmpty() ? VISIBLE : GONE);
            });
        });
    }

    private void setEventEnabled(CalendarAlarmEvent event, boolean enabled) {
        final Context appContext = getApplicationContext();
        CalendarAlarmSync.EXECUTOR.execute(() -> CalendarAlarmSync.setEventEnabled(appContext, event.id, enabled));
    }

    /**
     * Opens the calendar entry of the event in the device's calendar app.
     */
    static void openInCalendar(Context context, CalendarAlarmEvent event) {
        final Intent intent = new Intent(Intent.ACTION_VIEW)
            .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            CustomToast.showLong(context, context.getString(R.string.calendar_alarm_no_calendar_app));
        }
    }

    private static boolean isSameDay(long a, long b) {
        final java.util.Calendar first = java.util.Calendar.getInstance();
        first.setTimeInMillis(a);
        final java.util.Calendar second = java.util.Calendar.getInstance();
        second.setTimeInMillis(b);
        return first.get(java.util.Calendar.YEAR) == second.get(java.util.Calendar.YEAR)
            && first.get(java.util.Calendar.DAY_OF_YEAR) == second.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private final class EventAdapter extends RecyclerView.Adapter<EventViewHolder> {

        private final List<CalendarAlarmEvent> mEvents = new ArrayList<>();
        private CalendarProfile mProfile = new CalendarProfile();

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        void setEvents(List<CalendarAlarmEvent> events, CalendarProfile profile) {
            mEvents.clear();
            mEvents.addAll(events);
            mProfile = profile;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public EventViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new EventViewHolder(CalendarEventItemBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull EventViewHolder holder, int position) {
            holder.bind(mEvents.get(position), mProfile);
        }

        @Override
        public int getItemCount() {
            return mEvents.size();
        }
    }

    private final class EventViewHolder extends RecyclerView.ViewHolder {

        private final CalendarEventItemBinding mItem;

        EventViewHolder(CalendarEventItemBinding binding) {
            super(binding.getRoot());
            mItem = binding;
        }

        void bind(CalendarAlarmEvent event, CalendarProfile profile) {
            final Context context = itemView.getContext();
            final boolean profileEnabled = profile.enabled;

            // Events that ring at their end show when they start and end, for example 07:00–09:00.
            // The end follows the calendar, also after snoozing extended the event.
            final java.text.DateFormat timeFormat = DateFormat.getTimeFormat(context);
            final boolean showsEnd = event.end > event.begin && (event.endAlarmId >= 0
                || CalendarAlarmSync.ringsAtEnd(profile, event, System.currentTimeMillis()));
            mItem.eventTime.setText(showsEnd
                ? timeFormat.format(new java.util.Date(event.begin)) + "-" + timeFormat.format(new java.util.Date(event.end))
                : timeFormat.format(new java.util.Date(event.begin)));
            mItem.eventTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, showsEnd ? 28 : 36);
            mItem.eventTitle.setText(event.title);

            final String date = DateUtils.formatDateTime(context, event.begin,
                DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY
                    | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
            final boolean consumed = event.userEnabled && event.isDismissed();
            String dateText = consumed
                ? date + " · " + context.getString(R.string.calendar_alarm_event_dismissed)
                : context.getString(R.string.calendar_alarm_next, date);
            final long now = System.currentTimeMillis();
            final boolean atEnd = CalendarAlarmSync.ringsAtEnd(profile, event, now);
            if (event.isRunningWithEndAlarm(now)) {
                // Already started: only the alarm at the end is left.
                dateText = date + " · " + context.getString(R.string.calendar_alarm_running_rings_at_end,
                    DateFormat.getTimeFormat(context).format(new java.util.Date(
                        CalendarAlarmSync.alarmTimeFor(event.end, now))));
            } else if (!consumed && (profile.offsetEnabled || atEnd) && event.alarmId != CalendarAlarmEvent.ALARM_CONSUMED) {
                // With an offset, the alarm rings before the event time shown on the left.
                final long ringTime = CalendarAlarmSync.ringTimeFor(profile, event.begin, now);
                final boolean atStart = profile.ringsEarlyAndAtStart();
                final int text = atEnd
                    ? (atStart ? R.string.calendar_alarm_rings_at_start_and_end : R.string.calendar_alarm_rings_at_and_end)
                    : (atStart ? R.string.calendar_alarm_rings_at_and_start : R.string.calendar_alarm_rings_at);
                dateText += " · " + context.getString(text,
                    DateFormat.getTimeFormat(context).format(new java.util.Date(ringTime)));
            }
            if (showsEnd && !isSameDay(event.begin, event.end)) {
                // The time on the left only shows the hour, so say on which day the event ends.
                dateText += " · " + context.getString(R.string.calendar_alarm_ends_on,
                    DateUtils.formatDateTime(context, event.end, DateUtils.FORMAT_SHOW_WEEKDAY
                        | DateUtils.FORMAT_ABBREV_WEEKDAY | DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_TIME));
            }
            mItem.eventDate.setText(dateText);

            mItem.eventSwitch.setOnCheckedChangeListener(null);
            mItem.eventSwitch.setChecked(event.userEnabled && !consumed);
            mItem.eventSwitch.setEnabled(profileEnabled);
            mItem.eventSwitch.setOnCheckedChangeListener((button, checked) -> setEventEnabled(event, checked));

            itemView.setAlpha(profileEnabled && event.userEnabled && !consumed ? 1f : 0.6f);
            itemView.setOnClickListener(v -> openInCalendar(context, event));
        }
    }
}

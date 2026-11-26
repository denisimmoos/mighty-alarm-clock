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
import com.best.deskclock.provider.CalendarAlertEvent;
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
    }

    private static final int MENU_EDIT_PROFILE = 1;

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        // A gear next to the title opens the profile's settings.
        menu.add(0, MENU_EDIT_PROFILE, 0, R.string.calendar_alert_edit_profile)
            .setIcon(R.drawable.ic_settings)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        mBaseBinding.actionBar.post(() ->
            com.best.deskclock.utils.ThemeUtils.applyToolbarTooltips(mBaseBinding.actionBar));
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        if (item.getItemId() == MENU_EDIT_PROFILE) {
            startActivity(CalendarProfileEditActivity.createIntent(this, mProfileId));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();
        getContentResolver().registerContentObserver(CalendarProfile.CONTENT_URI, true, mObserver);
        getContentResolver().registerContentObserver(CalendarAlertEvent.CONTENT_URI, true, mObserver);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        final Context appContext = getApplicationContext();
        final long profileId = mProfileId;
        CalendarAlertSync.EXECUTOR.execute(() -> CalendarAlertSync.syncProfile(appContext, profileId));
    }

    @Override
    protected void onStop() {
        getContentResolver().unregisterContentObserver(mObserver);
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
            final List<CalendarAlertEvent> events = new ArrayList<>();
            if (profile != null) {
                final long now = System.currentTimeMillis();
                for (CalendarAlertEvent event : CalendarAlertEvent.getEventsForProfile(
                    appContext.getContentResolver(), profileId)) {
                    if (event.begin > now) {
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
                mBinding.eventsSubtitle.setText(getString(R.string.calendar_alert_events_subtitle,
                    profile.calendarName.isEmpty() ? profile.accountName : profile.calendarName,
                    profile.titleRegex));
                mAdapter.setEvents(events, profile.enabled);
                mBinding.eventsEmpty.setText(getString(R.string.calendar_alert_no_events,
                    CalendarProfile.clampLookaheadDays(profile.lookaheadDays)));
                mBinding.eventsEmpty.setVisibility(events.isEmpty() ? VISIBLE : GONE);
            });
        });
    }

    private void setEventEnabled(CalendarAlertEvent event, boolean enabled) {
        final Context appContext = getApplicationContext();
        CalendarAlertSync.EXECUTOR.execute(() -> CalendarAlertSync.setEventEnabled(appContext, event.id, enabled));
    }

    /**
     * Opens the calendar entry of the event in the device's calendar app.
     */
    static void openInCalendar(Context context, CalendarAlertEvent event) {
        final Intent intent = new Intent(Intent.ACTION_VIEW)
            .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            CustomToast.showLong(context, context.getString(R.string.calendar_alert_no_calendar_app));
        }
    }

    private final class EventAdapter extends RecyclerView.Adapter<EventViewHolder> {

        private final List<CalendarAlertEvent> mEvents = new ArrayList<>();
        private boolean mProfileEnabled = true;

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        void setEvents(List<CalendarAlertEvent> events, boolean profileEnabled) {
            mEvents.clear();
            mEvents.addAll(events);
            mProfileEnabled = profileEnabled;
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
            holder.bind(mEvents.get(position), mProfileEnabled);
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

        void bind(CalendarAlertEvent event, boolean profileEnabled) {
            final Context context = itemView.getContext();

            mItem.eventTime.setText(DateFormat.getTimeFormat(context).format(new java.util.Date(event.begin)));
            mItem.eventTitle.setText(event.title);

            final String date = DateUtils.formatDateTime(context, event.begin,
                DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY
                    | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
            final boolean consumed = event.userEnabled && event.alarmId == CalendarAlertEvent.ALARM_CONSUMED;
            mItem.eventDate.setText(consumed
                ? date + " · " + context.getString(R.string.calendar_alert_event_dismissed)
                : context.getString(R.string.calendar_alert_next, date));

            mItem.eventSwitch.setOnCheckedChangeListener(null);
            mItem.eventSwitch.setChecked(event.userEnabled && !consumed);
            mItem.eventSwitch.setEnabled(profileEnabled);
            mItem.eventSwitch.setOnCheckedChangeListener((button, checked) -> setEventEnabled(event, checked));

            itemView.setAlpha(profileEnabled && event.userEnabled && !consumed ? 1f : 0.6f);
            itemView.setOnClickListener(v -> openInCalendar(context, event));
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.SpannableString;
import android.text.format.DateUtils;
import android.text.style.UnderlineSpan;

import androidx.annotation.Nullable;

import com.best.deskclock.R;
import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.databinding.CalendarEventDetailsActivityBinding;
import com.best.deskclock.provider.CalendarAlarmEvent;
import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.uicomponents.CollapsingToolbarBaseActivity;
import com.best.deskclock.uicomponents.toast.CustomToast;

/**
 * Shows one calendar event of a profile's alarm list inside the app: time, calendar, place and
 * description, with a button to open it in the calendar app.
 *
 * <p>Some calendar apps (for example aCalendar) open their main view under an event, so Back from
 * there stayed in the calendar. This screen keeps Back in the app.</p>
 */
public final class CalendarEventDetailsActivity extends CollapsingToolbarBaseActivity {

    private static final String EXTRA_EVENT_ROW_ID = "extra_event_row_id";

    private CalendarEventDetailsActivityBinding mBinding;
    private String mTitle = "";

    public static Intent createIntent(Context context, long eventRowId) {
        return new Intent(context, CalendarEventDetailsActivity.class).putExtra(EXTRA_EVENT_ROW_ID, eventRowId);
    }

    @Override
    protected String getActivityTitle() {
        return mTitle;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = CalendarEventDetailsActivityBinding.inflate(getLayoutInflater(), mBaseBinding.contentFrame, true);
        load(getIntent().getLongExtra(EXTRA_EVENT_ROW_ID, CalendarAlarmEvent.INVALID_ID));
    }

    @Override
    protected void onDestroy() {
        mBinding = null;
        super.onDestroy();
    }

    private void load(long eventRowId) {
        final Context appContext = getApplicationContext();
        AppExecutors.getDiskIO().execute(() -> {
            final CalendarAlarmEvent event = CalendarAlarmEvent.getEvent(appContext.getContentResolver(), eventRowId);
            final CalendarProfile profile = event == null
                ? null : CalendarProfile.getProfile(appContext.getContentResolver(), event.profileId);
            final DeviceCalendars.EventDetails details = event == null
                ? null : DeviceCalendars.getEventDetails(appContext, event.eventId);
            AppExecutors.getMainThread().post(() -> show(event, profile, details));
        });
    }

    private void show(@Nullable CalendarAlarmEvent event, @Nullable CalendarProfile profile,
                      @Nullable DeviceCalendars.EventDetails details) {
        if (mBinding == null) {
            return;
        }
        if (event == null) {
            // The event is gone, for example deleted in the calendar meanwhile.
            finish();
            return;
        }

        mTitle = event.title == null ? "" : event.title;
        mBaseBinding.collapsingToolbar.setTitle(mTitle);

        mBinding.eventWhen.setText(DateUtils.formatDateTime(this, event.begin,
            DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY | DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_TIME));

        if (profile != null) {
            mBinding.eventCalendar.setText(profile.calendarName.isEmpty() ? profile.accountName : profile.calendarName);
        } else {
            mBinding.eventCalendar.setVisibility(GONE);
        }

        if (event.userEnabled && event.alarmId == CalendarAlarmEvent.ALARM_CONSUMED) {
            mBinding.eventAlarmState.setText(R.string.calendar_alarm_event_dismissed);
            mBinding.eventAlarmState.setVisibility(VISIBLE);
        } else if (!event.userEnabled) {
            mBinding.eventAlarmState.setText(R.string.calendar_alarm_event_switched_off);
            mBinding.eventAlarmState.setVisibility(VISIBLE);
        }

        if (details != null && !details.location().isEmpty()) {
            final SpannableString place = new SpannableString(details.location());
            place.setSpan(new UnderlineSpan(), 0, place.length(), 0);
            mBinding.eventLocation.setText(place);
            mBinding.eventLocation.setContentDescription(
                getString(R.string.calendar_alarm_open_location, details.location()));
            mBinding.eventLocation.setOnClickListener(v -> openLocation(details.location()));
            mBinding.eventLocation.setVisibility(VISIBLE);
        }
        if (details != null && !details.description().isEmpty()) {
            mBinding.eventDescription.setText(CalendarEventText.toDisplayText(details.description()));
            mBinding.eventDescription.setVisibility(VISIBLE);
        }

        mBinding.openInCalendarButton.setOnClickListener(v -> openInCalendar(event));
    }

    private void openLocation(String location) {
        for (Intent intent : CalendarEventText.createMapIntents(location)) {
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Try the next way to show the place.
            }
        }
        CustomToast.showLong(this, getString(R.string.calendar_alarm_no_maps_app));
    }

    private void openInCalendar(CalendarAlarmEvent event) {
        final Intent intent = new Intent(Intent.ACTION_VIEW)
            .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            CustomToast.showLong(this, getString(R.string.calendar_alarm_no_calendar_app));
        }
    }
}

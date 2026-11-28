// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.best.deskclock.DeskClock;
import com.best.deskclock.R;
import com.best.deskclock.alarms.AlarmStateManager;
import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.base.BaseActivity;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.CalendarAlarmEvent;
import com.best.deskclock.provider.CalendarProfile;
import com.best.deskclock.uicomponents.toast.CustomToast;
import com.best.deskclock.utils.LogUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Small menu shown when the "Next alarm" widget is tapped: edit the event or its profile, switch
 * this one alarm off, delete the event from the calendar, or open the app.
 */
public class NextAlarmMenuActivity extends BaseActivity {

    /** What the menu is about: the next alarm, its calendar event and the profile. */
    @VisibleForTesting
    static final class Target {
        final AlarmInstance instance;
        final CalendarAlarmEvent event;
        @Nullable
        final CalendarProfile profile;

        Target(AlarmInstance instance, CalendarAlarmEvent event, @Nullable CalendarProfile profile) {
            this.instance = instance;
            this.event = event;
            this.profile = profile;
        }
    }

    private Target mTarget;
    private AlertDialog mDialog;

    private final ActivityResultLauncher<String> mWritePermissionLauncher = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted && mTarget != null) {
                confirmDelete();
            } else {
                CustomToast.showLong(this, getString(R.string.calendar_alarm_permission_denied));
                finish();
            }
        });

    public static Intent createIntent(Context context) {
        return new Intent(context, NextAlarmMenuActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context appContext = getApplicationContext();
        AppExecutors.getDiskIO().execute(() -> {
            final Target target = loadTarget(appContext);
            AppExecutors.getMainThread().post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (target == null) {
                    // No calendar alarm is coming up: just open the app, as the widget did before.
                    openApp();
                    return;
                }
                mTarget = target;
                showMenu();
            });
        });
    }

    @Override
    protected void onDestroy() {
        if (mDialog != null) {
            mDialog.setOnDismissListener(null);
            mDialog.dismiss();
        }
        super.onDestroy();
    }

    /**
     * @return the next alarm with its event and profile, or {@code null} if there is none.
     * Must not run on the main thread.
     */
    @VisibleForTesting
    @Nullable
    static Target loadTarget(Context context) {
        final ContentResolver cr = context.getContentResolver();
        final AlarmInstance instance = AlarmStateManager.getNextFiringAlarm(context);
        if (instance == null || instance.mAlarmId == null) {
            return null;
        }
        final CalendarAlarmEvent event = CalendarAlarmEvent.getEventForAlarm(cr, instance.mAlarmId);
        if (event == null) {
            return null;
        }
        return new Target(instance, event, CalendarProfile.getProfile(cr, event.profileId));
    }

    private void showMenu() {
        final View view = LayoutInflater.from(this).inflate(R.layout.next_alarm_menu, null);
        final String title = TextUtils.isEmpty(mTarget.event.title)
            ? getString(R.string.next_alarm_menu_no_title)
            : mTarget.event.title;
        ((TextView) view.findViewById(R.id.next_alarm_menu_title)).setText(title);

        final long alarmTime = mTarget.instance.getAlarmTime().getTimeInMillis();
        String subtitle = DateUtils.formatDateTime(this, alarmTime, DateUtils.FORMAT_SHOW_WEEKDAY
            | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_ALL);
        final String profileName = profileName(mTarget.profile);
        if (!profileName.isEmpty()) {
            subtitle = getString(R.string.next_alarm_menu_subtitle, subtitle, profileName);
        }
        ((TextView) view.findViewById(R.id.next_alarm_menu_subtitle)).setText(subtitle);

        final LinearLayout items = view.findViewById(R.id.next_alarm_menu_items);
        addItem(items, R.drawable.ic_edit, R.string.next_alarm_menu_edit_event, this::editEvent);
        if (mTarget.profile != null) {
            addItem(items, R.drawable.ic_settings, R.string.next_alarm_menu_edit_profile, this::editProfile);
        }
        addDivider(items);
        addItem(items, R.drawable.ic_alarm_off, R.string.next_alarm_menu_disable, this::disableAlarm);
        addItem(items, R.drawable.ic_delete, R.string.next_alarm_menu_delete_event, this::deleteEvent);
        addDivider(items);
        addItem(items, R.drawable.ic_calendar_clock, R.string.next_alarm_menu_open_app, this::openApp);

        mDialog = new MaterialAlertDialogBuilder(this)
            .setView(view)
            .setOnDismissListener(d -> finish())
            .show();
    }

    private String profileName(@Nullable CalendarProfile profile) {
        if (profile == null) {
            return "";
        }
        if (!TextUtils.isEmpty(profile.name)) {
            return profile.name;
        }
        return profile.titleRegex == null ? "" : profile.titleRegex;
    }

    private void addItem(LinearLayout items, @DrawableRes int icon, int label, Runnable action) {
        final TextView item = (TextView) LayoutInflater.from(this).inflate(R.layout.next_alarm_menu_item, items, false);
        item.setText(label);
        item.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        item.setOnClickListener(v -> {
            // Run the action first: it may open another dialog that keeps this activity alive.
            final AlertDialog dialog = mDialog;
            mDialog = null;
            if (dialog != null) {
                dialog.setOnDismissListener(null);
                dialog.dismiss();
            }
            action.run();
        });
        items.addView(item);
    }

    private void addDivider(LinearLayout items) {
        final View divider = LayoutInflater.from(this).inflate(R.layout.next_alarm_menu_divider, items, false);
        items.addView(divider);
    }

    private void editEvent() {
        final Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, mTarget.event.eventId);
        final Intent edit = new Intent(Intent.ACTION_EDIT, uri)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, mTarget.event.begin)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (mTarget.event.end > 0) {
            edit.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, mTarget.event.end);
        }
        try {
            startActivity(edit);
        } catch (ActivityNotFoundException e) {
            // Some calendar apps can only show an event; editing starts from there.
            final Intent view = new Intent(Intent.ACTION_VIEW, uri)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, mTarget.event.begin)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(view);
            } catch (ActivityNotFoundException e2) {
                CustomToast.showLong(this, getString(R.string.calendar_alarm_no_calendar_app));
            }
        }
        finish();
    }

    private void editProfile() {
        startActivity(CalendarProfileEditActivity.createIntent(this, mTarget.profile.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private void disableAlarm() {
        final Context appContext = getApplicationContext();
        final long rowId = mTarget.event.id;
        CalendarAlarmSync.EXECUTOR.execute(() -> CalendarAlarmSync.setEventEnabled(appContext, rowId, false));
        CustomToast.show(this, getString(R.string.next_alarm_menu_disabled));
        finish();
    }

    private void deleteEvent() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            mWritePermissionLauncher.launch(Manifest.permission.WRITE_CALENDAR);
            return;
        }
        confirmDelete();
    }

    private void confirmDelete() {
        final Context appContext = getApplicationContext();
        final CalendarAlarmEvent event = mTarget.event;
        AppExecutors.getDiskIO().execute(() -> {
            final boolean recurring = isRecurring(appContext.getContentResolver(), event.eventId);
            AppExecutors.getMainThread().post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                final String title = TextUtils.isEmpty(event.title)
                    ? getString(R.string.next_alarm_menu_no_title) : event.title;
                mDialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.next_alarm_menu_delete_title)
                    .setMessage(getString(recurring
                        ? R.string.next_alarm_menu_delete_occurrence_message
                        : R.string.next_alarm_menu_delete_message, title))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.next_alarm_menu_delete_confirm, (d, w) ->
                        CalendarAlarmSync.EXECUTOR.execute(() -> {
                            final boolean deleted = deleteFromCalendar(appContext, event, recurring);
                            if (deleted) {
                                CalendarAlarmSync.syncAll(appContext);
                            }
                            AppExecutors.getMainThread().post(() -> CustomToast.show(appContext,
                                appContext.getString(deleted
                                    ? R.string.next_alarm_menu_deleted
                                    : R.string.next_alarm_menu_delete_failed)));
                        }))
                    .setOnDismissListener(d -> finish())
                    .show();
            });
        });
    }

    @VisibleForTesting
    static boolean isRecurring(ContentResolver cr, long eventId) {
        try (Cursor c = cr.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            new String[]{CalendarContract.Events.RRULE, CalendarContract.Events.RDATE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return !TextUtils.isEmpty(c.getString(0)) || !TextUtils.isEmpty(c.getString(1));
            }
        } catch (RuntimeException e) {
            LogUtils.e("NextAlarmMenu: could not read event " + eventId, e);
        }
        return false;
    }

    /**
     * Deletes the event from the calendar. For a recurring event only this one occurrence is
     * cancelled, the rest of the series stays. Must not run on the main thread.
     */
    @VisibleForTesting
    static boolean deleteFromCalendar(Context context, CalendarAlarmEvent event, boolean recurring) {
        final ContentResolver cr = context.getContentResolver();
        try {
            if (recurring) {
                final ContentValues values = new ContentValues();
                values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, event.begin);
                values.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED);
                final Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, event.eventId);
                return cr.insert(uri, values) != null;
            }
            return cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId),
                null, null) > 0;
        } catch (RuntimeException e) {
            LogUtils.e("NextAlarmMenu: could not delete event " + event.eventId, e);
            return false;
        }
    }

    private void openApp() {
        startActivity(new Intent(this, DeskClock.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}

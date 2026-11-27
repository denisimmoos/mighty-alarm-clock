package com.best.deskclock.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CalendarProfileUpgradeTest {

    @Test
    public void upgradeFromVersion31AddsVibrateOnlyColumnTurnedOff() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE calendar_profiles (_id INTEGER PRIMARY KEY, flash INTEGER NOT NULL DEFAULT 0);");
        db.execSQL("INSERT INTO calendar_profiles (_id) VALUES (1);");
        new ClockDatabaseHelper(ApplicationProvider.getApplicationContext()).onUpgrade(db, 31, 35);
        try (Cursor c = db.rawQuery("SELECT vibrate_only FROM calendar_profiles", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
        }
        db.close();
    }

    @Test
    public void upgradeFromVersion30AddsFlashColumnTurnedOff() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE calendar_profiles (_id INTEGER PRIMARY KEY, lookahead_days INTEGER NOT NULL DEFAULT 7);");
        db.execSQL("INSERT INTO calendar_profiles (_id) VALUES (1);");
        new ClockDatabaseHelper(ApplicationProvider.getApplicationContext()).onUpgrade(db, 30, 35);
        try (Cursor c = db.rawQuery("SELECT flash, vibrate_only, vibration_strength, flash_only FROM calendar_profiles", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
            assertEquals(100, c.getInt(2));
            assertEquals(0, c.getInt(3));
        }
        db.close();
    }

    @Test
    public void upgradeFromVersion34AddsEventFilterColumnsAndCanRunTwice() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE calendar_profiles (_id INTEGER PRIMARY KEY, flash INTEGER NOT NULL DEFAULT 0);");
        db.execSQL("INSERT INTO calendar_profiles (_id) VALUES (1);");
        final ClockDatabaseHelper helper = new ClockDatabaseHelper(ApplicationProvider.getApplicationContext());
        helper.onUpgrade(db, 34, 35);
        // A half-finished earlier upgrade must not make the next one fail.
        helper.onUpgrade(db, 34, 35);
        try (Cursor c = db.rawQuery(
            "SELECT ignore_case, skip_declined, skip_tentative, all_day_minutes FROM calendar_profiles", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
            assertEquals(1, c.getInt(1));
            assertEquals(0, c.getInt(2));
            assertEquals(-1, c.getInt(3));
        }
        db.close();
    }

    @Test
    public void upgradeFromVersion35ShowsDetailsOnTheLockScreenAsBefore() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE calendar_profiles (_id INTEGER PRIMARY KEY, flash INTEGER NOT NULL DEFAULT 0);");
        db.execSQL("INSERT INTO calendar_profiles (_id) VALUES (1);");
        new ClockDatabaseHelper(ApplicationProvider.getApplicationContext()).onUpgrade(db, 35, 36);
        try (Cursor c = db.rawQuery("SELECT show_details_locked FROM calendar_profiles", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(1, c.getInt(0));
        }
        db.close();
    }

    @Test
    public void upgradeFromVersion36AddsOffsetAndEscalationSwitchedOff() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE calendar_profiles (_id INTEGER PRIMARY KEY, flash INTEGER NOT NULL DEFAULT 0);");
        db.execSQL("INSERT INTO calendar_profiles (_id) VALUES (1);");
        final ClockDatabaseHelper helper = new ClockDatabaseHelper(ApplicationProvider.getApplicationContext());
        helper.onUpgrade(db, 36, 37);
        // Running it again must not fail.
        helper.onUpgrade(db, 36, 37);
        try (Cursor c = db.rawQuery("SELECT offset_enabled, offset_minutes, escalation FROM calendar_profiles", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
            assertEquals(10, c.getInt(1));
            assertEquals(0, c.getInt(2));
        }
        db.close();
    }

    @Test
    public void upgradeFromVersion37RingsAtTheStartAndAddsTheSecondAlarmColumn() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE calendar_profiles (_id INTEGER PRIMARY KEY, flash INTEGER NOT NULL DEFAULT 0);");
        db.execSQL("INSERT INTO calendar_profiles (_id) VALUES (1);");
        db.execSQL("CREATE TABLE calendar_alert_events (_id INTEGER PRIMARY KEY, alarm_id INTEGER NOT NULL DEFAULT -1);");
        db.execSQL("INSERT INTO calendar_alert_events (_id, alarm_id) VALUES (1, 5);");
        final ClockDatabaseHelper helper = new ClockDatabaseHelper(ApplicationProvider.getApplicationContext());
        helper.onUpgrade(db, 37, 38);
        helper.onUpgrade(db, 37, 38);
        try (Cursor c = db.rawQuery("SELECT ring_at_start FROM calendar_profiles", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(1, c.getInt(0));
        }
        try (Cursor c = db.rawQuery("SELECT alarm_id, start_alarm_id FROM calendar_alert_events", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(5, c.getInt(0));
            assertEquals(-1, c.getInt(1));
        }
        db.close();
    }

    @Test
    public void upgradeFromVersion38AddsTheWifiCheckSwitchedOff() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE calendar_profiles (_id INTEGER PRIMARY KEY, flash INTEGER NOT NULL DEFAULT 0);");
        db.execSQL("INSERT INTO calendar_profiles (_id) VALUES (1);");
        final ClockDatabaseHelper helper = new ClockDatabaseHelper(ApplicationProvider.getApplicationContext());
        helper.onUpgrade(db, 38, 39);
        helper.onUpgrade(db, 38, 39);
        try (Cursor c = db.rawQuery("SELECT wifi_check, wifi_networks, wifi_off_rings FROM calendar_profiles", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
            assertEquals("", c.getString(1));
            assertEquals(0, c.getInt(2));
        }
        db.close();
    }

    @Test
    public void upgradeFromVersion39AddsRingAtEndSwitchedOff() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE calendar_profiles (_id INTEGER PRIMARY KEY, flash INTEGER NOT NULL DEFAULT 0);");
        db.execSQL("INSERT INTO calendar_profiles (_id) VALUES (1);");
        db.execSQL("CREATE TABLE calendar_alert_events (_id INTEGER PRIMARY KEY, alarm_id INTEGER NOT NULL DEFAULT -1);");
        db.execSQL("INSERT INTO calendar_alert_events (_id) VALUES (1);");
        final ClockDatabaseHelper helper = new ClockDatabaseHelper(ApplicationProvider.getApplicationContext());
        helper.onUpgrade(db, 39, 40);
        helper.onUpgrade(db, 39, 40);
        try (Cursor c = db.rawQuery("SELECT ring_at_end, extend_on_snooze FROM calendar_profiles", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
            assertEquals(0, c.getInt(1));
        }
        try (Cursor c = db.rawQuery("SELECT start_alarm_id, event_end, end_alarm_id FROM calendar_alert_events", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(-1, c.getInt(0));
            assertEquals(0, c.getLong(1));
            assertEquals(-1, c.getInt(2));
        }
        db.close();
    }

    @Test
    public void upgradeFromVersion41AddsSnoozeLengthsTenAndTwenty() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE calendar_profiles (_id INTEGER PRIMARY KEY, flash INTEGER NOT NULL DEFAULT 0);");
        db.execSQL("INSERT INTO calendar_profiles (_id) VALUES (1);");
        db.execSQL("CREATE TABLE calendar_alert_events (_id INTEGER PRIMARY KEY, alarm_id INTEGER NOT NULL DEFAULT -1);");
        final ClockDatabaseHelper helper = new ClockDatabaseHelper(ApplicationProvider.getApplicationContext());
        helper.onUpgrade(db, 41, 42);
        helper.onUpgrade(db, 41, 42);
        try (Cursor c = db.rawQuery("SELECT snooze_minutes_2, snooze_minutes_3 FROM calendar_profiles", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(10, c.getInt(0));
            assertEquals(20, c.getInt(1));
        }
        db.close();
    }
}

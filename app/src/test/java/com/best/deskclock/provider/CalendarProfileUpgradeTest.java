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
}

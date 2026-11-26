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
    public void upgradeFromVersion30AddsFlashColumnTurnedOff() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE calendar_profiles (_id INTEGER PRIMARY KEY, lookahead_days INTEGER NOT NULL DEFAULT 7);");
        db.execSQL("INSERT INTO calendar_profiles (_id) VALUES (1);");
        new ClockDatabaseHelper(ApplicationProvider.getApplicationContext()).onUpgrade(db, 30, 31);
        try (Cursor c = db.rawQuery("SELECT flash FROM calendar_profiles", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
        }
        db.close();
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.content.ContentResolver;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.provider.CalendarContract;

import androidx.test.core.app.ApplicationProvider;

import com.best.deskclock.alarms.AlarmStateManager;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.CalendarAlarmEvent;
import com.best.deskclock.provider.CalendarProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowScanResult;
import org.robolectric.shadows.ShadowWifiInfo;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CalendarWifiTest {

    private Application mContext;
    private ContentResolver mCr;
    private CalendarProfile mProfile;
    private WifiManager mWifi;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mCr = mContext.getContentResolver();
        Shadows.shadowOf(mContext).grantPermissions(Manifest.permission.READ_CALENDAR,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        Shadows.shadowOf(mContext.getSystemService(LocationManager.class)).setLocationEnabled(true);
        mWifi = mContext.getSystemService(WifiManager.class);
        mWifi.setWifiEnabled(true);
        connect(null, null);
        Shadows.shadowOf(mWifi).setScanResults(new ArrayList<>());
        Robolectric.setupContentProvider(CalendarAlarmSyncTest.FakeCalendarProvider.class, CalendarContract.AUTHORITY);

        mProfile = new CalendarProfile();
        mProfile.name = "Home";
        mProfile.calendarId = 7;
        mProfile.calendarName = "Calendar 7";
        mProfile.accountName = "someone@example.com";
        mProfile.titleRegex = "^Alarm:";
        mProfile.addProfile(mCr);
        final Calendar c = Calendar.getInstance();
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.HOUR_OF_DAY, 2);
        CalendarAlarmSyncTest.clearEvents();
        CalendarAlarmSyncTest.addEvent(1, 7, c.getTimeInMillis(), "Alarm: Wake up");
        CalendarAlarmSync.syncAll(mContext);
    }

    @After
    public void drain() throws Exception {
        CalendarAlarmSync.EXECUTOR.submit(() -> { }).get();
        com.best.deskclock.base.AppExecutors.getDiskIO().submit(() -> { }).get();
    }

    private void connect(String ssid, String bssid) {
        final WifiInfo info = ShadowWifiInfo.newInstance();
        if (ssid != null) {
            Shadows.shadowOf(info).setSSID(ssid);
            Shadows.shadowOf(info).setBSSID(bssid);
            Shadows.shadowOf(info).setSupplicantState(SupplicantState.COMPLETED);
        } else {
            Shadows.shadowOf(info).setSupplicantState(SupplicantState.DISCONNECTED);
        }
        Shadows.shadowOf(mWifi).setConnectionInfo(info);
    }

    private void seeInRange(String ssid, String bssid, long ageMs) {
        final ScanResult result = ShadowScanResult.newInstance(ssid, bssid, "", -50, 2412);
        result.timestamp = (SystemClock.elapsedRealtime() - ageMs) * 1000L;
        final List<ScanResult> results = new ArrayList<>();
        results.add(result);
        Shadows.shadowOf(mWifi).setScanResults(results);
    }

    private void useWifi(boolean on, CalendarWifi.Network... networks) {
        mProfile.wifiCheck = on;
        mProfile.wifiNetworks = CalendarWifi.toJson(java.util.Arrays.asList(networks));
        mProfile.updateProfile(mCr);
    }

    private AlarmInstance instance() {
        final long alarmId = CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).alarmId;
        return AlarmInstance.getInstancesByAlarmId(mCr, alarmId).get(0);
    }

    /** @return {@code true} if the alarm rang, {@code false} if it stayed quiet. */
    private boolean fire() {
        final AlarmInstance instance = instance();
        AlarmStateManager.setFiredState(mContext, instance);
        final AlarmInstance after = AlarmInstance.getInstance(mCr, instance.mId);
        return after != null && after.mAlarmState == AlarmInstance.FIRED_STATE;
    }

    @Test
    public void networksAreStoredAndMatchedByNameMacOrBoth() {
        final List<CalendarWifi.Network> networks = new ArrayList<>();
        networks.add(new CalendarWifi.Network("Home", ""));
        networks.add(new CalendarWifi.Network("", "AA-BB-CC-DD-EE-FF"));
        networks.add(new CalendarWifi.Network("Office", "11:22:33:44:55:66"));
        final List<CalendarWifi.Network> back = CalendarWifi.parse(CalendarWifi.toJson(networks));
        assertEquals(networks, back);
        assertEquals("aa:bb:cc:dd:ee:ff", back.get(1).bssid);
        assertEquals("", CalendarWifi.toJson(new ArrayList<>()));
        assertTrue(CalendarWifi.parse("").isEmpty());
        assertTrue(CalendarWifi.parse("not json").isEmpty());

        assertTrue(back.get(0).matches("\"Home\"", "00:00:00:00:00:01"));
        assertFalse(back.get(0).matches("Home2", null));
        assertTrue(back.get(1).matches("Anything", "AA:BB:CC:DD:EE:FF"));
        assertFalse(back.get(1).matches("Anything", "aa:bb:cc:dd:ee:00"));
        assertTrue(back.get(2).matches("Office", "11:22:33:44:55:66"));
        assertFalse(back.get(2).matches("Office", "11:22:33:44:55:67"));
        assertFalse(back.get(2).matches("Other", "11:22:33:44:55:66"));

        assertTrue(CalendarWifi.isValidBssid("aa:bb:cc:dd:ee:ff"));
        assertTrue(CalendarWifi.isValidBssid(""));
        assertFalse(CalendarWifi.isValidBssid("aa:bb:cc"));
    }

    @Test
    public void wifiCheckIsOffByDefault() {
        final CalendarProfile fresh = CalendarProfile.getProfile(mCr, mProfile.id);
        assertFalse(fresh.wifiCheck);
        assertEquals("", fresh.wifiNetworks);
        // Away from any Wi-Fi, an alarm without the check rings.
        assertTrue(fire());
    }

    @Test
    public void ringsWhenConnectedToTheChosenWifi() {
        useWifi(true, new CalendarWifi.Network("Home", ""));
        connect("Home", "aa:bb:cc:dd:ee:ff");
        assertTrue(fire());
    }

    @Test
    public void ringsWhenTheChosenAccessPointWasSeenInRange() {
        useWifi(true, new CalendarWifi.Network("", "aa:bb:cc:dd:ee:ff"));
        seeInRange("Neighbour", "AA:BB:CC:DD:EE:FF", 60_000);
        assertTrue(fire());
    }

    @Test
    public void staysQuietAwayFromTheChosenWifiAndIsUsedUp() {
        useWifi(true, new CalendarWifi.Network("Home", ""));
        connect("Cafe", "11:11:11:11:11:11");
        // A scan from long ago doesn't count.
        seeInRange("Home", "aa:bb:cc:dd:ee:ff", 2 * CalendarWifi.MAX_SCAN_AGE_MS);
        final long alarmId = instance().mAlarmId;
        assertEquals(CalendarWifi.Result.NOT_NEAR, CalendarWifiGate.shouldSkip(mContext, instance()));
        assertFalse(fire());
        // Like a dismissed alarm: gone, and not created again by the next check.
        assertNull(Alarm.getAlarm(mCr, alarmId));
        CalendarAlarmSync.syncAll(mContext);
        assertTrue(CalendarAlarmEvent.getEventsForProfile(mCr, mProfile.id).get(0).isDismissed());
        assertTrue(Alarm.getAlarms(mCr, null).isEmpty());
    }

    @Test
    public void staysQuietWhenWifiCannotBeChecked() {
        useWifi(true, new CalendarWifi.Network("Home", ""));
        connect("Home", "aa:bb:cc:dd:ee:ff");
        Shadows.shadowOf(mContext).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        assertEquals(CalendarWifi.Result.UNKNOWN, CalendarWifiGate.shouldSkip(mContext, instance()));
        assertFalse(fire());
    }

    @Test
    public void switchedOffCheckRingsAnywhereAndKeepsTheNetworks() {
        useWifi(false, new CalendarWifi.Network("Home", ""));
        assertTrue(fire());
        assertEquals(1, CalendarWifi.parse(CalendarProfile.getProfile(mCr, mProfile.id).wifiNetworks).size());
    }

    @Test
    public void nearbyNetworksListTheConnectedOneFirst() {
        connect("Home", "aa:bb:cc:dd:ee:ff");
        seeInRange("Neighbour", "11:22:33:44:55:66", 0);
        final List<CalendarWifi.Seen> seen = CalendarWifi.getNearbyNetworks(mContext);
        assertEquals(2, seen.size());
        assertTrue(seen.get(0).connected);
        assertEquals("Home", seen.get(0).ssid);
        assertEquals("11:22:33:44:55:66", seen.get(1).bssid);
    }

    @Test
    public void wifiSettingsAreInTheBackup() throws Exception {
        useWifi(true, new CalendarWifi.Network("Home", "aa:bb:cc:dd:ee:ff"));
        mProfile.wifiOffRings = true;
        mProfile.updateProfile(mCr);
        final org.json.JSONArray backup = CalendarProfileRestore.exportProfiles(mCr);
        CalendarAlarmSync.deleteProfile(mContext, mProfile.id);
        CalendarProfileRestore.replaceAll(mContext, backup);
        final CalendarProfile restored = CalendarProfile.getProfiles(mCr).get(0);
        assertTrue(restored.wifiCheck);
        assertTrue(restored.wifiOffRings);
        assertEquals(new CalendarWifi.Network("Home", "aa:bb:cc:dd:ee:ff"),
            CalendarWifi.parse(restored.wifiNetworks).get(0));
        assertNotNull(restored);
    }

    @Test
    public void ringsAnywayWithWifiOffOnlyWhenAsked() {
        useWifi(true, new CalendarWifi.Network("Home", ""));
        assertFalse(CalendarProfile.getProfile(mCr, mProfile.id).wifiOffRings);
        mWifi.setWifiEnabled(false);
        // Off by default: Wi-Fi off means the alarm stays quiet.
        assertTrue(CalendarWifiGate.shouldSkip(mContext, instance()) != null);
        mProfile.wifiOffRings = true;
        mProfile.updateProfile(mCr);
        assertTrue(CalendarProfile.getProfile(mCr, mProfile.id).wifiOffRings);
        assertTrue(fire());
    }

    @Test
    public void ringAnywayWithWifiOffDoesNotRingAwayWithWifiOn() {
        useWifi(true, new CalendarWifi.Network("Home", ""));
        mProfile.wifiOffRings = true;
        mProfile.updateProfile(mCr);
        connect("Cafe", "11:11:11:11:11:11");
        assertFalse(fire());
    }

    @Test
    public void profileShowsWhatTheWifiCheckMeansNow() {
        final CalendarWifiGate.Status noCheck = CalendarWifiGate.status(mContext,
            CalendarProfile.getProfile(mCr, mProfile.id), false);
        assertEquals(CalendarWifiGate.Status.NO_CHECK, noCheck);
        assertNull(CalendarWifiGate.describe(mContext, noCheck));

        useWifi(true, new CalendarWifi.Network("Home", ""));
        connect("Home", "aa:bb:cc:dd:ee:ff");
        assertStatus(CalendarWifiGate.Status.NEAR, false);

        connect("Cafe", "11:11:11:11:11:11");
        assertStatus(CalendarWifiGate.Status.AWAY, true);

        // Wi-Fi off skips the alarm, even if the phone was near a chosen network before.
        connect("Home", "aa:bb:cc:dd:ee:ff");
        mWifi.setWifiEnabled(false);
        assertStatus(CalendarWifiGate.Status.WIFI_OFF_SKIPS, true);
        assertEquals(CalendarWifi.Result.WIFI_OFF, CalendarWifiGate.shouldSkip(mContext, instance()));

        mProfile.wifiOffRings = true;
        mProfile.updateProfile(mCr);
        assertStatus(CalendarWifiGate.Status.WIFI_OFF_RINGS, false);
    }

    private void assertStatus(CalendarWifiGate.Status expected, boolean skipping) {
        final CalendarWifiGate.Status status = CalendarWifiGate.status(mContext,
            CalendarProfile.getProfile(mCr, mProfile.id), false);
        assertEquals(expected, status);
        assertEquals(skipping, CalendarWifiGate.isSkipping(status));
        assertNotNull(CalendarWifiGate.describe(mContext, status));
        // What the profile shows matches what happens when the alarm is due.
        assertEquals(skipping, CalendarWifiGate.shouldSkip(mContext, instance()) != null);
    }
}

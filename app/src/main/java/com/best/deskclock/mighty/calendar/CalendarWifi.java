// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.calendar;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.SdkUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Wi-Fi networks a calendar profile rings near. A network is matched by its name (SSID), by the
 * MAC address of one access point (BSSID), or by both. The phone counts as near a network if it
 * is connected to it, or if its latest Wi-Fi scan saw it in range.
 */
public final class CalendarWifi {

    /** Scan results older than this don't count, so an old scan can't make the phone "near". */
    static final long MAX_SCAN_AGE_MS = TimeUnit.MINUTES.toMillis(30);

    /** MAC address Android reports when the app may not see the real one. */
    private static final String HIDDEN_BSSID = "02:00:00:00:00:00";

    public enum Result {
        /** Connected to or in range of one of the networks. */
        NEAR,
        /** Wi-Fi could be checked and none of the networks is near. */
        NOT_NEAR,
        /** Wi-Fi could not be checked: no location permission or location off. */
        UNKNOWN,
        /** The phone's Wi-Fi is turned off, so it is near none of the networks. */
        WIFI_OFF
    }

    /**
     * A network to ring near. An empty name or MAC address matches any.
     */
    public static final class Network {
        public final String ssid;
        public final String bssid;

        public Network(@Nullable String ssid, @Nullable String bssid) {
            this.ssid = ssid == null ? "" : ssid.trim();
            this.bssid = normalizeBssid(bssid);
        }

        public boolean isEmpty() {
            return ssid.isEmpty() && bssid.isEmpty();
        }

        boolean matches(@Nullable String otherSsid, @Nullable String otherBssid) {
            if (isEmpty()) {
                return false;
            }
            if (!ssid.isEmpty() && !ssid.equals(cleanSsid(otherSsid))) {
                return false;
            }
            return bssid.isEmpty() || bssid.equals(normalizeBssid(otherBssid));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Network n && n.ssid.equals(ssid) && n.bssid.equals(bssid);
        }

        @Override
        public int hashCode() {
            return ssid.hashCode() * 31 + bssid.hashCode();
        }

        @NonNull
        @Override
        public String toString() {
            if (bssid.isEmpty()) {
                return ssid;
            }
            return ssid.isEmpty() ? bssid : ssid + " · " + bssid;
        }
    }

    /**
     * A network seen nearby, for the list to choose from.
     */
    public static final class Seen {
        public final String ssid;
        public final String bssid;
        /** Signal strength in dBm. */
        public final int level;
        public final boolean connected;

        Seen(String ssid, String bssid, int level, boolean connected) {
            this.ssid = ssid;
            this.bssid = bssid;
            this.level = level;
            this.connected = connected;
        }
    }

    private CalendarWifi() {
    }

    // ---------------------------------------------------------------------------------------
    // Stored form

    public static List<Network> parse(@Nullable String json) {
        final List<Network> networks = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return networks;
        }
        try {
            final JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                final JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                final Network network = new Network(o.optString("ssid"), o.optString("bssid"));
                if (!network.isEmpty() && !networks.contains(network)) {
                    networks.add(network);
                }
            }
        } catch (JSONException e) {
            LogUtils.e("CalendarWifi: unreadable networks " + json, e);
        }
        return networks;
    }

    public static String toJson(List<Network> networks) {
        if (networks.isEmpty()) {
            return "";
        }
        final JSONArray array = new JSONArray();
        try {
            for (Network network : networks) {
                if (!network.isEmpty()) {
                    array.put(new JSONObject().put("ssid", network.ssid).put("bssid", network.bssid));
                }
            }
        } catch (JSONException e) {
            LogUtils.e("CalendarWifi: could not store networks", e);
        }
        return array.length() == 0 ? "" : array.toString();
    }

    static String normalizeBssid(@Nullable String bssid) {
        return bssid == null ? "" : bssid.trim().toLowerCase(Locale.ROOT).replace('-', ':');
    }

    /**
     * @return {@code true} for a MAC address such as {@code aa:bb:cc:dd:ee:ff}, or an empty one.
     */
    public static boolean isValidBssid(@Nullable String bssid) {
        final String value = normalizeBssid(bssid);
        return value.isEmpty() || value.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}");
    }

    @Nullable
    static String cleanSsid(@Nullable String ssid) {
        if (ssid != null && ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return ssid == null || ssid.equals(WifiManager.UNKNOWN_SSID) ? null : ssid;
    }

    // ---------------------------------------------------------------------------------------
    // Permission

    /**
     * @return {@code true} if the app may read Wi-Fi names and MAC addresses while it is open.
     */
    public static boolean hasForegroundPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @return {@code true} if the app may also read them in the background, when an alarm is
     * about to ring. Android 10 and newer need "Allow all the time" for this.
     */
    public static boolean hasBackgroundPermission(Context context) {
        if (!hasForegroundPermission(context)) {
            return false;
        }
        return !SdkUtils.isAtLeastAndroid10() || ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @return {@code true} if the phone's Wi-Fi is turned on.
     */
    public static boolean isWifiEnabled(Context context) {
        final WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        return wifi != null && wifi.isWifiEnabled();
    }

    public static boolean isLocationEnabled(Context context) {
        final LocationManager lm = context.getSystemService(LocationManager.class);
        return lm != null && LocationManagerCompat.isLocationEnabled(lm);
    }

    // ---------------------------------------------------------------------------------------
    // Checking

    /**
     * @return whether the phone is near one of the networks right now.
     */
    // The location permission is checked by hasBackgroundPermission() above.
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    public static Result check(Context context, List<Network> networks) {
        return check(context, networks, true);
    }

    /**
     * @param scan {@code true} to ask for a fresh scan for the next check. Screens that only show
     *             the status pass {@code false}, because Android limits how often apps may scan.
     */
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    public static Result check(Context context, List<Network> networks, boolean scan) {
        if (networks.isEmpty()) {
            return Result.NEAR;
        }
        if (!hasBackgroundPermission(context)) {
            return Result.UNKNOWN;
        }
        final WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        if (wifi == null) {
            return Result.UNKNOWN;
        }
        if (!wifi.isWifiEnabled()) {
            // Old scan results and "Wi-Fi scanning" could still make the phone look near; with
            // Wi-Fi off the alarm only rings if the profile asks for that.
            return Result.WIFI_OFF;
        }
        try {
            final WifiInfo info = wifi.getConnectionInfo();
            if (info != null) {
                for (Network network : networks) {
                    if (network.matches(info.getSSID(), info.getBSSID())) {
                        return Result.NEAR;
                    }
                }
            }
            final long nowMicros = SystemClock.elapsedRealtime() * 1000L;
            final List<ScanResult> results = wifi.getScanResults();
            if (results != null) {
                for (ScanResult result : results) {
                    if (nowMicros - result.timestamp > MAX_SCAN_AGE_MS * 1000L) {
                        continue;
                    }
                    for (Network network : networks) {
                        if (network.matches(result.SSID, result.BSSID)) {
                            return Result.NEAR;
                        }
                    }
                }
            }
            if (scan) {
                // Fresh results for the next check, for example after a snooze.
                wifi.startScan();
            }
        } catch (Exception e) {
            LogUtils.e("CalendarWifi: could not check Wi-Fi", e);
            return Result.UNKNOWN;
        }
        if (!isLocationEnabled(context)) {
            return Result.UNKNOWN;
        }
        return Result.NOT_NEAR;
    }

    /**
     * @return the networks the phone is connected to or sees in range, strongest first, to choose
     * from. Empty without location permission.
     */
    // The location permission is checked by hasForegroundPermission() above.
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    public static List<Seen> getNearbyNetworks(Context context) {
        final Map<String, Seen> byBssid = new LinkedHashMap<>();
        if (!hasForegroundPermission(context)) {
            return new ArrayList<>();
        }
        final WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        if (wifi == null) {
            return new ArrayList<>();
        }
        try {
            final WifiInfo info = wifi.getConnectionInfo();
            final String connectedSsid = info == null ? null : cleanSsid(info.getSSID());
            final String connectedBssid = info == null ? "" : normalizeBssid(info.getBSSID());
            if (connectedSsid != null && !connectedBssid.isEmpty() && !connectedBssid.equals(HIDDEN_BSSID)) {
                byBssid.put(connectedBssid, new Seen(connectedSsid, connectedBssid, info.getRssi(), true));
            }
            final List<ScanResult> results = wifi.getScanResults();
            if (results != null) {
                for (ScanResult result : results) {
                    final String bssid = normalizeBssid(result.BSSID);
                    if (bssid.isEmpty() || byBssid.containsKey(bssid)) {
                        continue;
                    }
                    final String ssid = result.SSID == null ? "" : result.SSID;
                    byBssid.put(bssid, new Seen(ssid, bssid, result.level, bssid.equals(connectedBssid)));
                }
            }
            wifi.startScan();
        } catch (Exception e) {
            LogUtils.e("CalendarWifi: could not list Wi-Fi networks", e);
        }
        final List<Seen> seen = new ArrayList<>(byBssid.values());
        Collections.sort(seen, (a, b) -> {
            if (a.connected != b.connected) {
                return a.connected ? -1 : 1;
            }
            return Integer.compare(b.level, a.level);
        });
        return seen;
    }
}

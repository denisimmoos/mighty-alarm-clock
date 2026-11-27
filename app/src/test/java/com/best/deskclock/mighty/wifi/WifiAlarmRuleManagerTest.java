// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.wifi;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ContentResolver;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.test.core.app.ApplicationProvider;

import com.best.deskclock.provider.Alarm;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWifiInfo;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class WifiAlarmRuleManagerTest {

    private Alarm addRuleAlarm(ContentResolver cr) {
        final Alarm alarm = new Alarm();
        alarm.hour = 7;
        alarm.enabled = true;
        alarm.wifiRuleEnabled = true;
        alarm.wifiSsid = "Home";
        alarm.wifiCondition = Alarm.WIFI_CONDITION_ABSENT;
        alarm.wifiAction = Alarm.WIFI_ACTION_DISABLE;
        alarm.addAlarm(cr);
        return alarm;
    }

    private void connect(WifiManager wifi, String ssid, SupplicantState state) {
        final WifiInfo info = ShadowWifiInfo.newInstance();
        if (ssid != null) {
            Shadows.shadowOf(info).setSSID(ssid);
        }
        Shadows.shadowOf(info).setSupplicantState(state);
        Shadows.shadowOf(wifi).setConnectionInfo(info);
    }

    @Test
    public void unreadableWifiNameNeverSwitchesAnAlarmOff() {
        final Application context = ApplicationProvider.getApplicationContext();
        final ContentResolver cr = context.getContentResolver();
        final WifiManager wifi = context.getSystemService(WifiManager.class);
        wifi.setWifiEnabled(true);
        final Alarm alarm = addRuleAlarm(cr);

        // Connected, but Android hides the name (no location permission, or location off).
        connect(wifi, WifiManager.UNKNOWN_SSID, SupplicantState.COMPLETED);
        WifiAlarmRuleManager.evaluateRules(context);
        assertTrue(Alarm.getAlarm(cr, alarm.id).enabled);

        // Really not connected: the rule applies as before.
        connect(wifi, null, SupplicantState.DISCONNECTED);
        WifiAlarmRuleManager.evaluateRules(context);
        assertFalse(Alarm.getAlarm(cr, alarm.id).enabled);
    }
}

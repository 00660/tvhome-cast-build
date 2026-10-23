package com.gathin.tvhome.hk;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 * Advertises the HomeKit accessory over mDNS (_hap._tcp) so the iOS "Home" app can discover it.
 * Uses JmDNS (open-source Java mDNS). Holds a MulticastLock (Android 4.4 drops multicast otherwise).
 */
public final class HapAdvertiser {
    private static final String TAG = "HapAdvertise";
    private static final String SERVICE = "_hap._tcp.local.";
    private JmDNS jmdns;
    private ServiceInfo info;
    private WifiManager.MulticastLock lock;

    public boolean start(Context ctx, String accessoryName, String accessoryId, int port, int configNum) {
        try {
            WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) { lock = wifi.createMulticastLock("hap-mdns"); lock.setReferenceCounted(false); lock.acquire(); }
            InetAddress addr = InetAddress.getByName(findLocalIp());
            jmdns = JmDNS.create(addr, accessoryName);

            Map<String, String> txt = new HashMap<>();
            txt.put("c#", String.valueOf(configNum));   // configuration number
            txt.put("ff", "0");                          // feature flags
            txt.put("id", accessoryId);                  // accessory pairing identifier (Device ID)
            txt.put("md", "TVHome");                     // model
            txt.put("pv", "1.0");                        // protocol version
            txt.put("s#", "1");                          // current state
            txt.put("sf", "1");                          // status flags (1 = not paired)
            txt.put("ci", "31");                         // category: Television? HAP category 31 = Television

            info = ServiceInfo.create(SERVICE, accessoryName, port, 0, 0, true, txt);
            jmdns.registerService(info);
            Log.i(TAG, "advertised _hap._tcp '" + accessoryName + "' on " + addr + ":" + port + " id=" + accessoryId);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "advertise failed", t);
            return false;
        }
    }

    public void stop() {
        try { if (jmdns != null && info != null) jmdns.unregisterService(info); } catch (Throwable ignored) { }
        try { if (jmdns != null) jmdns.close(); } catch (Throwable ignored) { }
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Throwable ignored) { }
        jmdns = null; info = null; lock = null;
    }

    private static String findLocalIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Throwable ignored) { }
        return "127.0.0.1";
    }
}

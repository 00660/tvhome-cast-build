package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import io.github.hapjava.server.impl.HomekitRoot;
import io.github.hapjava.server.impl.HomekitServer;
import io.github.hapjava.server.impl.HomekitStandaloneAccessoryServer;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Starts the HomeKit media accessory (a Television) so the iOS "Home" app can pair it and control
 * casting/remote keys over HAP. Pure-Java HAP (hap-java) + BouncyCastle, all 4.4-compatible.
 * Pairing code is the default 123-44-321 unless changed.
 */
final class HomeKitManager {
    private static final String TAG = "HomeKit";
    static final String DEFAULT_PIN = "12344321";   // shown as 123-44-321

    private static HomeKitManager instance;
    private HomekitServer server;
    private HomekitStandaloneAccessoryServer standalone;
    private HomekitRoot bridge;
    private HomeKitTvAccessory accessory;
    private boolean running;

    static synchronized HomeKitManager get() {
        if (instance == null) instance = new HomeKitManager();
        return instance;
    }

    String getPinText() {
        return DEFAULT_PIN.substring(0, 3) + "-" + DEFAULT_PIN.substring(3, 5) + "-" + DEFAULT_PIN.substring(5);
    }

    synchronized boolean isRunning() { return running; }

    synchronized void start(final Context ctx, final Runnable onActiveOn, final Runnable onActiveOff) {
        if (running) return;
        new Thread(() -> {
            try {
                HomeKitAuthInfoImpl auth = new HomeKitAuthInfoImpl(ctx.getApplicationContext(), DEFAULT_PIN);
                accessory = new HomeKitTvAccessory(1, "电视投屏", onActiveOn, onActiveOff);
                server = new HomekitServer(
                        InetAddress.getByName(findLocalIp()), 47128, 47129);
                standalone = server.createStandaloneAccessory(auth, accessory);
                standalone.start();
                running = true;
                Log.i(TAG, "HomeKit server started, pin=" + getPinText());
            } catch (Throwable t) {
                Log.e(TAG, "HomeKit start failed", t);
                running = false;
            }
        }, "homekit-start").start();
    }

    synchronized void stop() {
        try { if (standalone != null) standalone.stop(); } catch (Throwable ignored) { }
        try { if (bridge != null) bridge.stop(); } catch (Throwable ignored) { }
        try { if (server != null) server.stop(); } catch (Throwable ignored) { }
        standalone = null; bridge = null; server = null; running = false;
    }

    void setActiveFromPlayer(boolean a) { if (accessory != null) accessory.setActiveFromPlayer(a); }

    private static String findLocalIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) { }
        return "127.0.0.1";
    }
}

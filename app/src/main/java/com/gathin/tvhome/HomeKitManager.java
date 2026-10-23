package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import com.gathin.tvhome.hk.HapServer;
import com.gathin.tvhome.hk.TvAccessory;

/**
 * Starts the self-written HomeKit accessory (HAP implemented from spec with BouncyCastle) so the iOS
 * "Home" app can pair and control casting over encrypted HAP. Setup code 123-44-321. API19-safe.
 */
final class HomeKitManager {
    private static final String TAG = "HomeKit";
    static final String DEFAULT_PIN = "12344321";   // shown as 123-44-321
    private static final int PORT = 51826;

    private static HomeKitManager instance;
    private HapServer server;
    private TvAccessory accessory;
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
                accessory = new TvAccessory(onActiveOn, onActiveOff);
                server = new HapServer(ctx.getApplicationContext(), accessory, PORT);
                server.start();
                running = true;
                Log.i(TAG, "HomeKit HAP started on " + PORT + ", pin=" + getPinText());
            } catch (Throwable t) {
                Log.e(TAG, "HomeKit start failed", t);
                running = false;
            }
        }, "homekit-start").start();
    }

    synchronized void stop() {
        try { if (server != null) server.stop(); } catch (Throwable ignored) { }
        server = null; running = false;
    }
}

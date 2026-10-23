package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import com.gathin.tvhome.hk.HapAdvertiser;
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
    private HapAdvertiser advertiser;
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
                accessory = new TvAccessory(onActiveOn, onActiveOff, key -> {
                    int kc = mapRemoteKey(key);
                    if (kc != 0) RemoteKeys.sendKey(ctx.getApplicationContext(), kc);
                });
                server = new HapServer(ctx.getApplicationContext(), accessory, PORT);
                server.start();
                advertiser = new HapAdvertiser();
                advertiser.start(ctx.getApplicationContext(), "电视投屏", server.accessoryId(), PORT, 1);
                running = true;
                Log.i(TAG, "HomeKit HAP started on " + PORT + ", pin=" + getPinText() + ", advertised");
            } catch (Throwable t) {
                Log.e(TAG, "HomeKit start failed", t);
                running = false;
            }
        }, "homekit-start").start();
    }

    private static int mapRemoteKey(int key) {
        switch (key) {
            case 4: return android.view.KeyEvent.KEYCODE_DPAD_UP;
            case 5: return android.view.KeyEvent.KEYCODE_DPAD_DOWN;
            case 6: return android.view.KeyEvent.KEYCODE_DPAD_LEFT;
            case 7: return android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
            case 8: return android.view.KeyEvent.KEYCODE_DPAD_CENTER;
            case 9: return android.view.KeyEvent.KEYCODE_BACK;
            case 10: return android.view.KeyEvent.KEYCODE_HOME;
            case 11: return android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case 0: return android.view.KeyEvent.KEYCODE_MEDIA_REWIND;
            case 1: return android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            case 2: return android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
            case 3: return android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case 15: return android.view.KeyEvent.KEYCODE_INFO;
            default: return 0;
        }
    }

    synchronized void stop() {
        try { if (advertiser != null) advertiser.stop(); } catch (Throwable ignored) { }
        try { if (server != null) server.stop(); } catch (Throwable ignored) { }
        server = null; running = false;
    }
}

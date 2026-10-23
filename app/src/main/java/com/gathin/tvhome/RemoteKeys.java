package com.gathin.tvhome;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Injects remote-control key events into the TV from the HomeKit accessory. On Android 4.4 a normal
 * app cannot inject keys, so this uses root (`su -c input keyevent N`, the stock `input` tool is
 * present on 4.4). All calls are plain Java + ProcessBuilder — no API21+ APIs.
 */
final class RemoteKeys {
    private static final String TAG = "RemoteKeys";
    private static volatile boolean rootChecked = false;
    private static volatile boolean rootOk = false;

    private RemoteKeys() { }

    /** Whether root (su) is usable to inject keys. */
    static boolean hasRoot() {
        if (!rootChecked) {
            rootChecked = true;
            rootOk = run("su -c id") != null && true;
        }
        return rootOk;
    }

    static void key(int keycode) {
        sendKey(keycode);
    }

    static void sendKey(int keycode) {
        // Prefer root (works on unrooted user, rooted shell). Fall back to plain input if permitted.
        String cmd = "input keyevent " + keycode;
        if (run("su -c \"" + cmd + "\"") == null) {
            run(cmd);
        }
        Log.i(TAG, "key " + keycode);
    }

    private static String run(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            int rc = p.waitFor();
            return rc == 0 ? sb.toString() : null;
        } catch (Throwable t) {
            Log.e(TAG, "run failed: " + cmd, t);
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
    }
}

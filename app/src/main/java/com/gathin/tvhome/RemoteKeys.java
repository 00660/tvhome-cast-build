package com.gathin.tvhome;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Injects real remote-control key events into the TV from the HomeKit accessory. On Android 4.4 a
 * normal app cannot inject keys (INJECT_EVENTS is a system permission), so this runs the stock
 * `input keyevent N` tool through root (`su -c ...`) — the same mechanism this TV's system uses.
 * If root is unavailable it falls back to `input` directly (works on some firmwares / debuggable
 * builds). Pure Java + ProcessBuilder, no API21+ APIs.
 */
final class RemoteKeys {
    private static final String TAG = "RemoteKeys";
    private static volatile int rootState = 0; // 0 unknown, 1 ok, -1 unavailable

    private RemoteKeys() { }

    /** True if root works (cached). Runs `su -c id` once. */
    static synchronized boolean hasRoot() {
        if (rootState == 0) {
            String out = exec("su -c id");
            rootState = (out != null && out.contains("uid=0")) ? 1 : -1;
            Log.i(TAG, "root state=" + rootState);
        }
        return rootState == 1;
    }

    /** Inject a keycode: loopback adb (no root) → root `input` → plain `input`. */
    static void sendKey(android.content.Context ctx, int keycode) {
        String cmd = "input keyevent " + keycode;
        boolean done = false;
        // 1) loopback adb (shell uid, no root needed) if available
        try { if (ctx != null) done = AdbRemote.get().sendKey(ctx.getApplicationContext(), keycode); } catch (Throwable ignored) { }
        // 2) root
        if (!done && hasRoot()) {
            done = exec("su -c \"" + cmd + "\"") != null;
        }
        if (!done) {
            // Fallback: run `input` directly (same uid). Works on many 4.4 firmwares.
            String r = exec(cmd);
            if (r != null) done = true;
        }
        Log.i(TAG, "key " + keycode + (done ? " ok" : " FAILED"));
    }

    /** Run a shell command, return stdout+stderr if exit 0, else null. */
    private static String exec(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            int rc = p.waitFor();
            return rc == 0 ? sb.toString() : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
    }
}

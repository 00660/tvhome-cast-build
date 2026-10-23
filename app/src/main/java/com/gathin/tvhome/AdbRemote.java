package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import java.io.File;

import dadb.AdbKeyPair;
import dadb.Dadb;

/**
 * No-root remote control via loopback ADB. Connects to the device's own adbd (127.0.0.1:5555) and
 * runs `input keyevent N` as the shell user (uid 2000), which holds INJECT_EVENTS — so keys really
 * reach the foreground app without root. Requires adbd reachable on localhost and either
 * ro.adb.secure=0 (no key needed) or the generated key once authorised. Pure Java + dadb.
 */
final class AdbRemote {
    private static final String TAG = "AdbRemote";
    private static AdbRemote sInstance;
    private Dadb dadb;
    private boolean tried;

    static synchronized AdbRemote get() {
        if (sInstance == null) sInstance = new AdbRemote();
        return sInstance;
    }

    /** Connect lazily. Returns true if a working dadb connection is available. */
    synchronized boolean ensure(Context ctx) {
        if (dadb != null) return true;
        if (tried) return false;
        tried = true;
        try {
            File priv = new File(ctx.getFilesDir(), "adbkey");
            File pub = new File(ctx.getFilesDir(), "adbkey.pub");
            if (!priv.exists() || !pub.exists()) {
                AdbKeyPair.generate(priv, pub);
                Log.i(TAG, "generated adb keypair");
            }
            AdbKeyPair kp = AdbKeyPair.read(priv);
            Dadb d = Dadb.create("127.0.0.1", 5555, kp);
            // probe
            d.shell("echo ready");
            dadb = d;
            Log.i(TAG, "connected to loopback adbd");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "loopback adb unavailable: " + t);
            dadb = null;
            return false;
        }
    }

    /** Send a key via loopback adb. Returns true if the shell command ran. */
    synchronized boolean sendKey(Context ctx, int keycode) {
        if (!ensure(ctx)) return false;
        try {
            dadb.shell("input keyevent " + keycode);
            Log.i(TAG, "adb key " + keycode + " ok");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "adb key failed", t);
            dadb = null; tried = false;   // allow reconnect next time
            return false;
        }
    }
}

package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import java.io.File;

import dadb.AdbKeyPair;
import dadb.Dadb;

/**
 * No-root remote control via loopback ADB, device-agnostic. Discovers adbd on common localhost
 * ports (and any port from service.adb.tcp.port), connects with an on-device generated key, and runs
 * `input keyevent N` as the shell user (uid 2000, holds INJECT_EVENTS) — so keys reach the foreground
 * app without root. Works across TVs/boxes; falls back to root/plain input in RemoteKeys when adbd
 * is not reachable. Pure Java + dadb, no API21+ calls.
 */
final class AdbRemote {
    private static final String TAG = "AdbRemote";
    private static AdbRemote sInstance;
    private Dadb dadb;
    private int port = -1;
    private boolean triedAll;

    static synchronized AdbRemote get() {
        if (sInstance == null) sInstance = new AdbRemote();
        return sInstance;
    }

    synchronized boolean ensure(Context ctx) {
        if (dadb != null) return true;
        if (triedAll) return false;
        triedAll = true;
        int[] ports = discoverPorts();
        for (int p : ports) {
            try {
                File priv = new File(ctx.getFilesDir(), "adbkey");
                File pub = new File(ctx.getFilesDir(), "adbkey.pub");
                if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub);
                AdbKeyPair kp = AdbKeyPair.read(priv);
                Dadb d = Dadb.create("127.0.0.1", p, kp);
                d.shell("echo ready");
                dadb = d; port = p;
                Log.i(TAG, "loopback adbd on 127.0.0.1:" + p);
                return true;
            } catch (Throwable t) {
                Log.i(TAG, "no adbd on " + p + " (" + t + ")");
            }
        }
        return false;
    }

    synchronized boolean sendKey(Context ctx, int keycode) {
        if (!ensure(ctx)) return false;
        try {
            dadb.shell("input keyevent " + keycode);
            Log.i(TAG, "adb key " + keycode + " ok via " + port);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "adb key failed", t);
            dadb = null; triedAll = false;
            return false;
        }
    }

    /** Common loopback adbd ports, plus the firmware's declared tcp port. */
    private int[] discoverPorts() {
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        for (String prop : new String[]{"service.adb.tcp.port", "persist.adb.tcp.port"}) {
            try {
                Process p = new ProcessBuilder("/system/bin/sh", "-c", "getprop " + prop).redirectErrorStream(true).start();
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                String v = r.readLine();
                p.waitFor();
                if (v != null && !v.trim().isEmpty()) set.add(Integer.parseInt(v.trim()));
            } catch (Throwable ignored) { }
        }
        set.add(5555); set.add(5556); set.add(5557); set.add(5037);
        int[] out = new int[set.size()];
        int i = 0; for (int v : set) out[i++] = v;
        return out;
    }
}

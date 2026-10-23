package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/** Runtime services controller: AirPlay on/off, HomeKit status, remote-channel detection, boot flag. */
final class ServiceController {
    private static final String PREFS = "services";
    private static final String KEY_AIRPLAY = "airplay_on";
    private static final String KEY_HOMEKIT = "homekit_on";
    private static final String KEY_BOOT = "boot_start";

    private ServiceController() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ---- AirPlay ----
    static boolean isAirPlayOn(Context c) { return prefs(c).getBoolean(KEY_AIRPLAY, false); }
    static boolean setAirPlay(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_AIRPLAY, on).apply();
        if (on) {
            return AirPlayReceiver.get().start(c, c.getString(R.string.airplay_name), 1920, 1080);
        } else {
            AirPlayReceiver.get().stop();
            return true;
        }
    }

    // ---- HomeKit ----
    static boolean isHomeKitOn(Context c) { return prefs(c).getBoolean(KEY_HOMEKIT, true); }
    static void setHomeKit(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_HOMEKIT, on).apply();
        if (on) HomeKitManager.get().start(c, null, null);
        else HomeKitManager.get().stop();
    }

    // ---- Boot ----
    static boolean isBootStart(Context c) { return prefs(c).getBoolean(KEY_BOOT, true); }
    static void setBootStart(Context c, boolean on) { prefs(c).edit().putBoolean(KEY_BOOT, on).apply(); }

    /** Detect which remote channel is usable right now: "adb" / "root" / "input" / "-". */
    static String remoteChannel(Context c) {
        try {
            if (AdbRemote.get().ensure(c.getApplicationContext())) return "回环ADB";
        } catch (Throwable ignored) { }
        if (RemoteKeys.hasRoot()) return "Root";
        return "直接input";
    }

    /** Apply persisted services at startup (launcher onCreate / boot). */
    static void applyStartup(Context c) {
        if (isHomeKitOn(c)) HomeKitManager.get().start(c,
                () -> Log.i("Svc", "homekit active on"),
                () -> Log.i("Svc", "homekit active off"));
        if (isAirPlayOn(c)) AirPlayReceiver.get().start(c, c.getString(R.string.airplay_name), 1920, 1080);
    }
}

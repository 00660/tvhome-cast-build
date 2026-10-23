package com.gathin.tvhome.hk;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Persistent HomeKit accessory identity: pairing id, Ed25519 long-term key (seed), and paired
 * controllers (id -> LTPK). Stored in SharedPreferences. Pure Java, API19-safe.
 */
final class HapKeyStore {
    private static final String PREF = "hk_store";
    private final SharedPreferences p;
    private final byte[] ed25519Seed;
    private final String accessoryId;

    HapKeyStore(Context c) {
        p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String seedHex = p.getString("ed25519_seed", null);
        if (seedHex == null) {
            byte[] s = new byte[32]; new SecureRandom().nextBytes(s);
            p.edit().putString("ed25519_seed", hex(s)).commit();
            ed25519Seed = s;
        } else ed25519Seed = unhex(seedHex);
        String id = p.getString("accessory_id", null);
        if (id == null) {
            // HAP accessory pairing identifier: must be a valid MAC-like format, e.g. AA:BB:CC:DD:EE:FF
            id = "02:11:22:33:44:55";
            p.edit().putString("accessory_id", id).commit();
        }
        accessoryId = id;
    }

    String accessoryId() { return accessoryId; }
    byte[] ed25519Seed() { return ed25519Seed; }
    byte[] ed25519Public() { return Ed25519.publicFromSeed(ed25519Seed); }
    byte[] ed25519Sign(byte[] data) { return Ed25519.sign(ed25519Seed, data); }

    void saveController(String controllerId, byte[] ltpk) {
        p.edit().putString("ctl_" + controllerId, hex(ltpk)).commit();
    }
    byte[] controllerLtpk(String controllerId) {
        String v = p.getString("ctl_" + controllerId, null);
        return v == null ? null : unhex(v);
    }
    boolean hasControllers() {
        for (String k : p.getAll().keySet()) if (k.startsWith("ctl_")) return true;
        return false;
    }

    static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }
    static byte[] unhex(String s) { int n = s.length()/2; byte[] b = new byte[n]; for (int i=0;i<n;i++) b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16); return b; }
}

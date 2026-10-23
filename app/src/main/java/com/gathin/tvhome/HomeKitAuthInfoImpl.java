package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import io.github.hapjava.server.HomekitAuthInfo;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * HomekitAuthInfo backed by SharedPreferences. Stores the setup PIN, accessory MAC and the
 * long-term private key; remembers paired controllers (by user id -> public key). Pure Java, no
 * Android-API21 calls.
 */
final class HomeKitAuthInfoImpl implements HomekitAuthInfo {
    private static final String TAG = "HomeKit";
    private static final String PREF = "homekit_auth";
    private final SharedPreferences prefs;
    private final String pin;
    private final String mac;
    private final BigInteger salt;
    private final byte[] privateKey;

    HomeKitAuthInfoImpl(Context c, String pin) {
        this.prefs = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        this.pin = pin;
        String m = prefs.getString("mac", null);
        if (m == null) {
            m = io.github.hapjava.server.impl.HomekitServer.generateMac();
            prefs.edit().putString("mac", m).commit();
        }
        this.mac = m;
        String s = prefs.getString("salt", null);
        if (s == null) {
            BigInteger ns = io.github.hapjava.server.impl.HomekitServer.generateSalt();
            prefs.edit().putString("salt", ns.toString(16)).commit();
            this.salt = ns;
        } else {
            this.salt = new BigInteger(s, 16);
        }
        String k = prefs.getString("privkey", null);
        byte[] pk = null;
        try {
            if (k == null) {
                pk = io.github.hapjava.server.impl.HomekitServer.generateKey();
                prefs.edit().putString("privkey", bytesToHex(pk)).commit();
            } else {
                pk = hexToBytes(k);
            }
        } catch (Throwable t) {
            Log.e(TAG, "key gen failed", t);
            pk = new byte[32];
        }
        this.privateKey = pk;
    }

    @Override public String getPin() { return pin; }
    @Override public String getMac() { return mac; }
    @Override public BigInteger getSalt() { return salt; }
    @Override public byte[] getPrivateKey() { return privateKey; }

    @Override public void createUser(String username, byte[] publicKey, boolean admin) {
        prefs.edit().putString("u_" + username, bytesToHex(publicKey)).commit();
        Set<String> users = new HashSet<>(prefs.getStringSet("users", Collections.<String>emptySet()));
        users.add(username);
        prefs.edit().putStringSet("users", users).commit();
    }

    @Override public void removeUser(String username) {
        prefs.edit().remove("u_" + username).commit();
        Set<String> users = new HashSet<>(prefs.getStringSet("users", Collections.<String>emptySet()));
        users.remove(username);
        prefs.edit().putStringSet("users", users).commit();
    }

    @Override public byte[] getUserPublicKey(String username) {
        String v = prefs.getString("u_" + username, null);
        return v == null ? null : hexToBytes(v);
    }

    @Override public Collection<String> listUsers() {
        return new HashSet<>(prefs.getStringSet("users", Collections.<String>emptySet()));
    }

    @Override public boolean hasUser() {
        return !prefs.getStringSet("users", Collections.<String>emptySet()).isEmpty();
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
    private static byte[] hexToBytes(String s) {
        int n = s.length() / 2; byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}

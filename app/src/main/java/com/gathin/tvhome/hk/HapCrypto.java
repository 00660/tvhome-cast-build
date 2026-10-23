package com.gathin.tvhome.hk;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

/** HAP crypto helpers: HKDF-SHA512, constant-time equal, hex. */
public final class HapCrypto {
    private HapCrypto() { }

    /** HKDF-SHA512 (HAP uses SHA-512 for all HKDF derivations). */
    public static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int len) {
        Digest digest = new SHA512Digest();
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(digest);
        hkdf.init(new HKDFParameters(ikm, salt, info));
        byte[] out = new byte[len];
        hkdf.generateBytes(out, 0, len);
        return out;
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0; for (byte[] p : parts) if (p != null) n += p.length;
        byte[] out = new byte[n]; int o = 0;
        for (byte[] p : parts) if (p != null) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }
}

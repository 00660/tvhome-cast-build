package com.gathin.tvhome.hk;

import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * HAP encrypted session transport: each frame = 2-byte little-endian length, then (ciphertext||16-byte
 * tag). Nonce = 4 zero bytes + 8-byte LE frame counter (per direction). Wraps the plain socket streams.
 */
final class HapCipher {
    private final InputStream in;
    private final OutputStream out;
    private final byte[] readKey, writeKey;
    private long readCounter = 0, writeCounter = 0;

    HapCipher(InputStream in, OutputStream out, byte[] readKey, byte[] writeKey) {
        this.in = in; this.out = out; this.readKey = readKey; this.writeKey = writeKey;
    }

    byte[] read() throws Exception {
        byte[] lenB = readFully(in, 2);
        int len = (lenB[0] & 0xFF) | ((lenB[1] & 0xFF) << 8);
        if (len <= 16 || len > 0x400 + 16) return null;
        byte[] frame = readFully(in, len);
        byte[] nonce = nonce(readCounter++);
        return decrypt(readKey, nonce, frame);
    }

    void write(byte[] plain) throws Exception {
        byte[] nonce = nonce(writeCounter++);
        byte[] enc = encrypt(writeKey, nonce, plain);
        int len = enc.length;
        out.write(new byte[]{(byte) (len & 0xFF), (byte) ((len >> 8) & 0xFF)});
        out.write(enc);
        out.flush();
    }

    private static byte[] nonce(long counter) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) n[4 + i] = (byte) ((counter >> (8 * i)) & 0xFF);
        return n;
    }

    private static byte[] encrypt(byte[] key, byte[] nonce, byte[] plain) throws Exception {
        ChaCha20Poly1305 c = new ChaCha20Poly1305();
        c.init(true, new AEADParameters(new KeyParameter(key), 128, nonce));
        byte[] out = new byte[c.getOutputSize(plain.length)];
        int n = c.processBytes(plain, 0, plain.length, out, 0);
        n += c.doFinal(out, n);
        if (n != out.length) { byte[] r = new byte[n]; System.arraycopy(out, 0, r, 0, n); return r; }
        return out;
    }
    private static byte[] decrypt(byte[] key, byte[] nonce, byte[] cipher) throws Exception {
        ChaCha20Poly1305 c = new ChaCha20Poly1305();
        c.init(false, new AEADParameters(new KeyParameter(key), 128, nonce));
        byte[] out = new byte[c.getOutputSize(cipher.length)];
        int n = c.processBytes(cipher, 0, cipher.length, out, 0);
        n += c.doFinal(out, n);
        byte[] r = new byte[n]; System.arraycopy(out, 0, r, 0, n); return r;
    }
    private static byte[] readFully(InputStream in, int n) throws Exception {
        byte[] b = new byte[n]; int off = 0;
        while (off < n) { int r = in.read(b, off, n - off); if (r < 0) return null; off += r; }
        return b;
    }
}

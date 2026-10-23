package com.gathin.tvhome.hk;

import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * HAP encrypted session transport. Each frame = 2-byte little-endian length, then (ciphertext||16-byte
 * tag). HAP requires plaintext per frame <= 1024 bytes, so writes are chunked into <=1024-byte frames;
 * reads return one frame at a time (<=1024). Nonce = 4 zero bytes + 8-byte LE frame counter.
 */
final class HapCipher {
    private static final int MAX_FRAME = 1024;
    private final InputStream in;
    private final OutputStream out;
    private final byte[] readKey, writeKey;
    private long readCounter = 0, writeCounter = 0;

    HapCipher(InputStream in, OutputStream out, byte[] readKey, byte[] writeKey) {
        this.in = in; this.out = out; this.readKey = readKey; this.writeKey = writeKey;
    }

    /** Read one decrypted frame (<=1024 bytes), or null on EOF. */
    byte[] read() throws Exception {
        byte[] lenB = readFully(in, 2);
        if (lenB == null) return null;
        int plainLen = (lenB[0] & 0xFF) | ((lenB[1] & 0xFF) << 8);
        if (plainLen <= 0 || plainLen > MAX_FRAME) return null;
        byte[] frame = readFully(in, plainLen + 16);
        if (frame == null) return null;
        return decrypt(readKey, nonce(readCounter++), lenB, frame);
    }

    /** Write plaintext, splitting into <=1024-byte frames as HAP requires. */
    void write(byte[] plain) throws Exception {
        int off = 0;
        while (off < plain.length) {
            int chunk = Math.min(MAX_FRAME, plain.length - off);
            byte[] part = new byte[chunk];
            System.arraycopy(plain, off, part, 0, chunk);
            byte[] lenB = new byte[]{(byte) (chunk & 0xFF), (byte) ((chunk >> 8) & 0xFF)};
            byte[] enc = encrypt(writeKey, nonce(writeCounter++), lenB, part);
            out.write(lenB);
            out.write(enc);
            off += chunk;
        }
        out.flush();
    }

    private static byte[] nonce(long counter) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) n[4 + i] = (byte) ((counter >> (8 * i)) & 0xFF);
        return n;
    }

    private static byte[] encrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plain) throws Exception {
        ChaCha20Poly1305 c = new ChaCha20Poly1305();
        c.init(true, new AEADParameters(new KeyParameter(key), 128, nonce));
        c.processAADBytes(aad, 0, aad.length);
        byte[] o = new byte[c.getOutputSize(plain.length)];
        int n = c.processBytes(plain, 0, plain.length, o, 0); n += c.doFinal(o, n);
        if (n != o.length) { byte[] r = new byte[n]; System.arraycopy(o, 0, r, 0, n); return r; }
        return o;
    }
    private static byte[] decrypt(byte[] key, byte[] nonce, byte[] aad, byte[] cipher) throws Exception {
        ChaCha20Poly1305 c = new ChaCha20Poly1305();
        c.init(false, new AEADParameters(new KeyParameter(key), 128, nonce));
        c.processAADBytes(aad, 0, aad.length);
        byte[] o = new byte[c.getOutputSize(cipher.length)];
        int n = c.processBytes(cipher, 0, cipher.length, o, 0); n += c.doFinal(o, n);
        byte[] r = new byte[n]; System.arraycopy(o, 0, r, 0, n); return r;
    }
    private static byte[] readFully(InputStream in, int n) throws Exception {
        byte[] b = new byte[n]; int off = 0;
        while (off < n) { int r = in.read(b, off, n - off); if (r < 0) return null; off += r; }
        return b;
    }

    /** Read a full HTTP response (headers+body) assembled from multiple encrypted frames. */
    static String readHttpResponse(HapCipher cipher) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int contentLength = -1, headerEnd = -1;
        while (true) {
            byte[] chunk = cipher.read();
            if (chunk == null) return null;
            buf.write(chunk, 0, chunk.length);
            byte[] arr = buf.toByteArray();
            if (headerEnd < 0) {
                String s = new String(arr, "UTF-8");
                int idx = s.indexOf("\r\n\r\n");
                if (idx >= 0) {
                    headerEnd = idx + 4;
                    for (String line : s.substring(0, idx).split("\r\n")) {
                        int c = line.indexOf(':');
                        if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Content-Length"))
                            contentLength = Integer.parseInt(line.substring(c + 1).trim());
                    }
                }
            }
            if (headerEnd >= 0 && contentLength >= 0 && arr.length - headerEnd >= contentLength) {
                return new String(arr, "UTF-8");
            }
        }
    }
}

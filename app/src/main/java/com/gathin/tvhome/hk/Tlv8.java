package com.gathin.tvhome.hk;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** HAP TLV8 encoder/decoder (type, length, value; values >255 split into 255-byte chunks). */
public final class Tlv8 {
    public static final int METHOD = 0x00;
    public static final int IDENTIFIER = 0x01;
    public static final int SALT = 0x02;
    public static final int PUBLIC_KEY = 0x03;
    public static final int PROOF = 0x04;
    public static final int ENCRYPTED_DATA = 0x05;
    public static final int STATE = 0x06;
    public static final int ERROR = 0x07;
    public static final int SIGNATURE = 0x0A;
    public static final int PERMISSIONS = 0x0B;

    public static final class Tlv {
        public final int type;
        public final byte[] value;
        public Tlv(int t, byte[] v) { type = t; value = v; }
    }

    public static byte[] encode(int type, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int off = 0;
        if (value == null || value.length == 0) { out.write(type); out.write(0); return out.toByteArray(); }
        while (off < value.length) {
            int chunk = Math.min(255, value.length - off);
            out.write(type); out.write(chunk); out.write(value, off, chunk);
            off += chunk;
        }
        return out.toByteArray();
    }

    public static byte[] encodeList(List<Tlv> tlvs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Tlv t : tlvs) { byte[] b = encode(t.type, t.value); out.write(b, 0, b.length); }
        return out.toByteArray();
    }

    public static List<Tlv> decode(byte[] data) {
        List<Tlv> out = new ArrayList<>();
        int i = 0;
        int curType = -1; ByteArrayOutputStream cur = null;
        while (i + 1 < data.length) {
            int type = data[i] & 0xFF, len = data[i + 1] & 0xFF;
            int start = i + 2;
            if (start + len > data.length) break;
            byte[] val = new byte[len];
            System.arraycopy(data, start, val, 0, len);
            if (type == curType) { cur.write(val, 0, len); }
            else {
                if (cur != null) out.add(new Tlv(curType, cur.toByteArray()));
                curType = type; cur = new ByteArrayOutputStream(); cur.write(val, 0, len);
            }
            i = start + len;
        }
        if (cur != null) out.add(new Tlv(curType, cur.toByteArray()));
        return out;
    }

    public static byte[] find(List<Tlv> list, int type) {
        for (Tlv t : list) if (t.type == type) return t.value;
        return null;
    }
}

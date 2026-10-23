package com.gathin.tvhome.hk;

import org.bouncycastle.crypto.agreement.srp.SRP6Server;
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups;
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.List;

/**
 * HAP accessory-side Pair Setup (M1..M6): SRP6a (SRP6-NIST-3072, SHA-512), Ed25519 signatures and
 * ChaCha20-Poly1305 encryption — implemented from the HAP spec using BouncyCastle primitives, no
 * CompletableFuture (API19 friendly). One instance per pairing session.
 */
final class HapPairSetup {
    static final byte[] SRP_USER = "Pair-Setup".getBytes();
    private static final byte[] SALT_ENC = "Pair-Setup-Encrypt-Salt".getBytes();
    private static final byte[] INFO_ENC = "Pair-Setup-Encrypt-Info".getBytes();
    private static final byte[] NONCE_05 = {0,0,0,0,'P','S','-','M','s','g','0','5'};
    private static final byte[] NONCE_06 = {0,0,0,0,'P','S','-','M','s','g','0','6'};

    interface Store {
        String accessoryPairingId();
        byte[] accessoryEd25519Public();
        byte[] accessoryEd25519Sign(byte[] data);          // sign with accessory LTSK
        void saveController(String controllerId, byte[] controllerLtpk); // store paired controller
        byte[] controllerLtpkPublic();                     // accessory's own ltpk
    }

    private final Store store;
    private final String pin;
    private final SecureRandom rnd = new SecureRandom();
    private SRP6Server srp;
    private byte[] salt;
    private BigInteger B;         // server public
    private byte[] sessionKey;

    HapPairSetup(Store store, String pin) { this.store = store; this.pin = pin; }

    /** Handle one pair-setup request (TLV bytes) → response (TLV bytes). Returns null on error. */
    byte[] handle(byte[] request) {
        List<Tlv8.Tlv> in = Tlv8.decode(request);
        byte[] state = Tlv8.find(in, Tlv8.STATE);
        int st = state != null && state.length > 0 ? state[0] : -1;
        if (st == 1) return m2();
        if (st == 3) return m4(in);
        if (st == 5) return m6(in);
        return error();
    }

    private byte[] m2() {
        try {
            salt = new byte[16]; rnd.nextBytes(salt);
            SRP6VerifierGenerator vg = new SRP6VerifierGenerator();
            vg.init(SRP6StandardGroups.rfc5054_3072, new SHA512Digest());
            BigInteger v = vg.generateVerifier(salt, SRP_USER, pin.getBytes());
            srp = new SRP6Server();
            srp.init(SRP6StandardGroups.rfc5054_3072, v, new SHA512Digest(), rnd);
            B = srp.generateServerCredentials();
            byte[] tlv = HapCrypto.concat(
                    Tlv8.encode(Tlv8.STATE, new byte[]{2}),
                    Tlv8.encode(Tlv8.SALT, salt),
                    Tlv8.encode(Tlv8.PUBLIC_KEY, toLen(B, 384)));
            return tlv;
        } catch (Throwable t) { return error(); }
    }

    private byte[] m4(List<Tlv8.Tlv> in) {
        try {
            byte[] A = Tlv8.find(in, Tlv8.PUBLIC_KEY);
            byte[] proof = Tlv8.find(in, Tlv8.PROOF);
            BigInteger Aint = new BigInteger(1, A);
            BigInteger S = srp.calculateSecret(Aint);          // premaster secret
            if (!srp.verifyClientEvidenceMessage(new BigInteger(1, proof))) return error();
            BigInteger M2 = srp.calculateServerEvidenceMessage();
            sessionKey = toFixed(S, 384);                       // HAP uses S for HKDF (3072-bit group)
            return HapCrypto.concat(
                    Tlv8.encode(Tlv8.STATE, new byte[]{4}),
                    Tlv8.encode(Tlv8.PROOF, toLen(M2, 64)));
        } catch (Throwable t) { return error(); }
    }

    private byte[] m6(List<Tlv8.Tlv> in) {
        try {
            byte[] enc = Tlv8.find(in, Tlv8.ENCRYPTED_DATA);
            byte[] key = HapCrypto.hkdf(sessionKey, SALT_ENC, INFO_ENC, 32);
            byte[] plain = decrypt(key, NONCE_05, enc);
            if (plain == null) return error();
            List<Tlv8.Tlv> pt = Tlv8.decode(plain);
            byte[] controllerId = Tlv8.find(pt, Tlv8.IDENTIFIER);
            byte[] controllerLtpk = Tlv8.find(pt, Tlv8.PUBLIC_KEY);
            byte[] controllerSig = Tlv8.find(pt, Tlv8.PROOF);   // controller signature is in PROOF (0x04)
            if (controllerId == null || controllerLtpk == null || controllerSig == null) return error();

            // Verify controller signature per HAP:
            //   iOSDeviceX = HKDF(S, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", 32)
            //   signed = iOSDeviceX || iOSDevicePairingID || iOSDeviceLTPK
            byte[] iox = HapCrypto.hkdf(sessionKey, "Pair-Setup-Controller-Sign-Salt".getBytes(),
                    "Pair-Setup-Controller-Sign-Info".getBytes(), 32);
            byte[] cSigData = HapCrypto.concat(iox, controllerId, controllerLtpk);
            if (!Ed25519.verify(controllerLtpk, cSigData, controllerSig)) return error();

            // Store paired controller
            store.saveController(new String(controllerId), controllerLtpk);

            // Build M6 accessory signature:
            //   AccessoryX = HKDF(S, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", 32)
            //   signed = AccessoryX || AccessoryPairingID || AccessoryLTPK
            String accId = store.accessoryPairingId();
            byte[] accLtpk = store.controllerLtpkPublic();
            byte[] accX = HapCrypto.hkdf(sessionKey, "Pair-Setup-Accessory-Sign-Salt".getBytes(),
                    "Pair-Setup-Accessory-Sign-Info".getBytes(), 32);
            byte[] accSigData = HapCrypto.concat(accX, accId.getBytes(), accLtpk);
            byte[] accSig = store.accessoryEd25519Sign(accSigData);
            byte[] outPlain = HapCrypto.concat(
                    Tlv8.encode(Tlv8.IDENTIFIER, accId.getBytes()),
                    Tlv8.encode(Tlv8.PUBLIC_KEY, accLtpk),
                    Tlv8.encode(Tlv8.SIGNATURE, accSig));
            byte[] outEnc = encrypt(key, NONCE_06, outPlain);
            return HapCrypto.concat(
                    Tlv8.encode(Tlv8.STATE, new byte[]{6}),
                    Tlv8.encode(Tlv8.ENCRYPTED_DATA, outEnc));
        } catch (Throwable t) { return error(); }
    }

    static byte[] encrypt(byte[] key, byte[] nonce, byte[] plain) {
        try {
            ChaCha20Poly1305 c = new ChaCha20Poly1305();
            c.init(true, new AEADParameters(new KeyParameter(key), 128, nonce));
            byte[] out = new byte[c.getOutputSize(plain.length)];
            int n = c.processBytes(plain, 0, plain.length, out, 0);
            n += c.doFinal(out, n);
            if (n != out.length) { byte[] r = new byte[n]; System.arraycopy(out, 0, r, 0, n); return r; }
            return out;
        } catch (Throwable t) { return null; }
    }
    static byte[] decrypt(byte[] key, byte[] nonce, byte[] cipher) {
        try {
            ChaCha20Poly1305 c = new ChaCha20Poly1305();
            c.init(false, new AEADParameters(new KeyParameter(key), 128, nonce));
            byte[] out = new byte[c.getOutputSize(cipher.length)];
            int n = c.processBytes(cipher, 0, cipher.length, out, 0);
            n += c.doFinal(out, n);
            byte[] r = new byte[n]; System.arraycopy(out, 0, r, 0, n); return r;
        } catch (Throwable t) { return null; }
    }

    static byte[] toLen(BigInteger v, int len) { return toFixed(v, len); }
    static byte[] toFixed(BigInteger v, int len) {
        byte[] b = v.toByteArray(); byte[] out = new byte[len];
        if (b.length > len) System.arraycopy(b, b.length - len, out, 0, len);
        else System.arraycopy(b, 0, out, len - b.length, b.length);
        return out;
    }
    private byte[] error() { return Tlv8.encodeList(java.util.Arrays.asList(
            new Tlv8.Tlv(Tlv8.STATE, new byte[]{2}), new Tlv8.Tlv(Tlv8.ERROR, new byte[]{2}))); }
}

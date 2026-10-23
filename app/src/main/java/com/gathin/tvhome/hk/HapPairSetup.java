package com.gathin.tvhome.hk;

import android.util.Log;

import org.bouncycastle.crypto.agreement.srp.SRP6Server;
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups;
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;

/**
 * HAP accessory-side Pair Setup (M1..M6): SRP6a (SRP6-NIST-3072, SHA-512), Ed25519 signatures and
 * ChaCha20-Poly1305 encryption — implemented from the HAP spec using BouncyCastle primitives, no
 * CompletableFuture (API19 friendly). One instance per pairing session.
 */
final class HapPairSetup {
    private static final String TAG = "HapPairSetup";
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
    private HapSrpServer srp;
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
        return error(2, "unexpected request state " + st);
    }

    private byte[] m2() {
        try {
            salt = new byte[16]; rnd.nextBytes(salt);
            SRP6VerifierGenerator vg = new SRP6VerifierGenerator();
            vg.init(SRP6StandardGroups.rfc5054_3072, new SHA512Digest());
            BigInteger v = vg.generateVerifier(salt, SRP_USER, pin.getBytes());
            srp = new HapSrpServer();
            srp.init(SRP6StandardGroups.rfc5054_3072, v, new SHA512Digest(), rnd);
            B = srp.generateServerCredentials();
            byte[] tlv = HapCrypto.concat(
                    Tlv8.encode(Tlv8.STATE, new byte[]{2}),
                    Tlv8.encode(Tlv8.SALT, salt),
                    Tlv8.encode(Tlv8.PUBLIC_KEY, toLen(B, 384)));
            return tlv;
        } catch (Throwable t) { return error(2, "M2 failed", t); }
    }

    private byte[] m4(List<Tlv8.Tlv> in) {
        try {
            byte[] A = Tlv8.find(in, Tlv8.PUBLIC_KEY);
            byte[] proof = Tlv8.find(in, Tlv8.PROOF);
            if (A == null || A.length != 384 || proof == null || proof.length != 64)
                return error(4, "M3 payload has invalid SRP fields");
            BigInteger Aint = new BigInteger(1, A);
            srp.calculateSecret(Aint);                          // premaster secret
            byte[] key = sha512(toFixed(srp.sharedSecret(), 384));
            byte[] expectedProof = clientProof(A, B, salt, key);
            if (!MessageDigest.isEqual(expectedProof, proof))
                return error(4, "SRP client proof rejected");
            sessionKey = key;
            byte[] serverProof = sha512(HapCrypto.concat(toFixed(Aint, 384), proof, sessionKey));
            return HapCrypto.concat(
                    Tlv8.encode(Tlv8.STATE, new byte[]{4}),
                    Tlv8.encode(Tlv8.PROOF, serverProof));
        } catch (Throwable t) { return error(4, "M4 failed", t); }
    }

    private byte[] m6(List<Tlv8.Tlv> in) {
        try {
            byte[] enc = Tlv8.find(in, Tlv8.ENCRYPTED_DATA);
            byte[] key = HapCrypto.hkdf(SALT_ENC, sessionKey, INFO_ENC, 32);
            byte[] plain = decrypt(key, NONCE_05, enc);
            if (plain == null) return error(6, "M5 payload decryption failed");
            List<Tlv8.Tlv> pt = Tlv8.decode(plain);
            byte[] controllerId = Tlv8.find(pt, Tlv8.IDENTIFIER);
            byte[] controllerLtpk = Tlv8.find(pt, Tlv8.PUBLIC_KEY);
            byte[] controllerSig = Tlv8.find(pt, Tlv8.SIGNATURE);
            if (controllerId == null || controllerLtpk == null || controllerSig == null)
                return error(6, "M5 payload is missing controller fields");

            // Verify controller signature per HAP:
            //   iOSDeviceX = HKDF(K, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", 32)
            //   signed = iOSDeviceX || iOSDevicePairingID || iOSDeviceLTPK
            byte[] iox = HapCrypto.hkdf("Pair-Setup-Controller-Sign-Salt".getBytes(), sessionKey,
                    "Pair-Setup-Controller-Sign-Info".getBytes(), 32);
            byte[] cSigData = HapCrypto.concat(iox, controllerId, controllerLtpk);
            if (!Ed25519.verify(controllerLtpk, cSigData, controllerSig))
                return error(6, "controller signature rejected");

            // Build M6 accessory signature:
            //   AccessoryX = HKDF(K, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", 32)
            //   signed = AccessoryX || AccessoryPairingID || AccessoryLTPK
            String accId = store.accessoryPairingId();
            byte[] accLtpk = store.controllerLtpkPublic();
            byte[] accX = HapCrypto.hkdf("Pair-Setup-Accessory-Sign-Salt".getBytes(), sessionKey,
                    "Pair-Setup-Accessory-Sign-Info".getBytes(), 32);
            byte[] accSigData = HapCrypto.concat(accX, accId.getBytes(), accLtpk);
            byte[] accSig = store.accessoryEd25519Sign(accSigData);
            byte[] outPlain = HapCrypto.concat(
                    Tlv8.encode(Tlv8.IDENTIFIER, accId.getBytes()),
                    Tlv8.encode(Tlv8.PUBLIC_KEY, accLtpk),
                    Tlv8.encode(Tlv8.SIGNATURE, accSig));
            byte[] outEnc = encrypt(key, NONCE_06, outPlain);
            if (outEnc == null) return error(6, "M6 encryption failed");
            store.saveController(new String(controllerId), controllerLtpk);
            return HapCrypto.concat(
                    Tlv8.encode(Tlv8.STATE, new byte[]{6}),
                    Tlv8.encode(Tlv8.ENCRYPTED_DATA, outEnc));
        } catch (Throwable t) { return error(6, "M6 failed", t); }
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

    private static byte[] clientProof(byte[] A, BigInteger B, byte[] salt, byte[] key) throws Exception {
        BigInteger n = SRP6StandardGroups.rfc5054_3072.getN();
        BigInteger g = SRP6StandardGroups.rfc5054_3072.getG();
        byte[] hN = sha512(toFixed(n, 384));
        byte[] hG = sha512(g.toByteArray());
        byte[] hGroup = new byte[hN.length];
        for (int i = 0; i < hGroup.length; i++) hGroup[i] = (byte) (hN[i] ^ hG[i]);
        byte[] hUser = sha512(SRP_USER);
        return sha512(HapCrypto.concat(hGroup, hUser, salt, toFixed(new BigInteger(1, A), 384),
                toFixed(B, 384), key));
    }

    private static byte[] sha512(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-512").digest(data);
    }

    static byte[] toFixed(BigInteger v, int len) {
        byte[] b = v.toByteArray(); byte[] out = new byte[len];
        if (b.length > len) System.arraycopy(b, b.length - len, out, 0, len);
        else System.arraycopy(b, 0, out, len - b.length, b.length);
        return out;
    }
    private byte[] error(int responseState, String reason) {
        Log.w(TAG, "Pair-Setup response state=" + responseState + " error: " + reason);
        return errorResponse(responseState);
    }

    private byte[] error(int responseState, String reason, Throwable t) {
        Log.e(TAG, "Pair-Setup response state=" + responseState + " failed: " + reason, t);
        return errorResponse(responseState);
    }

    private static byte[] errorResponse(int responseState) {
        return Tlv8.encodeList(java.util.Arrays.asList(
                new Tlv8.Tlv(Tlv8.STATE, new byte[]{(byte) responseState}),
                new Tlv8.Tlv(Tlv8.ERROR, new byte[]{2})));
    }

    private static final class HapSrpServer extends SRP6Server {
        BigInteger sharedSecret() { return S; }
    }
}

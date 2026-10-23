package com.gathin.tvhome.hk;

import java.util.List;

/**
 * HAP accessory-side Pair Verify (M1..M4): X25519 ECDH, Ed25519 signatures, HKDF-SHA512 to derive the
 * per-session encryption keys. On success yields SessionKeys for the encrypted HTTP channel.
 */
final class HapPairVerify {
    private static final byte[] SALT = "Pair-Verify-Encrypt-Salt".getBytes();
    private static final byte[] INFO = "Pair-Verify-Encrypt-Info".getBytes();
    private static final byte[] NONCE = {0,0,0,0,'P','V','-','M','s','g','0','4'};

    interface Store {
        byte[] controllerLtpk(String controllerId);         // stored paired controller key (or null)
        String accessoryPairingId();
        byte[] accessoryEd25519Public();
        byte[] accessoryEd25519Sign(byte[] data);
    }

    static final class Result {
        byte[] sharedSecret;
        byte[] controllerCurvePub;
        byte[] accessoryCurvePub;
        boolean ok;
    }

    private final Store store;
    private byte[] privCurve, pubCurve;
    private byte[] controllerCurvePub;
    private byte[] sharedSecret;
    private byte[] sessionKey;
    private String controllerId;

    HapPairVerify(Store store) { this.store = store; }

    byte[] handle(byte[] request) {
        List<Tlv8.Tlv> in = Tlv8.decode(request);
        byte[] state = Tlv8.find(in, Tlv8.STATE);
        int st = state != null && state.length > 0 ? state[0] : -1;
        if (st == 1) return m2(in);
        if (st == 3) return m4(in);
        return err(2);
    }

    private byte[] m2(List<Tlv8.Tlv> in) {
        try {
            controllerCurvePub = Tlv8.find(in, Tlv8.PUBLIC_KEY);
            byte[][] kp = X25519.genKeyPair();
            privCurve = kp[0]; pubCurve = kp[1];
            sharedSecret = X25519.agree(privCurve, controllerCurvePub);
            // M2 = state2 || pub(accessoryCurve) || encrypted{ identifier(accessory) || signature }
            byte[] accId = store.accessoryPairingId().getBytes();
            byte[] accPub = store.accessoryEd25519Public();
            byte[] sigData = HapCrypto.concat(pubCurve, accId, controllerCurvePub);
            byte[] sig = store.accessoryEd25519Sign(sigData);
            byte[] plain = HapCrypto.concat(
                    Tlv8.encode(Tlv8.IDENTIFIER, accId),
                    Tlv8.encode(Tlv8.SIGNATURE, sig));
            byte[] encKey = HapCrypto.hkdf(sharedSecret, SALT, INFO, 32);
            byte[] enc = HapPairSetup.encrypt(encKey, NONCE, plain);
            return HapCrypto.concat(
                    Tlv8.encode(Tlv8.STATE, new byte[]{2}),
                    Tlv8.encode(Tlv8.PUBLIC_KEY, pubCurve),
                    Tlv8.encode(Tlv8.ENCRYPTED_DATA, enc));
        } catch (Throwable t) { return err(2); }
    }

    private byte[] m4(List<Tlv8.Tlv> in) {
        try {
            byte[] enc = Tlv8.find(in, Tlv8.ENCRYPTED_DATA);
            byte[] encKey = HapCrypto.hkdf(sharedSecret, SALT, INFO, 32);
            byte[] plain = HapPairSetup.decrypt(encKey, NONCE, enc);
            if (plain == null) return err(4);
            List<Tlv8.Tlv> pt = Tlv8.decode(plain);
            controllerId = new String(Tlv8.find(pt, Tlv8.IDENTIFIER));
            byte[] sig = Tlv8.find(pt, Tlv8.SIGNATURE);
            byte[] controllerLtpk = store.controllerLtpk(controllerId);
            if (controllerLtpk == null) return err(4);
            byte[] sigData = HapCrypto.concat(controllerCurvePub, controllerId.getBytes(), pubCurve);
            if (!Ed25519.verify(controllerLtpk, sigData, sig)) return err(4);
            // Derive session keys from sharedSecret + info "Control-Salt"/"Control-Write-Encryption-Key"
            sessionKey = sharedSecret;
            byte[] ctrlSalt = "Control-Salt".getBytes();
            byte[] wKey = HapCrypto.hkdf(sharedSecret, ctrlSalt, "Control-Write-Encryption-Key".getBytes(), 32);
            byte[] rKey = HapCrypto.hkdf(sharedSecret, ctrlSalt, "Control-Read-Encryption-Key".getBytes(), 32);
            this.writeKey = wKey; this.readKey = rKey;
            return Tlv8.encode(Tlv8.STATE, new byte[]{4});
        } catch (Throwable t) { return err(4); }
    }

    byte[] writeKey, readKey;

    private byte[] err(int st) { return Tlv8.encodeList(java.util.Arrays.asList(
            new Tlv8.Tlv(Tlv8.STATE, new byte[]{(byte) st}), new Tlv8.Tlv(Tlv8.ERROR, new byte[]{2}))); }
}

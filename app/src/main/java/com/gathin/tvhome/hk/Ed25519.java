package com.gathin.tvhome.hk;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/** Ed25519 sign/verify via BouncyCastle (HAP long-term keys). */
public final class Ed25519 {
    private Ed25519() { }
    public static byte[] sign(byte[] seed32, byte[] msg) {
        Ed25519PrivateKeyParameters k = new Ed25519PrivateKeyParameters(seed32, 0);
        Ed25519Signer s = new Ed25519Signer();
        s.init(true, k);
        s.update(msg, 0, msg.length);
        return s.generateSignature();
    }
    public static boolean verify(byte[] pub32, byte[] msg, byte[] sig) {
        try {
            Ed25519PublicKeyParameters k = new Ed25519PublicKeyParameters(pub32, 0);
            Ed25519Signer s = new Ed25519Signer();
            s.init(false, k);
            s.update(msg, 0, msg.length);
            return s.verifySignature(sig);
        } catch (Throwable t) { return false; }
    }
    public static byte[] publicFromSeed(byte[] seed32) {
        return new Ed25519PrivateKeyParameters(seed32, 0).generatePublicKey().getEncoded();
    }
}

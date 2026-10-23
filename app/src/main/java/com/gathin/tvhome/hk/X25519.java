package com.gathin.tvhome.hk;

import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

/** X25519 ECDH + key gen via BouncyCastle (HAP pair-verify). */
public final class X25519 {
    private X25519() { }
    public static byte[][] genKeyPair() {
        X25519PrivateKeyParameters priv = new X25519PrivateKeyParameters(new java.security.SecureRandom());
        return new byte[][]{ priv.getEncoded(), priv.generatePublicKey().getEncoded() };
    }
    public static byte[] agree(byte[] priv32, byte[] peerPub32) {
        X25519PrivateKeyParameters priv = new X25519PrivateKeyParameters(priv32, 0);
        X25519PublicKeyParameters pub = new X25519PublicKeyParameters(peerPub32, 0);
        X25519Agreement ag = new X25519Agreement();
        ag.init(priv);
        byte[] out = new byte[ag.getAgreementSize()];
        ag.calculateAgreement(pub, out, 0);
        return out;
    }
    public static byte[] publicFromPrivate(byte[] priv32) {
        return new X25519PrivateKeyParameters(priv32, 0).generatePublicKey().getEncoded();
    }
}

package io.github.ilyaslabs.foodstack.identity.core.security;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

/**
 * The ES256 keys that sign tokens (TDD 9.1). Keys come from configuration as EC P-256 PKCS#8
 * private keys; the public half is derived, and the key id is the JWK thumbprint, so the same key
 * always gets the same id on every replica.
 */
public final class SigningKeys {

    private SigningKeys() {
    }

    /** A JWK set from configured keys; the first key signs. */
    public static JWKSet fromConfiguration(List<String> encodedKeys) {
        List<com.nimbusds.jose.jwk.JWK> keys = new ArrayList<>();
        for (String encoded : encodedKeys) {
            keys.add(toJwk(parsePrivateKey(encoded)));
        }
        return new JWKSet(keys);
    }

    /** A fresh key, for development only: tokens die with the process. */
    public static JWKSet ephemeral() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            return new JWKSet(withThumbprint(new ECKey.Builder(Curve.P_256, (ECPublicKey) pair.getPublic())
                .privateKey((ECPrivateKey) pair.getPrivate())));
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not generate an EC key", ex);
        }
    }

    static ECPrivateKey parsePrivateKey(String encoded) {
        String base64 = encoded.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        }
        catch (GeneralSecurityException | IllegalArgumentException | ClassCastException ex) {
            throw new IllegalArgumentException("foodstack.identity.signing-keys must hold EC P-256 PKCS#8 private keys", ex);
        }
    }

    static ECKey toJwk(ECPrivateKey privateKey) {
        try {
            ECNamedCurveParameterSpec curve = ECNamedCurveTable.getParameterSpec("secp256r1");
            org.bouncycastle.math.ec.ECPoint q = curve.getG().multiply(privateKey.getS()).normalize();
            ECPoint point = new ECPoint(new BigInteger(1, q.getAffineXCoord().getEncoded()),
                    new BigInteger(1, q.getAffineYCoord().getEncoded()));
            ECPublicKey publicKey = (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(point, privateKey.getParams()));
            return withThumbprint(new ECKey.Builder(Curve.P_256, publicKey).privateKey(privateKey));
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("Invalid EC private key", ex);
        }
    }

    private static ECKey withThumbprint(ECKey.Builder builder) {
        try {
            ECKey key = builder.keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).build();
            return new ECKey.Builder(key).keyID(key.computeThumbprint().toString()).build();
        }
        catch (JOSEException ex) {
            throw new IllegalStateException("Could not compute the key id", ex);
        }
    }
}

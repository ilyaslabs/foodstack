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
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

/**
 * The ES256 keys that sign tokens (TDD 9.1). Keys come from configuration as EC P-256 private
 * keys, PKCS#8 ({@code openssl pkcs8}, the unit deployer) or SEC1 ({@code BEGIN EC PRIVATE KEY},
 * what the Helm chart generates), with or without the PEM lines; the public half is derived, and the key id is the JWK thumbprint, so the same key
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
            byte[] der = Base64.getDecoder().decode(base64);
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(isSec1(der) ? toPkcs8(der) : der));
        }
        catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException | ClassCastException ex) {
            throw new IllegalArgumentException("foodstack.identity.signing-keys must hold EC P-256 private keys (PKCS#8 or SEC1)", ex);
        }
    }

    /** SEC1 starts with version 1; PKCS#8 with version 0. */
    private static boolean isSec1(byte[] der) {
        try {
            ASN1Sequence sequence = ASN1Sequence.getInstance(der);
            return sequence.getObjectAt(0) instanceof ASN1Integer version && version.hasValue(1);
        }
        catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static byte[] toPkcs8(byte[] sec1) throws java.io.IOException {
        AlgorithmIdentifier algorithm = new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, SECObjectIdentifiers.secp256r1);
        return new PrivateKeyInfo(algorithm, org.bouncycastle.asn1.sec.ECPrivateKey.getInstance(sec1)).getEncoded();
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

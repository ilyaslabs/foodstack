package io.github.ilyaslabs.foodstack.identity.core.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SigningKeysTest {

    @Test
    @DisplayName("derives the public key of a configured private key, so tokens it signs verify")
    void derivesPublicKey() throws Exception {
        // given
        KeyPair pair = keyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";

        // when
        ECKey key = SigningKeys.fromConfiguration(List.of(pem)).getKeys().getFirst().toECKey();

        // then
        assertThat(key.toECPublicKey().getW()).isEqualTo(((ECPublicKey) pair.getPublic()).getW());
        assertThat(key.getKeyID()).isEqualTo(key.computeThumbprint().toString());
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), new JWTClaimsSet.Builder().subject("u").build());
        jwt.sign(new ECDSASigner(key));
        assertThat(jwt.verify(new ECDSAVerifier(key.toPublicJWK()))).isTrue();
    }

    @Test
    @DisplayName("keeps every configured key published and gives the same key the same id")
    void rotation() {
        String first = Base64.getEncoder().encodeToString(keyPair().getPrivate().getEncoded());
        String second = Base64.getEncoder().encodeToString(keyPair().getPrivate().getEncoded());

        JWKSet keys = SigningKeys.fromConfiguration(List.of(first, second));

        assertThat(keys.getKeys()).hasSize(2);
        assertThat(SigningKeys.fromConfiguration(List.of(first)).getKeys().getFirst().getKeyID())
            .isEqualTo(keys.getKeys().getFirst().getKeyID());
    }

    @Test
    @DisplayName("reads a SEC1 key on one line, as the Helm chart generates it, as the same key as its PKCS#8 form")
    void sec1() throws Exception {
        // given
        KeyPair pair = keyPair();
        byte[] sec1 = PrivateKeyInfo.getInstance(pair.getPrivate().getEncoded()).parsePrivateKey().toASN1Primitive().getEncoded();
        String pem = "-----BEGIN EC PRIVATE KEY-----" + Base64.getEncoder().encodeToString(sec1) + "-----END EC PRIVATE KEY-----";

        // when
        ECKey key = SigningKeys.fromConfiguration(List.of(pem)).getKeys().getFirst().toECKey();

        // then
        assertThat(key.toECPublicKey().getW()).isEqualTo(((ECPublicKey) pair.getPublic()).getW());
        assertThat(key.getKeyID()).isEqualTo(SigningKeys
            .fromConfiguration(List.of(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())))
            .getKeys().getFirst().getKeyID());
    }

    @Test
    @DisplayName("rejects values that are not EC private keys")
    void rejectsGarbage() {
        assertThatIllegalArgumentException().isThrownBy(() -> SigningKeys.fromConfiguration(List.of("bm90LWEta2V5")));
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}

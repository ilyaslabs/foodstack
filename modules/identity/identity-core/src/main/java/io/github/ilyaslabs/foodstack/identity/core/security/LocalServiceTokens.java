package io.github.ilyaslabs.foodstack.identity.core.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;
import io.github.ilyaslabs.foodstack.platform.security.ServiceTokens;
import io.github.ilyaslabs.foodstack.platform.security.TokenClaims;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Service tokens minted in process where identity is local, signed with the same key as every
 * other token, so no client secret and no network call are needed (TDD 9.1).
 */
public class LocalServiceTokens implements ServiceTokens {

    private static final Duration LIFETIME = Duration.ofMinutes(5);

    private final JWKSource<SecurityContext> keys;

    private final NimbusJwtEncoder encoder;

    private final String issuer;

    private final Clock clock;

    private String token;

    private Instant renewAt = Instant.EPOCH;

    public LocalServiceTokens(JWKSource<SecurityContext> keys, String issuer, Clock clock) {
        this.keys = keys;
        this.encoder = new NimbusJwtEncoder(keys);
        this.issuer = issuer;
        this.clock = clock;
    }

    @Override
    public synchronized String accessToken() {
        Instant now = this.clock.instant();
        if (this.token != null && now.isBefore(this.renewAt)) {
            return this.token;
        }
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(this.issuer)
            .subject(ModuleAccess.SERVICE_CLIENT)
            .issuedAt(now)
            .notBefore(now)
            .expiresAt(now.plus(LIFETIME))
            .claim(TokenClaims.PERMISSIONS, List.of(ModuleAccess.SERVICE))
            .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(signingKeyId()).build();
        this.token = this.encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        this.renewAt = now.plus(LIFETIME).minusSeconds(60);
        return this.token;
    }

    /** The first key signs, as for the authorization server's own tokens (SigningKeys). */
    private String signingKeyId() {
        try {
            List<JWK> found = this.keys.get(new JWKSelector(new JWKMatcher.Builder().privateOnly(true).build()), null);
            return found.getFirst().getKeyID();
        }
        catch (KeySourceException ex) {
            throw new IllegalStateException("No signing key", ex);
        }
    }
}

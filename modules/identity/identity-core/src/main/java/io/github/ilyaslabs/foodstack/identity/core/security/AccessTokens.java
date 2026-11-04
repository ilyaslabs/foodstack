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
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.platform.security.TokenClaims;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signs user access tokens (TDD 9.1): ES256 with the first configured key, 10 minutes by default, carrying
 * {@code roles}, {@code perms}, {@code tv}, {@code sid} (the sign-in) and, for store users,
 * {@code brandIds} and {@code storeIds}, so every module authorizes locally with the public keys.
 */
public class AccessTokens {

    private final JWKSource<SecurityContext> keys;

    private final NimbusJwtEncoder encoder;

    private final String issuer;

    private final Duration lifetime;

    private final Clock clock;

    public AccessTokens(JWKSource<SecurityContext> keys, String issuer, Duration lifetime, Clock clock) {
        this.keys = keys;
        this.encoder = new NimbusJwtEncoder(keys);
        this.issuer = issuer;
        this.lifetime = lifetime;
        this.clock = clock;
    }

    public Duration lifetime() {
        return this.lifetime;
    }

    public String mint(String userId, String sessionId, UserService.TokenClaims claims) {
        Instant now = this.clock.instant();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
            .issuer(this.issuer)
            .subject(userId)
            .issuedAt(now)
            .notBefore(now)
            .expiresAt(now.plus(this.lifetime))
            .claim(TokenClaims.SESSION_ID, sessionId)
            .claim(TokenClaims.ROLES, claims.roles().stream().map(Enum::name).sorted().toList())
            .claim(TokenClaims.PERMISSIONS, claims.permissions().stream().sorted().toList())
            .claim(TokenClaims.TOKEN_VERSION, claims.tokenVersion());
        if (!claims.brandIds().isEmpty()) {
            builder.claim(TokenClaims.BRAND_IDS, claims.brandIds().stream().sorted().toList());
        }
        if (!claims.storeIds().isEmpty()) {
            builder.claim(TokenClaims.STORE_IDS, claims.storeIds().stream().sorted().toList());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(signingKeyId(this.keys)).build();
        return this.encoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();
    }

    /** The first private key signs (SigningKeys keeps the configured order). */
    static String signingKeyId(JWKSource<SecurityContext> keys) {
        try {
            List<JWK> found = keys.get(new JWKSelector(new JWKMatcher.Builder().privateOnly(true).build()), null);
            return found.getFirst().getKeyID();
        }
        catch (KeySourceException ex) {
            throw new IllegalStateException("No signing key", ex);
        }
    }
}

package io.github.ilyaslabs.foodstack.identity.core.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * The signed challenge of a multi-step sign-in (TDD 9.1): after the first step (password, emailed
 * code, password reset, social) proves who the user is, the answer is a challenge that the second
 * step (a TOTP code, or enrolling one) sends back. Nothing is stored. Challenges are HS256 with a
 * key derived from the signing key and carry their own type, so no module can mistake one for an
 * access token (those are ES256 and checked against the JWKS); they live five minutes.
 */
public class ChallengeTokens {

    public static final Duration LIFETIME = Duration.ofMinutes(5);

    static final JOSEObjectType TYPE = new JOSEObjectType("fs-challenge+jwt");

    /** What the second step must do. */
    public enum Step {
        /** Enter a code from the authenticator app (or a recovery code). */
        TOTP,
        /** Set up an authenticator app first: required for this account's role. */
        TOTP_ENROLL
    }

    /** A verified challenge. */
    public record Challenge(String userId, Step step, String client, String how) {
    }

    private final byte[] key;

    private final Clock clock;

    public ChallengeTokens(JWKSource<SecurityContext> keys, Clock clock) {
        this.key = deriveKey(keys);
        this.clock = clock;
    }

    public String issue(String userId, Step step, String client, String how) {
        Instant now = this.clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(userId)
            .audience("foodstack-sign-in")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(LIFETIME)))
            .claim("step", step.name())
            .claim("client", client)
            .claim("how", how)
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(TYPE).build(), claims);
        try {
            jwt.sign(new MACSigner(this.key));
        }
        catch (JOSEException ex) {
            throw new IllegalStateException("Could not sign the challenge", ex);
        }
        return jwt.serialize();
    }

    /** The challenge, if the token is one of ours, is for {@code step} and has not run out. */
    public Optional<Challenge> verify(String token, Step step) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !TYPE.equals(jwt.getHeader().getType())
                    || !jwt.verify(new MACVerifier(this.key))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expires = claims.getExpirationTime();
            if (expires == null || !expires.toInstant().isAfter(this.clock.instant())
                    || !List.of("foodstack-sign-in").equals(claims.getAudience())
                    || !step.name().equals(claims.getStringClaim("step"))) {
                return Optional.empty();
            }
            return Optional.of(new Challenge(claims.getSubject(), step, claims.getStringClaim("client"),
                    claims.getStringClaim("how")));
        }
        catch (ParseException | JOSEException ex) {
            return Optional.empty();
        }
    }

    /** SHA-256 of a label and the first signing key's private part: the same on every replica. */
    private static byte[] deriveKey(JWKSource<SecurityContext> keys) {
        try {
            List<JWK> found = keys.get(new JWKSelector(new JWKMatcher.Builder().privateOnly(true).build()), null);
            ECKey signing = found.getFirst().toECKey();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("foodstack sign-in challenge v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(signing.getD().decode());
            return digest.digest();
        }
        catch (KeySourceException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("No signing key for challenges", ex);
        }
    }
}

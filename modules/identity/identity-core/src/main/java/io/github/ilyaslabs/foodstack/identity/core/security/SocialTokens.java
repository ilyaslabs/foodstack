package io.github.ilyaslabs.foodstack.identity.core.security;

import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * Checks the ID token a social provider gave the app (PRD ID-2): the app signs in with Google or
 * Apple itself and hands the token over, so FoodStack never redirects. The token must be signed by
 * the provider's published keys, come from its issuer, be meant for one of this deployment's client
 * ids, not have run out, and carry a verified email.
 */
public class SocialTokens {

    /** A provider FoodStack accepts tokens from. */
    public record Provider(String name, String issuer, String jwksUri) {
    }

    public static final Provider GOOGLE = new Provider("google", "https://accounts.google.com",
            "https://www.googleapis.com/oauth2/v3/certs");

    public static final Provider APPLE = new Provider("apple", "https://appleid.apple.com", "https://appleid.apple.com/auth/keys");

    /** Who the provider says signed in. */
    public record Identity(String provider, String subject, String email, String name) {
    }

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private final Map<String, Provider> providers;

    private final Map<String, JWKSource<SecurityContext>> keys = new ConcurrentHashMap<>();

    private final Clock clock;

    public SocialTokens(Collection<Provider> providers, Clock clock) {
        this.providers = new ConcurrentHashMap<>();
        providers.forEach((provider) -> this.providers.put(provider.name(), provider));
        this.clock = clock;
    }

    public boolean knows(String provider) {
        return provider != null && this.providers.containsKey(provider.toLowerCase(Locale.ROOT));
    }

    /** The identity in {@code idToken}, if it passes every check for one of {@code clientIds}. */
    public Optional<Identity> verify(String provider, String idToken, Collection<String> clientIds) {
        Provider p = this.providers.get(provider.toLowerCase(Locale.ROOT));
        if (p == null || idToken == null || idToken.isBlank() || clientIds.isEmpty()) {
            return Optional.empty();
        }
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keysOf(p)));
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });
        try {
            JWTClaimsSet claims = processor.process(idToken, null);
            Date expires = claims.getExpirationTime();
            boolean fresh = expires != null && expires.toInstant().plus(CLOCK_SKEW).isAfter(this.clock.instant());
            boolean forUs = claims.getAudience().stream().anyMatch(clientIds::contains);
            String email = claims.getStringClaim("email");
            Object verified = claims.getClaim("email_verified");
            boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equals(verified);
            if (!p.issuer().equals(claims.getIssuer()) || !fresh || !forUs || claims.getSubject() == null || email == null
                    || !emailVerified) {
                return Optional.empty();
            }
            return Optional.of(new Identity(p.name(), claims.getSubject(), email, claims.getStringClaim("name")));
        }
        catch (ParseException | BadJOSEException | JOSEException ex) {
            return Optional.empty();
        }
    }

    private JWKSource<SecurityContext> keysOf(Provider provider) {
        return this.keys.computeIfAbsent(provider.name(), (name) -> {
            try {
                // cached, refreshed in the background, and retried once when a token names an unknown key
                return JWKSourceBuilder.<SecurityContext>create(URI.create(provider.jwksUri()).toURL()).build();
            }
            catch (MalformedURLException ex) {
                throw new IllegalArgumentException("Bad JWKS URL for " + name, ex);
            }
        });
    }
}

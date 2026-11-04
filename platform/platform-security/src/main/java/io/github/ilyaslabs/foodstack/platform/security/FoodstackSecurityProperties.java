package io.github.ilyaslabs.foodstack.platform.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code foodstack.security}. */
@ConfigurationProperties("foodstack.security")
public class FoodstackSecurityProperties {

    /** Public URL of the identity module; the {@code iss} of every token. */
    private String issuer = "http://localhost:8080";

    /**
     * Where to fetch the token signing keys when the identity module is not local. Defaults to
     * {@code <issuer>/oauth2/jwks}; set it to identity's in-cluster address after a split.
     */
    private String jwkSetUri;

    /** Base64 key (32 bytes) for PII field encryption and blind indexes. From a Secret, never in images. */
    private String piiKey;

    /**
     * Secret of the {@code foodstack-service} client, with which a deployment that does not run
     * identity itself gets service tokens for background work (TDD 9.1). Not needed where identity is
     * local, as in the single-server install.
     */
    private String serviceClientSecret;

    /** Token endpoint for service tokens; defaults to {@code <issuer>/oauth2/token}. */
    private String tokenUri;

    /** How long a module trusts its in-memory copy of a user's revocation state. */
    private Duration revocationCacheTtl = Duration.ofSeconds(5);

    /** Most users whose revocation floor each process keeps in memory (least recently used dropped). */
    private long revocationCacheMaxUsers = TokenRevocations.DEFAULT_MAX_CACHED;

    public String getIssuer() {
        return this.issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getJwkSetUri() {
        return this.jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public String getPiiKey() {
        return this.piiKey;
    }

    public void setPiiKey(String piiKey) {
        this.piiKey = piiKey;
    }

    public long getRevocationCacheMaxUsers() {
        return this.revocationCacheMaxUsers;
    }

    public void setRevocationCacheMaxUsers(long revocationCacheMaxUsers) {
        this.revocationCacheMaxUsers = revocationCacheMaxUsers;
    }

    public Duration getRevocationCacheTtl() {
        return this.revocationCacheTtl;
    }

    public void setRevocationCacheTtl(Duration revocationCacheTtl) {
        this.revocationCacheTtl = revocationCacheTtl;
    }

    public String getServiceClientSecret() {
        return this.serviceClientSecret;
    }

    public void setServiceClientSecret(String serviceClientSecret) {
        this.serviceClientSecret = serviceClientSecret;
    }

    public String getTokenUri() {
        return this.tokenUri;
    }

    public void setTokenUri(String tokenUri) {
        this.tokenUri = tokenUri;
    }

    /** Where service tokens come from when identity is not local. */
    public String effectiveTokenUri() {
        if (this.tokenUri != null && !this.tokenUri.isBlank()) {
            return this.tokenUri;
        }
        String base = this.issuer.endsWith("/") ? this.issuer.substring(0, this.issuer.length() - 1) : this.issuer;
        return base + "/oauth2/token";
    }

    /** The JWKS location used by modules that verify tokens remotely. */
    public String effectiveJwkSetUri() {
        if (this.jwkSetUri != null && !this.jwkSetUri.isBlank()) {
            return this.jwkSetUri;
        }
        String base = this.issuer.endsWith("/") ? this.issuer.substring(0, this.issuer.length() - 1) : this.issuer;
        return base + "/oauth2/jwks";
    }
}

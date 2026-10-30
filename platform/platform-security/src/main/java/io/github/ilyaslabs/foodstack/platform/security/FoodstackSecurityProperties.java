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

    /** How long a module trusts its in-memory copy of a user's revocation state. */
    private Duration revocationCacheTtl = Duration.ofSeconds(5);

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

    public Duration getRevocationCacheTtl() {
        return this.revocationCacheTtl;
    }

    public void setRevocationCacheTtl(Duration revocationCacheTtl) {
        this.revocationCacheTtl = revocationCacheTtl;
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

package io.github.ilyaslabs.foodstack.identity.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code foodstack.identity}. */
@ConfigurationProperties("foodstack.identity")
public class IdentityProperties {

    /**
     * EC P-256 private keys (PEM or base64 PKCS#8) that sign tokens. The first signs; the others
     * stay published in the JWKS so tokens signed before a rotation remain valid. From a Secret.
     */
    private List<String> signingKeys = new ArrayList<>();

    /** How long access tokens live (TDD 9.1). */
    private Duration accessTokenTtl = Duration.ofMinutes(10);

    /** How long after a customer asks to delete their account it is deleted (PRD ID-7). */
    private Duration deletionGrace = Duration.ofDays(14);

    /** How often each replica looks for accounts whose grace period is over. */
    private Duration deletionSweep = Duration.ofMinutes(30);

    private final BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();

    private final TotpSettings totp = new TotpSettings();

    private final Social social = new Social();

    public TotpSettings getTotp() {
        return this.totp;
    }

    public Social getSocial() {
        return this.social;
    }

    /** Two-step sign-in with an authenticator app (PRD ID-5). */
    public static class TotpSettings {

        /** Roles that must use it; others may turn it on (back-office accounts only). */
        private java.util.Set<io.github.ilyaslabs.foodstack.identity.api.Role> requiredRoles = java.util.EnumSet
            .of(io.github.ilyaslabs.foodstack.identity.api.Role.ADMIN, io.github.ilyaslabs.foodstack.identity.api.Role.FINANCE);

        /** The name authenticator apps show next to the account. */
        private String issuer = "FoodStack";

        public java.util.Set<io.github.ilyaslabs.foodstack.identity.api.Role> getRequiredRoles() {
            return this.requiredRoles;
        }

        public void setRequiredRoles(java.util.Set<io.github.ilyaslabs.foodstack.identity.api.Role> requiredRoles) {
            this.requiredRoles = requiredRoles;
        }

        public String getIssuer() {
            return this.issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }
    }

    /**
     * Where the social providers publish their keys (PRD ID-2). Only tests change these; client
     * ids are set by an administrator in the back office.
     */
    public static class Social {

        private String googleIssuer = "https://accounts.google.com";

        private String googleJwksUri = "https://www.googleapis.com/oauth2/v3/certs";

        private String appleIssuer = "https://appleid.apple.com";

        private String appleJwksUri = "https://appleid.apple.com/auth/keys";

        public String getGoogleIssuer() {
            return this.googleIssuer;
        }

        public void setGoogleIssuer(String googleIssuer) {
            this.googleIssuer = googleIssuer;
        }

        public String getGoogleJwksUri() {
            return this.googleJwksUri;
        }

        public void setGoogleJwksUri(String googleJwksUri) {
            this.googleJwksUri = googleJwksUri;
        }

        public String getAppleIssuer() {
            return this.appleIssuer;
        }

        public void setAppleIssuer(String appleIssuer) {
            this.appleIssuer = appleIssuer;
        }

        public String getAppleJwksUri() {
            return this.appleJwksUri;
        }

        public void setAppleJwksUri(String appleJwksUri) {
            this.appleJwksUri = appleJwksUri;
        }
    }

    /**
     * Whether the web refresh cookie is marked {@code Secure} (HTTPS only). Leave on; turn off
     * only for a plain-HTTP trial on a machine whose browser refuses Secure cookies there.
     */
    private boolean refreshCookieSecure = true;

    public boolean isRefreshCookieSecure() {
        return this.refreshCookieSecure;
    }

    public void setRefreshCookieSecure(boolean refreshCookieSecure) {
        this.refreshCookieSecure = refreshCookieSecure;
    }

    public List<String> getSigningKeys() {
        return this.signingKeys;
    }

    public void setSigningKeys(List<String> signingKeys) {
        this.signingKeys = signingKeys;
    }

    public Duration getDeletionGrace() {
        return this.deletionGrace;
    }

    public void setDeletionGrace(Duration deletionGrace) {
        this.deletionGrace = deletionGrace;
    }

    public Duration getDeletionSweep() {
        return this.deletionSweep;
    }

    public void setDeletionSweep(Duration deletionSweep) {
        this.deletionSweep = deletionSweep;
    }

    public Duration getAccessTokenTtl() {
        return this.accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public BootstrapAdmin getBootstrapAdmin() {
        return this.bootstrapAdmin;
    }

    /** The first administrator, created at startup when no user has this email yet (set in application.yml or the chart values). */
    public static class BootstrapAdmin {

        private String email;

        private String password;

        private String displayName = "Administrator";

        public String getEmail() {
            return this.email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return this.password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDisplayName() {
            return this.displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }
}

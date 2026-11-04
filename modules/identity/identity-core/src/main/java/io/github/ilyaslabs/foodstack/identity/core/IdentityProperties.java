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

    private final BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();

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

    public Duration getAccessTokenTtl() {
        return this.accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public BootstrapAdmin getBootstrapAdmin() {
        return this.bootstrapAdmin;
    }

    /** The first administrator, created at startup when no user has this email yet (set by install.sh). */
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

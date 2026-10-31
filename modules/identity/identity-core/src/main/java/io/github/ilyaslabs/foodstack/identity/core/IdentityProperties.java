package io.github.ilyaslabs.foodstack.identity.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code foodstack.identity}. */
@ConfigurationProperties("foodstack.identity")
public class IdentityProperties {

    /**
     * EC P-256 private keys (PEM or base64 PKCS#8) that sign tokens. The first signs; the others
     * stay published in the JWKS so tokens signed before a rotation remain valid. From a Secret.
     */
    private List<String> signingKeys = new ArrayList<>();

    private Duration accessTokenTtl = Duration.ofMinutes(10);

    private final BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();

    /** OAuth2 clients (public, Authorization Code + PKCE) by client id. */
    private Map<String, Client> clients = new LinkedHashMap<>();

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

    public Map<String, Client> getClients() {
        return this.clients;
    }

    public void setClients(Map<String, Client> clients) {
        this.clients = clients;
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

    public static class Client {

        private List<String> redirectUris = new ArrayList<>();

        private List<String> postLogoutRedirectUris = new ArrayList<>();

        public List<String> getRedirectUris() {
            return this.redirectUris;
        }

        public void setRedirectUris(List<String> redirectUris) {
            this.redirectUris = redirectUris;
        }

        public List<String> getPostLogoutRedirectUris() {
            return this.postLogoutRedirectUris;
        }

        public void setPostLogoutRedirectUris(List<String> postLogoutRedirectUris) {
            this.postLogoutRedirectUris = postLogoutRedirectUris;
        }
    }
}

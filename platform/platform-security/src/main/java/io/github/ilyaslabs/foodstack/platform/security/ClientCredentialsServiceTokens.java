package io.github.ilyaslabs.foodstack.platform.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Service tokens from identity's token endpoint (client-credentials grant), for deployments without identity. */
public class ClientCredentialsServiceTokens implements ServiceTokens {

    private final RestClient client;

    private final String tokenUri;

    private final String secret;

    private final Clock clock;

    private volatile String token;

    private volatile Instant renewAt = Instant.EPOCH;

    public ClientCredentialsServiceTokens(RestClient.Builder builder, String tokenUri, String secret, Clock clock) {
        this.client = builder.build();
        this.tokenUri = tokenUri;
        this.secret = secret;
        this.clock = clock;
    }

    @Override
    public synchronized String accessToken() {
        if (this.token != null && this.clock.instant().isBefore(this.renewAt)) {
            return this.token;
        }
        if (this.secret == null || this.secret.isBlank()) {
            throw new ModuleException.Unavailable(
                    "foodstack.security.service-client-secret is not set, so background calls to other modules cannot authenticate",
                    "SERVICE_TOKEN_UNAVAILABLE");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        try {
            Map<?, ?> response = this.client.post()
                .uri(this.tokenUri)
                .headers((headers) -> headers.setBasicAuth(ModuleAccess.SERVICE_CLIENT, this.secret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
            String accessToken = (response != null) ? (String) response.get("access_token") : null;
            if (accessToken == null) {
                throw new ModuleException.Unavailable("The token endpoint returned no token", "SERVICE_TOKEN_UNAVAILABLE");
            }
            long expiresIn = (response.get("expires_in") instanceof Number n) ? n.longValue() : 300;
            this.token = accessToken;
            this.renewAt = this.clock.instant().plusSeconds(Math.max(expiresIn - 60, expiresIn / 2));
            return accessToken;
        }
        catch (RestClientException ex) {
            throw new ModuleException.Unavailable("Could not get a service token: " + ex.getMessage(),
                    "SERVICE_TOKEN_UNAVAILABLE");
        }
    }
}

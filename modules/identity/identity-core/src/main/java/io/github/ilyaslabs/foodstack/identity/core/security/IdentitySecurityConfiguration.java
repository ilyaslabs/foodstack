package io.github.ilyaslabs.foodstack.identity.core.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.ilyaslabs.foodstack.identity.core.IdentityProperties;
import io.github.ilyaslabs.foodstack.platform.security.FoodstackSecurityProperties;
import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;
import io.github.ilyaslabs.foodstack.platform.security.ServiceTokens;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Signing keys, Argon2id passwords, and the small OAuth2 authorization server that is left (TDD
 * 9.1): the JWKS that every module verifies tokens with, and the client-credentials token endpoint
 * for service tokens of deployments that do not run identity. People sign in through the
 * stateless {@code /auth} API (identity-web); there is no hosted login page and no server session.
 */
@Configuration(proxyBeanMethods = false)
public class IdentitySecurityConfiguration {

    private static final Log logger = LogFactory.getLog(IdentitySecurityConfiguration.class);

    @Bean
    JWKSource<SecurityContext> identityJwkSource(IdentityProperties properties,
            @Value("${foodstack.environment:development}") String environment) {
        JWKSet keys;
        List<String> configured = properties.getSigningKeys().stream().filter(key -> !key.isBlank()).toList();
        if (configured.isEmpty()) {
            if ("production".equals(environment)) {
                throw new IllegalStateException("foodstack.identity.signing-keys must be set in production");
            }
            logger.warn("No foodstack.identity.signing-keys configured: using a temporary key. Tokens stop working on restart.");
            keys = SigningKeys.ephemeral();
        }
        else {
            keys = SigningKeys.fromConfiguration(configured);
        }
        return new ImmutableJWKSet<>(keys);
    }

    /**
     * Only the service client (client credentials) for deployments that do not run identity
     * themselves (TDD 9.1). People sign in through the stateless {@code /auth} API instead.
     */
    @Bean
    RegisteredClientRepository identityRegisteredClients(FoodstackSecurityProperties security,
            PasswordEncoder identityPasswordEncoder) {
        String serviceSecret = security.getServiceClientSecret();
        if (serviceSecret == null || serviceSecret.isBlank()) {
            return new NoClients();
        }
        return new InMemoryRegisteredClientRepository(RegisteredClient
            .withId(UUID.nameUUIDFromBytes(ModuleAccess.SERVICE_CLIENT.getBytes(StandardCharsets.UTF_8)).toString())
            .clientId(ModuleAccess.SERVICE_CLIENT)
            .clientSecret(identityPasswordEncoder.encode(serviceSecret))
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5)).build())
            .build());
    }

    /** Service tokens for this deployment's own background work, minted without a network call. */
    @Bean
    ServiceTokens identityServiceTokens(JWKSource<SecurityContext> identityJwkSource, FoodstackSecurityProperties security,
            org.springframework.beans.factory.ObjectProvider<java.time.Clock> clock) {
        return new LocalServiceTokens(identityJwkSource, security.getIssuer(), clock.getIfAvailable(java.time.Clock::systemUTC));
    }

    @Bean
    AuthorizationServerSettings identityAuthorizationServerSettings(FoodstackSecurityProperties security) {
        return AuthorizationServerSettings.builder().issuer(security.getIssuer()).build();
    }

    @Bean
    OAuth2AuthorizationService identityAuthorizationService(MongoTemplate identityMongoTemplate,
            RegisteredClientRepository identityRegisteredClients) {
        return new MongoOAuth2AuthorizationService(identityMongoTemplate, identityRegisteredClients);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> identityTokenCustomizer() {
        return new FoodstackTokenCustomizer();
    }

    /** Argon2id for new hashes; the {@code {id}} prefix leaves room to migrate algorithms later. */
    @Bean
    PasswordEncoder identityPasswordEncoder() {
        return new DelegatingPasswordEncoder("argon2", Map.of("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    /** No client may use the token endpoint when no service secret is configured. */
    private static final class NoClients implements RegisteredClientRepository {

        @Override
        public void save(RegisteredClient registeredClient) {
            throw new UnsupportedOperationException("Clients are fixed by configuration");
        }

        @Override
        public RegisteredClient findById(String id) {
            return null;
        }

        @Override
        public RegisteredClient findByClientId(String clientId) {
            return null;
        }
    }
}

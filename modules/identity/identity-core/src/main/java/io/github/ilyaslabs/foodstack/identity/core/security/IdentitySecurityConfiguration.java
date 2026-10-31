package io.github.ilyaslabs.foodstack.identity.core.security;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.ilyaslabs.foodstack.identity.core.IdentityProperties;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.platform.security.FoodstackSecurityProperties;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * The OAuth2 / OIDC authorization server's state and keys (TDD 9.1): signing keys, the web and
 * back-office clients (public, Authorization Code + PKCE), authorizations in MongoDB, Argon2id
 * passwords and token claims. The HTTP side (filter chains, login page) is in identity-web.
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

    @Bean
    RegisteredClientRepository identityRegisteredClients(IdentityProperties properties) {
        List<RegisteredClient> clients = properties.getClients().entrySet().stream().map(this::client).toList();
        if (clients.isEmpty()) {
            throw new IllegalStateException("Configure at least one client under foodstack.identity.clients");
        }
        return new InMemoryRegisteredClientRepository(clients);
    }

    private RegisteredClient client(Map.Entry<String, IdentityProperties.Client> entry) {
        IdentityProperties.Client settings = entry.getValue();
        return RegisteredClient.withId(UUID.nameUUIDFromBytes(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString())
            .clientId(entry.getKey())
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUris((uris) -> uris.addAll(settings.getRedirectUris()))
            .postLogoutRedirectUris((uris) -> uris.addAll(settings.getPostLogoutRedirectUris()))
            .scope(OidcScopes.OPENID)
            .scope(OidcScopes.PROFILE)
            .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
            .tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(java.time.Duration.ofMinutes(10))
                .idTokenSignatureAlgorithm(SignatureAlgorithm.ES256)
                .build())
            .build();
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
    OAuth2TokenCustomizer<JwtEncodingContext> identityTokenCustomizer(UserService userService) {
        return new FoodstackTokenCustomizer(userService);
    }

    @Bean
    UserDetailsService identityUserDetailsService(UserService userService) {
        return new FoodstackUserDetailsService(userService);
    }

    /** Argon2id for new hashes; the {@code {id}} prefix leaves room to migrate algorithms later. */
    @Bean
    PasswordEncoder identityPasswordEncoder() {
        return new DelegatingPasswordEncoder("argon2", Map.of("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
}

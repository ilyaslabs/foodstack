package io.github.ilyaslabs.foodstack.identity.core.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.core.IdentityPersistenceTestApplication;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = IdentityPersistenceTestApplication.class, properties = "spring.data.mongodb.repositories.type=none")
class MongoOAuth2AuthorizationServiceTest {

    private static final RegisteredClient CLIENT = RegisteredClient.withId("client-1")
        .clientId("foodstack-backoffice")
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://food.example.com/admin/auth/callback")
        .scope("openid")
        .build();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    MongoTemplate identityMongoTemplate;

    private MongoOAuth2AuthorizationService service;

    @BeforeEach
    void setUp() {
        this.service = new MongoOAuth2AuthorizationService(this.identityMongoTemplate, new InMemoryRegisteredClientRepository(CLIENT));
    }

    @Test
    @DisplayName("round-trips an authorization with its principal, request, code and tokens")
    void roundTrip() {
        // given
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        OAuth2Authorization authorization = authorization("auth-1", now);

        // when
        this.service.save(authorization);
        OAuth2Authorization found = this.service.findById("auth-1");

        // then
        assertThat(found.getPrincipalName()).isEqualTo("user-1");
        assertThat(found.getAuthorizedScopes()).containsExactly("openid");
        assertThat(found.getToken(OAuth2AuthorizationCode.class).getToken().getTokenValue()).isEqualTo("code-auth-1");
        assertThat(found.getAccessToken().getToken().getTokenValue()).isEqualTo("access-auth-1");
        assertThat(found.getAccessToken().getToken().getExpiresAt()).isEqualTo(now.plusSeconds(600));
        assertThat(found.getToken(OidcIdToken.class).getToken().getSubject()).isEqualTo("user-1");
        assertThat(found.getToken(OidcIdToken.class).getClaims()).containsEntry("tv", 3L).containsEntry("iat", now)
            .containsEntry("roles", List.of("ADMIN"));
        UsernamePasswordAuthenticationToken principal = found.getAttribute("java.security.Principal");
        assertThat(principal.getName()).isEqualTo("user-1");
        assertThat((OAuth2AuthorizationRequest) found.getAttribute(OAuth2AuthorizationRequest.class.getName()))
            .extracting(OAuth2AuthorizationRequest::getClientId)
            .isEqualTo("foodstack-backoffice");
    }

    @Test
    @DisplayName("finds authorizations by code, token and state without storing lookups in clear")
    void findByToken() {
        this.service.save(authorization("auth-2", Instant.now()));

        assertThat(this.service.findByToken("code-auth-2", new OAuth2TokenType("code")).getId()).isEqualTo("auth-2");
        assertThat(this.service.findByToken("access-auth-2", OAuth2TokenType.ACCESS_TOKEN).getId()).isEqualTo("auth-2");
        assertThat(this.service.findByToken("id-auth-2", new OAuth2TokenType("id_token")).getId()).isEqualTo("auth-2");
        assertThat(this.service.findByToken("state-auth-2", new OAuth2TokenType("state")).getId()).isEqualTo("auth-2");
        assertThat(this.service.findByToken("access-auth-2", null).getId()).isEqualTo("auth-2");
        assertThat(this.service.findByToken("unknown", OAuth2TokenType.ACCESS_TOKEN)).isNull();
        Document stored = this.identityMongoTemplate.findById("auth-2", Document.class, "identity_authorizations");
        assertThat(stored.get("accessToken", Document.class).getString("hash"))
            .isEqualTo(MongoOAuth2AuthorizationService.hash("access-auth-2"));
    }

    @Test
    @DisplayName("expires the document with its longest-lived token and removes it on request")
    void expiryAndRemoval() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        OAuth2Authorization authorization = authorization("auth-3", now);
        this.service.save(authorization);

        Document stored = this.identityMongoTemplate.findById("auth-3", Document.class, "identity_authorizations");
        assertThat(stored.getDate("expiresAt")).isEqualTo(Date.from(now.plusSeconds(1800)));

        this.service.remove(authorization);
        assertThat(this.service.findById("auth-3")).isNull();
    }

    private static OAuth2Authorization authorization(String id, Instant now) {
        User user = new User("user-1", "", AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        UsernamePasswordAuthenticationToken principal = UsernamePasswordAuthenticationToken.authenticated(user, null,
                user.getAuthorities());
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://food.example.com/oauth2/authorize")
            .clientId("foodstack-backoffice")
            .redirectUri("https://food.example.com/admin/auth/callback")
            .scopes(Set.of("openid"))
            .state("state-" + id)
            .build();
        return OAuth2Authorization.withRegisteredClient(CLIENT)
            .id(id)
            .principalName("user-1")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizedScopes(Set.of("openid"))
            .attribute("java.security.Principal", principal)
            .attribute(OAuth2AuthorizationRequest.class.getName(), request)
            .attribute("state", "state-" + id)
            .token(new OAuth2AuthorizationCode("code-" + id, now, now.plusSeconds(300)))
            .token(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-" + id, now, now.plusSeconds(600), Set.of("openid")),
                    (metadata) -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, new HashMap<>(Map.of("sub", "user-1",
                            "roles", new ArrayList<>(List.of("ADMIN"))))))
            .token(new OidcIdToken("id-" + id, now, now.plusSeconds(1800), Map.of("sub", "user-1")),
                    // As issued: the token version is a Long, times are Instants (read back on logout).
                    (metadata) -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                            new HashMap<>(Map.of("sub", "user-1", "tv", 3L, "iat", now, "auth_time", now,
                                    "roles", List.of("ADMIN"), "perms", Set.of("settings:manage")))))
            .build();
    }
}

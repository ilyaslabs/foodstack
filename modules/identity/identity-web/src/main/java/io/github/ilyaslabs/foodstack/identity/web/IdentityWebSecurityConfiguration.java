package io.github.ilyaslabs.foodstack.identity.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * What is left of the authorization server, ahead of the API chain: the JWKS ({@code /oauth2/jwks})
 * every module verifies tokens with, server metadata, and the client-credentials token endpoint for
 * service tokens. Stateless: no login page, no session. People sign in at {@code /api/v1/auth}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class IdentityWebSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain identityAuthorizationServerFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
            .with(authorizationServer, (server) -> {
            })
            .sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests((authorize) -> authorize.anyRequest().authenticated());
        return http.build();
    }
}

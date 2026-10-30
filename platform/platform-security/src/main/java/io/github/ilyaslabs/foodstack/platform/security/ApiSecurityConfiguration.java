package io.github.ilyaslabs.foodstack.platform.security;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Security of the public REST API: every {@code /api/**} request is a stateless OAuth2 resource
 * server request validated in memory (TDD 9.1), except the endpoints modules declare as
 * {@link PublicEndpoints}. Everything else outside the API is denied unless another chain (the
 * identity module's login and authorization server) claims it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class ApiSecurityConfiguration {

    public static final int API_CHAIN_ORDER = 10;

    @Bean
    @Order(API_CHAIN_ORDER)
    SecurityFilterChain foodstackApiSecurityFilterChain(HttpSecurity http,
            ObjectProvider<PublicEndpoints> publicEndpoints) throws Exception {
        List<PublicEndpoints.Endpoint> open = publicEndpoints.orderedStream()
            .flatMap((endpoints) -> endpoints.endpoints().stream())
            .toList();
        http.securityMatcher("/api/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests((authorize) -> {
                for (PublicEndpoints.Endpoint endpoint : open) {
                    authorize.requestMatchers(PathPatternRequestMatcher.pathPattern(endpoint.method(), endpoint.pattern()))
                        .permitAll();
                }
                authorize.anyRequest().authenticated();
            })
            .oauth2ResourceServer((resourceServer) -> resourceServer
                .jwt((jwt) -> jwt.jwtAuthenticationConverter(new FoodstackJwtAuthenticationConverter())));
        return http.build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain foodstackDefaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests((authorize) -> authorize
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error")
                .permitAll()
                .anyRequest()
                .denyAll());
        return http.build();
    }
}

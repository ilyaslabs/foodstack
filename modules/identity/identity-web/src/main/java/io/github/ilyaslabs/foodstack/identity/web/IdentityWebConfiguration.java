package io.github.ilyaslabs.foodstack.identity.web;

import io.github.ilyaslabs.foodstack.platform.security.PublicEndpoints;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** Sign-up and sign-in need no token; sign-out does. */
@Configuration(proxyBeanMethods = false)
class IdentityWebConfiguration {

    @Bean
    PublicEndpoints identityPublicEndpoints() {
        return PublicEndpoints.of(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/code",
                "/api/v1/auth/reset", "/api/v1/auth/refresh", "/api/v1/auth/social", "/api/v1/auth/two-step",
                "/api/v1/auth/two-step/enroll", "/api/v1/auth/two-step/confirm");
    }

    /** Which social sign-in buttons the apps show. */
    @Bean
    PublicEndpoints identityPublicProviders() {
        return PublicEndpoints.of(HttpMethod.GET, "/api/v1/auth/providers");
    }
}

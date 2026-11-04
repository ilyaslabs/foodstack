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
                "/api/v1/auth/reset", "/api/v1/auth/refresh");
    }
}

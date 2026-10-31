package io.github.ilyaslabs.foodstack.identity.web;

import io.github.ilyaslabs.foodstack.platform.security.PublicEndpoints;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** Sign-up needs no token. */
@Configuration(proxyBeanMethods = false)
class IdentityWebConfiguration {

    @Bean
    PublicEndpoints identityPublicEndpoints() {
        return PublicEndpoints.of(HttpMethod.POST, "/api/v1/auth/register");
    }
}

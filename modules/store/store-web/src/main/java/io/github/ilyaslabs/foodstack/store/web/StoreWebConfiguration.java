package io.github.ilyaslabs.foodstack.store.web;

import io.github.ilyaslabs.foodstack.platform.security.PublicEndpoints;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** Customers browse live branches and their menus before logging in (PRD SE-1, SE-3). */
@Configuration(proxyBeanMethods = false)
class StoreWebConfiguration {

    @Bean
    PublicEndpoints storePublicEndpoints() {
        return PublicEndpoints.of(HttpMethod.GET, "/api/v1/stores/*", "/api/v1/stores/*/menu",
                "/api/v1/stores/*/delivery-quote");
    }
}

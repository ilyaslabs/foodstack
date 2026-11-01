package io.github.ilyaslabs.foodstack.search.web;

import io.github.ilyaslabs.foodstack.platform.security.PublicEndpoints;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** Customers search before logging in (PRD SE-1). */
@Configuration(proxyBeanMethods = false)
class SearchWebConfiguration {

    @Bean
    PublicEndpoints searchPublicEndpoints() {
        return PublicEndpoints.of(HttpMethod.GET, "/api/v1/search/stores");
    }
}

package io.github.ilyaslabs.foodstack.geo.web;

import io.github.ilyaslabs.foodstack.platform.security.PublicEndpoints;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** Customers search addresses before they sign in. */
@Configuration(proxyBeanMethods = false)
class GeoWebConfiguration {

    @Bean
    PublicEndpoints geoPublicEndpoints() {
        return PublicEndpoints.of(HttpMethod.GET, "/api/v1/geo/addresses", "/api/v1/geo/addresses/reverse",
                "/api/v1/geo/addresses/availability");
    }
}

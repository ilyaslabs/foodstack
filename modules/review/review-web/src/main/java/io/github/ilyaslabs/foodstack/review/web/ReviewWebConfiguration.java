package io.github.ilyaslabs.foodstack.review.web;

import io.github.ilyaslabs.foodstack.platform.security.PublicEndpoints;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** A branch's reviews and rating are public. */
@Configuration(proxyBeanMethods = false)
class ReviewWebConfiguration {

    @Bean
    PublicEndpoints reviewPublicEndpoints() {
        return PublicEndpoints.of(HttpMethod.GET, "/api/v1/reviews/stores/*", "/api/v1/reviews/stores/*/summary");
    }
}

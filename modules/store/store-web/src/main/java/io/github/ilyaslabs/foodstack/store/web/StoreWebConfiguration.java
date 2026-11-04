package io.github.ilyaslabs.foodstack.store.web;

import io.github.ilyaslabs.foodstack.platform.security.PublicEndpoints;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/** Customers browse live branches and their menus before logging in (PRD SE-1, SE-3). */
@Configuration(proxyBeanMethods = false)
class StoreWebConfiguration {

    /** ETags on the customer reads of branches, so a browser or CDN revalidates with a 304 (TDD 7.5). */
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> storeEtags() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/api/v1/stores/*");
        registration.setName("storeEtags");
        return registration;
    }

    @Bean
    PublicEndpoints storePublicEndpoints() {
        return PublicEndpoints.of(HttpMethod.GET, "/api/v1/stores/*", "/api/v1/stores/*/menu",
                "/api/v1/stores/*/delivery-quote");
    }
}

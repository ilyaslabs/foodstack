package io.github.ilyaslabs.foodstack.settings.web;

import io.github.ilyaslabs.foodstack.platform.security.PublicEndpoints;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** Branding and locale are read before anyone logs in. */
@Configuration(proxyBeanMethods = false)
class SettingsWebConfiguration {

    @Bean
    PublicEndpoints settingsPublicEndpoints() {
        return PublicEndpoints.of(HttpMethod.GET, "/api/v1/settings/branding", "/api/v1/settings/locale");
    }
}

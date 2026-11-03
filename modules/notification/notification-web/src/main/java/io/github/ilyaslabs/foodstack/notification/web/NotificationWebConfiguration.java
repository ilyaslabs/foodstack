package io.github.ilyaslabs.foodstack.notification.web;

import io.github.ilyaslabs.foodstack.platform.security.PublicEndpoints;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** Endpoints of the notification module that need no sign-in. */
@Configuration(proxyBeanMethods = false)
class NotificationWebConfiguration {

    @Bean
    PublicEndpoints notificationPublicEndpoints() {
        return PublicEndpoints.of(HttpMethod.GET, "/api/v1/devices/push-config");
    }
}

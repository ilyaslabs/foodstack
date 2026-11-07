package io.github.ilyaslabs.foodstack.payment.web;

import io.github.ilyaslabs.foodstack.platform.security.PublicEndpoints;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** Public payment endpoints: checkout options, the sandbox page and gateway webhooks (signed). */
@Configuration(proxyBeanMethods = false)
class PaymentWebConfiguration {

    @Bean
    PublicEndpoints paymentPublicEndpoints() {
        return PublicEndpoints.of(HttpMethod.GET, "/api/v1/payments/options", "/api/v1/payments/sandbox/*");
    }

    @Bean
    PublicEndpoints paymentPublicPosts() {
        return PublicEndpoints.of(HttpMethod.POST, "/api/v1/payment-webhooks/*", "/api/v1/payment-webhooks/*/*/*",
                "/api/v1/payments/sandbox/*", "/api/v1/payments/sandbox/*/card");
    }
}

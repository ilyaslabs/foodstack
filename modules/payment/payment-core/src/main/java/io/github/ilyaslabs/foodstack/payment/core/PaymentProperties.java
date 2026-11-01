package io.github.ilyaslabs.foodstack.payment.core;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code foodstack.payment.*}: which card gateway to use and its credentials (environment
 * variables, never settings in the database).
 *
 * @param gateway {@code none} (default, cash only), {@code sandbox} (development and tests) or {@code stripe}
 * @param publicUrl the server's public address, for the sandbox page
 * @param sessionTtl how long a hosted page accepts payment (default 30 minutes)
 * @param sandboxSecret signs sandbox webhooks; a random one is made when empty
 * @param stripe Stripe credentials
 */
@ConfigurationProperties("foodstack.payment")
public record PaymentProperties(String gateway, String publicUrl, Duration sessionTtl, String sandboxSecret, Stripe stripe) {

    public PaymentProperties {
        gateway = (gateway == null || gateway.isBlank()) ? "none" : gateway.strip();
        publicUrl = (publicUrl == null || publicUrl.isBlank()) ? "http://localhost:8080" : publicUrl;
        sessionTtl = (sessionTtl != null) ? sessionTtl : Duration.ofMinutes(30);
        stripe = (stripe != null) ? stripe : new Stripe(null, null, null);
    }

    /**
     * @param apiUrl API address (default {@code https://api.stripe.com})
     * @param secretKey secret API key ({@code sk_live_...} or {@code sk_test_...})
     * @param webhookSecret the webhook endpoint's signing secret ({@code whsec_...})
     */
    public record Stripe(String apiUrl, String secretKey, String webhookSecret) {

        public Stripe {
            apiUrl = (apiUrl == null || apiUrl.isBlank()) ? "https://api.stripe.com" : apiUrl;
        }
    }
}

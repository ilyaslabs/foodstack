package io.github.ilyaslabs.foodstack.platform.security;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Security services the modules need with or without a web server (for example in
 * {@code migrate-only} runs): personal-data encryption and token revocation.
 */
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
@EnableConfigurationProperties(FoodstackSecurityProperties.class)
public final class FoodstackSecurityCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StringRedisTemplate.class)
    TokenRevocations tokenRevocations(StringRedisTemplate redis, @Value("${foodstack.namespace:foodstack}") String namespace,
            FoodstackSecurityProperties properties, ObjectProvider<Clock> clock) {
        return new TokenRevocations(redis, namespace, properties.getRevocationCacheTtl(),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("foodstack.security.pii-key")
    PiiCipher piiCipher(FoodstackSecurityProperties properties) {
        return new PiiCipher(properties.getPiiKey());
    }
}

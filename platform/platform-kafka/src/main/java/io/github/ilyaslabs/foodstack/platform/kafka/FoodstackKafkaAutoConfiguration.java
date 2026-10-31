package io.github.ilyaslabs.foodstack.platform.kafka;

import java.time.Clock;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/** Provides {@link ModuleEventing}, from which each module builds its outbox, relay and topics. */
@AutoConfiguration(after = { KafkaAutoConfiguration.class, DataRedisAutoConfiguration.class })
@ConditionalOnBean({ KafkaTemplate.class, StringRedisTemplate.class })
@EnableConfigurationProperties(FoodstackKafkaProperties.class)
public final class FoodstackKafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ModuleEventing moduleEventing(@Value("${foodstack.namespace:foodstack}") String namespace,
            KafkaTemplate<String, String> kafkaTemplate, StringRedisTemplate redis, JsonMapper jsonMapper,
            FoodstackKafkaProperties properties, org.springframework.beans.factory.ObjectProvider<Clock> clock) {
        return new ModuleEventing(namespace, kafkaTemplate, redis, jsonMapper, properties,
                clock.getIfAvailable(Clock::systemUTC));
    }
}

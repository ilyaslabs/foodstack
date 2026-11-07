package io.github.ilyaslabs.foodstack.platform.kafka;

import java.time.Clock;

import io.github.ilyaslabs.foodstack.platform.core.AuditSink;
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
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Provides {@link ModuleEventing}, from which each module builds its outbox, relay and topics, and
 * the listener error handler of {@link DeadLetters}.
 */
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

    /** Back-office writes go to the identity module's audit log through {@code <namespace>.audit} (PRD AD-3). */
    @Bean
    @ConditionalOnMissingBean(AuditSink.class)
    KafkaAuditSink foodstackAuditSink(@Value("${foodstack.namespace:foodstack}") String namespace,
            KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper) {
        return new KafkaAuditSink(kafkaTemplate, jsonMapper, namespace);
    }

    /** The audit topic, created by whichever process starts first, so no entry waits for identity. */
    @Bean
    @ConditionalOnMissingBean(name = "foodstackAuditTopic")
    org.springframework.kafka.core.KafkaAdmin.NewTopics foodstackAuditTopic(
            @Value("${foodstack.namespace:foodstack}") String namespace, FoodstackKafkaProperties properties) {
        return new org.springframework.kafka.core.KafkaAdmin.NewTopics(
                org.springframework.kafka.config.TopicBuilder.name(KafkaAuditSink.topic(namespace))
                    .partitions(properties.getTopicPartitions())
                    .replicas(properties.getReplicationFactor())
                    .config(org.apache.kafka.common.config.TopicConfig.RETENTION_MS_CONFIG,
                            String.valueOf(java.time.Duration.ofDays(7).toMillis()))
                    .build());
    }

    /** Picked up by Boot's listener container factory: retries with backoff, then the {@code .dlt} topic. */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler foodstackKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
            FoodstackKafkaProperties properties) {
        return DeadLetters.errorHandler(kafkaTemplate, properties.getConsumer());
    }
}

package io.github.ilyaslabs.foodstack.media.core;

import io.github.ilyaslabs.foodstack.platform.kafka.ModuleEventing;
import io.github.ilyaslabs.foodstack.platform.kafka.OutboxRelay;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/** The media module's outbox, its relay to {@code foodstack.media.events} and its topics. */
@Configuration(proxyBeanMethods = false)
public class MediaEventsConfiguration {

    private static final String MODULE = MediaPersistenceConfiguration.MODULE;

    @Bean
    OutboxWriter mediaOutbox(ModuleEventing eventing, ModuleMongo mediaMongo) {
        return eventing.outbox(mediaMongo);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OutboxRelay mediaOutboxRelay(ModuleEventing eventing, ModuleMongo mediaMongo) {
        return eventing.relay(mediaMongo);
    }

    @Bean
    KafkaAdmin.NewTopics mediaTopics(ModuleEventing eventing) {
        return eventing.topics(MODULE);
    }
}

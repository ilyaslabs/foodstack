package io.github.ilyaslabs.foodstack.platform.kafka;

import java.time.Clock;

import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Builds the event plumbing of one module: its outbox writer, relay and topics. Each module's
 * configuration class registers the results as beans, so they exist only where it is local.
 */
public class ModuleEventing {

    private final String namespace;

    private final KafkaTemplate<String, String> kafka;

    private final StringRedisTemplate redis;

    private final JsonMapper jsonMapper;

    private final FoodstackKafkaProperties properties;

    private final Clock clock;

    public ModuleEventing(String namespace, KafkaTemplate<String, String> kafka, StringRedisTemplate redis,
            JsonMapper jsonMapper, FoodstackKafkaProperties properties, Clock clock) {
        this.namespace = namespace;
        this.kafka = kafka;
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public OutboxWriter outbox(ModuleMongo mongo) {
        return new OutboxWriter(mongo, this.jsonMapper, this.clock);
    }

    public OutboxRelay relay(ModuleMongo mongo) {
        String lockKey = this.namespace + ":" + mongo.module() + ":outbox:leader";
        return new OutboxRelay(mongo, topic(mongo.module()), this.kafka,
                new RedisLeaderLock(this.redis, lockKey, this.properties.getRelay().getLeaderLease()), this.jsonMapper,
                this.properties, this.clock);
    }

    public KafkaAdmin.NewTopics topics(String module) {
        return ModuleTopics.create(this.namespace, module, this.properties);
    }

    public String topic(String module) {
        return ModuleTopics.events(this.namespace, module);
    }

    public String namespace() {
        return this.namespace;
    }
}

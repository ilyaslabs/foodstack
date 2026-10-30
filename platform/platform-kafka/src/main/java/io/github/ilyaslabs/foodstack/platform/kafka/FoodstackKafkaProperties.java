package io.github.ilyaslabs.foodstack.platform.kafka;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code foodstack.kafka} (TDD 7.1). */
@ConfigurationProperties("foodstack.kafka")
public class FoodstackKafkaProperties {

    /**
     * Partitions of every module topic. High from day one: adding partitions later re-maps keys
     * and briefly breaks per-aggregate ordering.
     */
    private int topicPartitions = 24;

    /** 3 in production; 1 on a single server. */
    private short replicationFactor = 1;

    private final Relay relay = new Relay();

    public int getTopicPartitions() {
        return this.topicPartitions;
    }

    public void setTopicPartitions(int topicPartitions) {
        this.topicPartitions = topicPartitions;
    }

    public short getReplicationFactor() {
        return this.replicationFactor;
    }

    public void setReplicationFactor(short replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    public Relay getRelay() {
        return this.relay;
    }

    public static class Relay {

        /** Pause between outbox polls when the last poll found less than a full batch. */
        private Duration pollInterval = Duration.ofMillis(100);

        private int batchSize = 500;

        /** Lifetime of the per-module leader lock; renewed on every poll. */
        private Duration leaderLease = Duration.ofSeconds(10);

        /** Time to wait for Kafka to acknowledge a batch. */
        private Duration sendTimeout = Duration.ofSeconds(10);

        public Duration getPollInterval() {
            return this.pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public int getBatchSize() {
            return this.batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getLeaderLease() {
            return this.leaderLease;
        }

        public void setLeaderLease(Duration leaderLease) {
            this.leaderLease = leaderLease;
        }

        public Duration getSendTimeout() {
            return this.sendTimeout;
        }

        public void setSendTimeout(Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }
    }
}

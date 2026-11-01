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

    private final Consumer consumer = new Consumer();

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

    public Consumer getConsumer() {
        return this.consumer;
    }

    /**
     * How listeners treat a failing event (TDD 7.1): a few attempts with a growing pause, then the
     * record goes to the dead-letter topic of the module that published it, {@code <topic>.dlt}.
     */
    public static class Consumer {

        /** Deliveries of one record, the first included, before it is dead-lettered. */
        private int attempts = 3;

        /** Pause before the second attempt; doubled for each further one. */
        private Duration initialBackoff = Duration.ofSeconds(1);

        /** Upper bound of the pause between attempts. */
        private Duration maxBackoff = Duration.ofSeconds(10);

        public int getAttempts() {
            return this.attempts;
        }

        public void setAttempts(int attempts) {
            this.attempts = attempts;
        }

        public Duration getInitialBackoff() {
            return this.initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return this.maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }
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

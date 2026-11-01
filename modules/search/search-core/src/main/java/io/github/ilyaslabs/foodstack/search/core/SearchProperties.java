package io.github.ilyaslabs.foodstack.search.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code foodstack.search.*}.
 *
 * @param indexPrefix prefix of index and alias names, so several deployments or test runs can share a cluster
 * @param replicas replicas per index; 0 on one server, 1 or more on a cluster
 * @param pickupRadiusMeters how far a branch offering pickup may be from the customer
 * @param rebuildInterval how often one instance re-reads every branch, repairing anything an event
 *        missed and renewing the opening windows (at most 7 days, which the windows outlast)
 */
@ConfigurationProperties("foodstack.search")
public record SearchProperties(String indexPrefix, Integer replicas, Integer pickupRadiusMeters,
        java.time.Duration rebuildInterval) {

    public SearchProperties {
        indexPrefix = (indexPrefix == null || indexPrefix.isBlank()) ? "foodstack_" : indexPrefix;
        replicas = (replicas == null) ? 0 : replicas;
        pickupRadiusMeters = (pickupRadiusMeters == null) ? 15_000 : pickupRadiusMeters;
        rebuildInterval = (rebuildInterval == null) ? java.time.Duration.ofHours(24) : rebuildInterval;
        if (rebuildInterval.compareTo(java.time.Duration.ofMinutes(1)) < 0 || rebuildInterval.compareTo(java.time.Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("foodstack.search.rebuild-interval must be between 1 minute and 7 days");
        }
    }
}

package io.github.ilyaslabs.foodstack.search.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code foodstack.search.*}.
 *
 * @param indexPrefix prefix of index and alias names, so several deployments or test runs can share a cluster
 * @param replicas replicas per index; 0 on one server, 1 or more on a cluster
 * @param pickupRadiusMeters how far a branch offering pickup may be from the customer
 */
@ConfigurationProperties("foodstack.search")
public record SearchProperties(String indexPrefix, Integer replicas, Integer pickupRadiusMeters) {

    public SearchProperties {
        indexPrefix = (indexPrefix == null || indexPrefix.isBlank()) ? "foodstack_" : indexPrefix;
        replicas = (replicas == null) ? 0 : replicas;
        pickupRadiusMeters = (pickupRadiusMeters == null) ? 15_000 : pickupRadiusMeters;
    }
}

package io.github.ilyaslabs.foodstack.search.core;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * {@code foodstack.search.*}.
 *
 * @param indexPrefix prefix of index and alias names, so several deployments or test runs can share a cluster
 * @param replicas fixed replicas per index, used only when {@code autoExpandReplicas} is empty
 * @param pickupRadiusMeters how far a branch offering pickup may be from the customer
 * @param rebuildInterval how often one instance re-reads every branch, repairing anything an event
 *        missed and renewing the opening windows (at most 7 days, which the windows outlast)
 * @param shards primary shards of a new index (default 1); changing it takes effect with the next
 *        mapping version or after deleting the index, which the bootstrap then rebuilds
 * @param autoExpandReplicas Elasticsearch {@code auto_expand_replicas}: {@code 0-1} (default) keeps
 *        one copy on a single server and a replica once a second node joins; {@code 0-all} puts a
 *        copy on every node for read-heavy clusters. Applied to the live index at startup.
 * @param discoveryStepsKm how far, in road kilometres, delivery search looks: the first step by
 *        default, then each "show restaurants further away" moves to the next (default 8, 15, 25).
 *        A branch's own zones still decide whether it delivers to the customer.
 * @param roadFactor road distance per straight-line distance; keep it equal to {@code foodstack.geo.road-factor}
 * @param maxResults deepest result a search pages to (default 500)
 * @param rankingOffsetMeters how near counts as "right here": no ranking penalty within it (default 1 km)
 * @param rankingScaleMeters how far beyond the offset a branch's nearness score halves (default 4 km)
 */
@ConfigurationProperties("foodstack.search")
public record SearchProperties(String indexPrefix, Integer replicas, Integer pickupRadiusMeters, Duration rebuildInterval,
        Integer shards, String autoExpandReplicas, List<Integer> discoveryStepsKm, Double roadFactor, Integer maxResults,
        Integer rankingOffsetMeters, Integer rankingScaleMeters) {

    @ConstructorBinding
    public SearchProperties {
        indexPrefix = (indexPrefix == null || indexPrefix.isBlank()) ? "foodstack_" : indexPrefix;
        replicas = (replicas == null) ? 0 : replicas;
        pickupRadiusMeters = (pickupRadiusMeters == null) ? 15_000 : pickupRadiusMeters;
        rebuildInterval = (rebuildInterval == null) ? Duration.ofHours(24) : rebuildInterval;
        if (rebuildInterval.compareTo(Duration.ofMinutes(1)) < 0 || rebuildInterval.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("foodstack.search.rebuild-interval must be between 1 minute and 7 days");
        }
        shards = (shards == null || shards < 1) ? 1 : shards;
        autoExpandReplicas = (autoExpandReplicas == null) ? "0-1" : autoExpandReplicas.trim();
        discoveryStepsKm = (discoveryStepsKm == null || discoveryStepsKm.isEmpty()) ? List.of(8, 15, 25)
                : discoveryStepsKm.stream().sorted().distinct().toList();
        if (discoveryStepsKm.getFirst() < 1 || discoveryStepsKm.getLast() > 200) {
            throw new IllegalArgumentException("foodstack.search.discovery-steps-km must be between 1 and 200 km");
        }
        roadFactor = (roadFactor == null || roadFactor < 1) ? 1.3 : roadFactor;
        maxResults = (maxResults == null || maxResults < 20) ? 500 : maxResults;
        rankingOffsetMeters = (rankingOffsetMeters == null || rankingOffsetMeters < 0) ? 1_000 : rankingOffsetMeters;
        rankingScaleMeters = (rankingScaleMeters == null || rankingScaleMeters < 100) ? 4_000 : rankingScaleMeters;
    }

    /** Defaults for everything added after the first four settings. */
    public SearchProperties(String indexPrefix, Integer replicas, Integer pickupRadiusMeters, Duration rebuildInterval) {
        this(indexPrefix, replicas, pickupRadiusMeters, rebuildInterval, null, null, null, null, null, null, null);
    }

    /** The discovery steps in road metres. */
    public List<Integer> discoveryStepsMeters() {
        return this.discoveryStepsKm.stream().map((km) -> km * 1000).toList();
    }
}

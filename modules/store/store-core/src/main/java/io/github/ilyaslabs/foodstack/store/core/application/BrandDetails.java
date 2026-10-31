package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A brand.
 *
 * @param id brand id
 * @param name display name
 * @param description translated description
 * @param cuisines cuisine tags
 * @param logoMediaId logo media id, or {@code null}
 * @param logoUrls logo variant URLs
 * @param coverMediaId cover media id, or {@code null}
 * @param coverUrls cover variant URLs
 * @param commission default commission
 * @param branchCount number of branches
 * @param version current version
 * @param updatedAt last change
 */
public record BrandDetails(String id, String name, Map<String, String> description, List<String> cuisines,
        String logoMediaId, Map<String, String> logoUrls, String coverMediaId, Map<String, String> coverUrls,
        Commission commission, int branchCount, long version, Instant updatedAt) {
}

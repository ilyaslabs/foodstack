package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * @param commission default commission in force now
 * @param branchCount number of branches
 * @param version current version
 * @param updatedAt last change
 * @param commissionChanges the commission over time, oldest first: the starting value, then dated changes (some may
 *        lie ahead)
 * @param ownerUserIds the brand's owners
 * @param branchPromotionsAllowed whether branch managers may create promotions for their branch
 */
public record BrandDetails(String id, String name, Map<String, String> description, List<String> cuisines,
        String logoMediaId, Map<String, String> logoUrls, String coverMediaId, Map<String, String> coverUrls,
        Commission commission, int branchCount, long version, Instant updatedAt, List<CommissionChange> commissionChanges,
        Set<String> ownerUserIds, boolean branchPromotionsAllowed) {

    /** A brand with no dated commission changes or owners yet. */
    public BrandDetails(String id, String name, Map<String, String> description, List<String> cuisines, String logoMediaId,
            Map<String, String> logoUrls, String coverMediaId, Map<String, String> coverUrls, Commission commission,
            int branchCount, long version, Instant updatedAt) {
        this(id, name, description, cuisines, logoMediaId, logoUrls, coverMediaId, coverUrls, commission, branchCount, version,
                updatedAt, List.of(), Set.of(), false);
    }
}

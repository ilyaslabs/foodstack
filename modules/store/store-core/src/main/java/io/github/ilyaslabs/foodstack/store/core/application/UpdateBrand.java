package io.github.ilyaslabs.foodstack.store.core.application;

import java.util.List;
import java.util.Map;

/**
 * New brand details; every field is replaced.
 *
 * @param name display name
 * @param description translated description
 * @param cuisines cuisine tags
 * @param logoMediaId a processed STORE upload, or {@code null} for none
 * @param coverMediaId a processed STORE upload, or {@code null} for none
 */
public record UpdateBrand(String name, Map<String, String> description, List<String> cuisines, String logoMediaId,
        String coverMediaId) {
}

package io.github.ilyaslabs.foodstack.store.core.application;

import java.util.Map;

/**
 * A choice inside a modifier group.
 *
 * @param id option id, stable across edits
 * @param name translated name
 * @param priceDeltaMinor added to the item price, may be 0
 */
public record ModifierOption(String id, Map<String, String> name, long priceDeltaMinor) {
}

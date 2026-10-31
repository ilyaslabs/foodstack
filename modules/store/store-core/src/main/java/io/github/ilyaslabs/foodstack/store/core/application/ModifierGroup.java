package io.github.ilyaslabs.foodstack.store.core.application;

import java.util.List;
import java.util.Map;

/**
 * Choices for an item, such as size or extra toppings (PRD ST-3).
 *
 * @param id group id, stable across edits
 * @param name translated name
 * @param minSelect 0 for optional, 1 or more for required
 * @param maxSelect most choices allowed
 * @param options the choices
 */
public record ModifierGroup(String id, Map<String, String> name, int minSelect, int maxSelect, List<ModifierOption> options) {
}

package io.github.ilyaslabs.foodstack.store.api;

import java.util.List;

/**
 * One page of branch ids.
 *
 * @param storeIds ids in ascending order
 * @param nextAfterId pass as {@code afterId} for the next page; {@code null} on the last page
 */
public record StoreIdPage(List<String> storeIds, String nextAfterId) {
}

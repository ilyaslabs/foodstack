package io.github.ilyaslabs.foodstack.search.api;

import io.github.ilyaslabs.moduleswitch.Param;

/** Store discovery for other modules, such as a home feed or a reorder screen. */
public interface SearchApi {

    /** Live branches near a customer that match the query, nearest brand branch only (PRD SE-1 to SE-4). */
    StoreSearchPage searchStores(@Param("query") StoreSearchQuery query);
}

package io.github.ilyaslabs.foodstack.ledger.api;

import io.github.ilyaslabs.moduleswitch.Param;

/**
 * What other modules may ask the ledger. Payment checks a branch's balance before it lets the
 * branch collect card payments itself (TDD 5.4).
 */
public interface LedgerApi {

    /** The branch's balance with the operator now. Only the platform may ask. */
    StoreBalance storeBalance(@Param("storeId") String storeId);
}

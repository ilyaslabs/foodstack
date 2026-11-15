package io.github.ilyaslabs.foodstack.ledger.api;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.moduleswitch.Param;

/**
 * What other modules may ask the ledger. Payment checks a branch's balance before it lets the
 * branch collect card payments itself (TDD 5.4).
 */
@EVL.Interface(value = "LedgerApi", description = "The ledger module API: an in-process call, or HTTP when ledger runs as its own service")
public interface LedgerApi {

    /** The branch's balance with the operator now. Only the platform may ask. */
    StoreBalance storeBalance(@Param("storeId") String storeId);
}

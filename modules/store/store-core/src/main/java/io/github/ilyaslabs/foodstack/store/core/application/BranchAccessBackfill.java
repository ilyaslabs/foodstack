package io.github.ilyaslabs.foodstack.store.core.application;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

/**
 * At start-up, after the demo seeder (order 30): publishes every branch's brand for brand owners'
 * access checks (TDD 9.1) and moves owners of single restaurants from the branch to the brand
 * (PRD BB-1, M4). Safe to run on several instances at once.
 */
public class BranchAccessBackfill {

    private static final Log logger = LogFactory.getLog(BranchAccessBackfill.class);

    private final StoreService stores;

    public BranchAccessBackfill(StoreService stores) {
        this.stores = stores;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(41)
    public void run() {
        try {
            int moved = this.stores.backfillBranchAccess();
            if (moved > 0) {
                logger.info("Moved the owners of " + moved + " branches to their brand");
            }
        }
        catch (RuntimeException ex) {
            logger.warn("Could not publish branch brands for access checks; brand owners may be refused until the next start", ex);
        }
    }
}

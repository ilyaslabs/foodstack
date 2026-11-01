package io.github.ilyaslabs.foodstack.store.core.application;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

/** Gives every branch without a web address one at start-up, after the demo seeder (order 30). */
public class SlugBackfill {

    private static final Log logger = LogFactory.getLog(SlugBackfill.class);

    private final StoreService stores;

    public SlugBackfill(StoreService stores) {
        this.stores = stores;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(40)
    public void assign() {
        int assigned = this.stores.assignMissingSlugs("system");
        if (assigned > 0) {
            logger.info("Gave " + assigned + " branches a web address");
        }
    }
}

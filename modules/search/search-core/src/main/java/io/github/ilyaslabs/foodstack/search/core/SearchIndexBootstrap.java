package io.github.ilyaslabs.foodstack.search.core;

import io.github.ilyaslabs.foodstack.search.core.application.StoreIndexer;
import io.github.ilyaslabs.foodstack.search.core.engine.StoreSearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Creates the index on first start and fills it from the catalogue in the background, so a new
 * deployment, or a new mapping version, needs no manual step. Search being down never stops the
 * server from starting.
 */
class SearchIndexBootstrap implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexBootstrap.class);

    private final StoreSearchEngine engine;

    private final StoreIndexer indexer;

    SearchIndexBootstrap(StoreSearchEngine engine, StoreIndexer indexer) {
        this.engine = engine;
        this.indexer = indexer;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            if (this.engine.ensureIndex()) {
                Thread.ofVirtual().name("search-rebuild").start(this::rebuild);
            }
        }
        catch (RuntimeException ex) {
            log.warn("Search index not ready, store search is unavailable until Elasticsearch is reachable: {}",
                    ex.getMessage());
        }
    }

    private void rebuild() {
        try {
            this.indexer.rebuild();
        }
        catch (RuntimeException ex) {
            log.warn("Search rebuild stopped: {}", ex.getMessage());
        }
    }
}

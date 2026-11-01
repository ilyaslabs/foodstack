package io.github.ilyaslabs.foodstack.search.core;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.ilyaslabs.foodstack.search.core.application.StoreIndexer;
import io.github.ilyaslabs.foodstack.search.core.engine.StoreSearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Creates the index on first start and fills it from the catalogue in the background, so a new
 * deployment, or a new mapping version, needs no manual step. Then re-reads every branch once per
 * {@code foodstack.search.rebuild-interval} (one instance per round): this repairs an update whose
 * event ended in the dead-letter topic and renews each branch's opening windows. Search being down
 * never stops the server from starting.
 */
class SearchIndexBootstrap implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexBootstrap.class);

    private final StoreSearchEngine engine;

    private final StoreIndexer indexer;

    private final Duration interval;

    private final ScheduledRebuildLock lock;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("search-scheduled-rebuild").factory());

    SearchIndexBootstrap(StoreSearchEngine engine, StoreIndexer indexer, Duration interval, ScheduledRebuildLock lock) {
        this.engine = engine;
        this.indexer = indexer;
        this.interval = interval;
        this.lock = lock;
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
        long seconds = this.interval.toSeconds();
        this.scheduler.scheduleAtFixedRate(this::scheduledRebuild, seconds, seconds, TimeUnit.SECONDS);
    }

    void scheduledRebuild() {
        try {
            if (this.lock.tryTake(this.interval)) {
                this.engine.ensureIndex();
                rebuild();
            }
        }
        catch (RuntimeException ex) {
            log.warn("Scheduled search rebuild skipped: {}", ex.getMessage());
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

    @Override
    public void destroy() {
        this.scheduler.shutdownNow();
    }
}

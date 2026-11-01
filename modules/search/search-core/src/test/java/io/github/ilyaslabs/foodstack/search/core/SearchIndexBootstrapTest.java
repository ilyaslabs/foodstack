package io.github.ilyaslabs.foodstack.search.core;

import java.time.Duration;

import io.github.ilyaslabs.foodstack.search.core.application.StoreIndexer;
import io.github.ilyaslabs.foodstack.search.core.engine.StoreSearchEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SearchIndexBootstrapTest {

    private final StoreSearchEngine engine = mock(StoreSearchEngine.class);

    private final StoreIndexer indexer = mock(StoreIndexer.class);

    private final ScheduledRebuildLock lock = mock(ScheduledRebuildLock.class);

    private final SearchIndexBootstrap bootstrap = new SearchIndexBootstrap(this.engine, this.indexer, Duration.ofHours(24),
            this.lock);

    @Test
    @DisplayName("the scheduled rebuild re-reads every branch on the instance that takes the round")
    void rebuildsWhenLockTaken() {
        given(this.lock.tryTake(Duration.ofHours(24))).willReturn(true);

        this.bootstrap.scheduledRebuild();

        verify(this.indexer).rebuild();
    }

    @Test
    @DisplayName("another instance already running the round means no rebuild here")
    void skipsWhenTaken() {
        given(this.lock.tryTake(Duration.ofHours(24))).willReturn(false);

        this.bootstrap.scheduledRebuild();

        verify(this.indexer, never()).rebuild();
    }
}

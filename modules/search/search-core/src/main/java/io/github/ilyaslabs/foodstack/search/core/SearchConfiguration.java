package io.github.ilyaslabs.foodstack.search.core;

import java.time.Clock;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.search.core.application.StoreIndexer;
import io.github.ilyaslabs.foodstack.search.core.application.StoreSearchService;
import io.github.ilyaslabs.foodstack.search.core.engine.ElasticsearchStoreSearchEngine;
import io.github.ilyaslabs.foodstack.search.core.engine.StoreSearchEngine;
import io.github.ilyaslabs.foodstack.search.core.events.CatalogueEventsListener;
import io.github.ilyaslabs.foodstack.store.api.StoreApi;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Everything the search module runs on. Search keeps no database of its own: the index is a read
 * model that can always be rebuilt from the store and geo modules.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfiguration {

    @Bean
    StoreSearchEngine searchStoreEngine(ElasticsearchClient elasticsearchClient, JsonMapper jsonMapper,
            SearchProperties properties) {
        return new ElasticsearchStoreSearchEngine(elasticsearchClient, jsonMapper, properties);
    }

    @Bean
    StoreIndexer searchStoreIndexer(StoreApi storeApi, GeoApi geoApi, StoreSearchEngine searchStoreEngine) {
        return new StoreIndexer(storeApi, geoApi, searchStoreEngine);
    }

    @Bean
    StoreSearchService searchStoreSearchService(StoreSearchEngine searchStoreEngine, Clock clock) {
        return new StoreSearchService(searchStoreEngine, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    CatalogueEventsListener searchCatalogueEventsListener(StoreIndexer searchStoreIndexer, JsonMapper jsonMapper) {
        return new CatalogueEventsListener(searchStoreIndexer, jsonMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    SearchIndexBootstrap searchIndexBootstrap(StoreSearchEngine searchStoreEngine, StoreIndexer searchStoreIndexer) {
        return new SearchIndexBootstrap(searchStoreEngine, searchStoreIndexer);
    }
}

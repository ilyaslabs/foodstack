package io.github.ilyaslabs.foodstack.search.web;

import java.util.Locale;
import java.util.Map;

import io.github.ilyaslabs.foodstack.search.api.Fulfilment;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchPage;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchQuery;
import io.github.ilyaslabs.foodstack.search.core.application.StoreIndexer;
import io.github.ilyaslabs.foodstack.search.core.application.StoreSearchService;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Store search for customers (public) and an index rebuild for operator staff with
 * {@code search:manage}.
 */
@RestController
@RequestMapping("/api/v1/search")
class SearchController {

    private final StoreSearchService searchService;

    private final StoreIndexer indexer;

    SearchController(StoreSearchService searchService, StoreIndexer indexer) {
        this.searchService = searchService;
        this.indexer = indexer;
    }

    @GetMapping("/stores")
    StoreSearchPage stores(@RequestParam double lat, @RequestParam double lng, @RequestParam(required = false) String q,
            @RequestParam(required = false) String cuisine, @RequestParam(defaultValue = "DELIVERY") String fulfilment,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer radius) {
        Fulfilment mode;
        try {
            mode = Fulfilment.valueOf(fulfilment.trim().toUpperCase(Locale.ROOT));
        }
        catch (RuntimeException ex) {
            throw new ModuleException.BadRequest("fulfilment must be DELIVERY or PICKUP", "SEARCH_INVALID");
        }
        return this.searchService.search(new StoreSearchQuery(lat, lng, q, cuisine, mode, page, size, radius));
    }

    /** Re-reads every branch into the index in the background. */
    @PostMapping("/reindex")
    @PreAuthorize("hasAuthority('search:manage')")
    ResponseEntity<Map<String, String>> reindex() {
        Thread.ofVirtual().name("search-rebuild").start(this.indexer::rebuild);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "STARTED"));
    }
}

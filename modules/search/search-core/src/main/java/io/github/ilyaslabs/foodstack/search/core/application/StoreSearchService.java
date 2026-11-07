package io.github.ilyaslabs.foodstack.search.core.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import io.github.ilyaslabs.foodstack.search.api.Fulfilment;
import io.github.ilyaslabs.foodstack.search.api.StoreCard;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchPage;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchQuery;
import io.github.ilyaslabs.foodstack.search.core.SearchProperties;
import io.github.ilyaslabs.foodstack.search.core.domain.OpenNow;
import io.github.ilyaslabs.foodstack.search.core.engine.IndexedStore;
import io.github.ilyaslabs.foodstack.search.core.engine.StoreSearchEngine;
import io.github.ilyaslabs.moduleswitch.ModuleException;

/**
 * Customer store search (PRD SE-1 to SE-4): branches that deliver to the customer's point (or are
 * near and offer pickup), matching free text in any enabled language, with cuisine chips. The
 * engine ranks open branches first across all pages, then by relevance and distance; this service
 * keeps that order. Delivery search also stays within a discovery radius (8 km by road at first)
 * that the customer can widen step by step; a branch's own zones still decide whether it delivers.
 */
public class StoreSearchService {

    private static final Pattern CUISINE = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} &'-]{0,29}");

    private final StoreSearchEngine engine;

    private final Clock clock;

    private final List<Integer> steps;

    private final int pickupRadiusMeters;

    private final int maxResults;

    public StoreSearchService(StoreSearchEngine engine, Clock clock) {
        this(engine, clock, new SearchProperties(null, null, null, null));
    }

    public StoreSearchService(StoreSearchEngine engine, Clock clock, SearchProperties properties) {
        this.engine = engine;
        this.clock = clock;
        this.steps = properties.discoveryStepsMeters();
        this.pickupRadiusMeters = properties.pickupRadiusMeters();
        this.maxResults = properties.maxResults();
    }

    public StoreSearchPage search(StoreSearchQuery query) {
        if (query.lat() < -90 || query.lat() > 90 || query.lng() < -180 || query.lng() > 180) {
            throw new ModuleException.BadRequest("lat and lng must be a point on the map", "LOCATION_INVALID");
        }
        String text = (query.text() != null && !query.text().isBlank()) ? query.text().trim() : null;
        if (text != null && text.length() > 100) {
            throw new ModuleException.BadRequest("The search text must be at most 100 characters", "SEARCH_INVALID");
        }
        String cuisine = (query.cuisine() != null && !query.cuisine().isBlank()) ? query.cuisine().trim() : null;
        if (cuisine != null && !CUISINE.matcher(cuisine).matches()) {
            throw new ModuleException.BadRequest("Unknown cuisine", "SEARCH_INVALID");
        }
        int size = Math.min(Math.max(query.size(), 1), 50);
        int page = Math.max(query.page(), 0);
        Fulfilment fulfilment = (query.fulfilment() != null) ? query.fulfilment() : Fulfilment.DELIVERY;
        int step = step(query.radiusMeters());
        int radius = (fulfilment == Fulfilment.PICKUP) ? this.pickupRadiusMeters : this.steps.get(step);
        Integer wider = (fulfilment == Fulfilment.PICKUP || step + 1 >= this.steps.size()) ? null : this.steps.get(step + 1);
        // results stop at a fixed depth: deep pages cost every shard more than anyone scrolls (TDD 7.5)
        int from = page * size;
        if (from >= this.maxResults) {
            return new StoreSearchPage(List.of(), List.of(), 0, page, size, false, radius, wider);
        }
        int pageSize = Math.min(size, this.maxResults - from);
        StoreSearchQuery clean = new StoreSearchQuery(query.lat(), query.lng(), text,
                (cuisine != null) ? cuisine.toLowerCase(Locale.ROOT) : null, fulfilment, page, pageSize,
                (fulfilment == Fulfilment.PICKUP) ? null : radius);
        StoreSearchEngine.Result result = (pageSize == size) ? this.engine.search(clean)
                : this.engine.search(clean, from);
        Instant now = this.clock.instant();
        List<StoreCard> cards = result.hits()
            .stream()
            .map((hit) -> card(hit, now))
            .toList();
        boolean hasMore = cards.size() == pageSize && from + pageSize < Math.min(result.total(), this.maxResults);
        return new StoreSearchPage(cards, result.cuisines(), result.total(), page, size, hasMore, radius, wider);
    }

    /** The configured step for a requested radius: the smallest one that reaches it, else the widest. */
    private int step(Integer radiusMeters) {
        if (radiusMeters == null) {
            return 0;
        }
        for (int i = 0; i < this.steps.size(); i++) {
            if (this.steps.get(i) >= radiusMeters) {
                return i;
            }
        }
        return this.steps.size() - 1;
    }

    private static StoreCard card(StoreSearchEngine.Hit hit, Instant now) {
        IndexedStore s = hit.store();
        boolean open = OpenNow.isOpen(s.openingHours(), s.closedDates(), s.temporarilyClosed(), s.timezone(), now);
        return new StoreCard(s.storeId(), s.slug(), s.brandId(), s.brandName(), s.branchName(), s.branchCount(), s.description(),
                s.cuisines(), s.logoUrls(), s.coverUrls(), s.area(), s.city(), hit.distanceMeters(), s.deliveryMode(),
                s.pickupEnabled(), open, s.temporarilyClosed(), s.prepMinutes(), s.dietaryTags(), s.ratingCount(),
                s.ratingAverage());
    }
}

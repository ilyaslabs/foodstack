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
import io.github.ilyaslabs.foodstack.search.core.domain.OpenNow;
import io.github.ilyaslabs.foodstack.search.core.engine.IndexedStore;
import io.github.ilyaslabs.foodstack.search.core.engine.StoreSearchEngine;
import io.github.ilyaslabs.moduleswitch.ModuleException;

/**
 * Customer store search (PRD SE-1 to SE-4): branches that deliver to the customer's point (or are
 * near and offer pickup), matching free text in any enabled language, with cuisine chips. The
 * engine ranks open branches first across all pages, then by relevance and distance; this service
 * keeps that order.
 */
public class StoreSearchService {

    private static final Pattern CUISINE = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} &'-]{0,29}");

    private final StoreSearchEngine engine;

    private final Clock clock;

    public StoreSearchService(StoreSearchEngine engine, Clock clock) {
        this.engine = engine;
        this.clock = clock;
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
        int page = Math.min(Math.max(query.page(), 0), 50);
        StoreSearchQuery clean = new StoreSearchQuery(query.lat(), query.lng(), text,
                (cuisine != null) ? cuisine.toLowerCase(Locale.ROOT) : null,
                (query.fulfilment() != null) ? query.fulfilment() : Fulfilment.DELIVERY, page, size);
        StoreSearchEngine.Result result = this.engine.search(clean);
        Instant now = this.clock.instant();
        List<StoreCard> cards = result.hits()
            .stream()
            .map((hit) -> card(hit, now))
            .toList();
        return new StoreSearchPage(cards, result.cuisines(), result.total(), page, size);
    }

    private static StoreCard card(StoreSearchEngine.Hit hit, Instant now) {
        IndexedStore s = hit.store();
        boolean open = OpenNow.isOpen(s.openingHours(), s.closedDates(), s.temporarilyClosed(), s.timezone(), now);
        return new StoreCard(s.storeId(), s.slug(), s.brandId(), s.brandName(), s.branchName(), s.branchCount(), s.description(),
                s.cuisines(), s.logoUrls(), s.coverUrls(), s.area(), s.city(), hit.distanceMeters(), s.deliveryMode(),
                s.pickupEnabled(), open, s.temporarilyClosed(), s.prepMinutes(), s.dietaryTags());
    }
}

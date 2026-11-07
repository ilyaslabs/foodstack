package io.github.ilyaslabs.foodstack.search.core.application;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaRequest;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaView;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.review.api.ReviewApi;
import io.github.ilyaslabs.foodstack.review.api.StoreRating;
import io.github.ilyaslabs.foodstack.search.core.engine.IndexedStore;
import io.github.ilyaslabs.foodstack.search.core.engine.StoreSearchEngine;
import io.github.ilyaslabs.foodstack.store.api.StoreApi;
import io.github.ilyaslabs.foodstack.store.api.StoreIdPage;
import io.github.ilyaslabs.foodstack.store.api.StoreListingView;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the index in step with the catalogue. Every change re-reads the branch from the store and
 * geo modules instead of trusting the event body, so duplicates and reordering do no harm: the
 * last refresh writes the current state. Only live branches are indexed.
 */
public class StoreIndexer {

    private static final Logger log = LoggerFactory.getLogger(StoreIndexer.class);

    private static final int PAGE = 200;

    private final StoreApi stores;

    private final GeoApi geo;

    private final StoreSearchEngine engine;

    private final ServiceCalls serviceCalls;

    private final ReviewApi reviews;

    private final AtomicBoolean rebuilding = new AtomicBoolean();

    /** @param serviceCalls reads run as the platform: listings include branches that are not live yet (TDD 9.1) */
    public StoreIndexer(StoreApi stores, GeoApi geo, StoreSearchEngine engine, ServiceCalls serviceCalls) {
        this(stores, geo, engine, serviceCalls, null);
    }

    /** @param reviews branch ratings for the cards (PRD RV-4), or {@code null} without the review module */
    public StoreIndexer(StoreApi stores, GeoApi geo, StoreSearchEngine engine, ServiceCalls serviceCalls, ReviewApi reviews) {
        this.reviews = reviews;
        this.stores = stores;
        this.geo = geo;
        this.engine = engine;
        this.serviceCalls = serviceCalls;
    }

    /** Re-reads one branch and indexes it, or removes it when it is not live (or gone). */
    public void refresh(String storeId) {
        this.serviceCalls.run(() -> refreshAsService(storeId));
    }

    private void refreshAsService(String storeId) {
        StoreListingView listing;
        try {
            listing = this.stores.getListing(storeId);
        }
        catch (ModuleException.NotFound ex) {
            this.engine.delete(storeId);
            return;
        }
        if (listing.status() != StoreStatus.LIVE || listing.location() == null) {
            this.engine.delete(storeId, listing.version());
            return;
        }
        StoreRating rating = rating(storeId);
        DeliveryAreaView area = this.geo.deliveryArea(
                new DeliveryAreaRequest(storeId, listing.location(), listing.deliveryMode(), listing.cityId()));
        this.engine.index(new IndexedStore(listing.storeId(), listing.slug(), listing.brandId(), listing.brandName(), listing.branchName(),
                listing.branchCount(), listing.description(), listing.cuisines(), listing.logoUrls(), listing.coverUrls(),
                listing.location(), listing.area(), listing.city(), listing.deliveryMode().name(), listing.pickupEnabled(),
                listing.temporarilyClosed(), listing.prepMinutes(), listing.timezone(), listing.openingHours(),
                listing.closedDates(), listing.dishNames(), listing.dietaryTags(),
                (area.polygons() != null) ? area.polygons() : List.of(), listing.version(), rating.count(), rating.average()));
    }

    private StoreRating rating(String storeId) {
        if (this.reviews == null) {
            return StoreRating.none(storeId);
        }
        try {
            StoreRating rating = this.reviews.storeRating(storeId);
            return (rating != null) ? rating : StoreRating.none(storeId);
        }
        catch (RuntimeException ex) {
            // a branch without its rating beats a branch missing from search
            log.warn("Could not read the rating of branch {}: {}", storeId, ex.getMessage());
            return StoreRating.none(storeId);
        }
    }

    /** A brand change (name, images, menu) touches every indexed branch of the brand. */
    public void refreshBrand(String brandId) {
        this.engine.storeIdsOfBrand(brandId).forEach(this::refresh);
    }

    /** Operator delivery pricing sets the area of every branch delivered by operator riders. */
    public void refreshOperatorDelivery() {
        this.engine.storeIdsWithDeliveryMode(DeliveryMode.OPERATOR.name()).forEach(this::refresh);
    }

    /**
     * Re-reads every branch. Runs at most once at a time per instance; returns the number of
     * branches read, or -1 when a rebuild was already running.
     */
    public int rebuild() {
        if (!this.rebuilding.compareAndSet(false, true)) {
            return -1;
        }
        try {
            return this.serviceCalls.call(this::rebuildAsService);
        }
        finally {
            this.rebuilding.set(false);
        }
    }

    private int rebuildAsService() {
        {
            int count = 0;
            String after = null;
            do {
                StoreIdPage page = this.stores.listStoreIds(after, PAGE);
                for (String storeId : page.storeIds()) {
                    try {
                        refresh(storeId);
                    }
                    catch (RuntimeException ex) {
                        log.warn("Could not index branch {}: {}", storeId, ex.getMessage());
                    }
                    count++;
                }
                after = page.nextAfterId();
            }
            while (after != null);
            log.info("Search rebuild read {} branches", count);
            return count;
        }
    }
}

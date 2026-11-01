package io.github.ilyaslabs.foodstack.search.core.events;

import io.github.ilyaslabs.foodstack.geo.api.events.OperatorDeliveryPricingChanged;
import io.github.ilyaslabs.foodstack.geo.api.events.ZoneChanged;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import io.github.ilyaslabs.foodstack.search.core.application.StoreIndexer;
import io.github.ilyaslabs.foodstack.store.api.events.BrandChanged;
import io.github.ilyaslabs.foodstack.store.api.events.ItemAvailabilityChanged;
import io.github.ilyaslabs.foodstack.store.api.events.MenuChanged;
import io.github.ilyaslabs.foodstack.store.api.events.StoreAvailabilityChanged;
import io.github.ilyaslabs.foodstack.store.api.events.StoreChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Feeds the index from store and geo events (TDD 11.2). The handler only needs the id in the
 * event: {@link StoreIndexer} re-reads the source, so a redelivered or late event is harmless and
 * no inbox is kept. A failing refresh is retried by the container and then skipped; the periodic
 * or manual rebuild repairs anything missed.
 */
public class CatalogueEventsListener {

    private static final Logger log = LoggerFactory.getLogger(CatalogueEventsListener.class);

    private static final String STORE_CHANGED = EventTypes.type("store", StoreChanged.class);

    private static final String STORE_AVAILABILITY = EventTypes.type("store", StoreAvailabilityChanged.class);

    private static final String ITEM_AVAILABILITY = EventTypes.type("store", ItemAvailabilityChanged.class);

    private static final String BRAND_CHANGED = EventTypes.type("store", BrandChanged.class);

    private static final String MENU_CHANGED = EventTypes.type("store", MenuChanged.class);

    private static final String ZONE_CHANGED = EventTypes.type("geo", ZoneChanged.class);

    private static final String PRICING_CHANGED = EventTypes.type("geo", OperatorDeliveryPricingChanged.class);

    private final StoreIndexer indexer;

    private final JsonMapper jsonMapper;

    public CatalogueEventsListener(StoreIndexer indexer, JsonMapper jsonMapper) {
        this.indexer = indexer;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "${foodstack.namespace:foodstack}-search-catalogue",
            groupId = "${foodstack.namespace:foodstack}-search",
            topics = { "${foodstack.namespace:foodstack}.store.events", "${foodstack.namespace:foodstack}.geo.events" },
            properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        String type = event.type();
        if (STORE_CHANGED.equals(type)) {
            this.indexer.refresh(event.dataAs(StoreChanged.class, this.jsonMapper).storeId());
        }
        else if (STORE_AVAILABILITY.equals(type)) {
            this.indexer.refresh(event.dataAs(StoreAvailabilityChanged.class, this.jsonMapper).storeId());
        }
        else if (ITEM_AVAILABILITY.equals(type)) {
            this.indexer.refresh(event.dataAs(ItemAvailabilityChanged.class, this.jsonMapper).storeId());
        }
        else if (BRAND_CHANGED.equals(type)) {
            this.indexer.refreshBrand(event.dataAs(BrandChanged.class, this.jsonMapper).brandId());
        }
        else if (MENU_CHANGED.equals(type)) {
            this.indexer.refreshBrand(event.dataAs(MenuChanged.class, this.jsonMapper).brandId());
        }
        else if (ZONE_CHANGED.equals(type)) {
            this.indexer.refresh(event.dataAs(ZoneChanged.class, this.jsonMapper).storeId());
        }
        else if (PRICING_CHANGED.equals(type)) {
            this.indexer.refreshOperatorDelivery();
        }
        else {
            log.debug("Ignoring event {}", type);
        }
    }
}

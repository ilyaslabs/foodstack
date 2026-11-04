package io.github.ilyaslabs.foodstack.geo.core.events;

import io.github.ilyaslabs.foodstack.geo.api.events.OperatorDeliveryPricingChanged;
import io.github.ilyaslabs.foodstack.geo.api.events.ZoneChanged;
import io.github.ilyaslabs.foodstack.geo.core.application.GeoCache;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Evicts cached zones and pricing again when geo's own change events come back from Kafka, so a
 * value a reader cached just before the change commits does not live out its TTL. Eviction is
 * harmless to repeat, so there is no inbox.
 */
public class GeoCacheListener {

    private static final String ZONE_CHANGED = EventTypes.type("geo", ZoneChanged.class);

    private static final String PRICING_CHANGED = EventTypes.type("geo", OperatorDeliveryPricingChanged.class);

    private final GeoCache cache;

    private final JsonMapper jsonMapper;

    public GeoCacheListener(GeoCache cache, JsonMapper jsonMapper) {
        this.cache = cache;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.geo:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-geo-cache", groupId = "${foodstack.namespace:foodstack}-geo-cache",
            topics = "${foodstack.namespace:foodstack}.geo.events", properties = "auto.offset.reset=latest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        if (ZONE_CHANGED.equals(event.type())) {
            this.cache.evictZones(event.dataAs(ZoneChanged.class, this.jsonMapper).storeId());
        }
        else if (PRICING_CHANGED.equals(event.type())) {
            this.cache.evictOperatorPricing();
        }
    }
}

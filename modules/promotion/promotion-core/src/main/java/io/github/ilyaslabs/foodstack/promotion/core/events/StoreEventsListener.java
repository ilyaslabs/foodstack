package io.github.ilyaslabs.foodstack.promotion.core.events;

import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import io.github.ilyaslabs.foodstack.promotion.core.application.BrandRules;
import io.github.ilyaslabs.foodstack.store.api.events.BranchPromotionsChanged;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Keeps each brand's branch-promotions switch (PRD BB-11) from the store module's events. Applying
 * an event twice, or an older one late, changes nothing, so no inbox is needed.
 */
public class StoreEventsListener {

    private static final String BRANCH_PROMOTIONS = EventTypes.type("store", BranchPromotionsChanged.class);

    private final BrandRules rules;

    private final JsonMapper jsonMapper;

    public StoreEventsListener(BrandRules rules, JsonMapper jsonMapper) {
        this.rules = rules;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.promotion:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-promotion-stores", groupId = "${foodstack.namespace:foodstack}-promotion",
            topics = "${foodstack.namespace:foodstack}.store.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        if (BRANCH_PROMOTIONS.equals(event.type())) {
            BranchPromotionsChanged changed = event.dataAs(BranchPromotionsChanged.class, this.jsonMapper);
            this.rules.apply(changed.brandId(), changed.allowed(), changed.version());
        }
    }
}

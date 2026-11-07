package io.github.ilyaslabs.foodstack.ledger.core.events;

import io.github.ilyaslabs.foodstack.ledger.core.application.CreditLimits;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import io.github.ilyaslabs.foodstack.settings.api.events.SettingsChanged;
import io.github.ilyaslabs.foodstack.store.api.events.StorePaymentCollectionChanged;

import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who collects for each branch and its credit limit, from the store module, and the operator's
 * default limit, from settings (PRD DP-7).
 */
public class StoreEventsListener {

    private static final String COLLECTION = EventTypes.type("store", StorePaymentCollectionChanged.class);

    private static final String SETTINGS = EventTypes.type("settings", SettingsChanged.class);

    private final CreditLimits creditLimits;

    private final JsonMapper jsonMapper;

    public StoreEventsListener(CreditLimits creditLimits, JsonMapper jsonMapper) {
        this.creditLimits = creditLimits;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.ledger:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-ledger-stores", groupId = "${foodstack.namespace:foodstack}-ledger",
            topics = { "${foodstack.namespace:foodstack}.store.events", "${foodstack.namespace:foodstack}.settings.events" },
            properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        if (COLLECTION.equals(event.type())) {
            StorePaymentCollectionChanged changed = event.dataAs(StorePaymentCollectionChanged.class, this.jsonMapper);
            this.creditLimits.collectionChanged(changed.storeId(), changed.brandId(), changed.storeName(), changed.ownerUserIds(),
                    changed.mode(), changed.creditLimitMinor());
        }
        else if (SETTINGS.equals(event.type())
                && SettingsChanged.ORDERING.equals(event.dataAs(SettingsChanged.class, this.jsonMapper).section())) {
            this.creditLimits.defaultChanged();
        }
    }
}

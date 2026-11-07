package io.github.ilyaslabs.foodstack.payment.core.events;

import io.github.ilyaslabs.foodstack.ledger.api.events.StoreCreditLimitExceeded;
import io.github.ilyaslabs.foodstack.ledger.api.events.StoreCreditRestored;
import io.github.ilyaslabs.foodstack.payment.core.application.MerchantAccounts;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import io.github.ilyaslabs.foodstack.store.api.events.StorePaymentCollectionChanged;

import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who may collect for each branch, from the store module, and suspensions for going over the
 * credit limit, from the ledger (PRD DP-2, DP-7, TDD 5.4).
 */
public class CollectionEventsListener {

    private static final String COLLECTION = EventTypes.type("store", StorePaymentCollectionChanged.class);

    private static final String EXCEEDED = EventTypes.type("ledger", StoreCreditLimitExceeded.class);

    private static final String RESTORED = EventTypes.type("ledger", StoreCreditRestored.class);

    private final MerchantAccounts accounts;

    private final JsonMapper jsonMapper;

    public CollectionEventsListener(MerchantAccounts accounts, JsonMapper jsonMapper) {
        this.accounts = accounts;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.payment:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-payment-collection", groupId = "${foodstack.namespace:foodstack}-payment",
            topics = { "${foodstack.namespace:foodstack}.store.events", "${foodstack.namespace:foodstack}.ledger.events" },
            properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        String type = event.type();
        if (COLLECTION.equals(type)) {
            StorePaymentCollectionChanged changed = event.dataAs(StorePaymentCollectionChanged.class, this.jsonMapper);
            this.accounts.collectionChanged(changed.storeId(), changed.brandId(), changed.mode());
        }
        else if (EXCEEDED.equals(type)) {
            StoreCreditLimitExceeded exceeded = event.dataAs(StoreCreditLimitExceeded.class, this.jsonMapper);
            this.accounts.suspended(exceeded.storeId(), exceeded.brandId(), true);
        }
        else if (RESTORED.equals(type)) {
            StoreCreditRestored restored = event.dataAs(StoreCreditRestored.class, this.jsonMapper);
            this.accounts.suspended(restored.storeId(), restored.brandId(), false);
        }
    }
}

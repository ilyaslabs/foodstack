package io.github.ilyaslabs.foodstack.notification.core.events;

import io.github.ilyaslabs.foodstack.ledger.api.events.StoreCreditLimitExceeded;
import io.github.ilyaslabs.foodstack.ledger.api.events.StoreCreditRestored;
import io.github.ilyaslabs.foodstack.notification.core.application.FinanceMessages;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;

import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.json.JsonMapper;

/** Credit limit messages from the ledger (PRD DP-7). */
public class LedgerNotificationsListener {

    private static final String EXCEEDED = EventTypes.type("ledger", StoreCreditLimitExceeded.class);

    private static final String RESTORED = EventTypes.type("ledger", StoreCreditRestored.class);

    private final FinanceMessages messages;

    private final JsonMapper jsonMapper;

    public LedgerNotificationsListener(FinanceMessages messages, JsonMapper jsonMapper) {
        this.messages = messages;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.notification:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-notification-ledger",
            groupId = "${foodstack.namespace:foodstack}-notification",
            topics = "${foodstack.namespace:foodstack}.ledger.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        if (EXCEEDED.equals(event.type())) {
            this.messages.onExceeded(event.id(), event.dataAs(StoreCreditLimitExceeded.class, this.jsonMapper));
        }
        else if (RESTORED.equals(event.type())) {
            this.messages.onRestored(event.id(), event.dataAs(StoreCreditRestored.class, this.jsonMapper));
        }
    }
}

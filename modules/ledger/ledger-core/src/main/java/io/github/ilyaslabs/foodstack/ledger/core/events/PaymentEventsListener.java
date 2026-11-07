package io.github.ilyaslabs.foodstack.ledger.core.events;

import java.time.Clock;
import java.time.Instant;

import io.github.ilyaslabs.foodstack.ledger.core.application.LedgerPosting;
import io.github.ilyaslabs.foodstack.payment.api.events.PaymentSucceeded;
import io.github.ilyaslabs.foodstack.payment.api.events.RefundSucceeded;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/** Posts card money in and out of the operator's gateway from the payment module's events (PRD FI-1). */
public class PaymentEventsListener {

    private static final String SUCCEEDED = EventTypes.type("payment", PaymentSucceeded.class);

    private static final String REFUNDED = EventTypes.type("payment", RefundSucceeded.class);

    private final LedgerPosting posting;

    private final JsonMapper jsonMapper;

    private final Clock clock;

    public PaymentEventsListener(LedgerPosting posting, JsonMapper jsonMapper, Clock clock) {
        this.posting = posting;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.ledger:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-ledger-payments", groupId = "${foodstack.namespace:foodstack}-ledger",
            topics = "${foodstack.namespace:foodstack}.payment.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        Instant at = (event.time() != null) ? event.time() : this.clock.instant();
        if (SUCCEEDED.equals(event.type())) {
            this.posting.paymentReceived(event.dataAs(PaymentSucceeded.class, this.jsonMapper), at);
        }
        else if (REFUNDED.equals(event.type())) {
            this.posting.refundPaidOut(event.dataAs(RefundSucceeded.class, this.jsonMapper), at);
        }
    }
}

package io.github.ilyaslabs.foodstack.notification.core.events;

import io.github.ilyaslabs.foodstack.identity.api.events.EmailCodeIssued;
import io.github.ilyaslabs.foodstack.identity.api.events.UserRegistered;
import io.github.ilyaslabs.foodstack.notification.core.application.AccountMessages;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/** Account messages from identity events: a welcome for new customers, and emailed one-time codes. */
public class IdentityNotificationsListener {

    private static final String REGISTERED = EventTypes.type("identity", UserRegistered.class);

    private static final String EMAIL_CODE = EventTypes.type("identity", EmailCodeIssued.class);

    private final AccountMessages messages;

    private final JsonMapper jsonMapper;

    public IdentityNotificationsListener(AccountMessages messages, JsonMapper jsonMapper) {
        this.messages = messages;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "${foodstack.namespace:foodstack}-notification-identity",
            groupId = "${foodstack.namespace:foodstack}-notification",
            topics = "${foodstack.namespace:foodstack}.identity.events", properties = "auto.offset.reset=earliest")
    public void onEvent(String value) {
        CloudEvents.Envelope event = CloudEvents.parse(value, this.jsonMapper);
        if (REGISTERED.equals(event.type())) {
            this.messages.onRegistered(event.id(), event.dataAs(UserRegistered.class, this.jsonMapper));
        }
        else if (EMAIL_CODE.equals(event.type())) {
            this.messages.onEmailCode(event.id(), event.dataAs(EmailCodeIssued.class, this.jsonMapper));
        }
    }
}

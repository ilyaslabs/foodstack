package io.github.ilyaslabs.foodstack.notification.core.events;

import io.github.ilyaslabs.foodstack.identity.api.events.AccountDeletionScheduled;
import io.github.ilyaslabs.foodstack.identity.api.events.EmailCodeIssued;
import io.github.ilyaslabs.foodstack.identity.api.events.UserDeleted;
import io.github.ilyaslabs.foodstack.identity.api.events.UserRegistered;
import io.github.ilyaslabs.foodstack.notification.core.application.AccountMessages;
import io.github.ilyaslabs.foodstack.notification.core.application.CustomerNotificationData;
import io.github.ilyaslabs.foodstack.platform.core.EventTypes;
import io.github.ilyaslabs.foodstack.platform.kafka.CloudEvents;

import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Account messages from identity events: a welcome for new customers, emailed one-time codes and
 * the notice of a requested account deletion; a deleted account's notification data is removed.
 */
public class IdentityNotificationsListener {

    private static final String REGISTERED = EventTypes.type("identity", UserRegistered.class);

    private static final String EMAIL_CODE = EventTypes.type("identity", EmailCodeIssued.class);

    private static final String DELETION_SCHEDULED = EventTypes.type("identity", AccountDeletionScheduled.class);

    private static final String DELETED = EventTypes.type("identity", UserDeleted.class);

    private final AccountMessages messages;

    private final CustomerNotificationData data;

    private final JsonMapper jsonMapper;

    public IdentityNotificationsListener(AccountMessages messages, CustomerNotificationData data, JsonMapper jsonMapper) {
        this.messages = messages;
        this.data = data;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.notification:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-notification-identity",
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
        else if (DELETION_SCHEDULED.equals(event.type())) {
            this.messages.onDeletionScheduled(event.id(), event.dataAs(AccountDeletionScheduled.class, this.jsonMapper));
        }
        else if (DELETED.equals(event.type())) {
            this.data.erase(event.dataAs(UserDeleted.class, this.jsonMapper).userId());
        }
    }
}

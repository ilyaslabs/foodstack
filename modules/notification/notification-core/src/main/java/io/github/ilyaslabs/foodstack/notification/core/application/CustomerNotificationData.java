package io.github.ilyaslabs.foodstack.notification.core.application;

import java.util.List;

import io.github.ilyaslabs.foodstack.notification.core.persistence.DeliveryDocument;
import io.github.ilyaslabs.foodstack.notification.core.persistence.DeviceDocument;
import io.github.ilyaslabs.foodstack.notification.core.persistence.InboxDocument;
import io.github.ilyaslabs.foodstack.notification.core.persistence.PreferencesDocument;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * A user's notification data for their data export, and its removal when the account is deleted
 * (PRD ID-7): the notification centre, push devices, email preferences and the delivery log.
 */
public class CustomerNotificationData {

    private static final int MAX_EXPORT = 5_000;

    private final MongoTemplate mongo;

    private final NotificationService notifications;

    public CustomerNotificationData(MongoTemplate mongo, NotificationService notifications) {
        this.mongo = mongo;
        this.notifications = notifications;
    }

    /**
     * @param preferences email choices
     * @param messages the notification centre, newest first
     * @param devices phones and browsers that get push messages (the tokens themselves are left out)
     */
    public record Export(NotificationService.Preferences preferences, List<InboxService.Item> messages,
            List<Device> devices) {

        /**
         * @param platform {@code android}, {@code ios} or {@code web}
         * @param app which app
         * @param createdAt first registered, epoch seconds
         */
        public record Device(String platform, String app, long createdAt) {
        }
    }

    public Export export(String userId) {
        List<InboxService.Item> messages = this.mongo
            .find(Query.query(Criteria.where("userId").is(userId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(MAX_EXPORT), InboxDocument.class)
            .stream()
            .map((d) -> new InboxService.Item(d.id(), d.template(), d.title(), d.body(), d.link(),
                    d.createdAt().getEpochSecond(), d.readAt() != null))
            .toList();
        List<Export.Device> devices = this.mongo.find(Query.query(Criteria.where("userId").is(userId)), DeviceDocument.class)
            .stream()
            .map((d) -> new Export.Device(d.platform(), d.app(), d.createdAt().getEpochSecond()))
            .toList();
        return new Export(this.notifications.preferences(userId), messages, devices);
    }

    /** Removes everything this module keeps about the user. Safe to run again. */
    public void erase(String userId) {
        Query mine = Query.query(Criteria.where("userId").is(userId));
        this.mongo.remove(mine, InboxDocument.class);
        this.mongo.remove(mine, DeviceDocument.class);
        this.mongo.remove(mine, DeliveryDocument.class);
        this.mongo.remove(Query.query(Criteria.where("_id").is(userId)), PreferencesDocument.class);
    }
}

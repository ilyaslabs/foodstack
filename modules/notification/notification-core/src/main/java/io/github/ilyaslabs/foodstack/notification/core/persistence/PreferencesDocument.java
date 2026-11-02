package io.github.ilyaslabs.foodstack.notification.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A user's notification choices (PRD NO-3) in {@code notification_preferences} ({@code _id = userId},
 * shard key {@code { _id: hashed }}). Missing means the defaults: order emails on, marketing off.
 *
 * @param id user id
 * @param orderEmails emails about the user's orders (in-app messages always arrive)
 * @param marketingEmails offers and news (opt-in)
 * @param updatedAt last change
 */
@Document("notification_preferences")
public record PreferencesDocument(@Id String id, boolean orderEmails, boolean marketingEmails, Instant updatedAt) {
}

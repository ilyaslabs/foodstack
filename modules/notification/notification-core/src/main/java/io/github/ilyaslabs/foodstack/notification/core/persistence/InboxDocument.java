package io.github.ilyaslabs.foodstack.notification.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A message in a user's notification centre (PRD NO-1), in {@code notification_inbox} (shard key
 * {@code { userId: hashed }}, TDD 6.3), written in the user's language. The id is derived from the
 * event and the user, so a redelivered event adds nothing. Kept 180 days.
 *
 * @param id event id and user id
 * @param userId recipient
 * @param template which message, such as {@code order-accepted}
 * @param title headline
 * @param body text
 * @param link app path to open, such as {@code /orders/<id>}, or {@code null}
 * @param createdAt created
 * @param readAt read, or {@code null}
 */
@Document("notification_inbox")
public record InboxDocument(@Id String id, String userId, String template, String title, String body, String link,
        Instant createdAt, Instant readAt) {
}

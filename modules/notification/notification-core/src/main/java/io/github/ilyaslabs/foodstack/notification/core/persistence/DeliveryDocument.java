package io.github.ilyaslabs.foodstack.notification.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One email in the delivery log (PRD NO-4), in {@code notification_deliveries} (shard key
 * {@code { _id: hashed }}). The rendered message is kept so a retry sends the same text; the
 * address is encrypted (PII). Kept 90 days.
 *
 * @param id event id, user id and channel
 * @param userId recipient, or {@code null}
 * @param channel {@code EMAIL}
 * @param template which message
 * @param recipient encrypted address
 * @param subject subject line
 * @param html HTML body
 * @param text plain-text body
 * @param status {@code QUEUED}, {@code SENDING}, {@code SENT}, {@code FAILED} or {@code SKIPPED}
 * @param attempts tries so far
 * @param lastError why the last try failed, or {@code null}
 * @param nextAttemptAt when to try next
 * @param sentAt sent at, or {@code null}
 * @param createdAt created
 */
@Document("notification_deliveries")
public record DeliveryDocument(@Id String id, String userId, String channel, String template, String recipient, String subject,
        String html, String text, String status, int attempts, String lastError, Instant nextAttemptAt, Instant sentAt,
        Instant createdAt) {

    public static final String QUEUED = "QUEUED";

    public static final String SENDING = "SENDING";

    public static final String SENT = "SENT";

    public static final String FAILED = "FAILED";

    public static final String SKIPPED = "SKIPPED";
}

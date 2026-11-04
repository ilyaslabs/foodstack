package io.github.ilyaslabs.foodstack.notification.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One email or push message in the delivery log (PRD NO-4), in {@code notification_deliveries}
 * (shard key {@code { _id: hashed }}). Event consumers only queue it; senders on every replica
 * claim and send it (TDD 7.5), so a slow mail server or push service never holds up the events.
 * The rendered message is kept so a retry sends the same text; the address is encrypted (PII).
 * Kept 90 days.
 *
 * @param id event id, user id and channel
 * @param userId recipient, or {@code null}
 * @param channel {@code EMAIL} or {@code PUSH}
 * @param template which message
 * @param recipient encrypted address (email), or {@code null} (push)
 * @param subject subject line, or the push title
 * @param html HTML body
 * @param text plain-text body, or the push body
 * @param status {@code QUEUED}, {@code SENDING}, {@code SENT}, {@code FAILED} or {@code SKIPPED}
 * @param attempts tries so far
 * @param lastError why the last try failed, or {@code null}
 * @param nextAttemptAt when to try next
 * @param sentAt sent at, or {@code null}
 * @param createdAt created
 * @param sealedSubject encrypted subject of a sign-in code email, or {@code null}
 * @param app push only: {@code web} or {@code backoffice}
 * @param link push only: the URL the message opens, or {@code null}
 */
@Document("notification_deliveries")
public record DeliveryDocument(@Id String id, String userId, String channel, String template, String recipient, String subject,
        String html, String text, String status, int attempts, String lastError, Instant nextAttemptAt, Instant sentAt,
        Instant createdAt, String sealedSubject, String app, String link) {

    public DeliveryDocument(String id, String userId, String channel, String template, String recipient, String subject,
            String html, String text, String status, int attempts, String lastError, Instant nextAttemptAt, Instant sentAt,
            Instant createdAt, String sealedSubject) {
        this(id, userId, channel, template, recipient, subject, html, text, status, attempts, lastError, nextAttemptAt, sentAt,
                createdAt, sealedSubject, null, null);
    }

    /** Whether this email carries a secret (a sign-in or reset code), kept encrypted and wiped once done. */
    public boolean sealed() {
        return this.sealedSubject != null;
    }

    public static final String QUEUED = "QUEUED";

    public static final String SENDING = "SENDING";

    public static final String SENT = "SENT";

    public static final String FAILED = "FAILED";

    public static final String SKIPPED = "SKIPPED";
}

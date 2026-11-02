package io.github.ilyaslabs.foodstack.notification.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.notification.core.channel.MailTransport.MailFailure;
import io.github.ilyaslabs.foodstack.notification.core.channel.MailTransport;
import io.github.ilyaslabs.foodstack.notification.core.persistence.DeliveryDocument;
import io.github.ilyaslabs.foodstack.notification.core.persistence.InboxDocument;
import io.github.ilyaslabs.foodstack.notification.core.persistence.PreferencesDocument;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.settings.api.BrandingView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Turns platform events into messages (PRD 4.10): one in the user's notification centre and, when
 * their preferences allow, an email. Emails go through the delivery log: queued, sent at once when
 * possible, retried with growing pauses (1, 5, 30 and 120 minutes) and marked failed with the
 * reason after five tries. Both are keyed by the event, so an event delivered twice sends once.
 */
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public static final String EMAIL = "EMAIL";

    static final int MAX_ATTEMPTS = 5;

    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofMinutes(120));

    static final Duration SEND_LEASE = Duration.ofMinutes(5);

    private final MongoTemplate mongo;

    private final Templates templates;

    private final SettingsApi settings;

    private final IdentityApi identity;

    private final ServiceCalls serviceCalls;

    private final PiiCipher pii;

    private final MailTransport mail;

    private final String webUrl;

    private final String adminUrl;

    private final Clock clock;

    /** @param mail the email transport, or {@code null} when no SMTP server is set up */
    public NotificationService(MongoTemplate mongo, Templates templates, SettingsApi settings, IdentityApi identity,
            ServiceCalls serviceCalls, PiiCipher pii, MailTransport mail, String webUrl, String adminUrl, Clock clock) {
        this.mongo = mongo;
        this.templates = templates;
        this.settings = settings;
        this.identity = identity;
        this.serviceCalls = serviceCalls;
        this.pii = pii;
        this.mail = mail;
        this.webUrl = webUrl.replaceAll("/+$", "");
        this.adminUrl = adminUrl.replaceAll("/+$", "");
        this.clock = clock;
    }

    /**
     * A message to send.
     *
     * @param eventId the event that caused it, so it is sent once
     * @param userId recipient, or {@code null} when only {@code email} is known
     * @param email address to use instead of the account's, or {@code null}
     * @param template template name
     * @param language language to write in
     * @param params placeholder values
     * @param link app path to open, or {@code null}
     * @param backOffice whether the link opens the back office instead of the customer app
     * @param inApp whether to add it to the notification centre
     * @param email whether to email it
     * @param required whether it is sent whatever the preferences (sign-in codes)
     */
    public record Message(String eventId, String userId, String emailAddress, String template, String language,
            Map<String, ?> params, String link, boolean backOffice, boolean inApp, boolean email, boolean required) {
    }

    public void send(Message message) {
        Instant now = this.clock.instant();
        Templates.Rendered rendered = this.templates.render(message.template(), message.language(), message.params());
        if (message.inApp() && message.userId() != null) {
            try {
                this.mongo.insert(new InboxDocument(message.eventId() + ":" + message.userId(), message.userId(),
                        message.template(), rendered.title(), rendered.body(), message.link(), now, null));
            }
            catch (DuplicateKeyException ex) {
                // this event was already turned into a message
            }
        }
        if (!message.email()) {
            return;
        }
        if (!message.required() && message.userId() != null && !preferences(message.userId()).orderEmails()) {
            return;
        }
        String address = message.emailAddress();
        if (address == null && message.userId() != null) {
            UserView user = this.serviceCalls.call(() -> {
                try {
                    return this.identity.getUser(message.userId());
                }
                catch (ModuleException.NotFound ex) {
                    return null;
                }
            });
            address = (user != null) ? user.email() : null;
        }
        if (address == null || address.isBlank()) {
            return;
        }
        BrandingView branding = this.settings.getBranding();
        String brand = (branding != null && branding.name() != null) ? branding.name() : "FoodStack";
        String url = (message.link() != null) ? (message.backOffice() ? this.adminUrl : this.webUrl) + message.link() : null;
        String button = this.templates.phrase("button", message.language(), Map.of());
        String html = EmailLayout.html(brand, (branding != null) ? branding.primaryColor() : null, message.language(),
                rendered.title(), rendered.body(), button, url);
        String text = EmailLayout.text(brand, rendered.title(), rendered.body(), url);
        String id = message.eventId() + ":" + ((message.userId() != null) ? message.userId() : "-") + ":" + EMAIL;
        boolean ready = this.mail != null;
        try {
            this.mongo.insert(new DeliveryDocument(id, message.userId(), EMAIL, message.template(), this.pii.encrypt(address),
                    rendered.subject(), html, text, ready ? DeliveryDocument.QUEUED : DeliveryDocument.SKIPPED, 0,
                    ready ? null : "Email is not set up (SMTP_HOST)", now, null, now));
        }
        catch (DuplicateKeyException ex) {
            return;
        }
        if (ready) {
            sendOne(id);
        }
    }

    /** Sends emails that are due; returns how many went out. */
    public int sendDue() {
        if (this.mail == null) {
            return 0;
        }
        int sent = 0;
        for (int i = 0; i < 50; i++) {
            DeliveryDocument claimed = claim(Criteria.where("status").in(DeliveryDocument.QUEUED, DeliveryDocument.SENDING)
                .and("nextAttemptAt").lte(this.clock.instant()));
            if (claimed == null) {
                break;
            }
            if (deliver(claimed)) {
                sent++;
            }
        }
        return sent;
    }

    /** Puts a failed email back in the queue (PRD NO-4). */
    public void retry(String deliveryId) {
        DeliveryDocument updated = this.mongo.findAndModify(
                Query.query(Criteria.where("_id").is(deliveryId)
                    .and("status").in(DeliveryDocument.FAILED, DeliveryDocument.SKIPPED)),
                new Update().set("status", DeliveryDocument.QUEUED).set("nextAttemptAt", this.clock.instant()).set("attempts", 0)
                    .unset("lastError"),
                FindAndModifyOptions.options().returnNew(true), DeliveryDocument.class);
        if (updated == null) {
            throw new ModuleException.Conflict("Only failed emails can be sent again", "DELIVERY_NOT_FAILED");
        }
        if (this.mail != null) {
            sendOne(deliveryId);
        }
    }

    /**
     * A user's choices.
     *
     * @param orderEmails emails about their orders
     * @param marketingEmails offers and news
     */
    public record Preferences(boolean orderEmails, boolean marketingEmails) {
    }

    /**
     * A row of the delivery log.
     *
     * @param id delivery id
     * @param template which message
     * @param recipient address with most of it hidden
     * @param subject subject line
     * @param status status
     * @param attempts tries so far
     * @param lastError why the last try failed
     * @param createdAt epoch seconds
     * @param sentAt epoch seconds, or {@code null}
     */
    public record DeliveryItem(String id, String template, String recipient, String subject, String status, int attempts,
            String lastError, long createdAt, Long sentAt) {
    }

    public Preferences preferences(String userId) {
        PreferencesDocument stored = this.mongo.findById(userId, PreferencesDocument.class);
        return (stored != null) ? new Preferences(stored.orderEmails(), stored.marketingEmails()) : new Preferences(true, false);
    }

    public Preferences updatePreferences(String userId, boolean orderEmails, boolean marketingEmails) {
        this.mongo.save(new PreferencesDocument(userId, orderEmails, marketingEmails, this.clock.instant()));
        return new Preferences(orderEmails, marketingEmails);
    }

    /** The admin's view of the log, newest first. */
    public List<DeliveryItem> deliveries(String status, Long before, int limit) {
        Criteria criteria = new Criteria();
        if (status != null && !status.isBlank()) {
            criteria = criteria.and("status").is(status);
        }
        if (before != null) {
            criteria = criteria.and("createdAt").lt(Instant.ofEpochSecond(before));
        }
        return this.mongo.find(Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "createdAt"))
            .limit(Math.max(1, Math.min(limit, 100))), DeliveryDocument.class)
            .stream()
            .map((d) -> new DeliveryItem(d.id(), d.template(), maskedRecipient(d), d.subject(), d.status(), d.attempts(),
                    d.lastError(), d.createdAt().getEpochSecond(), (d.sentAt() != null) ? d.sentAt().getEpochSecond() : null))
            .toList();
    }

    private String maskedRecipient(DeliveryDocument delivery) {
        try {
            String address = this.pii.decrypt(delivery.recipient());
            int at = address.indexOf('@');
            return (at <= 1) ? "***" + address.substring(Math.max(at, 0)) : address.charAt(0) + "***" + address.substring(at);
        }
        catch (RuntimeException ex) {
            return "***";
        }
    }

    private void sendOne(String id) {
        DeliveryDocument claimed = claim(Criteria.where("_id").is(id).and("status").is(DeliveryDocument.QUEUED));
        if (claimed != null) {
            deliver(claimed);
        }
    }

    private DeliveryDocument claim(Criteria criteria) {
        Instant now = this.clock.instant();
        return this.mongo.findAndModify(Query.query(criteria).with(Sort.by("nextAttemptAt")),
                new Update().set("status", DeliveryDocument.SENDING).set("nextAttemptAt", now.plus(SEND_LEASE)).inc("attempts", 1),
                FindAndModifyOptions.options().returnNew(true), DeliveryDocument.class);
    }

    private boolean deliver(DeliveryDocument delivery) {
        Instant now = this.clock.instant();
        try {
            this.mail.send(this.pii.decrypt(delivery.recipient()), delivery.subject(), delivery.html(), delivery.text());
            this.mongo.updateFirst(Query.query(Criteria.where("_id").is(delivery.id())),
                    new Update().set("status", DeliveryDocument.SENT).set("sentAt", now).unset("lastError"), DeliveryDocument.class);
            return true;
        }
        catch (MailFailure ex) {
            boolean last = ex.isPermanent() || delivery.attempts() >= MAX_ATTEMPTS;
            log.warn("Email {} not sent (try {}): {}", delivery.id(), delivery.attempts(), ex.getMessage());
            Update update = new Update().set("lastError", truncate(ex.getMessage()));
            if (last) {
                update.set("status", DeliveryDocument.FAILED);
            }
            else {
                update.set("status", DeliveryDocument.QUEUED)
                    .set("nextAttemptAt", now.plus(BACKOFF.get(Math.min(delivery.attempts(), BACKOFF.size()) - 1)));
            }
            this.mongo.updateFirst(Query.query(Criteria.where("_id").is(delivery.id())), update, DeliveryDocument.class);
            return false;
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return (message.length() > 500) ? message.substring(0, 500) : message;
    }
}

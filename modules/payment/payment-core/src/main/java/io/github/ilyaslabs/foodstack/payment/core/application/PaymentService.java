package io.github.ilyaslabs.foodstack.payment.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.ilyaslabs.foodstack.payment.api.CardSessionView;
import io.github.ilyaslabs.foodstack.payment.api.PaymentOptionsView;
import io.github.ilyaslabs.foodstack.payment.api.PaymentView;
import io.github.ilyaslabs.foodstack.payment.api.StartCardRequest;
import io.github.ilyaslabs.foodstack.payment.api.events.PaymentFailed;
import io.github.ilyaslabs.foodstack.payment.api.events.PaymentSucceeded;
import io.github.ilyaslabs.foodstack.payment.api.events.RefundFailed;
import io.github.ilyaslabs.foodstack.payment.api.events.RefundSucceeded;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway.GatewayException;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway.WebhookEvent;
import io.github.ilyaslabs.foodstack.payment.core.persistence.MerchantAccountDocument;
import io.github.ilyaslabs.foodstack.payment.core.persistence.PaymentDocument;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.OrderingView;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Card payments on the operator's merchant account or a brand's or branch's own (TDD 5.4). One payment
 * per order. Every status change is a conditional update plus its event in one transaction, so a
 * webhook delivered twice, or a webhook racing the expiry sweep, changes the payment once. A
 * payment that succeeds after its page expired still counts: the order then refunds it.
 */
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Pages are expired this long after their deadline, so a late webhook usually wins. */
    static final Duration EXPIRY_GRACE = Duration.ofMinutes(5);

    private final MongoTemplate mongo;

    private final TransactionTemplate transaction;

    private final OutboxWriter outbox;

    private final SettingsApi settings;

    /** {@code merchantAccount} of a payment into the operator's own account. */
    public static final String OPERATOR_ACCOUNT = "operator";

    private final CardGateway gateway;

    private final Duration sessionTtl;

    private final Clock clock;

    private final MerchantAccounts accounts;

    /** @param gateway the configured card gateway, or {@code null} for none */
    public PaymentService(MongoTemplate mongo, TransactionTemplate transaction, OutboxWriter outbox, SettingsApi settings,
            CardGateway gateway, Duration sessionTtl, Clock clock) {
        this(mongo, transaction, outbox, settings, gateway, sessionTtl, clock, null);
    }

    /**
     * @param gateway the configured card gateway, or {@code null} for none
     * @param accounts store and brand merchant accounts, or {@code null} for the operator's only
     */
    public PaymentService(MongoTemplate mongo, TransactionTemplate transaction, OutboxWriter outbox, SettingsApi settings,
            CardGateway gateway, Duration sessionTtl, Clock clock, MerchantAccounts accounts) {
        this.mongo = mongo;
        this.transaction = transaction;
        this.outbox = outbox;
        this.settings = settings;
        this.gateway = gateway;
        this.sessionTtl = sessionTtl;
        this.clock = clock;
        this.accounts = accounts;
    }

    /** The gateway on a payment's merchant account. */
    private CardGateway gatewayFor(String merchantAccount) {
        if (this.accounts == null || merchantAccount == null || OPERATOR_ACCOUNT.equals(merchantAccount)) {
            return this.gateway;
        }
        return this.accounts.gateway(merchantAccount);
    }

    private static String account(String merchantAccountId) {
        return (merchantAccountId != null && !merchantAccountId.isBlank()) ? merchantAccountId : OPERATOR_ACCOUNT;
    }

    public PaymentOptionsView options() {
        OrderingView ordering = this.settings.getOrdering();
        return new PaymentOptionsView(ordering.cashEnabled(), ordering.cardEnabled() && this.gateway != null, gatewayId());
    }

    public String gatewayId() {
        return (this.gateway != null) ? this.gateway.id() : "none";
    }

    public CardSessionView startCard(StartCardRequest request) {
        if (!options().card()) {
            throw new ModuleException.BadRequest("Card payments are not available", "PAYMENT_UNAVAILABLE");
        }
        if (request.amountMinor() <= 0) {
            throw new ModuleException.BadRequest("Nothing to pay by card", "PAYMENT_AMOUNT_INVALID");
        }
        Instant now = this.clock.instant();
        PaymentDocument existing = find(request.orderId());
        if (existing == null) {
            try {
                this.mongo.insert(new PaymentDocument(request.orderId(), request.customerId(), PaymentDocument.CREATED,
                        request.amountMinor(), request.currency(), account(request.merchantAccountId()), this.gateway.id(), null,
                        null, null, request.returnUrl(),
                        request.cancelUrl(), null, 0,
                        List.of(), null, now, now));
            }
            catch (DuplicateKeyException ex) {
                existing = find(request.orderId());
            }
        }
        if (existing != null) {
            if (PaymentDocument.PENDING.equals(existing.status()) && existing.redirectUrl() != null) {
                return session(existing);
            }
            if (!PaymentDocument.CREATED.equals(existing.status())) {
                throw new ModuleException.Conflict("This order's payment is already " + existing.status().toLowerCase(),
                        "PAYMENT_CLOSED", Map.of("status", existing.status()));
            }
        }
        String merchantAccount = (existing != null) ? existing.merchantAccount() : account(request.merchantAccountId());
        CardGateway.HostedSession session;
        try {
            session = gatewayFor(merchantAccount).createSession(new CardGateway.SessionRequest(request.orderId(), request.orderNumber(),
                    request.amountMinor(), request.currency(), request.description(), request.returnUrl(),
                    request.cancelUrl(), now.plus(this.sessionTtl)));
        }
        catch (GatewayException ex) {
            log.warn("Could not open a payment page for order {}: {}", request.orderId(), ex.getMessage());
            if (!ex.isRetryable() && this.accounts != null) {
                this.accounts.markFailed(merchantAccount, ex.getMessage());
            }
            throw new ModuleException.Unavailable("The card payment service is not answering; try again or pay cash",
                    "PAYMENT_GATEWAY_UNAVAILABLE");
        }
        PaymentDocument pending = this.mongo.findAndModify(
                Query.query(Criteria.where("_id").is(request.orderId()).and("status").is(PaymentDocument.CREATED)),
                new Update().set("status", PaymentDocument.PENDING).set("sessionReference", session.sessionReference())
                    .set("redirectUrl", session.redirectUrl()).set("expiresAt", session.expiresAt()).set("updatedAt", now),
                FindAndModifyOptions.options().returnNew(true), PaymentDocument.class);
        return session((pending != null) ? pending : find(request.orderId()));
    }

    public PaymentView get(String orderId) {
        PaymentDocument p = find(orderId);
        if (p == null) {
            return null;
        }
        return new PaymentView(p.id(), PaymentDocument.CREATED.equals(p.status()) ? PaymentDocument.PENDING : p.status(),
                p.amountMinor(), p.refundedMinor(), p.currency(), p.gateway(),
                PaymentDocument.PENDING.equals(p.status()) ? p.redirectUrl() : null,
                (p.expiresAt() != null) ? p.expiresAt().getEpochSecond() : 0);
    }

    /** Who pays for the order, or {@code null} when it has no card payment. */
    public String customerOf(String orderId) {
        PaymentDocument p = find(orderId);
        return (p != null) ? p.customerId() : null;
    }

    /** A webhook from the operator's own account: verified, then applied once. */
    public void handleWebhook(String gatewayId, byte[] body, Map<String, String> headers) {
        if (this.gateway == null || !this.gateway.id().equals(gatewayId)) {
            throw new ModuleException.NotFound("No gateway " + gatewayId + " is set up", "GATEWAY_NOT_FOUND");
        }
        WebhookEvent event;
        try {
            event = this.gateway.parseWebhook(body, headers);
        }
        catch (GatewayException ex) {
            throw new ModuleException.BadRequest(ex.getMessage(), "WEBHOOK_INVALID");
        }
        apply(gatewayId, OPERATOR_ACCOUNT, event);
    }

    /**
     * A webhook from a brand's or branch's own account (TDD 5.4), sent to the owner's address. It is
     * verified with the owner's current account, else one it had before (payments keep the account
     * they were taken on), and applied only to payments taken on that account.
     */
    public void handleOwnerWebhook(String gatewayId, String scope, String ownerId, byte[] body, Map<String, String> headers) {
        if (this.gateway == null || !this.gateway.id().equals(gatewayId) || this.accounts == null) {
            throw new ModuleException.NotFound("No gateway " + gatewayId + " is set up", "GATEWAY_NOT_FOUND");
        }
        for (MerchantAccountDocument account : this.accounts.webhookAccounts(scope, ownerId)) {
            WebhookEvent event;
            try {
                event = this.accounts.gateway(account.id()).parseWebhook(body, headers);
            }
            catch (GatewayException ex) {
                continue;
            }
            apply(gatewayId, account.id(), event);
            return;
        }
        throw new ModuleException.BadRequest("Webhook signature does not match this account", "WEBHOOK_INVALID");
    }

    /** The sandbox page's outcome, signed like a webhook, for a payment on any account. */
    void handleSandbox(PaymentDocument payment, byte[] body, Map<String, String> headers) {
        WebhookEvent event;
        try {
            event = gatewayFor(payment.merchantAccount()).parseWebhook(body, headers);
        }
        catch (GatewayException ex) {
            throw new ModuleException.BadRequest(ex.getMessage(), "WEBHOOK_INVALID");
        }
        apply(payment.gateway(), payment.merchantAccount(), event);
    }

    private void apply(String gatewayId, String merchantAccount, WebhookEvent event) {
        if (event == null) {
            return;
        }
        PaymentDocument payment = find(event.orderId());
        if (payment == null || !Objects.equals(payment.sessionReference(), event.sessionReference())
                || !merchantAccount.equals(account(payment.merchantAccount()))) {
            log.warn("Ignoring a {} webhook for an unknown payment (order {})", gatewayId, event.orderId());
            return;
        }
        Instant now = this.clock.instant();
        if (WebhookEvent.SUCCEEDED.equals(event.outcome())) {
            this.transaction.executeWithoutResult((status) -> {
                PaymentDocument before = this.mongo.findAndModify(
                        Query.query(Criteria.where("_id").is(payment.id()).and("status").ne(PaymentDocument.SUCCEEDED)),
                        new Update().set("status", PaymentDocument.SUCCEEDED).set("paymentReference", event.paymentReference())
                            .set("updatedAt", now),
                        PaymentDocument.class);
                if (before != null) {
                    this.outbox.append(new PaymentSucceeded(payment.id(), payment.amountMinor(), payment.currency(),
                            payment.gateway(), event.paymentReference()));
                }
            });
        }
        else {
            String outcome = WebhookEvent.EXPIRED.equals(event.outcome()) ? PaymentDocument.EXPIRED : PaymentDocument.FAILED;
            String reason = WebhookEvent.EXPIRED.equals(event.outcome()) ? PaymentFailed.EXPIRED
                    : Objects.requireNonNullElse(event.reason(), PaymentFailed.DECLINED);
            fail(payment.id(), outcome, reason, now);
        }
    }

    /** Ends pages nobody finished; returns how many. */
    public int expireStale() {
        Instant now = this.clock.instant();
        Instant cutoff = now.minus(EXPIRY_GRACE);
        Query stale = Query.query(new Criteria().orOperator(
                Criteria.where("status").is(PaymentDocument.PENDING).and("expiresAt").lt(cutoff),
                Criteria.where("status").is(PaymentDocument.CREATED).and("createdAt").lt(cutoff.minus(this.sessionTtl))))
            .with(Sort.by("expiresAt"))
            .limit(100);
        int count = 0;
        for (PaymentDocument payment : this.mongo.find(stale, PaymentDocument.class)) {
            if (fail(payment.id(), PaymentDocument.EXPIRED, PaymentFailed.EXPIRED, now)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Gives money back for an order. Idempotent per {@code refundId}; asks for at most what is left.
     * Throws {@link GatewayException} when the gateway may succeed later, so the event is retried.
     */
    public void refund(String orderId, long amountMinor, String refundId, String reason) {
        PaymentDocument payment = find(orderId);
        if (payment == null || amountMinor <= 0 || !PaymentDocument.SUCCEEDED.equals(payment.status())
                || payment.refunds().stream().anyMatch((r) -> r.id().equals(refundId))) {
            return;
        }
        long amount = Math.min(amountMinor, payment.amountMinor() - payment.refundedMinor());
        if (amount <= 0) {
            return;
        }
        Instant now = this.clock.instant();
        PaymentDocument reserved = this.mongo.findAndModify(
                Query.query(Criteria.where("_id").is(orderId).and("status").is(PaymentDocument.SUCCEEDED)
                    .and("refunds.id").ne(refundId).and("refundedMinor").lte(payment.amountMinor() - amount)),
                new Update().push("refunds", new PaymentDocument.Refund(refundId, amount, "PENDING", reason, null, now))
                    .inc("refundedMinor", amount).set("updatedAt", now),
                FindAndModifyOptions.options().returnNew(true), PaymentDocument.class);
        if (reserved == null) {
            return;
        }
        String reference;
        try {
            reference = gatewayFor(payment.merchantAccount()).refund(payment.paymentReference(), amount, payment.currency(),
                    refundId);
        }
        catch (GatewayException ex) {
            if (ex.isRetryable()) {
                this.mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId)),
                        new Update().pull("refunds", Query.query(Criteria.where("id").is(refundId)).getQueryObject())
                            .inc("refundedMinor", -amount),
                        PaymentDocument.class);
                throw ex;
            }
            this.transaction.executeWithoutResult((status) -> {
                this.mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId).and("refunds.id").is(refundId)),
                        new Update().set("refunds.$.status", "FAILED").inc("refundedMinor", -amount), PaymentDocument.class);
                this.outbox.append(new RefundFailed(orderId, refundId, amount, ex.getMessage()));
            });
            return;
        }
        this.transaction.executeWithoutResult((status) -> {
            this.mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId).and("refunds.id").is(refundId)),
                    new Update().set("refunds.$.status", "SUCCEEDED").set("refunds.$.gatewayReference", reference),
                    PaymentDocument.class);
            this.outbox.append(new RefundSucceeded(orderId, refundId, amount, reserved.refundedMinor(), payment.currency()));
        });
    }

    private boolean fail(String orderId, String outcome, String reason, Instant now) {
        return Boolean.TRUE.equals(this.transaction.execute((status) -> {
            PaymentDocument before = this.mongo.findAndModify(
                    Query.query(Criteria.where("_id").is(orderId).and("status").in(PaymentDocument.CREATED, PaymentDocument.PENDING)),
                    new Update().set("status", outcome).set("failureReason", reason).set("updatedAt", now), PaymentDocument.class);
            if (before == null) {
                return false;
            }
            this.outbox.append(new PaymentFailed(orderId, reason));
            return true;
        }));
    }

    private PaymentDocument find(String orderId) {
        return this.mongo.findById(orderId, PaymentDocument.class);
    }

    private static CardSessionView session(PaymentDocument p) {
        return new CardSessionView(p.id(), p.redirectUrl(), (p.expiresAt() != null) ? p.expiresAt().getEpochSecond() : 0);
    }
}

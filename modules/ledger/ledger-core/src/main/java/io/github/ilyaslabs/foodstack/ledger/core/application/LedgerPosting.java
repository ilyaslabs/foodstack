package io.github.ilyaslabs.foodstack.ledger.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryCompleted;
import io.github.ilyaslabs.foodstack.ledger.core.domain.Accounts;
import io.github.ilyaslabs.foodstack.ledger.core.domain.Leg;
import io.github.ilyaslabs.foodstack.ledger.core.domain.Postings;
import io.github.ilyaslabs.foodstack.ledger.core.persistence.AccountDocument;
import io.github.ilyaslabs.foodstack.ledger.core.persistence.OrderDocument;
import io.github.ilyaslabs.foodstack.ledger.core.persistence.TransactionDocument;
import io.github.ilyaslabs.foodstack.order.api.OrderMoney;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRefunded;
import io.github.ilyaslabs.foodstack.payment.api.events.PaymentSucceeded;
import io.github.ilyaslabs.foodstack.payment.api.events.RefundSucceeded;
import io.github.ilyaslabs.foodstack.platform.core.Buckets;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Turns money events into ledger transactions (PRD FI-1). Each transaction's id comes from its
 * event, so an event delivered twice posts once; the rules themselves are in {@link Postings}.
 */
public class LedgerPosting {

    private static final Logger log = LoggerFactory.getLogger(LedgerPosting.class);

    public static final String ORDER_DONE = "ORDER_DONE";

    public static final String REFUND = "REFUND";

    public static final String PAYMENT = "PAYMENT";

    public static final String REFUND_PAID = "REFUND_PAID";

    public static final String DELIVERY = "DELIVERY";

    public static final String SETTLEMENT = "SETTLEMENT";

    private final MongoTemplate mongo;

    private final SettingsApi settings;

    private final Clock clock;

    public LedgerPosting(MongoTemplate mongo, SettingsApi settings, Clock clock) {
        this.mongo = mongo;
        this.settings = settings;
        this.clock = clock;
    }

    // ---- order events -------------------------------------------------------------------------

    /** Remembers the order's branch, for payment postings that do not carry it. */
    public void orderPlaced(OrderSummary order) {
        OrderMoney money = order.money();
        Update update = new Update().setOnInsert("storeId", order.storeId())
            .setOnInsert("brandId", (money != null) ? money.brandId() : null)
            .setOnInsert("collectedBy", (money != null) ? money.collectedBy() : OrderMoney.OPERATOR)
            .setOnInsert("done", false)
            .setOnInsert("closed", false);
        this.mongo.upsert(Query.query(Criteria.where("_id").is(order.orderId())), update, OrderDocument.class);
    }

    /** The order was delivered or collected: what the customer paid is shared out, then any refund asked before. */
    public void orderDone(OrderSummary order, Instant at) {
        OrderMoney money = order.money();
        if (money == null) {
            log.warn("Order {} has no money breakdown (sent before the ledger existed); nothing posted", order.orderId());
            return;
        }
        List<Leg> legs = Postings.orderCompleted(order.storeId(), order.paymentMethod(), money);
        post("order:" + order.orderId() + ":done", order.storeId(), ORDER_DONE, order.storeId(), money.brandId(),
                order.orderId(), legs, order.currency(), at, null);
        account(Accounts.store(order.storeId()), order.storeName(), money.brandId(), order.currency(), at);
        OrderDocument kept = this.mongo.findAndModify(Query.query(Criteria.where("_id").is(order.orderId())),
                new Update().set("done", true).setOnInsert("storeId", order.storeId()).setOnInsert("brandId", money.brandId())
                    .setOnInsert("collectedBy", money.collectedBy()),
                FindAndModifyOptions.options().upsert(true).returnNew(true), OrderDocument.class);
        if (kept != null && kept.pendingRefunds() != null) {
            for (OrderDocument.PendingRefund pending : kept.pendingRefunds()) {
                postRefund(pending.id(), order.storeId(), money.brandId(), order.orderId(), pending.amountMinor(),
                        pending.fundedBy(), money.collectedBy(), order.currency(), at, pending.reason());
            }
        }
    }

    /**
     * Support refunded part of an order. On a done order it is posted now against whoever bears it;
     * on an order still in progress it waits for the order to be done, and is dropped if the order
     * is cancelled instead (the customer simply gets the money back).
     */
    public void orderRefunded(String eventId, OrderRefunded event, Instant at) {
        OrderSummary order = event.order();
        if (event.fundedBy() == null || event.amountMinor() <= 0) {
            return;
        }
        String brandId = (order.money() != null) ? order.money().brandId() : null;
        String collectedBy = (order.money() != null) ? order.money().collectedBy() : OrderMoney.OPERATOR;
        if (order.status() == OrderStatus.DELIVERED || order.status() == OrderStatus.COLLECTED) {
            postRefund(eventId, order.storeId(), brandId, order.orderId(), event.amountMinor(), event.fundedBy(), collectedBy,
                    order.currency(), at, event.reason());
            return;
        }
        if (order.status().isFinal()) {
            return;
        }
        OrderDocument.PendingRefund pending = new OrderDocument.PendingRefund(eventId, event.amountMinor(), event.fundedBy(),
                event.reason());
        try {
            this.mongo.upsert(Query.query(Criteria.where("_id").is(order.orderId()).and("pendingRefunds.id").ne(eventId)),
                    new Update().push("pendingRefunds", pending).setOnInsert("storeId", order.storeId())
                        .setOnInsert("brandId", brandId).setOnInsert("collectedBy", collectedBy)
                        .setOnInsert("done", false).setOnInsert("closed", false),
                    OrderDocument.class);
        }
        catch (DuplicateKeyException ex) {
            // the refund is already waiting: the filter missed only because it is there
        }
    }

    /** The order was cancelled, rejected or failed: refunds waiting for it are not posted. */
    public void orderClosed(String orderId) {
        this.mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId)), new Update().set("closed", true),
                OrderDocument.class);
    }

    private void postRefund(String refundId, String storeId, String brandId, String orderId, long amountMinor,
            String fundedBy, String collectedBy, String currency, Instant at, String reason) {
        post("refund:" + refundId, storeId, REFUND, storeId, brandId, orderId,
                Postings.refundFunded(storeId, amountMinor, fundedBy, collectedBy), currency, at, reason);
    }

    // ---- payment events -----------------------------------------------------------------------

    /** Card money reached the operator's gateway. */
    public void paymentReceived(PaymentSucceeded event, Instant at) {
        OrderDocument order = order(event.orderId());
        if (order != null && OrderMoney.STORE.equals(order.collectedBy())) {
            return;
        }
        String storeId = (order != null) ? order.storeId() : null;
        post("payment:" + event.orderId(), (storeId != null) ? storeId : event.orderId(), PAYMENT, storeId,
                (order != null) ? order.brandId() : null, event.orderId(), Postings.paymentReceived(event.amountMinor()),
                event.currency(), at, event.gatewayReference());
    }

    /** Card money went back to the customer from the operator's gateway. */
    public void refundPaidOut(RefundSucceeded event, Instant at) {
        OrderDocument order = order(event.orderId());
        if (order != null && OrderMoney.STORE.equals(order.collectedBy())) {
            return;
        }
        String storeId = (order != null) ? order.storeId() : null;
        post("refund-paid:" + event.refundId(), (storeId != null) ? storeId : event.orderId(), REFUND_PAID, storeId,
                (order != null) ? order.brandId() : null, event.orderId(), Postings.refundPaidOut(event.amountMinor()),
                event.currency(), at, null);
    }

    // ---- dispatch events ----------------------------------------------------------------------

    /** An operator rider delivered: pay, tip and cash carried move onto their account. */
    public void deliveryCompleted(DeliveryCompleted event, Instant at) {
        String riderId = event.delivery().riderId();
        List<Leg> legs = Postings.deliveryCompleted(riderId, event.riderKind(), event.earningMinor(), event.tipMinor(),
                event.cashCollectedMinor());
        if (legs.isEmpty()) {
            return;
        }
        String storeId = event.delivery().storeId();
        OrderDocument order = order(event.delivery().orderId());
        post("delivery:" + event.delivery().orderId(), storeId, DELIVERY, storeId, (order != null) ? order.brandId() : null,
                event.delivery().orderId(), legs, event.currency(), at, null);
        account(Accounts.rider(riderId), event.delivery().riderName(), null, event.currency(), at);
    }

    // ---- mechanics ----------------------------------------------------------------------------

    /** Writes a transaction once; posting it again does nothing. Returns whether it was new. */
    boolean post(String id, String partition, String type, String storeId, String brandId, String orderId, List<Leg> legs,
            String currency, Instant at, String note) {
        if (legs.isEmpty()) {
            return false;
        }
        String key = (partition != null) ? partition : id;
        TransactionDocument transaction = new TransactionDocument(id, key, Buckets.of(key), type, storeId, brandId, orderId,
                legs, currency, day(at), at, note, false);
        try {
            this.mongo.insert(transaction);
            return true;
        }
        catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** Creates the party's account, and keeps its name current. */
    void account(String accountId, String name, String brandId, String currency, Instant at) {
        Update update = new Update().setOnInsert("partyType", Accounts.partyType(accountId))
            .setOnInsert("partyId", Accounts.partyId(accountId))
            .setOnInsert("currency", currency)
            .setOnInsert("createdAt", at);
        if (name != null) {
            update.set("name", name);
        }
        if (brandId != null) {
            update.set("brandId", brandId);
        }
        this.mongo.upsert(Query.query(Criteria.where("_id").is(accountId)), update, AccountDocument.class);
    }

    private OrderDocument order(String orderId) {
        return this.mongo.findById(orderId, OrderDocument.class);
    }

    /** The deployment's calendar day of an instant. */
    String day(Instant at) {
        return LocalDate.ofInstant((at != null) ? at : this.clock.instant(), zone()).toString();
    }

    /** The deployment's time zone (PRD SG-2); reports and roll-ups count days in it. */
    public ZoneId zone() {
        String timezone = this.settings.getLocale().timezone();
        return (timezone != null && !timezone.isBlank()) ? ZoneId.of(timezone) : ZoneId.of("UTC");
    }
}

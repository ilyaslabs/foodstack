package io.github.ilyaslabs.foodstack.order.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.order.api.OrderDelivery;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.api.events.OrderAccepted;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCancelled;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCollected;
import io.github.ilyaslabs.foodstack.order.api.events.OrderConfirmed;
import io.github.ilyaslabs.foodstack.order.api.events.OrderDelivered;
import io.github.ilyaslabs.foodstack.order.api.events.OrderFailed;
import io.github.ilyaslabs.foodstack.order.api.events.OrderPickedUp;
import io.github.ilyaslabs.foodstack.order.api.events.OrderPreparing;
import io.github.ilyaslabs.foodstack.order.api.events.OrderReady;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRefunded;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRejected;
import io.github.ilyaslabs.foodstack.order.core.persistence.ActiveOrderDocument;
import io.github.ilyaslabs.foodstack.order.core.persistence.OrderDocument.TimelineEntry;
import io.github.ilyaslabs.foodstack.order.core.persistence.OrderDocument;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The order state machine (PRD 3). Every step is one conditional update on the order's status and
 * revision, the matching {@code order_active} change and the step's event, in one transaction; a
 * step that lost a race is retried on the fresh order or refused with {@code ORDER_STATUS_CHANGED}.
 * After the commit a live update goes to the order's and the store's streams.
 */
public class OrderLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleService.class);

    /** Store and support actions, as offered on screens. */
    public static final String ACCEPT = "ACCEPT";

    public static final String REJECT = "REJECT";

    public static final String PREPARING = "PREPARING";

    public static final String READY = "READY";

    public static final String COLLECTED = "COLLECTED";

    public static final String PICKED_UP = "PICKED_UP";

    public static final String DELIVERED = "DELIVERED";

    public static final String CANCEL = "CANCEL";

    public static final String REFUND = "REFUND";

    public static final String PAY = "PAY";

    static final Set<OrderStatus> CUSTOMER_CANCELLABLE = EnumSet.of(OrderStatus.PAYMENT_PENDING, OrderStatus.CONFIRMED);

    static final Set<OrderStatus> STORE_CANCELLABLE = EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY);

    private static final int MAX_ATTEMPTS = 3;

    private final MongoTemplate mongo;

    private final TransactionTemplate transaction;

    private final OutboxWriter outbox;

    private final OrderLiveUpdates live;

    private final SettingsApi settings;

    private final Clock clock;

    public OrderLifecycleService(MongoTemplate mongo, TransactionTemplate transaction, OutboxWriter outbox, OrderLiveUpdates live,
            SettingsApi settings, Clock clock) {
        this.mongo = mongo;
        this.transaction = transaction;
        this.outbox = outbox;
        this.live = live;
        this.settings = settings;
        this.clock = clock;
    }

    // ---- store and support -------------------------------------------------------------------

    /** The store takes the order with a prep-time estimate (PRD OF-2). */
    public OrderDocument accept(String orderId, Actor actor, int prepMinutes) {
        return accept(orderId, actor, prepMinutes, null);
    }

    /**
     * The store takes the order with a prep-time estimate (PRD OF-2) and, for a self-delivery order,
     * optionally one of its own riders (PRD OF-8). Dispatch takes it from the event.
     */
    public OrderDocument accept(String orderId, Actor actor, int prepMinutes, String storeRiderId) {
        if (prepMinutes < 1 || prepMinutes > 180) {
            throw new ModuleException.BadRequest("Prep time must be 1 to 180 minutes", "PREP_TIME_INVALID");
        }
        String rider = (storeRiderId == null || storeRiderId.isBlank()) ? null : storeRiderId.strip();
        return step(orderId, EnumSet.of(OrderStatus.CONFIRMED), OrderStatus.ACCEPTED, actor, null, (order, now) -> {
            if (rider != null && (order.fulfilment() != Fulfilment.DELIVERY || order.deliveryMode() != DeliveryMode.SELF)) {
                throw new ModuleException.BadRequest("Only self-delivery orders take a store rider", "STORE_RIDER_NOT_ALLOWED");
            }
            Instant readyBy = now.plus(Duration.ofMinutes(prepMinutes));
            return new Change(new Update().set("prepMinutes", prepMinutes).set("readyBy", readyBy).unset("acceptBy"),
                    (after) -> new OrderAccepted(summary(after), actor.asEventActor(), prepMinutes, readyBy.getEpochSecond(),
                            delivery(after, rider)));
        });
    }

    /** The store says no; a paid order is refunded (PRD OF-2). */
    public OrderDocument reject(String orderId, Actor actor, String reason) {
        String why = requireReason(reason);
        return step(orderId, EnumSet.of(OrderStatus.CONFIRMED), OrderStatus.REJECTED, actor, why, (order, now) -> {
            long refund = refundable(order);
            return new Change(closing(why, refund),
                    (after) -> new OrderRejected(summary(after), actor.asEventActor(), why, false, refund));
        });
    }

    public OrderDocument preparing(String orderId, Actor actor) {
        return step(orderId, EnumSet.of(OrderStatus.ACCEPTED), OrderStatus.PREPARING, actor, null,
                (order, now) -> new Change(new Update(), (after) -> new OrderPreparing(summary(after), actor.asEventActor())));
    }

    public OrderDocument ready(String orderId, Actor actor) {
        return step(orderId, EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING), OrderStatus.READY, actor, null,
                (order, now) -> new Change(new Update(), (after) -> new OrderReady(summary(after), actor.asEventActor())));
    }

    /** The customer collected a pickup order. */
    public OrderDocument collected(String orderId, Actor actor) {
        return step(orderId, EnumSet.of(OrderStatus.READY), OrderStatus.COLLECTED, actor, null, (order, now) -> {
            requireFulfilment(order, Fulfilment.PICKUP);
            return new Change(new Update(), (after) -> new OrderCollected(summary(after), actor.asEventActor()));
        });
    }

    /**
     * A delivery order left the store: self-delivery stores mark it themselves (PRD OF-4), riders
     * through dispatch. A rider's pickup also counts as ready when the store forgot to say so.
     */
    public OrderDocument pickedUp(String orderId, Actor actor) {
        Set<OrderStatus> from = Actor.RIDER.equals(actor.role())
                ? EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY) : EnumSet.of(OrderStatus.READY);
        return step(orderId, from, OrderStatus.PICKED_UP, actor, null, (order, now) -> {
            requireFulfilment(order, Fulfilment.DELIVERY);
            requireSelfDeliveryOrSupport(order, actor);
            return new Change(new Update(), (after) -> new OrderPickedUp(summary(after), actor.asEventActor()));
        });
    }

    public OrderDocument delivered(String orderId, Actor actor) {
        return step(orderId, EnumSet.of(OrderStatus.PICKED_UP), OrderStatus.DELIVERED, actor, null, (order, now) -> {
            requireSelfDeliveryOrSupport(order, actor);
            return new Change(new Update(), (after) -> new OrderDelivered(summary(after), actor.asEventActor()));
        });
    }

    /**
     * Cancels an order with a reason (PRD OF-5, OF-6): the customer before the store accepts, the
     * store after it accepted, support at any time before the end. A paid order is refunded in full.
     */
    public OrderDocument cancel(String orderId, Actor actor, String reason) {
        Set<OrderStatus> from = switch (actor.role()) {
            case Actor.CUSTOMER -> CUSTOMER_CANCELLABLE;
            case Actor.STORE -> STORE_CANCELLABLE;
            default -> EnumSet.of(OrderStatus.PLACED, OrderStatus.PAYMENT_PENDING, OrderStatus.CONFIRMED, OrderStatus.ACCEPTED,
                    OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.PICKED_UP);
        };
        String why = Actor.CUSTOMER.equals(actor.role()) && (reason == null || reason.isBlank()) ? "Cancelled by the customer"
                : requireReason(reason);
        return step(orderId, from, OrderStatus.CANCELLED, actor, why, (order, now) -> {
            long refund = refundable(order);
            return new Change(closing(why, refund),
                    (after) -> new OrderCancelled(summary(after), actor.asEventActor(), why, refund));
        });
    }

    /** Support gives money back, in full or in part, on a card-paid order (PRD OF-6). */
    public OrderDocument refund(String orderId, Actor actor, long amountMinor, String reason) {
        String why = requireReason(reason);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            OrderDocument order = require(orderId);
            long left = refundable(order);
            if (order.paymentMethod() != PaymentMethod.CARD || left <= 0) {
                throw new ModuleException.Conflict("Nothing paid by card is left to refund", "REFUND_NOT_POSSIBLE");
            }
            if (amountMinor <= 0 || amountMinor > left) {
                throw new ModuleException.BadRequest("Refund must be between 1 and " + left, "REFUND_AMOUNT_INVALID",
                        Map.of("maxMinor", left));
            }
            OrderDocument after = note(order, actor, "Refund requested: " + why,
                    new Update().inc("refundRequestedMinor", amountMinor),
                    (changed) -> new OrderRefunded(summary(changed), actor.asEventActor(), amountMinor, why));
            if (after != null) {
                return after;
            }
        }
        throw changed();
    }

    // ---- payment -------------------------------------------------------------------------------

    /**
     * The card payment went through: a waiting order is confirmed for the store. When the order was
     * already closed (cancelled, or the page expired first), the money is given back at once.
     *
     * @return the order when it moved to {@code CONFIRMED}, otherwise {@code null}
     */
    public OrderDocument paymentSucceeded(String orderId, long amountMinor) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            OrderDocument order = find(orderId);
            if (order == null || order.paidMinor() > 0) {
                return null;
            }
            if (order.status() == OrderStatus.PAYMENT_PENDING) {
                Instant now = this.clock.instant();
                OrderDocument after = apply(order, OrderStatus.CONFIRMED, Actor.system(), "Paid by card",
                        new Update().set("paidMinor", amountMinor).unset("paymentUrl").set("acceptBy", acceptBy(now)), now,
                        (changed) -> new OrderConfirmed(summary(changed), "payment", changed.deliveryCodeCipher()));
                if (after != null) {
                    return after;
                }
                continue;
            }
            OrderDocument after = note(order, Actor.system(), "Payment arrived after the order closed; refunding",
                    new Update().set("paidMinor", amountMinor).inc("refundRequestedMinor", amountMinor),
                    (changed) -> new OrderRefunded(summary(changed), "payment", amountMinor,
                            "Payment arrived after the order closed"));
            if (after != null) {
                return null;
            }
        }
        throw changed();
    }

    /** The card payment failed or its page expired: the order fails and its coupon use is given back. */
    public void paymentFailed(String orderId, String reason) {
        try {
            step(orderId, EnumSet.of(OrderStatus.PAYMENT_PENDING), OrderStatus.FAILED, Actor.system(),
                    "Payment " + reason.toLowerCase(java.util.Locale.ROOT), (order, now) -> new Change(
                            new Update().set("closeReason", reason).unset("paymentUrl"),
                            (after) -> new OrderFailed(summary(after), "payment", reason)));
        }
        catch (ModuleException.Conflict | ModuleException.NotFound ex) {
            log.debug("Payment failure for order {} ignored: {}", orderId, ex.getMessage());
        }
    }

    /** Money went back to the customer. */
    public void refundSucceeded(String orderId, long amountMinor) {
        OrderDocument order = find(orderId);
        if (order == null) {
            return;
        }
        recordNote(order, "Refunded " + amountMinor, new Update().inc("refundedMinor", amountMinor));
    }

    /** The gateway refused a refund: support sees it on the timeline and the amount can be asked again. */
    public void refundFailed(String orderId, long amountMinor, String reason) {
        OrderDocument order = find(orderId);
        if (order == null) {
            return;
        }
        recordNote(order, "Refund of " + amountMinor + " failed: " + reason,
                new Update().inc("refundRequestedMinor", -amountMinor));
    }

    // ---- dispatch ------------------------------------------------------------------------------

    /** A rider took the delivery (dispatch {@code RiderAssigned}): shown to the customer and the store. */
    public void riderAssigned(String orderId, String riderId, String name, String vehicle, String eventActor) {
        OrderDocument order = find(orderId);
        if (order == null || order.status().isFinal()
                || (order.rider() != null && order.rider().riderId().equals(riderId))) {
            return;
        }
        recordNote(order, "Rider " + (name != null ? name : riderId) + " is on the way to the store",
                new Update().set("rider", new OrderDocument.Rider(riderId, name, vehicle, this.clock.instant())),
                Actor.fromEvent(eventActor));
    }

    /** The rider was taken off the delivery before pickup (dispatch {@code RiderUnassigned}). */
    public void riderUnassigned(String orderId, String riderId, String reason, String eventActor) {
        OrderDocument order = find(orderId);
        if (order == null || order.status().isFinal() || order.rider() == null || !order.rider().riderId().equals(riderId)) {
            return;
        }
        recordNote(order, "Rider changed: " + reason, new Update().unset("rider"), Actor.fromEvent(eventActor));
    }

    /**
     * Support gave an operator delivery back to the store (PRD OF-8): from now on the store delivers
     * it and gets the delivery charge, so the order counts as self-delivered.
     */
    public void handedBack(String orderId, String reason, String eventActor) {
        OrderDocument order = find(orderId);
        if (order == null || order.status().isFinal() || order.handedBackAt() != null) {
            return;
        }
        recordNote(order, "Handed back to the store to deliver: " + reason,
                new Update().set("deliveryMode", DeliveryMode.SELF).set("handedBackAt", this.clock.instant()).unset("rider"),
                Actor.fromEvent(eventActor));
    }

    /** The 4-digit code for a new order (PRD DR-6), when the operator asks for one at this total. */
    public String deliveryCodeFor(Fulfilment fulfilment, long totalMinor) {
        if (fulfilment != Fulfilment.DELIVERY) {
            return null;
        }
        Long threshold = this.settings.getDelivery().proofCodeAboveMinor();
        if (threshold == null || totalMinor < threshold) {
            return null;
        }
        return String.format(java.util.Locale.ROOT, "%04d", CODES.nextInt(10_000));
    }

    private static final java.security.SecureRandom CODES = new java.security.SecureRandom();

    /** What dispatch needs from an accepted delivery order; {@code null} for pickup. */
    static OrderDelivery delivery(OrderDocument o, String storeRiderId) {
        if (o.fulfilment() != Fulfilment.DELIVERY || o.destination() == null) {
            return null;
        }
        OrderDocument.Store s = o.store();
        long charge = Math.max(0, o.totals().deliveryChargeMinor() - o.totals().deliveryDiscountMinor());
        long cash = (o.paymentMethod() == PaymentMethod.CASH) ? o.totals().totalMinor() : 0;
        // rider pay is based on the normal charge, so free and discounted deliveries pay the rider the same (ilyas, 2026-11-03)
        long payBasis = Math.max(charge, (o.normalDeliveryChargeMinor() != null) ? o.normalDeliveryChargeMinor() : 0);
        return new OrderDelivery(o.deliveryMode(), storeRiderId, new GeoPoint(s.lat(), s.lng()), s.addressLine(),
                new GeoPoint(o.destination().lat(), o.destination().lng()), o.destination().addressLine(),
                o.destination().instructions(), o.customerName(), o.contactPhone(), charge, o.totals().tipMinor(), cash,
                o.currency(), o.deliveryCodeCipher(), payBasis);
    }

    /** Rejects confirmed orders the store did not answer in time (PRD 3); returns how many. */
    public int rejectUnanswered() {
        return rejectUnanswered(null);
    }

    /** Rejects late orders of the given work buckets ({@code null} = every bucket). */
    public int rejectUnanswered(java.util.Set<Integer> buckets) {
        Instant now = this.clock.instant();
        Criteria late = Criteria.where("status").is(OrderStatus.CONFIRMED).and("acceptBy").lt(now);
        if (buckets != null) {
            // rows written before buckets existed have none; every replica may take those (the step is conditional)
            late = late.orOperator(Criteria.where("bucket").in(buckets), Criteria.where("bucket").exists(false));
        }
        List<ActiveOrderDocument> lateOrders = this.mongo.find(Query.query(late).limit(100), ActiveOrderDocument.class);
        int count = 0;
        for (ActiveOrderDocument active : lateOrders) {
            try {
                step(active.id(), EnumSet.of(OrderStatus.CONFIRMED), OrderStatus.REJECTED, Actor.system(),
                        "The store did not answer in time", (order, at) -> {
                            long refund = refundable(order);
                            return new Change(closing("NO_ANSWER", refund), (after) -> new OrderRejected(summary(after),
                                    "system", "The store did not answer in time", true, refund));
                        });
                count++;
            }
            catch (ModuleException.Conflict | ModuleException.NotFound ex) {
                // accepted or cancelled meanwhile
            }
        }
        return count;
    }

    // ---- reading -------------------------------------------------------------------------------

    public OrderDocument find(String orderId) {
        return this.mongo.findById(orderId, OrderDocument.class);
    }

    public OrderDocument require(String orderId) {
        OrderDocument order = find(orderId);
        if (order == null) {
            throw new ModuleException.NotFound("No such order", "ORDER_NOT_FOUND");
        }
        return order;
    }

    /** When a confirmed order must be accepted by. */
    Instant acceptBy(Instant confirmedAt) {
        return confirmedAt.plusSeconds(this.settings.getOrdering().acceptTimeoutSeconds());
    }

    /** What is left to give back. */
    static long refundable(OrderDocument order) {
        return Math.max(0, order.paidMinor() - order.refundRequestedMinor());
    }

    public static OrderSummary summary(OrderDocument o) {
        int items = o.lines().stream().mapToInt(OrderDocument.Line::quantity).sum();
        return new OrderSummary(o.id(), o.number(), o.customerId(), o.store().storeId(), o.store().name(),
                o.store().ownerUserIds(), o.status(), o.fulfilment(), o.paymentMethod(), items, o.totals().totalMinor(),
                o.currency(), (o.coupon() != null) ? o.coupon().promotionId() : null, o.language(), o.placedAt().getEpochSecond());
    }

    // ---- mechanics -----------------------------------------------------------------------------

    /** What a step changes besides the status, and the event it sends. */
    record Change(Update update, Function<OrderDocument, ModuleEvent> event) {
    }

    @FunctionalInterface
    interface ChangeFactory {

        /** Throws to refuse the step for this order. */
        Change create(OrderDocument order, Instant now);
    }

    private OrderDocument step(String orderId, Set<OrderStatus> from, OrderStatus to, Actor actor, String note,
            ChangeFactory factory) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            OrderDocument order = require(orderId);
            if (!from.contains(order.status())) {
                throw new ModuleException.Conflict("The order is " + order.status().name().toLowerCase(java.util.Locale.ROOT)
                        .replace('_', ' ') + " now", "ORDER_STATUS_CHANGED", Map.of("status", order.status().name()));
            }
            Instant now = this.clock.instant();
            Change change = factory.create(order, now);
            OrderDocument after = apply(order, to, actor, note, change.update(), now, change.event());
            if (after != null) {
                return after;
            }
        }
        throw changed();
    }

    /** One status change, if the order is still at the revision read; {@code null} when it is not. */
    private OrderDocument apply(OrderDocument order, OrderStatus to, Actor actor, String note, Update update, Instant now,
            Function<OrderDocument, ModuleEvent> event) {
        update.set("status", to)
            .set("updatedAt", now)
            .inc("revision", 1)
            .push("timeline", new TimelineEntry(to, now, actor.role(), actor.id(), note));
        OrderDocument after = this.transaction.execute((status) -> {
            OrderDocument changed = this.mongo.findAndModify(revisionQuery(order), update,
                    org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), OrderDocument.class);
            if (changed == null) {
                return null;
            }
            if (to.isFinal()) {
                this.mongo.remove(Query.query(Criteria.where("_id").is(order.id()).and("storeId").is(order.store().storeId())),
                        ActiveOrderDocument.class);
            }
            else {
                this.mongo.save(new ActiveOrderDocument(order.id(), order.store().storeId(), to,
                        (to == OrderStatus.CONFIRMED) ? changed.acceptBy() : null, order.placedAt()));
            }
            this.outbox.append(event.apply(changed));
            return changed;
        });
        if (after != null) {
            this.live.publish(after.id(), after.store().storeId(), after.status().name(), after.revision());
        }
        return after;
    }

    /** A timeline note without a status change, with its event; {@code null} when the order changed meanwhile. */
    private OrderDocument note(OrderDocument order, Actor actor, String note, Update update,
            Function<OrderDocument, ModuleEvent> event) {
        Instant now = this.clock.instant();
        update.set("updatedAt", now).inc("revision", 1).push("timeline", new TimelineEntry(null, now, actor.role(), actor.id(), note));
        OrderDocument after = this.transaction.execute((status) -> {
            OrderDocument changed = this.mongo.findAndModify(revisionQuery(order), update,
                    org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), OrderDocument.class);
            if (changed != null) {
                this.outbox.append(event.apply(changed));
            }
            return changed;
        });
        if (after != null) {
            this.live.publish(after.id(), after.store().storeId(), after.status().name(), after.revision());
        }
        return after;
    }

    /** A note that sends no event (refund outcomes; payment already sent its own). */
    private void recordNote(OrderDocument order, String note, Update update) {
        recordNote(order, note, update, Actor.system());
    }

    private void recordNote(OrderDocument order, String note, Update update, Actor actor) {
        Instant now = this.clock.instant();
        update.set("updatedAt", now).inc("revision", 1)
            .push("timeline", new TimelineEntry(null, now, actor.role(), actor.id(), note));
        OrderDocument after = this.mongo.findAndModify(Query.query(Criteria.where("_id").is(order.id())), update,
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), OrderDocument.class);
        if (after != null) {
            this.live.publish(after.id(), after.store().storeId(), after.status().name(), after.revision());
        }
    }

    private static Query revisionQuery(OrderDocument order) {
        return Query.query(Criteria.where("_id").is(order.id()).and("revision").is(order.revision()));
    }

    private static Update closing(String reason, long refund) {
        return new Update().set("closeReason", reason).unset("acceptBy").unset("paymentUrl").inc("refundRequestedMinor", refund);
    }

    private static void requireFulfilment(OrderDocument order, Fulfilment fulfilment) {
        if (order.fulfilment() != fulfilment) {
            throw new ModuleException.Conflict("This is a " + order.fulfilment().name().toLowerCase(java.util.Locale.ROOT)
                    + " order", "ORDER_FULFILMENT_MISMATCH");
        }
    }

    /** Operator riders report their own steps through dispatch; besides them only support moves those orders. */
    private static void requireSelfDeliveryOrSupport(OrderDocument order, Actor actor) {
        if (order.deliveryMode() == DeliveryMode.OPERATOR && !Actor.SUPPORT.equals(actor.role())
                && !Actor.RIDER.equals(actor.role())) {
            throw new ModuleException.Forbidden("The operator's rider reports this step", "OPERATOR_DELIVERY");
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ModuleException.BadRequest("Give a reason", "REASON_REQUIRED");
        }
        String trimmed = reason.strip();
        return (trimmed.length() > 300) ? trimmed.substring(0, 300) : trimmed;
    }

    private static ModuleException changed() {
        return new ModuleException.Conflict("The order changed meanwhile; try again", "ORDER_STATUS_CHANGED");
    }
}

package io.github.ilyaslabs.foodstack.order.core.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.core.persistence.ActiveOrderDocument;
import io.github.ilyaslabs.foodstack.order.core.persistence.OrderDocument;
import io.github.ilyaslabs.foodstack.order.core.persistence.OrderSummaryDocument;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Orders for screens, with access checks (TDD 9.1): a customer sees and cancels their own orders; a
 * branch's people ({@code storeIds} claim) and support ({@code order:manage}) see the branch's
 * orders and move them on; support also cancels at any time and refunds.
 */
public class OrderService {

    public static final String ORDER_MANAGE = "order:manage";

    private static final int MAX_PAGE = 50;

    private final MongoTemplate mongo;

    private final OrderLifecycleService lifecycle;

    private final PiiCipher pii;

    public OrderService(MongoTemplate mongo, OrderLifecycleService lifecycle, PiiCipher pii) {
        this.mongo = mongo;
        this.lifecycle = lifecycle;
        this.pii = pii;
    }

    // ---- reading -------------------------------------------------------------------------------

    public OrderDetails get(String orderId) {
        OrderDocument order = this.lifecycle.require(orderId);
        requireViewer(order);
        return details(order);
    }

    /** An order's summary for other modules: the platform, its customer, its branch's people or support. */
    public io.github.ilyaslabs.foodstack.order.api.OrderSummary summary(String orderId) {
        OrderDocument order = this.lifecycle.require(orderId);
        if (!io.github.ilyaslabs.foodstack.platform.security.ModuleAccess.isService()) {
            requireViewer(order);
        }
        return OrderLifecycleService.summary(order);
    }

    /** The signed-in customer's orders, newest first. */
    public OrderPage mine(String before, int limit) {
        String customerId = CurrentUser.id();
        Criteria criteria = Criteria.where("customerId").is(customerId);
        return page(OrderSummaryDocument.BY_CUSTOMER, criteria, before, limit);
    }

    /**
     * Every order of the signed-in customer, newest first, in full: their part of the data export
     * (PRD ID-7).
     */
    public List<OrderDetails> export() {
        String customerId = CurrentUser.id();
        Query mine = Query.query(Criteria.where("customerId").is(customerId))
            .with(Sort.by(Sort.Direction.DESC, "placedAt"))
            .limit(10_000);
        mine.fields().include("_id");
        List<String> ids = this.mongo.find(mine, org.bson.Document.class, OrderSummaryDocument.BY_CUSTOMER)
            .stream()
            .map((row) -> row.getString("_id"))
            .toList();
        List<OrderDetails> orders = new java.util.ArrayList<>(ids.size());
        for (int from = 0; from < ids.size(); from += 200) {
            List<String> batch = ids.subList(from, Math.min(ids.size(), from + 200));
            java.util.Map<String, OrderDocument> found = new java.util.HashMap<>();
            this.mongo.find(Query.query(Criteria.where("_id").in(batch)), OrderDocument.class)
                .forEach((order) -> found.put(order.id(), order));
            batch.stream()
                .map(found::get)
                .filter((order) -> order != null && customerId.equals(order.customerId()))
                .forEach((order) -> orders.add(details(order)));
        }
        return orders;
    }

    /** A branch's orders still in progress, oldest first (the live board, PRD OF-1). */
    public List<OrderDetails> board(String storeId) {
        CurrentUser.requireStoreAccess(storeId, ORDER_MANAGE);
        List<String> ids = this.mongo.find(Query.query(Criteria.where("storeId").is(storeId)).with(Sort.by("placedAt")).limit(200),
                ActiveOrderDocument.class).stream().map(ActiveOrderDocument::id).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return this.mongo.find(Query.query(Criteria.where("_id").in(ids)), OrderDocument.class)
            .stream()
            .filter((o) -> o.status() != OrderStatus.PLACED && o.status() != OrderStatus.PAYMENT_PENDING)
            .sorted(java.util.Comparator.comparing(OrderDocument::placedAt))
            .map(this::details)
            .toList();
    }

    /** A branch's orders, newest first. */
    public OrderPage storeHistory(String storeId, String before, int limit) {
        CurrentUser.requireStoreAccess(storeId, ORDER_MANAGE);
        return page(OrderSummaryDocument.BY_STORE, Criteria.where("storeId").is(storeId), before, limit);
    }

    /** All orders for support, newest first, optionally of one status or branch. */
    public OrderPage all(OrderStatus status, String storeId, String before, int limit) {
        requireSupport();
        Criteria criteria = new Criteria();
        if (storeId != null && !storeId.isBlank()) {
            criteria = criteria.and("storeId").is(storeId);
        }
        if (status != null) {
            criteria = criteria.and("status").is(status);
        }
        return page(OrderSummaryDocument.BY_STORE, criteria, before, limit);
    }

    /** Checks the caller may watch an order's live updates; returns its branch. */
    public String requireWatch(String orderId) {
        OrderDocument order = this.lifecycle.require(orderId);
        requireViewer(order);
        return order.store().storeId();
    }

    /** Checks the caller may watch a branch's board. */
    public void requireBoard(String storeId) {
        CurrentUser.requireStoreAccess(storeId, ORDER_MANAGE);
    }

    // ---- actions -------------------------------------------------------------------------------

    /** The customer cancels before the store accepts; store people and support as {@link #storeAction}. */
    public OrderDetails cancel(String orderId, String reason) {
        OrderDocument order = this.lifecycle.require(orderId);
        Actor actor = actor(order);
        return details(this.lifecycle.cancel(orderId, actor, reason));
    }

    /** A store step: accept, reject, preparing, ready, collected, picked up or delivered. */
    public OrderDetails storeAction(String orderId, String action, Integer prepMinutes, String reason) {
        return storeAction(orderId, action, prepMinutes, reason, null);
    }

    /**
     * A store step; when accepting a self-delivery order the store may name one of its own riders,
     * who then gets the job in the rider app (PRD DR-2).
     */
    public OrderDetails storeAction(String orderId, String action, Integer prepMinutes, String reason, String storeRiderId) {
        OrderDocument order = this.lifecycle.require(orderId);
        Actor actor = actor(order);
        if (Actor.CUSTOMER.equals(actor.role())) {
            throw new ModuleException.Forbidden("Only the store can do this", "ORDER_ACCESS_DENIED");
        }
        OrderDocument after = switch (action) {
            case OrderLifecycleService.ACCEPT -> this.lifecycle.accept(orderId, actor,
                    (prepMinutes != null) ? prepMinutes : order.prepMinutes(), storeRiderId);
            case OrderLifecycleService.REJECT -> this.lifecycle.reject(orderId, actor, reason);
            case OrderLifecycleService.PREPARING -> this.lifecycle.preparing(orderId, actor);
            case OrderLifecycleService.READY -> this.lifecycle.ready(orderId, actor);
            case OrderLifecycleService.COLLECTED -> this.lifecycle.collected(orderId, actor);
            case OrderLifecycleService.PICKED_UP -> this.lifecycle.pickedUp(orderId, actor);
            case OrderLifecycleService.DELIVERED -> this.lifecycle.delivered(orderId, actor);
            default -> throw new ModuleException.BadRequest("Unknown action " + action, "ACTION_INVALID");
        };
        return details(after);
    }

    /** Support gives money back (PRD OF-6). */
    public OrderDetails refund(String orderId, long amountMinor, String reason) {
        return refund(orderId, amountMinor, reason, null);
    }

    /** Support gives money back and picks who bears it, the store (default) or the operator. */
    public OrderDetails refund(String orderId, long amountMinor, String reason, String fundedBy) {
        requireSupport();
        return details(this.lifecycle.refund(orderId, Actor.support(CurrentUser.id()), amountMinor, reason, fundedBy));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The caller's role for this order; refuses callers who have none. */
    Actor actor(OrderDocument order) {
        String userId = CurrentUser.id();
        if (userId == null) {
            throw new ModuleException.Unauthorized("Sign in first", "UNAUTHENTICATED");
        }
        if (CurrentUser.managesStore(order.store().storeId())) {
            return Actor.store(userId);
        }
        if (CurrentUser.hasAuthority(ORDER_MANAGE)) {
            return Actor.support(userId);
        }
        if (userId.equals(order.customerId())) {
            return Actor.customer(userId);
        }
        throw new ModuleException.NotFound("No such order", "ORDER_NOT_FOUND");
    }

    private void requireViewer(OrderDocument order) {
        actor(order);
    }

    private static void requireSupport() {
        if (!CurrentUser.hasAuthority(ORDER_MANAGE)) {
            throw new ModuleException.Forbidden("Only support staff can do this", "ORDER_ACCESS_DENIED");
        }
    }

    private OrderPage page(String collection, Criteria criteria, String before, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        Query query = Query.query(criteria);
        Cursor cursor = Cursor.parse(before);
        if (cursor != null) {
            query.addCriteria(new Criteria().orOperator(Criteria.where("placedAt").lt(cursor.placedAt()),
                    Criteria.where("placedAt").is(cursor.placedAt()).and("_id").lt(cursor.id())));
        }
        query.with(Sort.by(Sort.Order.desc("placedAt"), Sort.Order.desc("_id"))).limit(size + 1);
        List<OrderSummaryDocument> rows = this.mongo.find(query, OrderSummaryDocument.class, collection);
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            OrderSummaryDocument last = rows.get(size - 1);
            next = new Cursor(last.placedAt(), last.id()).encode();
        }
        return new OrderPage(rows.stream().map(OrderService::row).toList(), next);
    }

    record Cursor(Instant placedAt, String id) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((this.placedAt.toEpochMilli() + ":" + this.id).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split(":", 2);
                return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), parts[1]);
            }
            catch (RuntimeException ex) {
                throw new ModuleException.BadRequest("The page cursor is not valid", "CURSOR_INVALID");
            }
        }
    }

    private static OrderRow row(OrderSummaryDocument s) {
        return new OrderRow(s.id(), s.number(), s.customerName(), s.storeId(), s.storeName(), s.storeSlug(), s.logoUrls(),
                s.status(), s.fulfilment(), s.paymentMethod(), s.itemNames(), s.itemCount(), s.totalMinor(), s.currency(),
                s.placedAt().getEpochSecond());
    }

    OrderDetails details(OrderDocument o) {
        Actor viewer = actor(o);
        OrderDocument.Store s = o.store();
        Long estimatedAt = null;
        if (o.readyBy() != null) {
            estimatedAt = o.readyBy().plusSeconds(60L * o.travelMinutes()).getEpochSecond();
        }
        else if (!o.status().isFinal()) {
            estimatedAt = o.placedAt().plusSeconds(60L * (o.prepMinutes() + o.travelMinutes())).getEpochSecond();
        }
        String phone = null;
        if (o.contactPhone() != null) {
            try {
                phone = this.pii.decrypt(o.contactPhone());
            }
            catch (RuntimeException ex) {
                phone = null;
            }
        }
        return new OrderDetails(o.id(), o.number(), o.status(), o.fulfilment(), o.deliveryMode(), o.paymentMethod(), o.currency(),
                o.customerId(), o.customerName(), phone,
                new OrderDetails.StoreInfo(s.storeId(), s.name(), s.slug(), s.addressLine(), s.city(), s.lat(), s.lng(), s.logoUrls(),
                        s.timezone()),
                (o.destination() != null) ? new OrderDetails.Destination(o.destination().lat(), o.destination().lng(),
                        o.destination().addressLine(), o.destination().instructions()) : null,
                o.lines().stream().map((l) -> new OrderDetails.Line(l.itemId(), l.name(), l.quantity(), l.unitPriceMinor(),
                        l.options().stream().map((p) -> new OrderDetails.Option(p.groupName(), p.name(), p.priceDeltaMinor())).toList(),
                        l.lineTotalMinor(), l.note())).toList(),
                new OrderDetails.Totals(o.totals().subtotalMinor(), o.totals().itemsDiscountMinor(), o.totals().deliveryChargeMinor(),
                        o.totals().deliveryDiscountMinor(), o.totals().serviceFeeMinor(), o.totals().taxMinor(), o.totals().tipMinor(),
                        o.totals().totalMinor()),
                (o.coupon() != null) ? new OrderDetails.Coupon(o.coupon().code(), o.coupon().title()) : null, o.prepMinutes(),
                (o.acceptBy() != null) ? o.acceptBy().getEpochSecond() : null,
                (o.readyBy() != null) ? o.readyBy().getEpochSecond() : null, estimatedAt,
                (o.status() == OrderStatus.PAYMENT_PENDING) ? o.paymentUrl() : null, o.paidMinor(), o.refundRequestedMinor(),
                o.refundedMinor(),
                o.timeline().stream().map((t) -> new OrderDetails.Step(t.status(), t.at().getEpochSecond(), t.actorRole(), t.note()))
                    .toList(),
                o.closeReason(), o.placedAt().getEpochSecond(), o.revision(), actions(o, viewer),
                (o.rider() != null) ? new OrderDetails.RiderInfo(o.rider().riderId(), o.rider().name(), o.rider().vehicle(),
                        o.rider().assignedAt().getEpochSecond()) : null,
                // only the customer sees the code, they read it out to the rider at the door
                (Actor.CUSTOMER.equals(viewer.role()) && o.deliveryCodeCipher() != null) ? this.pii.decrypt(o.deliveryCodeCipher()) : null,
                (o.handedBackAt() != null) ? o.handedBackAt().getEpochSecond() : null);
    }

    /** What the viewer may do now, so screens show only working buttons. */
    static Set<String> actions(OrderDocument o, Actor viewer) {
        Set<String> actions = new LinkedHashSet<>();
        OrderStatus status = o.status();
        boolean support = Actor.SUPPORT.equals(viewer.role());
        if (Actor.CUSTOMER.equals(viewer.role())) {
            if (OrderLifecycleService.CUSTOMER_CANCELLABLE.contains(status)) {
                actions.add(OrderLifecycleService.CANCEL);
            }
            if (status == OrderStatus.PAYMENT_PENDING && o.paymentUrl() != null) {
                actions.add(OrderLifecycleService.PAY);
            }
            return actions;
        }
        boolean operatorDelivery = o.deliveryMode() == DeliveryMode.OPERATOR;
        switch (status) {
            case CONFIRMED -> {
                actions.add(OrderLifecycleService.ACCEPT);
                actions.add(OrderLifecycleService.REJECT);
            }
            case ACCEPTED -> {
                actions.add(OrderLifecycleService.PREPARING);
                actions.add(OrderLifecycleService.READY);
            }
            case PREPARING -> actions.add(OrderLifecycleService.READY);
            case READY -> {
                if (o.fulfilment() == Fulfilment.PICKUP) {
                    actions.add(OrderLifecycleService.COLLECTED);
                }
                else if (!operatorDelivery || support) {
                    actions.add(OrderLifecycleService.PICKED_UP);
                }
            }
            case PICKED_UP -> {
                if (!operatorDelivery || support) {
                    actions.add(OrderLifecycleService.DELIVERED);
                }
            }
            default -> {
            }
        }
        if (OrderLifecycleService.STORE_CANCELLABLE.contains(status) || (support && !status.isFinal())) {
            actions.add(OrderLifecycleService.CANCEL);
        }
        if (support && o.paymentMethod() == PaymentMethod.CARD && OrderLifecycleService.refundable(o) > 0
                && status != OrderStatus.PAYMENT_PENDING) {
            actions.add(OrderLifecycleService.REFUND);
        }
        return actions;
    }
}

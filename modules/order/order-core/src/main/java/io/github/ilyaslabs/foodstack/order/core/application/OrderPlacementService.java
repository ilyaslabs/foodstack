package io.github.ilyaslabs.foodstack.order.core.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.ilyaslabs.foodstack.cart.api.CartApi;
import io.github.ilyaslabs.foodstack.cart.api.CheckoutRequest;
import io.github.ilyaslabs.foodstack.cart.api.CheckoutView;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.api.events.OrderConfirmed;
import io.github.ilyaslabs.foodstack.order.api.events.OrderPlaced;
import io.github.ilyaslabs.foodstack.order.core.persistence.ActiveOrderDocument;
import io.github.ilyaslabs.foodstack.order.core.persistence.OrderDocument;
import io.github.ilyaslabs.foodstack.order.core.persistence.OrderDocument.TimelineEntry;
import io.github.ilyaslabs.foodstack.order.core.persistence.PlacementKeyDocument;
import io.github.ilyaslabs.foodstack.payment.api.CardSessionView;
import io.github.ilyaslabs.foodstack.payment.api.MerchantView;
import io.github.ilyaslabs.foodstack.payment.api.PaymentApi;
import io.github.ilyaslabs.foodstack.payment.api.StartCardRequest;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.RateLimiter;
import io.github.ilyaslabs.foodstack.promotion.api.CouponEvaluation;
import io.github.ilyaslabs.foodstack.promotion.api.CouponRequest;
import io.github.ilyaslabs.foodstack.promotion.api.PromotionApi;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView.StoreSnapshot;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Places orders (TDD 5.3, PRD CO-6, CO-7). The cart prices the order again (items, delivery,
 * coupon, fees); if anything stops it, or the total differs from what the customer saw, nothing is
 * placed and the customer is told. Otherwise the coupon use is held, and the order, its
 * {@code Idempotency-Key} and its first events are saved in one transaction, so a double tap
 * returns the first order. A cash order is confirmed at once; a card order waits for its payment
 * page, and its cart is emptied only once paid.
 */
public class OrderPlacementService {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacementService.class);

    static final int PLACEMENTS_PER_WINDOW = 10;

    static final Duration PLACEMENT_WINDOW = Duration.ofMinutes(10);

    private final MongoTemplate mongo;

    private final TransactionTemplate transaction;

    private final OutboxWriter outbox;

    private final CartApi carts;

    private final PromotionApi promotions;

    private final PaymentApi payments;

    private final IdentityApi identity;

    private final OrderLifecycleService lifecycle;

    private final OrderLiveUpdates live;

    private final PiiCipher pii;

    private final RateLimiter rateLimiter;

    private final String webUrl;

    private final Clock clock;

    public OrderPlacementService(MongoTemplate mongo, TransactionTemplate transaction, OutboxWriter outbox, CartApi carts,
            PromotionApi promotions, PaymentApi payments, IdentityApi identity, OrderLifecycleService lifecycle,
            OrderLiveUpdates live, PiiCipher pii, RateLimiter rateLimiter, String webUrl, Clock clock) {
        this.mongo = mongo;
        this.transaction = transaction;
        this.outbox = outbox;
        this.carts = carts;
        this.promotions = promotions;
        this.payments = payments;
        this.identity = identity;
        this.lifecycle = lifecycle;
        this.live = live;
        this.pii = pii;
        this.rateLimiter = rateLimiter;
        this.webUrl = webUrl.replaceAll("/+$", "");
        this.clock = clock;
    }

    public PlacedOrder place(String customerId, String idempotencyKey, PlaceOrder request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ModuleException.BadRequest("An Idempotency-Key header is required", "IDEMPOTENCY_KEY_REQUIRED");
        }
        String keyId = customerId + ":" + idempotencyKey.strip();
        String fingerprint = fingerprint(request);
        PlacedOrder earlier = earlier(keyId, fingerprint);
        if (earlier != null) {
            return earlier;
        }
        validate(request);
        if (this.rateLimiter != null) {
            this.rateLimiter.check("order", "place", customerId, PLACEMENTS_PER_WINDOW, PLACEMENT_WINDOW);
        }

        GeoPoint destination = (request.fulfilment() == Fulfilment.DELIVERY)
                ? new GeoPoint(request.address().lat(), request.address().lng()) : null;
        CheckoutView checkout = this.carts.checkout(new CheckoutRequest(customerId, request.fulfilment(), destination,
                request.couponCode(), request.tipMinor(), request.paymentMethod()));
        if (!checkout.orderable()) {
            throw new ModuleException.Conflict("The order cannot be placed as it is", "CHECKOUT_PROBLEMS",
                    Map.of("problems", checkout.problems()));
        }
        if (request.expectedTotalMinor() != null && request.expectedTotalMinor() != checkout.totalMinor()) {
            throw new ModuleException.Conflict("The total changed; check the new total and order again", "PRICE_CHANGED",
                    Map.of("totalMinor", checkout.totalMinor()));
        }

        String orderId = Ids.newId();
        StoreSnapshot store = checkout.store();
        MerchantView merchant = (request.paymentMethod() == PaymentMethod.CARD) ? this.payments.resolveMerchant(store.storeId())
                : MerchantView.BY_OPERATOR;
        CouponEvaluation coupon = checkout.coupon();
        if (coupon != null) {
            CouponEvaluation held = this.promotions.reserve(orderId, new CouponRequest(coupon.code(), customerId, store.storeId(),
                    store.brandId(), store.city(), checkout.subtotalMinor(), checkout.deliveryChargeMinor(),
                    request.fulfilment(), request.paymentMethod(), MerchantView.OPERATOR.equals(merchant.collectedBy()),
                    checkout.currency(), store.cityId()));
            if (!held.applies() || held.totalDiscountMinor() != coupon.totalDiscountMinor()) {
                if (held.applies()) {
                    this.promotions.release(orderId);
                }
                throw new ModuleException.Conflict("The coupon can no longer be used", "PRICE_CHANGED",
                        Map.of("reason", held.applies() ? "COUPON_CHANGED" : held.reason()));
            }
        }

        Instant now = this.clock.instant();
        boolean cash = request.paymentMethod() == PaymentMethod.CASH;
        OrderDocument order = newOrder(orderId, customerId, request, checkout, merchant, now);
        OrderDocument confirmed = null;
        try {
            OrderDocument saved = this.transaction.execute((status) -> {
                this.mongo.insert(new PlacementKeyDocument(keyId, orderId, fingerprint, now));
                OrderDocument placed = order;
                if (cash) {
                    placed = withConfirmed(order, now);
                }
                this.mongo.insert(placed);
                this.mongo.insert(new ActiveOrderDocument(orderId, store.storeId(), placed.status(), placed.acceptBy(), now));
                String actor = "customer:" + customerId;
                this.outbox.append(new OrderPlaced(OrderLifecycleService.summary(placed), actor));
                if (cash) {
                    this.outbox.append(new OrderConfirmed(OrderLifecycleService.summary(placed), actor, placed.deliveryCodeCipher()));
                }
                return placed;
            });
            confirmed = saved;
        }
        catch (DuplicateKeyException ex) {
            if (coupon != null) {
                this.promotions.release(orderId);
            }
            PlacedOrder winner = earlier(keyId, fingerprint);
            if (winner != null) {
                return winner;
            }
            throw ex;
        }
        catch (RuntimeException ex) {
            if (coupon != null) {
                this.promotions.release(orderId);
            }
            throw ex;
        }
        this.live.publish(orderId, store.storeId(), confirmed.status().name(), confirmed.revision());

        if (cash) {
            clearCart(customerId, checkout.cartVersion());
            return new PlacedOrder(orderId, confirmed.number(), confirmed.status(), null);
        }
        CardSessionView session;
        try {
            session = this.payments.startCard(new StartCardRequest(orderId, confirmed.number(), customerId,
                    checkout.totalMinor(), checkout.currency(), store.brandName(), this.webUrl + "/orders/" + orderId,
                    this.webUrl + "/orders/" + orderId + "?payment=cancelled", confirmed.merchantAccountId()));
        }
        catch (ModuleException ex) {
            this.lifecycle.paymentFailed(orderId, "UNAVAILABLE");
            throw ex;
        }
        this.mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(orderId)
                    .and("status").is(OrderStatus.PAYMENT_PENDING)),
                new org.springframework.data.mongodb.core.query.Update().set("paymentUrl", session.redirectUrl()),
                OrderDocument.class);
        return new PlacedOrder(orderId, confirmed.number(), OrderStatus.PAYMENT_PENDING, session.redirectUrl());
    }

    /** Empties the cart that was ordered, unless the customer changed it since. */
    public void clearCart(String customerId, long cartVersion) {
        try {
            this.carts.clear(customerId, cartVersion);
        }
        catch (RuntimeException ex) {
            log.warn("Could not empty the cart of customer {} after ordering: {}", customerId, ex.getMessage());
        }
    }

    /** The short number people read out: the random end of the id, such as {@code 7F3A-91C2}. */
    static String number(String orderId) {
        String hex = orderId.replace("-", "");
        String tail = hex.substring(hex.length() - 8).toUpperCase(Locale.ROOT);
        return tail.substring(0, 4) + "-" + tail.substring(4);
    }

    private PlacedOrder earlier(String keyId, String fingerprint) {
        PlacementKeyDocument key = this.mongo.findById(keyId, PlacementKeyDocument.class);
        if (key == null) {
            return null;
        }
        if (!key.fingerprint().equals(fingerprint)) {
            throw new ModuleException.Conflict("This Idempotency-Key was used for another order", "IDEMPOTENCY_KEY_REUSED");
        }
        OrderDocument order = this.lifecycle.find(key.orderId());
        if (order == null) {
            return null;
        }
        return new PlacedOrder(order.id(), order.number(), order.status(),
                (order.status() == OrderStatus.PAYMENT_PENDING) ? order.paymentUrl() : null);
    }

    private OrderDocument withConfirmed(OrderDocument order, Instant now) {
        List<TimelineEntry> timeline = new ArrayList<>(order.timeline());
        timeline.add(new TimelineEntry(OrderStatus.CONFIRMED, now, Actor.SYSTEM, null, "Cash on delivery"));
        return new OrderDocument(order.id(), order.number(), order.customerId(), order.customerName(), order.contactPhone(),
                order.store(), OrderStatus.CONFIRMED, order.fulfilment(), order.deliveryMode(), order.destination(),
                order.paymentMethod(), order.currency(), order.language(), order.lines(), order.totals(), order.coupon(),
                order.commission(), order.prepMinutes(), order.travelMinutes(), this.lifecycle.acceptBy(now), null, null, 0, 0, 0,
                order.cartVersion(), List.copyOf(timeline), null, order.placedAt(), now, 1, order.deliveryCodeCipher(), null, null,
                order.normalDeliveryChargeMinor(), order.merchantAccountId());
    }

    private OrderDocument newOrder(String orderId, String customerId, PlaceOrder request, CheckoutView checkout,
            MerchantView merchant, Instant now) {
        StoreSnapshot s = checkout.store();
        String storeName = (s.branchName() == null || s.branchName().isBlank()) ? s.brandName()
                : s.brandName() + " · " + s.branchName();
        OrderDocument.Store store = new OrderDocument.Store(s.storeId(), s.brandId(), storeName, s.slug(), s.addressLine(), s.city(),
                (s.location() != null) ? s.location().lat() : 0, (s.location() != null) ? s.location().lng() : 0,
                s.logoUrls(), s.ownerUserIds(), s.timezone(), s.cityId());
        List<OrderDocument.Line> lines = checkout.lines().stream()
            .map((line) -> new OrderDocument.Line(line.priced().itemId(), line.priced().name(), line.priced().quantity(),
                    line.priced().unitPriceMinor(),
                    line.priced().options().stream()
                        .map((o) -> new OrderDocument.Option(o.groupName(), o.name(), o.priceDeltaMinor()))
                        .toList(),
                    line.priced().lineTotalMinor(), line.note()))
            .toList();
        OrderDocument.Totals totals = new OrderDocument.Totals(checkout.subtotalMinor(), checkout.itemsDiscountMinor(),
                checkout.deliveryChargeMinor(), checkout.deliveryDiscountMinor(), checkout.serviceFeeMinor(), checkout.taxMinor(),
                checkout.tipMinor(), checkout.totalMinor());
        CouponEvaluation c = checkout.coupon();
        OrderDocument.Coupon coupon = (c != null)
                ? new OrderDocument.Coupon(c.promotionId(), c.code(), c.title(), c.operatorShareMinor()) : null;
        OrderDocument.Destination destination = (request.fulfilment() == Fulfilment.DELIVERY)
                ? new OrderDocument.Destination(request.address().lat(), request.address().lng(),
                        request.address().addressLine().strip(), blankToNull(request.address().instructions()))
                : null;
        String phone = blankToNull(request.contactPhone());
        int travel = (request.fulfilment() == Fulfilment.DELIVERY) ? Math.max(0, checkout.etaMinutes() - s.prepMinutes()) : 0;
        OrderStatus status = (request.paymentMethod() == PaymentMethod.CASH) ? OrderStatus.PLACED : OrderStatus.PAYMENT_PENDING;
        List<TimelineEntry> timeline = new ArrayList<>();
        timeline.add(new TimelineEntry(OrderStatus.PLACED, now, Actor.CUSTOMER, customerId, null));
        if (status == OrderStatus.PAYMENT_PENDING) {
            timeline.add(new TimelineEntry(OrderStatus.PAYMENT_PENDING, now, Actor.SYSTEM, null, "Waiting for the card payment"));
        }
        return new OrderDocument(orderId, number(orderId), customerId, customerName(customerId),
                (phone != null) ? this.pii.encrypt(phone) : null, store, status, request.fulfilment(),
                (request.fulfilment() == Fulfilment.DELIVERY && checkout.delivery() != null) ? checkout.delivery().deliveryMode()
                        : null,
                destination, request.paymentMethod(), checkout.currency(), language(request.language()), lines, totals, coupon,
                new OrderDocument.Commission(s.commissionBasisPoints(), s.commissionFixedMinor()), s.prepMinutes(), travel, null,
                null, null, 0, 0, 0, checkout.cartVersion(), List.copyOf(timeline), null, now, now, 0,
                encrypt(this.lifecycle.deliveryCodeFor(request.fulfilment(), totals.totalMinor())), null, null,
                (request.fulfilment() == Fulfilment.DELIVERY && checkout.delivery() != null) ? checkout.delivery().chargeMinor()
                        : null,
                merchant.accountId());
    }

    private String customerName(String customerId) {
        try {
            UserView user = this.identity.getUser(customerId);
            return (user != null) ? user.displayName() : null;
        }
        catch (ModuleException ex) {
            return null;
        }
    }

    private static void validate(PlaceOrder request) {
        if (request.fulfilment() == null || request.paymentMethod() == null) {
            throw new ModuleException.BadRequest("Choose delivery or pickup and how to pay", "ORDER_INVALID");
        }
        if (request.fulfilment() == Fulfilment.DELIVERY && (request.address() == null || request.address().addressLine() == null
                || request.address().addressLine().isBlank())) {
            throw new ModuleException.BadRequest("A delivery address is required", "ADDRESS_REQUIRED");
        }
        if (request.address() != null && ((request.address().addressLine() != null && request.address().addressLine().length() > 300)
                || (request.address().instructions() != null && request.address().instructions().length() > 300))) {
            throw new ModuleException.BadRequest("The address is too long", "ORDER_INVALID");
        }
        if (request.contactPhone() != null && !request.contactPhone().isBlank()
                && !request.contactPhone().strip().matches("\\+?[0-9 ()-]{6,20}")) {
            throw new ModuleException.BadRequest("The phone number is not valid", "PHONE_INVALID");
        }
    }

    private static String language(String language) {
        return (language == null || !language.matches("[a-z]{2,3}")) ? "en" : language;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }

    static String fingerprint(PlaceOrder r) {
        String text = String.join("|", String.valueOf(r.fulfilment()), String.valueOf(r.paymentMethod()),
                String.valueOf(r.couponCode()), String.valueOf(r.tipMinor()), String.valueOf(r.expectedTotalMinor()),
                (r.address() != null) ? r.address().lat() + "," + r.address().lng() + "," + r.address().addressLine() : "-");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The delivery code is kept and sent in events encrypted (TDD 9); only the customer's view decrypts it. */
    private String encrypt(String code) {
        return (code != null) ? this.pii.encrypt(code) : null;
    }
}

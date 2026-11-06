package io.github.ilyaslabs.foodstack.cart.core.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.cart.api.CheckoutRequest;
import io.github.ilyaslabs.foodstack.cart.api.CheckoutView;
import io.github.ilyaslabs.foodstack.cart.core.persistence.CartDocument;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryQuoteView;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.geo.api.QuoteRequest;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.promotion.api.CouponEvaluation;
import io.github.ilyaslabs.foodstack.promotion.api.CouponRequest;
import io.github.ilyaslabs.foodstack.promotion.api.PromotionApi;
import io.github.ilyaslabs.foodstack.settings.api.OrderingView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView;

/**
 * Prices a cart for checkout (PRD CO-2 to CO-6): current menu prices from the store module, the
 * delivery charge from geo (store zones for self-delivery, operator pricing otherwise, CO-3), the
 * coupon from promotion, then the operator's service fee, tax and the tip. The order module calls
 * this again at placement, so what the customer saw is exactly what is charged, or the problems say
 * what changed. Totals: subtotal − food discount + delivery − delivery discount + service fee + tax + tip.
 */
public class CheckoutService {

    /** Largest tip accepted, in minor units. */
    static final long MAX_TIP_MINOR = 100_000_000L;

    private final CartService carts;

    private final GeoApi geo;

    private final PromotionApi promotions;

    private final SettingsApi settings;

    public CheckoutService(CartService carts, GeoApi geo, PromotionApi promotions, SettingsApi settings) {
        this.carts = carts;
        this.geo = geo;
        this.promotions = promotions;
        this.settings = settings;
    }

    public CheckoutView checkout(CheckoutRequest request) {
        OrderingView ordering = this.settings.getOrdering();
        List<PaymentMethod> methods = new ArrayList<>();
        if (ordering.cashEnabled()) {
            methods.add(PaymentMethod.CASH);
        }
        if (ordering.cardEnabled()) {
            methods.add(PaymentMethod.CARD);
        }
        Fulfilment fulfilment = (request.fulfilment() != null) ? request.fulfilment() : Fulfilment.DELIVERY;
        CartDocument cart = this.carts.find(request.customerId());
        if (cart == null || cart.lines() == null || cart.lines().isEmpty()) {
            return new CheckoutView(request.customerId(), (cart != null) ? cart.versionOrZero() : 0, null, null, fulfilment,
                    List.of(), 0, null, null, 0, 0, 0, 0, 0, 0, 0, 0, List.copyOf(methods), ordering.tipsEnabled(),
                    List.of(new CheckoutView.Problem(CheckoutView.CART_EMPTY, Map.of())));
        }
        OrderValidationView priced = this.carts.price(cart);
        OrderValidationView.StoreSnapshot store = priced.store();
        long subtotal = priced.subtotalMinor();
        List<CheckoutView.Problem> problems = new ArrayList<>(CartService.lineProblems(cart, priced));
        if (!store.live()) {
            problems.add(problem(CheckoutView.STORE_NOT_LIVE));
        }
        else if (!store.openNow()) {
            problems.add(problem(CheckoutView.STORE_CLOSED));
        }

        CheckoutView.Delivery delivery = null;
        long deliveryCharge = 0;
        long distance = 0;
        if (fulfilment == Fulfilment.PICKUP) {
            if (!store.pickupEnabled()) {
                problems.add(problem(CheckoutView.PICKUP_UNAVAILABLE));
            }
        }
        else if (request.destination() == null) {
            problems.add(problem(CheckoutView.ADDRESS_REQUIRED));
        }
        else if (store.location() == null) {
            problems.add(problem(CheckoutView.NOT_DELIVERABLE, "reason", DeliveryQuoteView.OUT_OF_AREA));
        }
        else {
            DeliveryQuoteView quote = this.geo.quote(new QuoteRequest(store.storeId(), store.location(), store.deliveryMode(),
                    request.destination(), store.timezone(), store.cityId()));
            delivery = new CheckoutView.Delivery(quote.deliverable(), quote.reason(), quote.deliveryMode(), quote.chargeMinor(),
                    quote.minOrderMinor(), quote.freeDeliveryAboveMinor(), quote.distanceMeters());
            if (!quote.deliverable()) {
                problems.add(problem(CheckoutView.NOT_DELIVERABLE, "reason", quote.reason()));
            }
            else {
                deliveryCharge = CheckoutMath.deliveryCharge(quote.chargeMinor(), quote.freeDeliveryAboveMinor(), subtotal);
                distance = quote.distanceMeters();
                if (subtotal < quote.minOrderMinor()) {
                    problems.add(problem(CheckoutView.MIN_ORDER, "minOrderMinor", quote.minOrderMinor()));
                }
            }
        }

        CouponEvaluation coupon = null;
        long itemsDiscount = 0;
        long deliveryDiscount = 0;
        if (request.couponCode() != null && !request.couponCode().isBlank()) {
            coupon = this.promotions.evaluate(new CouponRequest(request.couponCode().strip(), request.customerId(),
                    store.storeId(), store.brandId(), store.city(), subtotal, deliveryCharge, fulfilment,
                    request.paymentMethod(), true, priced.currency(), store.cityId()));
            if (coupon.applies()) {
                itemsDiscount = Math.min(coupon.itemsDiscountMinor(), subtotal);
                deliveryDiscount = Math.min(coupon.deliveryDiscountMinor(), deliveryCharge);
            }
            else {
                Map<String, Object> details = new LinkedHashMap<>(coupon.details());
                details.put("reason", coupon.reason());
                problems.add(new CheckoutView.Problem(CheckoutView.COUPON_INVALID, Map.copyOf(details)));
            }
        }

        long serviceFee = CheckoutMath.serviceFee(ordering.serviceFeeMinor(), ordering.serviceFeeBasisPoints(), subtotal);
        long tax = CheckoutMath.tax(subtotal, itemsDiscount, ordering.taxBasisPoints());
        long tip = 0;
        if (request.tipMinor() < 0 || request.tipMinor() > MAX_TIP_MINOR
                || (request.tipMinor() > 0 && !ordering.tipsEnabled())) {
            problems.add(problem(CheckoutView.TIP_INVALID));
        }
        else {
            tip = request.tipMinor();
        }
        if (request.paymentMethod() != null && !methods.contains(request.paymentMethod())) {
            problems.add(problem(CheckoutView.PAYMENT_METHOD_UNAVAILABLE, "paymentMethod", request.paymentMethod().name()));
        }
        long total = subtotal - itemsDiscount + deliveryCharge - deliveryDiscount + serviceFee + tax + tip;
        int eta = CheckoutMath.etaMinutes(store.prepMinutes(), distance);
        return new CheckoutView(request.customerId(), cart.versionOrZero(), store, priced.currency(), fulfilment,
                CartService.lines(cart, priced), subtotal, delivery, coupon, itemsDiscount, deliveryDiscount, deliveryCharge,
                serviceFee, tax, tip, total, eta, List.copyOf(methods), ordering.tipsEnabled(), List.copyOf(problems));
    }

    private static CheckoutView.Problem problem(String code) {
        return new CheckoutView.Problem(code, Map.of());
    }

    private static CheckoutView.Problem problem(String code, String key, Object value) {
        return new CheckoutView.Problem(code, Map.of(key, value));
    }
}

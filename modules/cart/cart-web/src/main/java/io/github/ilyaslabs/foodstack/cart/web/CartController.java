package io.github.ilyaslabs.foodstack.cart.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.cart.api.CheckoutRequest;
import io.github.ilyaslabs.foodstack.cart.api.CheckoutView;
import io.github.ilyaslabs.foodstack.cart.core.application.AddLine;
import io.github.ilyaslabs.foodstack.cart.core.application.CartDetails;
import io.github.ilyaslabs.foodstack.cart.core.application.CartService;
import io.github.ilyaslabs.foodstack.cart.core.application.CheckoutService;
import io.github.ilyaslabs.foodstack.cart.web.dto.AddLineRequest;
import io.github.ilyaslabs.foodstack.cart.web.dto.CartResponse;
import io.github.ilyaslabs.foodstack.cart.web.dto.CheckoutQuoteRequest;
import io.github.ilyaslabs.foodstack.cart.web.dto.QuantityRequest;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.security.RateLimiter;
import jakarta.validation.Valid;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's cart (PRD 4.4). Every answer carries current prices from the store
 * module; {@code POST checkout} adds delivery, coupon, fees, tax and tip exactly as placing the
 * order will.
 */
@RestController
@RequestMapping("/api/v1/carts/me")
class CartController {

    static final int COUPON_CHECKS_PER_WINDOW = 30;

    static final java.time.Duration COUPON_WINDOW = java.time.Duration.ofMinutes(10);

    private final CartService carts;

    private final CheckoutService checkout;

    private final RateLimiter rateLimiter;

    CartController(CartService carts, CheckoutService checkout, ObjectProvider<RateLimiter> rateLimiter) {
        this.carts = carts;
        this.checkout = checkout;
        this.rateLimiter = rateLimiter.getIfAvailable();
    }

    @GetMapping
    @Flow(value = "cart-get", label = "Show cart", description = "Prices the cart's lines with current menu prices")
    @EVL.Endpoint(method = "GET", path = "/api/v1/carts/me", caller = "Customer web")
    CartResponse get() {
        return toResponse(this.carts.get(CurrentUser.id()));
    }

    @PostMapping("/lines")
    @Flow(value = "cart-add-line", label = "Add item to cart", description = "Checks the item against the branch menu, then adds or merges the line")
    @EVL.Endpoint(method = "POST", path = "/api/v1/carts/me/lines", caller = "Customer web")
    CartResponse addLine(@Valid @RequestBody AddLineRequest request) {
        return toResponse(this.carts.addLine(CurrentUser.id(), new AddLine(request.storeId(), request.itemId(),
                request.quantity(), request.optionIds(), request.note(), Boolean.TRUE.equals(request.replaceOtherStore()))));
    }

    @PatchMapping("/lines/{lineId}")
    @Flow(value = "cart-set-quantity", label = "Change line quantity", description = "0 removes the line")
    @EVL.Endpoint(method = "PATCH", path = "/api/v1/carts/me/lines/{lineId}", caller = "Customer web")
    CartResponse setQuantity(@PathVariable String lineId, @RequestBody QuantityRequest request) {
        return toResponse(this.carts.setQuantity(CurrentUser.id(), lineId, request.quantity()));
    }

    @DeleteMapping("/lines/{lineId}")
    @Flow(value = "cart-remove-line", label = "Remove cart line")
    @EVL.Endpoint(method = "DELETE", path = "/api/v1/carts/me/lines/{lineId}", caller = "Customer web")
    CartResponse removeLine(@PathVariable String lineId) {
        return toResponse(this.carts.setQuantity(CurrentUser.id(), lineId, 0));
    }

    @DeleteMapping
    @Flow(value = "cart-clear", label = "Empty cart")
    @EVL.Endpoint(method = "DELETE", path = "/api/v1/carts/me", caller = "Customer web")
    ResponseEntity<Void> clear() {
        this.carts.clear(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/checkout")
    @Flow(value = "cart-checkout", label = "Checkout quote", description = "Adds delivery, coupon, fees, tax and tip exactly as placing the order will")
    @EVL.Endpoint(method = "POST", path = "/api/v1/carts/me/checkout", caller = "Customer web")
    CheckoutView quote(@RequestBody CheckoutQuoteRequest request) {
        GeoPoint destination = (request.lat() != null && request.lng() != null) ? new GeoPoint(request.lat(), request.lng())
                : null;
        if (this.rateLimiter != null && request.couponCode() != null && !request.couponCode().isBlank()) {
            // coupon checks per customer, so codes cannot be guessed by trying many (TDD 8.1)
            // @flow cart-checkout>coupon-limit: Rate-limit coupon checks per customer [when coupon code given]
            this.rateLimiter.check("cart", "coupon", CurrentUser.id(), COUPON_CHECKS_PER_WINDOW, COUPON_WINDOW);
        }
        return this.checkout.checkout(new CheckoutRequest(CurrentUser.id(), request.fulfilment(), destination,
                request.couponCode(), (request.tipMinor() != null) ? request.tipMinor() : 0, request.paymentMethod()));
    }

    private static CartResponse toResponse(CartDetails c) {
        return new CartResponse(c.version(), c.store(), c.currency(), c.lines(), c.subtotalMinor(), c.itemCount(), c.problems());
    }
}

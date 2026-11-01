package io.github.ilyaslabs.foodstack.cart.web;

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
import jakarta.validation.Valid;

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

    private final CartService carts;

    private final CheckoutService checkout;

    CartController(CartService carts, CheckoutService checkout) {
        this.carts = carts;
        this.checkout = checkout;
    }

    @GetMapping
    CartResponse get() {
        return toResponse(this.carts.get(CurrentUser.id()));
    }

    @PostMapping("/lines")
    CartResponse addLine(@Valid @RequestBody AddLineRequest request) {
        return toResponse(this.carts.addLine(CurrentUser.id(), new AddLine(request.storeId(), request.itemId(),
                request.quantity(), request.optionIds(), request.note(), Boolean.TRUE.equals(request.replaceOtherStore()))));
    }

    @PatchMapping("/lines/{lineId}")
    CartResponse setQuantity(@PathVariable String lineId, @RequestBody QuantityRequest request) {
        return toResponse(this.carts.setQuantity(CurrentUser.id(), lineId, request.quantity()));
    }

    @DeleteMapping("/lines/{lineId}")
    CartResponse removeLine(@PathVariable String lineId) {
        return toResponse(this.carts.setQuantity(CurrentUser.id(), lineId, 0));
    }

    @DeleteMapping
    ResponseEntity<Void> clear() {
        this.carts.clear(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/checkout")
    CheckoutView quote(@RequestBody CheckoutQuoteRequest request) {
        GeoPoint destination = (request.lat() != null && request.lng() != null) ? new GeoPoint(request.lat(), request.lng())
                : null;
        return this.checkout.checkout(new CheckoutRequest(CurrentUser.id(), request.fulfilment(), destination,
                request.couponCode(), (request.tipMinor() != null) ? request.tipMinor() : 0, request.paymentMethod()));
    }

    private static CartResponse toResponse(CartDetails c) {
        return new CartResponse(c.version(), c.store(), c.currency(), c.lines(), c.subtotalMinor(), c.itemCount(), c.problems());
    }
}

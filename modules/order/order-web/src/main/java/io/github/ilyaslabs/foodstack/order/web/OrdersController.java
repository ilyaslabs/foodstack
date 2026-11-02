package io.github.ilyaslabs.foodstack.order.web;

import java.util.List;
import java.util.Locale;

import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.core.application.OrderDetails;
import io.github.ilyaslabs.foodstack.order.core.application.OrderPage;
import io.github.ilyaslabs.foodstack.order.core.application.OrderPlacementService;
import io.github.ilyaslabs.foodstack.order.core.application.OrderProjections;
import io.github.ilyaslabs.foodstack.order.core.application.OrderService;
import io.github.ilyaslabs.foodstack.order.core.application.PlaceOrder;
import io.github.ilyaslabs.foodstack.order.core.application.PlacedOrder;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Orders (PRD 4.4, 4.5): placing one, the customer's history and order page, the store's live board
 * and history, store steps, support's list, cancel and refund. Access is checked by the order service.
 */
@RestController
class OrdersController {

    private final OrderPlacementService placement;

    private final OrderService orders;

    private final OrderProjections projections;

    OrdersController(OrderPlacementService placement, OrderService orders, OrderProjections projections) {
        this.placement = placement;
        this.orders = orders;
        this.projections = projections;
    }

    /**
     * @param fulfilment {@code DELIVERY} or {@code PICKUP}
     * @param address delivery address
     * @param couponCode coupon
     * @param tipMinor tip
     * @param paymentMethod {@code CASH} or {@code CARD}
     * @param expectedTotalMinor the total shown at checkout
     * @param contactPhone phone for the store or rider
     * @param language the customer's language
     */
    record PlaceOrderRequest(Fulfilment fulfilment, AddressRequest address, String couponCode, Long tipMinor,
            PaymentMethod paymentMethod, Long expectedTotalMinor, String contactPhone, String language) {
    }

    record AddressRequest(Double lat, Double lng, String addressLine, String instructions) {
    }

    record ActionRequest(Integer prepMinutes, String reason) {
    }

    record ReasonRequest(String reason) {
    }

    record RefundRequest(Long amountMinor, String reason) {
    }

    @PostMapping("/api/v1/orders")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<PlacedOrder> place(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "Accept-Language", required = false) String acceptLanguage,
            @RequestBody PlaceOrderRequest body) {
        PlaceOrder.Address address = (body.address() != null && body.address().lat() != null && body.address().lng() != null)
                ? new PlaceOrder.Address(body.address().lat(), body.address().lng(), body.address().addressLine(),
                        body.address().instructions())
                : null;
        String language = (body.language() != null) ? body.language() : firstLanguage(acceptLanguage);
        PlacedOrder placed = this.placement.place(CurrentUser.id(), idempotencyKey,
                new PlaceOrder(body.fulfilment(), address, body.couponCode(), (body.tipMinor() != null) ? body.tipMinor() : 0,
                        body.paymentMethod(), body.expectedTotalMinor(), body.contactPhone(), language));
        return ResponseEntity.status(HttpStatus.CREATED).body(placed);
    }

    @GetMapping("/api/v1/orders/mine")
    @PreAuthorize("isAuthenticated()")
    OrderPage mine(@RequestParam(required = false) String before, @RequestParam(defaultValue = "20") int limit) {
        return this.orders.mine(before, limit);
    }

    @GetMapping("/api/v1/orders")
    @PreAuthorize("hasAuthority('order:manage')")
    OrderPage all(@RequestParam(required = false) OrderStatus status, @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String before, @RequestParam(defaultValue = "20") int limit) {
        return this.orders.all(status, storeId, before, limit);
    }

    @GetMapping("/api/v1/orders/{orderId}")
    @PreAuthorize("isAuthenticated()")
    OrderDetails get(@PathVariable String orderId) {
        return this.orders.get(orderId);
    }

    @PostMapping("/api/v1/orders/{orderId}/cancel")
    @PreAuthorize("isAuthenticated()")
    OrderDetails cancel(@PathVariable String orderId, @RequestBody(required = false) ReasonRequest body) {
        return this.orders.cancel(orderId, (body != null) ? body.reason() : null);
    }

    @PostMapping("/api/v1/orders/{orderId}/actions/{action}")
    @PreAuthorize("isAuthenticated()")
    OrderDetails action(@PathVariable String orderId, @PathVariable String action,
            @RequestBody(required = false) ActionRequest body) {
        return this.orders.storeAction(orderId, action.toUpperCase(Locale.ROOT).replace('-', '_'),
                (body != null) ? body.prepMinutes() : null, (body != null) ? body.reason() : null);
    }

    @PostMapping("/api/v1/orders/{orderId}/refund")
    @PreAuthorize("hasAuthority('order:manage')")
    OrderDetails refund(@PathVariable String orderId, @RequestBody RefundRequest body) {
        return this.orders.refund(orderId, (body.amountMinor() != null) ? body.amountMinor() : 0, body.reason());
    }

    @GetMapping("/api/v1/orders/stores/{storeId}/board")
    @PreAuthorize("isAuthenticated()")
    List<OrderDetails> board(@PathVariable String storeId) {
        return this.orders.board(storeId);
    }

    @GetMapping("/api/v1/orders/stores/{storeId}")
    @PreAuthorize("isAuthenticated()")
    OrderPage storeHistory(@PathVariable String storeId, @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "20") int limit) {
        return this.orders.storeHistory(storeId, before, limit);
    }

    @PostMapping("/api/v1/orders/projections/rebuild")
    @PreAuthorize("hasAuthority('order:manage')")
    java.util.Map<String, Long> rebuild() {
        return java.util.Map.of("orders", this.projections.rebuild());
    }

    static String firstLanguage(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return null;
        }
        String first = acceptLanguage.split(",")[0].split(";")[0].strip();
        return first.split("-")[0].toLowerCase(Locale.ROOT);
    }
}

package io.github.ilyaslabs.foodstack.order.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
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

    record ActionRequest(Integer prepMinutes, String reason, String storeRiderId) {
    }

    record ReasonRequest(String reason) {
    }

    record RefundRequest(Long amountMinor, String reason, String fundedBy) {
    }

    @PostMapping("/api/v1/orders")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "order-place", label = "Place order", description = "Re-prices the cart, holds the coupon and saves the order with its first events; cash is confirmed at once, card waits for the payment page")
    @EVL.Endpoint(method = "POST", path = "/api/v1/orders", caller = "Customer web")
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
    @Flow(value = "order-list-mine", label = "List my orders")
    @EVL.Endpoint(method = "GET", path = "/api/v1/orders/mine", caller = "Customer web")
    OrderPage mine(@RequestParam(required = false) String before, @RequestParam(defaultValue = "20") int limit) {
        return this.orders.mine(before, limit);
    }

    /** The customer's orders in full, for their data export (PRD ID-7). */
    @GetMapping("/api/v1/orders/mine/export")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "order-export-mine", label = "Export my orders", description = "Full orders for the customer's data export")
    @EVL.Endpoint(method = "GET", path = "/api/v1/orders/mine/export", caller = "Customer web")
    List<OrderDetails> export() {
        return this.orders.export();
    }

    @GetMapping("/api/v1/orders")
    @PreAuthorize("hasAuthority('order:manage')")
    @Flow(value = "order-list-all", label = "List all orders", description = "Support view, filter by status and branch")
    @EVL.Endpoint(method = "GET", path = "/api/v1/orders", caller = "Back office")
    OrderPage all(@RequestParam(required = false) OrderStatus status, @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String before, @RequestParam(defaultValue = "20") int limit) {
        return this.orders.all(status, storeId, before, limit);
    }

    @GetMapping("/api/v1/orders/{orderId}")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "order-get", label = "Show order")
    @EVL.Endpoint(method = "GET", path = "/api/v1/orders/{orderId}", caller = "Customer web")
    OrderDetails get(@PathVariable String orderId) {
        return this.orders.get(orderId);
    }

    @PostMapping("/api/v1/orders/{orderId}/cancel")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "order-cancel", label = "Cancel order", description = "Customer before accept, store after accept, support any time; paid orders are refunded")
    @EVL.Endpoint(method = "POST", path = "/api/v1/orders/{orderId}/cancel", caller = "Customer web")
    OrderDetails cancel(@PathVariable String orderId, @RequestBody(required = false) ReasonRequest body) {
        return this.orders.cancel(orderId, (body != null) ? body.reason() : null);
    }

    @PostMapping("/api/v1/orders/{orderId}/actions/{action}")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "order-store-action", label = "Store order step", description = "accept, reject, preparing, ready, collected, picked up, delivered")
    @EVL.Endpoint(method = "POST", path = "/api/v1/orders/{orderId}/actions/{action}", caller = "Back office")
    OrderDetails action(@PathVariable String orderId, @PathVariable String action,
            @RequestBody(required = false) ActionRequest body) {
        return this.orders.storeAction(orderId, action.toUpperCase(Locale.ROOT).replace('-', '_'),
                (body != null) ? body.prepMinutes() : null, (body != null) ? body.reason() : null,
                (body != null) ? body.storeRiderId() : null);
    }

    @PostMapping("/api/v1/orders/{orderId}/refund")
    @PreAuthorize("hasAuthority('order:manage')")
    @Flow(value = "order-refund", label = "Refund order", description = "Support refunds all or part of a card payment")
    @EVL.Endpoint(method = "POST", path = "/api/v1/orders/{orderId}/refund", caller = "Back office")
    OrderDetails refund(@PathVariable String orderId, @RequestBody RefundRequest body) {
        return this.orders.refund(orderId, (body.amountMinor() != null) ? body.amountMinor() : 0, body.reason(),
                body.fundedBy());
    }

    @GetMapping("/api/v1/orders/stores/{storeId}/board")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "order-board", label = "Store order board", description = "Open orders of one branch")
    @EVL.Endpoint(method = "GET", path = "/api/v1/orders/stores/{storeId}/board", caller = "Back office")
    List<OrderDetails> board(@PathVariable String storeId) {
        return this.orders.board(storeId);
    }

    @GetMapping("/api/v1/orders/stores/{storeId}")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "order-store-history", label = "Branch order history")
    @EVL.Endpoint(method = "GET", path = "/api/v1/orders/stores/{storeId}", caller = "Back office")
    OrderPage storeHistory(@PathVariable String storeId, @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "20") int limit) {
        return this.orders.storeHistory(storeId, before, limit);
    }

    @PostMapping("/api/v1/orders/projections/rebuild")
    @PreAuthorize("hasAuthority('order:manage')")
    @Flow(value = "order-rebuild-projections", label = "Rebuild order lists", description = "Rewrites the per-customer and per-branch order lists from the orders")
    @EVL.Endpoint(method = "POST", path = "/api/v1/orders/projections/rebuild", caller = "Back office")
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

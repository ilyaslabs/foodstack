package io.github.ilyaslabs.foodstack.order.core.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

import org.springframework.data.annotation.Id;

/**
 * A row of an order list: the read projections {@code order_by_customer} (shard key
 * {@code { customerId: hashed }}) and {@code order_by_store} (shard key
 * {@code { storeId: 1, placedAt: 1 }}) hold the same summary (TDD 6.3). They are rebuilt from
 * {@code order_orders} on every order event and never written by API calls.
 *
 * @param id order id
 * @param number order number
 * @param customerId customer
 * @param customerName customer's name
 * @param storeId branch
 * @param storeName branch name
 * @param storeSlug branch URL name
 * @param logoUrls branch logo
 * @param status status
 * @param fulfilment delivery or pickup
 * @param paymentMethod cash or card
 * @param itemNames the first few item names by language
 * @param itemCount number of items
 * @param totalMinor total
 * @param currency ISO 4217 code
 * @param placedAt placed at
 * @param updatedAt last change of the order
 * @param revision the order's revision
 */
public record OrderSummaryDocument(@Id String id, String number, String customerId, String customerName, String storeId,
        String storeName, String storeSlug, Map<String, String> logoUrls, OrderStatus status, Fulfilment fulfilment,
        PaymentMethod paymentMethod, List<Map<String, String>> itemNames, int itemCount, long totalMinor, String currency,
        Instant placedAt, Instant updatedAt, long revision) {

    public static final String BY_CUSTOMER = "order_by_customer";

    public static final String BY_STORE = "order_by_store";
}

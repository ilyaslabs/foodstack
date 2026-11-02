package io.github.ilyaslabs.foodstack.order.core.application;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

/**
 * A row of an order list (history, store history, support search).
 *
 * @param id order id
 * @param number order number
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
 * @param placedAt epoch seconds
 */
public record OrderRow(String id, String number, String customerName, String storeId, String storeName, String storeSlug,
        Map<String, String> logoUrls, OrderStatus status, Fulfilment fulfilment, PaymentMethod paymentMethod,
        List<Map<String, String>> itemNames, int itemCount, long totalMinor, String currency, long placedAt) {
}

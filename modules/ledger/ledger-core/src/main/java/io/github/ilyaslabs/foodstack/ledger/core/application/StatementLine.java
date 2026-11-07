package io.github.ilyaslabs.foodstack.ledger.core.application;

import java.time.Instant;
import java.util.Map;

/**
 * One transaction as it touched one account (PRD FI-1).
 *
 * @param transactionId the transaction
 * @param type what happened, such as {@code ORDER_DONE}
 * @param orderId the order, or {@code null}
 * @param day the day it counts for
 * @param at when
 * @param kinds the account's lines, by kind
 * @param amountMinor the account's lines added up
 * @param note detail, such as a refund reason or settlement reference, or {@code null}
 */
public record StatementLine(String transactionId, String type, String orderId, String day, Instant at, Map<String, Long> kinds,
        long amountMinor, String note) {
}

package io.github.ilyaslabs.foodstack.ledger.core.application;

import java.util.Map;

/**
 * What the operator earned and spent in a period (PRD FI-1), from the roll-ups.
 *
 * @param from first day
 * @param to last day
 * @param currency ISO 4217 code
 * @param accounts each operator account's movement in the period, such as {@code operator:commission}
 * @param netMinor all of them added up: the operator's own earnings for the period
 * @param heldMinor money on its way at the end of the period, by clearing account
 */
public record OperatorSummary(String from, String to, String currency, Map<String, Long> accounts, long netMinor,
        Map<String, Long> heldMinor) {
}

package io.github.ilyaslabs.foodstack.ledger.core.domain;

/**
 * One line of a ledger transaction. Positive credits the account (the operator owes it more),
 * negative debits it.
 *
 * @param accountId the account
 * @param kind what it is for, one of {@link Kinds}
 * @param amountMinor signed amount in minor units
 */
public record Leg(String accountId, String kind, long amountMinor) {
}

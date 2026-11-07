package io.github.ilyaslabs.foodstack.ledger.api;

/**
 * A branch's balance with the operator (PRD FI-7). Positive: the operator owes the branch (orders the
 * operator collected). Negative: the branch owes the operator (commission and fees on orders and cash
 * the branch collected itself).
 *
 * @param storeId the branch
 * @param balanceMinor what the operator owes the branch, negative when the branch owes the operator
 * @param currency ISO 4217 code, or {@code null} when nothing was posted yet
 */
public record StoreBalance(String storeId, long balanceMinor, String currency) {

    /** What the branch owes the operator, 0 when it owes nothing. */
    public long owedToOperatorMinor() {
        return Math.max(0, -this.balanceMinor);
    }
}

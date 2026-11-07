package io.github.ilyaslabs.foodstack.payment.api;

/**
 * Whose merchant account collects a card payment (TDD 5.4).
 *
 * @param accountId the branch's or brand's merchant account, or {@code null} for the operator's
 * @param collectedBy {@link #OPERATOR} or {@link #STORE}
 */
public record MerchantView(String accountId, String collectedBy) {

    public static final String OPERATOR = "OPERATOR";

    public static final String STORE = "STORE";

    /** The operator collects. */
    public static final MerchantView BY_OPERATOR = new MerchantView(null, OPERATOR);
}

package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Instant;

/**
 * Who collects a branch's card payments (PRD DP-1, DP-2, DP-7): the operator, or the branch into its
 * own or its brand's merchant account. Only finance changes it, with a reason.
 *
 * @param mode {@link #OPERATOR} or {@link #DIRECT}
 * @param creditLimitMinor what the branch may owe the operator before direct collection is suspended,
 *        or {@code null} for the operator's default
 * @param reason why it was last changed, or {@code null}
 * @param changedBy who last changed it, or {@code null}
 * @param changedAt when, or {@code null}
 */
public record PaymentCollection(String mode, Long creditLimitMinor, String reason, String changedBy, Instant changedAt) {

    public static final String OPERATOR = "OPERATOR";

    public static final String DIRECT = "DIRECT";

    /** The operator collects; the starting point of every branch. */
    public static final PaymentCollection BY_OPERATOR = new PaymentCollection(OPERATOR, null, null, null, null);
}

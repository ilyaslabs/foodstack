package io.github.ilyaslabs.foodstack.ledger.core.application;

import java.util.Map;

/**
 * One branch or rider on a payout report (PRD FI-3, FI-7).
 *
 * @param accountId the ledger account
 * @param partyType {@code STORE} or {@code RIDER}
 * @param partyId the branch or rider
 * @param name its name
 * @param brandId the branch's brand, or {@code null}
 * @param openingMinor balance before the period
 * @param kinds what moved in the period, by kind (see {@code Kinds})
 * @param closingMinor balance at the end of the period
 * @param settledAfterMinor settlements made after the period, already counted against it
 * @param dueMinor what is left to settle for the period: positive, the operator pays; negative, the party pays
 * @param direction {@code OPERATOR_PAYS}, {@code PARTY_PAYS} or {@code SETTLED}
 */
public record PayoutRow(String accountId, String partyType, String partyId, String name, String brandId, long openingMinor,
        Map<String, Long> kinds, long closingMinor, long settledAfterMinor, long dueMinor, String direction) {

    public static final String OPERATOR_PAYS = "OPERATOR_PAYS";

    public static final String PARTY_PAYS = "PARTY_PAYS";

    public static final String SETTLED = "SETTLED";

    static String direction(long dueMinor) {
        return (dueMinor > 0) ? OPERATOR_PAYS : (dueMinor < 0) ? PARTY_PAYS : SETTLED;
    }
}

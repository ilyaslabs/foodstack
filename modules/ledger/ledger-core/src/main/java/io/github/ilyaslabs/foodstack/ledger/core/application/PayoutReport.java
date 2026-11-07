package io.github.ilyaslabs.foodstack.ledger.core.application;

import java.util.List;
import java.util.Map;

/**
 * Branch or rider balances for a period, netted (PRD FI-3, FI-7), and their totals: across every
 * branch for finance, or across a brand's branches for its owner (the brand roll-up, PRD BB-8).
 *
 * @param partyType {@code STORE} or {@code RIDER}
 * @param from first day, {@code yyyy-MM-dd}
 * @param to last day, {@code yyyy-MM-dd}
 * @param brandId the brand the report is limited to, or {@code null}
 * @param currency ISO 4217 code
 * @param rows one per branch or rider with anything on it
 * @param totalKinds the rows' movements added up, by kind
 * @param totalOpeningMinor the rows' opening balances added up
 * @param totalClosingMinor the rows' closing balances added up
 * @param totalDueMinor the rows' amounts due added up (net: positive, the operator pays out overall)
 * @param operatorPaysMinor what the operator pays out, adding up only the rows where it pays
 * @param partiesPayMinor what branches or riders pay the operator, adding up only the rows where they pay
 */
public record PayoutReport(String partyType, String from, String to, String brandId, String currency, List<PayoutRow> rows,
        Map<String, Long> totalKinds, long totalOpeningMinor, long totalClosingMinor, long totalDueMinor,
        long operatorPaysMinor, long partiesPayMinor) {
}

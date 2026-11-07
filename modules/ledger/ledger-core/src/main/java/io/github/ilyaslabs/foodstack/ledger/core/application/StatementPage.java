package io.github.ilyaslabs.foodstack.ledger.core.application;

import java.util.List;

/**
 * A page of an account's statement, newest first.
 *
 * @param accountId the account
 * @param name the branch or rider
 * @param balanceMinor the balance now
 * @param currency ISO 4217 code
 * @param lines this page
 * @param nextBefore pass as {@code before} for the next page, or {@code null} at the end
 */
public record StatementPage(String accountId, String name, long balanceMinor, String currency, List<StatementLine> lines,
        String nextBefore) {
}

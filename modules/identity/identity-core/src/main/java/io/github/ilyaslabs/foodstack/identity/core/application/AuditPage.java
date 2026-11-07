package io.github.ilyaslabs.foodstack.identity.core.application;

import java.util.List;

/**
 * A page of the audit log.
 *
 * @param items entries, newest first
 * @param nextBefore pass as {@code before} for the next page, or {@code null} at the end
 */
public record AuditPage(List<AuditLine> items, String nextBefore) {
}

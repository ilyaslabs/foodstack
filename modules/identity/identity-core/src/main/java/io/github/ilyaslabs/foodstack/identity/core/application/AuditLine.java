package io.github.ilyaslabs.foodstack.identity.core.application;

import java.util.List;

/**
 * One audit entry for the back office (PRD AD-3, ID-8); bodies are JSON text with secrets masked.
 *
 * @param id entry id, time ordered
 * @param kind {@code WRITE}, {@code SIGN_IN} or {@code SIGN_IN_FAILED}
 * @param at epoch seconds
 * @param actorId who
 * @param actorName their name when it happened
 * @param roles their roles
 * @param area module area, such as {@code stores}
 * @param action method and route, such as {@code PUT /api/v1/stores/{storeId}}
 * @param resource the path written
 * @param status HTTP status
 * @param request what was sent
 * @param before the resource's previous write, or {@code null}
 * @param after what it became
 * @param ip client address
 * @param traceId trace, or {@code null}
 */
public record AuditLine(String id, String kind, Long at, String actorId, String actorName, List<String> roles, String area,
        String action, String resource, int status, String request, String before, String after, String ip, String traceId) {
}

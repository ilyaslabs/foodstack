package io.github.ilyaslabs.foodstack.identity.core.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One line of the audit log in {@code identity_audit_log} (PRD AD-3, ID-8): a back-office write
 * recorded by the module that served it, or a sign-in to the back office. Never changed after it is
 * written. Shard key {@code { _id: hashed }}.
 *
 * @param id entry id (the writer's, so a repeated delivery is kept once)
 * @param kind {@code WRITE}, {@code SIGN_IN} or {@code SIGN_IN_FAILED}
 * @param at when it happened
 * @param actorId the user, or {@code null} for a failed sign-in with an unknown email
 * @param actorName the user's display name when it was recorded
 * @param roles the user's roles
 * @param area the API area written, such as {@code stores}; {@code auth} for sign-ins
 * @param action the endpoint, such as {@code PUT /api/v1/stores/{storeId}}, or how the user signed in
 * @param resource the concrete path written
 * @param status the HTTP status answered
 * @param request the JSON sent, secrets masked
 * @param before the state before: the previous entry's {@code after} for the same resource
 * @param after the JSON answered: the state after the write
 * @param ip the caller's address
 * @param traceId the request's trace
 */
@Document("identity_audit_log")
public record AuditLogDocument(@Id String id, String kind, Instant at, String actorId, String actorName, List<String> roles,
        String area, String action, String resource, int status, String request, String before, String after, String ip,
        String traceId) {

    public static final String WRITE = "WRITE";

    public static final String SIGN_IN = "SIGN_IN";

    public static final String SIGN_IN_FAILED = "SIGN_IN_FAILED";
}

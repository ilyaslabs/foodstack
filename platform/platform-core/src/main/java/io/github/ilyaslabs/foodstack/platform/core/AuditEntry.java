package io.github.ilyaslabs.foodstack.platform.core;

import java.time.Instant;
import java.util.List;

/**
 * One back-office write (PRD AD-3), recorded by the web layer of the module that served it and
 * kept by the identity module in {@code identity_audit_log}. Secrets in the bodies are masked
 * before the entry leaves the process.
 *
 * @param id unique id, so a repeated delivery is kept once
 * @param at when the write finished
 * @param actorId the user who made it
 * @param actorName the user's display name from the token, or {@code null}
 * @param roles the user's roles
 * @param area the API area that served it, the first path segment after the version, such as {@code stores}
 * @param action the endpoint, such as {@code PUT /api/v1/stores/{storeId}}
 * @param resource the concrete path written, such as {@code /api/v1/stores/s1}; the previous entry
 *        for the same resource holds the state before this one
 * @param status the HTTP status answered
 * @param request the JSON sent, masked and cut to size, or {@code null}
 * @param after the JSON answered (the state after the write), masked and cut to size, or {@code null}
 * @param ip the caller's address
 * @param traceId the trace of the request, to find it in the logs
 */
public record AuditEntry(String id, Instant at, String actorId, String actorName, List<String> roles, String area,
        String action, String resource, int status, String request, String after, String ip, String traceId) {
}

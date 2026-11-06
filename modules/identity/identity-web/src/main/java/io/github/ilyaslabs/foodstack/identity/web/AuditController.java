package io.github.ilyaslabs.foodstack.identity.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.identity.core.application.AuditLog;
import io.github.ilyaslabs.foodstack.identity.core.persistence.AuditLogDocument;
import io.github.ilyaslabs.foodstack.platform.core.EpochSeconds;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's audit log (PRD AD-3, ID-8): back-office writes with the state before and after,
 * and back-office sign-ins, newest first. Pages continue from the {@code nextBefore} the previous page
 * returned.
 */
@RestController
@RequestMapping("/api/v1/audit")
class AuditController {

    private final AuditLog log;

    AuditController(AuditLog log) {
        this.log = log;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('identity:audit:read')")
    AuditPage list(@RequestParam(required = false) String actorId, @RequestParam(required = false) String kind,
            @RequestParam(required = false) String q, @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int limit) {
        List<AuditLogDocument> found = this.log.search(actorId, kind, q, before, limit);
        String next = (found.size() == Math.clamp(limit, 1, 200)) ? found.getLast().id() : null;
        return new AuditPage(found.stream().map(AuditController::line).toList(), next);
    }

    private static AuditLine line(AuditLogDocument d) {
        return new AuditLine(d.id(), d.kind(), EpochSeconds.of(d.at()), d.actorId(), d.actorName(),
                d.roles(), d.area(), d.action(), d.resource(), d.status(), d.request(), d.before(), d.after(), d.ip(),
                d.traceId());
    }

    /**
     * A page of the log.
     *
     * @param items entries, newest first
     * @param nextBefore pass as {@code before} for the next page, or {@code null} at the end
     */
    record AuditPage(List<AuditLine> items, String nextBefore) {
    }

    /** One entry; bodies are JSON text with secrets masked. */
    record AuditLine(String id, String kind, Long at, String actorId, String actorName, List<String> roles,
            String area, String action, String resource, int status, String request, String before, String after, String ip,
            String traceId) {
    }
}

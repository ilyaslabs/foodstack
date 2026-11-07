package io.github.ilyaslabs.foodstack.identity.web;

import io.github.ilyaslabs.foodstack.identity.core.application.AuditLog;
import io.github.ilyaslabs.foodstack.identity.core.application.AuditPage;

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
        return this.log.page(actorId, kind, q, before, limit);
    }
}

package io.github.ilyaslabs.foodstack.settings.web;

import java.util.List;
import java.util.Set;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.settings.core.deadletters.DeadLetterService;
import io.github.ilyaslabs.foodstack.settings.core.deadletters.DeadLetterService.DeadLetter;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Failed events (TDD 7.1, gap G1): the list, the event itself, replay to the module that failed,
 * and dismiss. Operators with {@code settings:manage} only, since an event can hold personal data.
 */
@EVL.Component(id = "settings.dead-letter-controller", name = "Dead Letter Controller", module = "settings",
        layer = EVL.Layer.PRESENTATION,
        responsibilities = "REST API under /api/v1/settings/dead-letters, for settings:manage only")
@RestController
@RequestMapping("/api/v1/settings/dead-letters")
@PreAuthorize("hasAuthority('settings:manage')")
class DeadLetterController {

    private static final Set<String> STATUSES = Set.of("OPEN", "REPLAYED", "DISMISSED");

    private final DeadLetterService deadLetters;

    DeadLetterController(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    @EVL.Endpoint(id = "settings.list-dead-letters", name = "List Failed Events", protocol = EVL.Protocol.HTTP,
            method = "GET", path = "/api/v1/settings/dead-letters", responseRef = "settings.dead-letter-response",
            auth = "settings:manage", errors = "settings.error.status-invalid")
    @GetMapping
    ResponseEntity<List<DeadLetter>> list(@RequestParam(required = false) String status) {
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            throw new ModuleException.BadRequest("status must be OPEN, REPLAYED or DISMISSED", "STATUS_INVALID");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.deadLetters.list(status));
    }

    /** The number waiting, for the navigation badge. */
    @EVL.Endpoint(id = "settings.count-open-dead-letters", name = "Count Open Failed Events",
            protocol = EVL.Protocol.HTTP, method = "GET", path = "/api/v1/settings/dead-letters/open-count",
            auth = "settings:manage")
    @GetMapping("/open-count")
    long openCount() {
        return this.deadLetters.openCount();
    }

    /** The event as it was published (JSON, or the raw text if it could not be read). */
    @EVL.Endpoint(id = "settings.get-dead-letter-event", name = "Show Failed Event", protocol = EVL.Protocol.HTTP,
            method = "GET", path = "/api/v1/settings/dead-letters/{id}/event", auth = "settings:manage; no-store",
            errors = "settings.error.dead-letter-not-found")
    @GetMapping("/{id}/event")
    ResponseEntity<EventResponse> event(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new EventResponse(this.deadLetters.event(id)));
    }

    @EVL.Endpoint(id = "settings.replay-dead-letter", name = "Replay Failed Event", protocol = EVL.Protocol.HTTP,
            method = "POST", path = "/api/v1/settings/dead-letters/{id}/replay",
            responseRef = "settings.dead-letter-response", flow = "settings.dead-letter-replay",
            auth = "settings:manage",
            errors = { "settings.error.dead-letter-not-found", "settings.error.dead-letter-handled",
                    "settings.error.dead-letter-no-consumer", "settings.error.dead-letter-foreign" })
    @PostMapping("/{id}/replay")
    DeadLetter replay(@PathVariable String id) {
        return this.deadLetters.replay(id, CurrentUser.id());
    }

    @EVL.Endpoint(id = "settings.dismiss-dead-letter", name = "Dismiss Failed Event", protocol = EVL.Protocol.HTTP,
            method = "POST", path = "/api/v1/settings/dead-letters/{id}/dismiss",
            responseRef = "settings.dead-letter-response", auth = "settings:manage",
            errors = { "settings.error.dead-letter-not-found", "settings.error.dead-letter-handled" })
    @PostMapping("/{id}/dismiss")
    DeadLetter dismiss(@PathVariable String id) {
        return this.deadLetters.dismiss(id, CurrentUser.id());
    }

    record EventResponse(String value) {
    }
}

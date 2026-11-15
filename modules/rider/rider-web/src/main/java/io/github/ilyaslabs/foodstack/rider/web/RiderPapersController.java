package io.github.ilyaslabs.foodstack.rider.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import java.util.List;

import io.github.ilyaslabs.foodstack.rider.core.application.RiderOnboarding;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderOnboarding.DocumentInput;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderOnboarding.Papers;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderOnboarding.PayoutInput;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * An operator rider's papers (PRD DR-1): ID documents with photos and payout details, for
 * operator staff only. Photos are uploaded first through the media endpoints with purpose
 * {@code RIDER_DOCUMENT}.
 */
@RestController
class RiderPapersController {

    private final RiderOnboarding onboarding;

    RiderPapersController(RiderOnboarding onboarding) {
        this.onboarding = onboarding;
    }

    record DocumentRequest(String type, String number, String frontMediaId, String backMediaId, String expiresOn) {

        @Override
        public String toString() {
            return "DocumentRequest[type=" + this.type + "]";
        }
    }

    record PayoutRequest(String method, String accountName, String provider, String account) {

        @Override
        public String toString() {
            return "PayoutRequest[method=" + this.method + "]";
        }
    }

    @GetMapping("/api/v1/riders/papers/complete")
    @PreAuthorize("hasAuthority('rider:manage')")
    @Flow(value = "rider-papers-complete", label = "Riders with complete papers")
    @EVL.Endpoint(method = "GET", path = "/api/v1/riders/papers/complete", caller = "Back office")
    List<String> complete() {
        return this.onboarding.completeRiders();
    }

    @GetMapping("/api/v1/riders/{riderId}/papers")
    @PreAuthorize("hasAuthority('rider:manage')")
    @Flow(value = "rider-papers-get", label = "Show rider papers")
    @EVL.Endpoint(method = "GET", path = "/api/v1/riders/{riderId}/papers", caller = "Back office")
    Papers get(@PathVariable String riderId) {
        return this.onboarding.get(riderId);
    }

    @PostMapping("/api/v1/riders/{riderId}/papers/documents")
    @PreAuthorize("hasAuthority('rider:manage')")
    @Flow(value = "rider-papers-add-document", label = "Add ID document", description = "Number encrypted; photos claimed in media")
    @EVL.Endpoint(method = "POST", path = "/api/v1/riders/{riderId}/papers/documents", caller = "Back office")
    ResponseEntity<Papers> addDocument(@PathVariable String riderId, @RequestBody DocumentRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(this.onboarding.addDocument(riderId,
                new DocumentInput(body.type(), body.number(), body.frontMediaId(), body.backMediaId(), body.expiresOn())));
    }

    @PostMapping("/api/v1/riders/{riderId}/papers/documents/{documentId}/verify")
    @PreAuthorize("hasAuthority('rider:manage')")
    @Flow(value = "rider-papers-verify", label = "Verify ID document")
    @EVL.Endpoint(method = "POST", path = "/api/v1/riders/{riderId}/papers/documents/{documentId}/verify", caller = "Back office")
    Papers verify(@PathVariable String riderId, @PathVariable String documentId) {
        return this.onboarding.verifyDocument(riderId, documentId);
    }

    @DeleteMapping("/api/v1/riders/{riderId}/papers/documents/{documentId}")
    @PreAuthorize("hasAuthority('rider:manage')")
    @Flow(value = "rider-papers-remove-document", label = "Remove ID document", description = "Releases the photos in media")
    @EVL.Endpoint(method = "DELETE", path = "/api/v1/riders/{riderId}/papers/documents/{documentId}", caller = "Back office")
    Papers removeDocument(@PathVariable String riderId, @PathVariable String documentId) {
        return this.onboarding.removeDocument(riderId, documentId);
    }

    @PutMapping("/api/v1/riders/{riderId}/papers/payout")
    @PreAuthorize("hasAuthority('rider:manage')")
    @Flow(value = "rider-papers-payout", label = "Set payout account", description = "Account number encrypted")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/riders/{riderId}/papers/payout", caller = "Back office")
    Papers payout(@PathVariable String riderId, @RequestBody PayoutRequest body) {
        return this.onboarding.setPayout(riderId,
                new PayoutInput(body.method(), body.accountName(), body.provider(), body.account()));
    }
}

package io.github.ilyaslabs.foodstack.rider.web;

import java.util.List;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
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

    @Flow(value = "rider-papers-complete", label = "Riders with complete papers", description = "")
    @EVL.Endpoint(method = "GET", path = "/api/v1/riders/papers/complete", caller = "Back office (operator)")
    @GetMapping("/api/v1/riders/papers/complete")
    @PreAuthorize("hasAuthority('rider:manage')")
    List<String> complete() {
        return this.onboarding.completeRiders();
    }

    @Flow(value = "rider-papers-view", label = "A rider's papers", description = "Documents and payout details")
    @EVL.Endpoint(method = "GET", path = "/api/v1/riders/{riderId}/papers", caller = "Back office (operator)")
    @GetMapping("/api/v1/riders/{riderId}/papers")
    @PreAuthorize("hasAuthority('rider:manage')")
    Papers get(@PathVariable String riderId) {
        return this.onboarding.get(riderId);
    }

    @Flow(value = "rider-papers-add", label = "Add a rider document", description = "With front and back photos")
    @EVL.Endpoint(method = "POST", path = "/api/v1/riders/{riderId}/papers/documents", caller = "Back office (operator)")
    @PostMapping("/api/v1/riders/{riderId}/papers/documents")
    @PreAuthorize("hasAuthority('rider:manage')")
    ResponseEntity<Papers> addDocument(@PathVariable String riderId, @RequestBody DocumentRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(this.onboarding.addDocument(riderId,
                new DocumentInput(body.type(), body.number(), body.frontMediaId(), body.backMediaId(), body.expiresOn())));
    }

    @Flow(value = "rider-papers-verify", label = "Verify a rider document", description = "")
    @EVL.Endpoint(method = "POST", path = "/api/v1/riders/{riderId}/papers/documents/{documentId}/verify", caller = "Back office (operator)")
    @PostMapping("/api/v1/riders/{riderId}/papers/documents/{documentId}/verify")
    @PreAuthorize("hasAuthority('rider:manage')")
    Papers verify(@PathVariable String riderId, @PathVariable String documentId) {
        return this.onboarding.verifyDocument(riderId, documentId);
    }

    @Flow(value = "rider-papers-remove", label = "Remove a rider document", description = "")
    @EVL.Endpoint(method = "DELETE", path = "/api/v1/riders/{riderId}/papers/documents/{documentId}", caller = "Back office (operator)")
    @DeleteMapping("/api/v1/riders/{riderId}/papers/documents/{documentId}")
    @PreAuthorize("hasAuthority('rider:manage')")
    Papers removeDocument(@PathVariable String riderId, @PathVariable String documentId) {
        return this.onboarding.removeDocument(riderId, documentId);
    }

    @Flow(value = "rider-papers-payout", label = "Set payout details", description = "")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/riders/{riderId}/papers/payout", caller = "Back office (operator)")
    @PutMapping("/api/v1/riders/{riderId}/papers/payout")
    @PreAuthorize("hasAuthority('rider:manage')")
    Papers payout(@PathVariable String riderId, @RequestBody PayoutRequest body) {
        return this.onboarding.setPayout(riderId,
                new PayoutInput(body.method(), body.accountName(), body.provider(), body.account()));
    }
}

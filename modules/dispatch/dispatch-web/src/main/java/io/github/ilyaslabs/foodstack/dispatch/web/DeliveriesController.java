package io.github.ilyaslabs.foodstack.dispatch.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliveryStatus;
import io.github.ilyaslabs.foodstack.dispatch.core.application.Delivered;
import io.github.ilyaslabs.foodstack.dispatch.core.application.DispatchService;
import io.github.ilyaslabs.foodstack.dispatch.core.application.JobDetails;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delivery jobs (PRD 4.6): the rider's offers and steps, the store's choice of its own rider, and
 * support's board, reassign and hand back. Access is checked by the dispatch service.
 */
@RestController
class DeliveriesController {

    private final DispatchService dispatch;

    DeliveriesController(DispatchService dispatch) {
        this.dispatch = dispatch;
    }

    record ReasonRequest(String reason) {
    }

    record RiderRequest(String riderId, String reason) {
    }

    record DeliveredRequest(String code, String photoUrl, Long cashCollectedMinor) {

        @Override
        public String toString() {
            return "DeliveredRequest[photo=" + (this.photoUrl != null) + "]";
        }
    }

    // ---- the rider -----------------------------------------------------------------------------

    @GetMapping("/api/v1/dispatch/rider/jobs")
    @PreAuthorize("hasAuthority('rider:portal')")
    List<JobDetails> myJobs() {
        return this.dispatch.myJobs();
    }

    @PostMapping("/api/v1/dispatch/rider/offers/{orderId}/accept")
    @PreAuthorize("hasAuthority('rider:portal')")
    JobDetails accept(@PathVariable String orderId) {
        return this.dispatch.accept(orderId);
    }

    @PostMapping("/api/v1/dispatch/rider/offers/{orderId}/decline")
    @PreAuthorize("hasAuthority('rider:portal')")
    ResponseEntity<Void> decline(@PathVariable String orderId) {
        this.dispatch.decline(orderId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/deliveries/{orderId}/arrived")
    @PreAuthorize("hasAuthority('rider:portal')")
    JobDetails arrived(@PathVariable String orderId) {
        return this.dispatch.arrived(orderId);
    }

    @PostMapping("/api/v1/deliveries/{orderId}/picked-up")
    @PreAuthorize("hasAuthority('rider:portal')")
    JobDetails pickedUp(@PathVariable String orderId) {
        return this.dispatch.pickedUp(orderId);
    }

    @PostMapping("/api/v1/deliveries/{orderId}/delivered")
    @PreAuthorize("isAuthenticated()")
    JobDetails delivered(@PathVariable String orderId, @RequestBody(required = false) DeliveredRequest body) {
        Delivered proof = (body != null) ? new Delivered(body.code(), body.photoUrl(), body.cashCollectedMinor()) : null;
        return this.dispatch.delivered(orderId, proof);
    }

    @PostMapping("/api/v1/deliveries/{orderId}/unassign")
    @PreAuthorize("hasAuthority('rider:portal')")
    ResponseEntity<Void> unassign(@PathVariable String orderId, @RequestBody ReasonRequest body) {
        this.dispatch.unassign(orderId, body.reason());
        return ResponseEntity.noContent().build();
    }

    // ---- store and support ---------------------------------------------------------------------

    @GetMapping("/api/v1/deliveries/{orderId}")
    @PreAuthorize("isAuthenticated()")
    JobDetails get(@PathVariable String orderId) {
        return this.dispatch.get(orderId);
    }

    @PostMapping("/api/v1/deliveries/{orderId}/store-rider")
    @PreAuthorize("isAuthenticated()")
    JobDetails storeRider(@PathVariable String orderId, @RequestBody RiderRequest body) {
        return this.dispatch.assignStoreRider(orderId, body.riderId());
    }

    @GetMapping("/api/v1/dispatch/jobs")
    @PreAuthorize("hasAuthority('dispatch:manage')")
    List<JobDetails> open(@RequestParam(required = false) DeliveryStatus status) {
        return this.dispatch.open(status);
    }

    @PostMapping("/api/v1/dispatch/jobs/{orderId}/reassign")
    @PreAuthorize("hasAuthority('dispatch:manage')")
    JobDetails reassign(@PathVariable String orderId, @RequestBody RiderRequest body) {
        return this.dispatch.reassign(orderId, body.riderId(), body.reason());
    }

    @PostMapping("/api/v1/dispatch/jobs/{orderId}/hand-back")
    @PreAuthorize("hasAuthority('dispatch:manage')")
    JobDetails handBack(@PathVariable String orderId, @RequestBody ReasonRequest body) {
        return this.dispatch.handBack(orderId, body.reason());
    }
}

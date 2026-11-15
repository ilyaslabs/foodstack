package io.github.ilyaslabs.foodstack.dispatch.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
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
    @Flow(value = "dispatch-rider-jobs", label = "My jobs and offers")
    @EVL.Endpoint(method = "GET", path = "/api/v1/dispatch/rider/jobs", caller = "Rider app")
    List<JobDetails> myJobs() {
        return this.dispatch.myJobs();
    }

    @PostMapping("/api/v1/dispatch/rider/offers/{orderId}/accept")
    @PreAuthorize("hasAuthority('rider:portal')")
    @Flow(value = "dispatch-rider-accept", label = "Accept delivery offer")
    @EVL.Endpoint(method = "POST", path = "/api/v1/dispatch/rider/offers/{orderId}/accept", caller = "Rider app")
    JobDetails accept(@PathVariable String orderId) {
        return this.dispatch.accept(orderId);
    }

    @PostMapping("/api/v1/dispatch/rider/offers/{orderId}/decline")
    @PreAuthorize("hasAuthority('rider:portal')")
    @Flow(value = "dispatch-rider-decline", label = "Decline delivery offer")
    @EVL.Endpoint(method = "POST", path = "/api/v1/dispatch/rider/offers/{orderId}/decline", caller = "Rider app")
    ResponseEntity<Void> decline(@PathVariable String orderId) {
        this.dispatch.decline(orderId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/deliveries/{orderId}/arrived")
    @PreAuthorize("hasAuthority('rider:portal')")
    @Flow(value = "dispatch-rider-arrived", label = "Arrived at store")
    @EVL.Endpoint(method = "POST", path = "/api/v1/deliveries/{orderId}/arrived", caller = "Rider app")
    JobDetails arrived(@PathVariable String orderId) {
        return this.dispatch.arrived(orderId);
    }

    @PostMapping("/api/v1/deliveries/{orderId}/picked-up")
    @PreAuthorize("hasAuthority('rider:portal')")
    @Flow(value = "dispatch-rider-picked-up", label = "Picked up the food")
    @EVL.Endpoint(method = "POST", path = "/api/v1/deliveries/{orderId}/picked-up", caller = "Rider app")
    JobDetails pickedUp(@PathVariable String orderId) {
        return this.dispatch.pickedUp(orderId);
    }

    @PostMapping("/api/v1/deliveries/{orderId}/delivered")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "dispatch-delivered", label = "Delivered", description = "Rider with code or photo proof, or support")
    @EVL.Endpoint(method = "POST", path = "/api/v1/deliveries/{orderId}/delivered", caller = "Rider app")
    JobDetails delivered(@PathVariable String orderId, @RequestBody(required = false) DeliveredRequest body) {
        Delivered proof = (body != null) ? new Delivered(body.code(), body.photoUrl(), body.cashCollectedMinor()) : null;
        return this.dispatch.delivered(orderId, proof);
    }

    @PostMapping("/api/v1/deliveries/{orderId}/unassign")
    @PreAuthorize("hasAuthority('rider:portal')")
    @Flow(value = "dispatch-rider-unassign", label = "Give up delivery", description = "Rider drops the job before pickup")
    @EVL.Endpoint(method = "POST", path = "/api/v1/deliveries/{orderId}/unassign", caller = "Rider app")
    ResponseEntity<Void> unassign(@PathVariable String orderId, @RequestBody ReasonRequest body) {
        this.dispatch.unassign(orderId, body.reason());
        return ResponseEntity.noContent().build();
    }

    // ---- store and support ---------------------------------------------------------------------

    @GetMapping("/api/v1/deliveries/{orderId}")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "dispatch-get", label = "Show delivery")
    @EVL.Endpoint(method = "GET", path = "/api/v1/deliveries/{orderId}", caller = "Back office")
    JobDetails get(@PathVariable String orderId) {
        return this.dispatch.get(orderId);
    }

    @PostMapping("/api/v1/deliveries/{orderId}/store-rider")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "dispatch-store-rider", label = "Assign store rider", description = "Store gives a self-delivery order to one of its riders")
    @EVL.Endpoint(method = "POST", path = "/api/v1/deliveries/{orderId}/store-rider", caller = "Back office")
    JobDetails storeRider(@PathVariable String orderId, @RequestBody RiderRequest body) {
        return this.dispatch.assignStoreRider(orderId, body.riderId());
    }

    @GetMapping("/api/v1/dispatch/jobs")
    @PreAuthorize("hasAuthority('dispatch:manage')")
    @Flow(value = "dispatch-open-jobs", label = "Open deliveries", description = "Support's dispatch board")
    @EVL.Endpoint(method = "GET", path = "/api/v1/dispatch/jobs", caller = "Back office")
    List<JobDetails> open(@RequestParam(required = false) DeliveryStatus status) {
        return this.dispatch.open(status);
    }

    @PostMapping("/api/v1/dispatch/jobs/{orderId}/reassign")
    @PreAuthorize("hasAuthority('dispatch:manage')")
    @Flow(value = "dispatch-reassign", label = "Reassign rider", description = "Support moves the delivery to another rider")
    @EVL.Endpoint(method = "POST", path = "/api/v1/dispatch/jobs/{orderId}/reassign", caller = "Back office")
    JobDetails reassign(@PathVariable String orderId, @RequestBody RiderRequest body) {
        return this.dispatch.reassign(orderId, body.riderId(), body.reason());
    }

    @PostMapping("/api/v1/dispatch/jobs/{orderId}/hand-back")
    @PreAuthorize("hasAuthority('dispatch:manage')")
    @Flow(value = "dispatch-hand-back", label = "Hand back to store", description = "Support gives an operator delivery back to the store")
    @EVL.Endpoint(method = "POST", path = "/api/v1/dispatch/jobs/{orderId}/hand-back", caller = "Back office")
    JobDetails handBack(@PathVariable String orderId, @RequestBody ReasonRequest body) {
        return this.dispatch.handBack(orderId, body.reason());
    }
}

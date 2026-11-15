package io.github.ilyaslabs.foodstack.dispatch.web;

import java.util.List;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
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

    @Flow(value = "dispatch-my-jobs", label = "My jobs and offers", description = "")
    @EVL.Endpoint(method = "GET", path = "/api/v1/dispatch/rider/jobs", caller = "Rider app")
    @GetMapping("/api/v1/dispatch/rider/jobs")
    @PreAuthorize("hasAuthority('rider:portal')")
    List<JobDetails> myJobs() {
        return this.dispatch.myJobs();
    }

    @Flow(value = "dispatch-offer-accept", label = "Accept a job offer", description = "")
    @EVL.Endpoint(method = "POST", path = "/api/v1/dispatch/rider/offers/{orderId}/accept", caller = "Rider app")
    @PostMapping("/api/v1/dispatch/rider/offers/{orderId}/accept")
    @PreAuthorize("hasAuthority('rider:portal')")
    JobDetails accept(@PathVariable String orderId) {
        return this.dispatch.accept(orderId);
    }

    @Flow(value = "dispatch-offer-decline", label = "Decline a job offer", description = "")
    @EVL.Endpoint(method = "POST", path = "/api/v1/dispatch/rider/offers/{orderId}/decline", caller = "Rider app")
    @PostMapping("/api/v1/dispatch/rider/offers/{orderId}/decline")
    @PreAuthorize("hasAuthority('rider:portal')")
    ResponseEntity<Void> decline(@PathVariable String orderId) {
        this.dispatch.decline(orderId);
        return ResponseEntity.noContent().build();
    }

    @Flow(value = "dispatch-arrived", label = "Arrived at the branch", description = "")
    @EVL.Endpoint(method = "POST", path = "/api/v1/deliveries/{orderId}/arrived", caller = "Rider app")
    @PostMapping("/api/v1/deliveries/{orderId}/arrived")
    @PreAuthorize("hasAuthority('rider:portal')")
    JobDetails arrived(@PathVariable String orderId) {
        return this.dispatch.arrived(orderId);
    }

    @Flow(value = "dispatch-picked-up", label = "Picked up the food", description = "")
    @EVL.Endpoint(method = "POST", path = "/api/v1/deliveries/{orderId}/picked-up", caller = "Rider app")
    @PostMapping("/api/v1/deliveries/{orderId}/picked-up")
    @PreAuthorize("hasAuthority('rider:portal')")
    JobDetails pickedUp(@PathVariable String orderId) {
        return this.dispatch.pickedUp(orderId);
    }

    @Flow(value = "dispatch-delivered", label = "Delivered", description = "With the delivery code or a photo, and the cash collected")
    @EVL.Endpoint(method = "POST", path = "/api/v1/deliveries/{orderId}/delivered", caller = "Rider app, back office (support)")
    @PostMapping("/api/v1/deliveries/{orderId}/delivered")
    @PreAuthorize("isAuthenticated()")
    JobDetails delivered(@PathVariable String orderId, @RequestBody(required = false) DeliveredRequest body) {
        Delivered proof = (body != null) ? new Delivered(body.code(), body.photoUrl(), body.cashCollectedMinor()) : null;
        return this.dispatch.delivered(orderId, proof);
    }

    @Flow(value = "dispatch-unassign", label = "Give up a job", description = "Before pickup, with a reason")
    @EVL.Endpoint(method = "POST", path = "/api/v1/deliveries/{orderId}/unassign", caller = "Rider app")
    @PostMapping("/api/v1/deliveries/{orderId}/unassign")
    @PreAuthorize("hasAuthority('rider:portal')")
    ResponseEntity<Void> unassign(@PathVariable String orderId, @RequestBody ReasonRequest body) {
        this.dispatch.unassign(orderId, body.reason());
        return ResponseEntity.noContent().build();
    }

    // ---- store and support ---------------------------------------------------------------------

    @Flow(value = "dispatch-job-view", label = "View a delivery", description = "Rider, branch or dispatch staff")
    @EVL.Endpoint(method = "GET", path = "/api/v1/deliveries/{orderId}", caller = "Rider app, back office")
    @GetMapping("/api/v1/deliveries/{orderId}")
    @PreAuthorize("isAuthenticated()")
    JobDetails get(@PathVariable String orderId) {
        return this.dispatch.get(orderId);
    }

    @Flow(value = "dispatch-store-rider", label = "Give a delivery to a branch rider", description = "Self-delivery orders")
    @EVL.Endpoint(method = "POST", path = "/api/v1/deliveries/{orderId}/store-rider", caller = "Back office (store)")
    @PostMapping("/api/v1/deliveries/{orderId}/store-rider")
    @PreAuthorize("isAuthenticated()")
    JobDetails storeRider(@PathVariable String orderId, @RequestBody RiderRequest body) {
        return this.dispatch.assignStoreRider(orderId, body.riderId());
    }

    @Flow(value = "dispatch-open-jobs", label = "Open deliveries", description = "By status")
    @EVL.Endpoint(method = "GET", path = "/api/v1/dispatch/jobs", caller = "Back office (dispatch)")
    @GetMapping("/api/v1/dispatch/jobs")
    @PreAuthorize("hasAuthority('dispatch:manage')")
    List<JobDetails> open(@RequestParam(required = false) DeliveryStatus status) {
        return this.dispatch.open(status);
    }

    @Flow(value = "dispatch-reassign", label = "Reassign a delivery", description = "Or send it back to searching")
    @EVL.Endpoint(method = "POST", path = "/api/v1/dispatch/jobs/{orderId}/reassign", caller = "Back office (dispatch)")
    @PostMapping("/api/v1/dispatch/jobs/{orderId}/reassign")
    @PreAuthorize("hasAuthority('dispatch:manage')")
    JobDetails reassign(@PathVariable String orderId, @RequestBody RiderRequest body) {
        return this.dispatch.reassign(orderId, body.riderId(), body.reason());
    }

    @Flow(value = "dispatch-hand-back", label = "Hand a delivery back to the branch", description = "The branch delivers it itself")
    @EVL.Endpoint(method = "POST", path = "/api/v1/dispatch/jobs/{orderId}/hand-back", caller = "Back office (dispatch)")
    @PostMapping("/api/v1/dispatch/jobs/{orderId}/hand-back")
    @PreAuthorize("hasAuthority('dispatch:manage')")
    JobDetails handBack(@PathVariable String orderId, @RequestBody ReasonRequest body) {
        return this.dispatch.handBack(orderId, body.reason());
    }
}

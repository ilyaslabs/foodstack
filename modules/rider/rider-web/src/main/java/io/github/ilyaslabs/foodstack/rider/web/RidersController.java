package io.github.ilyaslabs.foodstack.rider.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import java.util.List;

import io.github.ilyaslabs.foodstack.rider.api.RiderKind;
import io.github.ilyaslabs.foodstack.rider.api.RiderStatus;
import io.github.ilyaslabs.foodstack.rider.api.Vehicle;
import io.github.ilyaslabs.foodstack.rider.core.application.Earnings;
import io.github.ilyaslabs.foodstack.rider.core.application.LiveRider;
import io.github.ilyaslabs.foodstack.rider.core.application.LocationPing;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderActivity;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderDetails;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderInput;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderService;
import io.github.ilyaslabs.foodstack.rider.core.application.Tracking;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Riders (PRD DR-1 to DR-7, AD-9): the rider's own shift, position and earnings under
 * {@code /api/v1/rider/me}; the operator's fleet, a store's own riders, the live map and following
 * one delivery under {@code /api/v1/riders}. Access is checked by the core services.
 */
@RestController
class RidersController {

    private final RiderService riders;

    private final RiderActivity activity;

    RidersController(RiderService riders, RiderActivity activity) {
        this.riders = riders;
        this.activity = activity;
    }

    record RiderRequest(String email, String initialPassword, String name, String phone, Vehicle vehicle, String plate,
            String city, String cityId, Long version) {

        RiderInput toInput() {
            return new RiderInput(this.email, this.initialPassword, this.name, this.phone, this.vehicle, this.plate, this.city,
                    this.cityId, this.version);
        }

        @Override
        public String toString() {
            return "RiderRequest[name=" + this.name + "]";
        }
    }

    record OnlineRequest(boolean online, Double lat, Double lng, Double accuracyMeters) {
    }

    record LocationsRequest(List<LocationPing> points) {
    }

    record StatusRequest(RiderStatus status) {
    }

    // ---- the rider -----------------------------------------------------------------------------

    @GetMapping("/api/v1/rider/me")
    @PreAuthorize("hasAuthority('rider:portal')")
    @Flow(value = "rider-me", label = "My rider profile")
    @EVL.Endpoint(method = "GET", path = "/api/v1/rider/me", caller = "Rider app")
    RiderDetails me() {
        return this.activity.me();
    }

    @PostMapping("/api/v1/rider/me/online")
    @PreAuthorize("hasAuthority('rider:portal')")
    @Flow(value = "rider-go-online", label = "Go online or offline", description = "Active riders only; going offline leaves the matching pool")
    @EVL.Endpoint(method = "POST", path = "/api/v1/rider/me/online", caller = "Rider app")
    RiderDetails online(@RequestBody OnlineRequest body) {
        LocationPing at = (body.lat() != null && body.lng() != null)
                ? new LocationPing(body.lat(), body.lng(), body.accuracyMeters(), null) : null;
        return this.activity.setOnline(body.online(), at);
    }

    @PostMapping("/api/v1/rider/me/locations")
    @PreAuthorize("hasAuthority('rider:portal')")
    @Flow(value = "rider-locations", label = "Send positions", description = "Batched GPS pings from the rider app")
    @EVL.Endpoint(method = "POST", path = "/api/v1/rider/me/locations", caller = "Rider app")
    ResponseEntity<Void> locations(@RequestBody LocationsRequest body) {
        this.activity.locations(body.points());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/rider/me/earnings")
    @PreAuthorize("hasAuthority('rider:portal')")
    @Flow(value = "rider-my-earnings", label = "My earnings")
    @EVL.Endpoint(method = "GET", path = "/api/v1/rider/me/earnings", caller = "Rider app")
    Earnings myEarnings(@RequestParam(defaultValue = "7") int days, @RequestParam(required = false) String tz) {
        return this.activity.myEarnings(days, tz);
    }

    // ---- operator ------------------------------------------------------------------------------

    @GetMapping("/api/v1/riders")
    @PreAuthorize("hasAuthority('rider:manage')")
    @Flow(value = "rider-list", label = "List riders", description = "Filter by kind, status, branch, text")
    @EVL.Endpoint(method = "GET", path = "/api/v1/riders", caller = "Back office")
    List<RiderDetails> list(@RequestParam(required = false) RiderKind kind, @RequestParam(required = false) RiderStatus status,
            @RequestParam(required = false) String storeId, @RequestParam(required = false) String q) {
        return this.riders.list(kind, status, storeId, q);
    }

    @PostMapping("/api/v1/riders")
    @PreAuthorize("hasAuthority('rider:manage')")
    @Flow(value = "rider-add-operator", label = "Add operator rider", description = "Creates the sign-in in identity and the rider record")
    @EVL.Endpoint(method = "POST", path = "/api/v1/riders", caller = "Back office")
    ResponseEntity<RiderDetails> add(@RequestBody RiderRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(this.riders.addOperatorRider(body.toInput()));
    }

    @GetMapping("/api/v1/riders/live")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "rider-live-map", label = "Online riders on the map")
    @EVL.Endpoint(method = "GET", path = "/api/v1/riders/live", caller = "Back office")
    List<LiveRider> live(@RequestParam(required = false) Double south, @RequestParam(required = false) Double west,
            @RequestParam(required = false) Double north, @RequestParam(required = false) Double east) {
        boolean bounded = south != null && west != null && north != null && east != null;
        return this.activity.live(bounded ? new RiderActivity.MapBounds(south, west, north, east) : null);
    }

    @GetMapping("/api/v1/riders/tracking/{orderId}")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "rider-tracking", label = "Track order's rider", description = "Rider position and ETA for one order")
    @EVL.Endpoint(method = "GET", path = "/api/v1/riders/tracking/{orderId}", caller = "Customer web")
    Tracking tracking(@PathVariable String orderId) {
        return this.activity.tracking(orderId);
    }

    @GetMapping("/api/v1/riders/{riderId}")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "rider-get", label = "Show rider")
    @EVL.Endpoint(method = "GET", path = "/api/v1/riders/{riderId}", caller = "Back office")
    RiderDetails get(@PathVariable String riderId) {
        return this.riders.get(riderId);
    }

    @PutMapping("/api/v1/riders/{riderId}")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "rider-update", label = "Edit rider")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/riders/{riderId}", caller = "Back office")
    RiderDetails update(@PathVariable String riderId, @RequestBody RiderRequest body) {
        return this.riders.update(riderId, body.toInput());
    }

    @PostMapping("/api/v1/riders/{riderId}/status")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "rider-set-status", label = "Suspend or reactivate rider")
    @EVL.Endpoint(method = "POST", path = "/api/v1/riders/{riderId}/status", caller = "Back office")
    RiderDetails status(@PathVariable String riderId, @RequestBody StatusRequest body) {
        return this.riders.setStatus(riderId, body.status());
    }

    @GetMapping("/api/v1/riders/{riderId}/earnings")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "rider-earnings", label = "Rider earnings")
    @EVL.Endpoint(method = "GET", path = "/api/v1/riders/{riderId}/earnings", caller = "Back office")
    Earnings earnings(@PathVariable String riderId, @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String tz) {
        return this.activity.earningsOf(riderId, days, tz);
    }

    // ---- store ---------------------------------------------------------------------------------

    @GetMapping("/api/v1/riders/stores/{storeId}")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "rider-store-list", label = "List brand's store riders")
    @EVL.Endpoint(method = "GET", path = "/api/v1/riders/stores/{storeId}", caller = "Back office")
    List<RiderDetails> storeRiders(@PathVariable String storeId) {
        return this.riders.storeRiders(storeId);
    }

    @PostMapping("/api/v1/riders/stores/{storeId}")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "rider-add-store", label = "Add store rider")
    @EVL.Endpoint(method = "POST", path = "/api/v1/riders/stores/{storeId}", caller = "Back office")
    ResponseEntity<RiderDetails> addStoreRider(@PathVariable String storeId, @RequestBody RiderRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(this.riders.addStoreRider(storeId, body.toInput()));
    }

    @DeleteMapping("/api/v1/riders/stores/{storeId}/{riderId}")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "rider-remove-store", label = "Remove store rider")
    @EVL.Endpoint(method = "DELETE", path = "/api/v1/riders/stores/{storeId}/{riderId}", caller = "Back office")
    ResponseEntity<Void> removeStoreRider(@PathVariable String storeId, @PathVariable String riderId) {
        this.riders.removeStoreRider(storeId, riderId);
        return ResponseEntity.noContent().build();
    }
}

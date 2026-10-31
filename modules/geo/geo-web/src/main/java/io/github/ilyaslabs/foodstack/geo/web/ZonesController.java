package io.github.ilyaslabs.foodstack.geo.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.core.application.ZoneDetails;
import io.github.ilyaslabs.foodstack.geo.core.application.ZoneInput;
import io.github.ilyaslabs.foodstack.geo.core.application.ZoneService;
import io.github.ilyaslabs.foodstack.geo.web.dto.LatLng;
import io.github.ilyaslabs.foodstack.geo.web.dto.ZoneRequest;
import io.github.ilyaslabs.foodstack.geo.web.dto.ZoneResponse;
import io.github.ilyaslabs.foodstack.geo.web.dto.ZonesRequest;
import io.github.ilyaslabs.foodstack.geo.web.dto.ZonesResponse;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A branch's delivery zones (PRD ST-11). The branch's owner edits them; operator staff with
 * {@code geo:manage} can view and correct any branch's zones for support (SE-7).
 */
@RestController
@RequestMapping("/api/v1/zones")
class ZonesController {

    private static final String OPERATOR_PERMISSION = "geo:manage";

    private final ZoneService zoneService;

    ZonesController(ZoneService zoneService) {
        this.zoneService = zoneService;
    }

    @GetMapping("/stores/{storeId}")
    @PreAuthorize("isAuthenticated()")
    ZonesResponse zones(@PathVariable String storeId) {
        CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        return toResponse(storeId, this.zoneService.getZones(storeId));
    }

    @PutMapping("/stores/{storeId}")
    @PreAuthorize("isAuthenticated()")
    ZonesResponse replace(@PathVariable String storeId, @Valid @RequestBody ZonesRequest request) {
        CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        List<ZoneInput> inputs = request.zones().stream().map(ZonesController::toInput).toList();
        return toResponse(storeId, this.zoneService.replaceZones(storeId, inputs, CurrentUser.id()));
    }

    private static ZoneInput toInput(ZoneRequest zone) {
        List<GeoPoint> polygon = (zone.polygon() != null)
                ? zone.polygon().stream().map((p) -> new GeoPoint(p.lat(), p.lng())).toList() : null;
        GeoPoint center = (zone.center() != null) ? new GeoPoint(zone.center().lat(), zone.center().lng()) : null;
        return new ZoneInput(zone.name(), center, zone.radiusMeters(), polygon, zone.chargeMinor(), zone.minOrderMinor(),
                zone.freeDeliveryAboveMinor());
    }

    private static ZonesResponse toResponse(String storeId, List<ZoneDetails> zones) {
        return new ZonesResponse(storeId, zones.stream()
            .map((z) -> new ZoneResponse(z.id(), z.name(),
                    (z.center() != null) ? new LatLng(z.center().lat(), z.center().lng()) : null, z.radiusMeters(),
                    z.polygon().stream().map((p) -> new LatLng(p.lat(), p.lng())).toList(), z.chargeMinor(),
                    z.minOrderMinor(), z.freeDeliveryAboveMinor()))
            .toList());
    }
}

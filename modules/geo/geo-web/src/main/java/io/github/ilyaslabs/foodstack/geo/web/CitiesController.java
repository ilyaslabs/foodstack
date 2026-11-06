package io.github.ilyaslabs.foodstack.geo.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.core.application.City;
import io.github.ilyaslabs.foodstack.geo.core.application.CityInput;
import io.github.ilyaslabs.foodstack.geo.core.application.CityPricing;
import io.github.ilyaslabs.foodstack.geo.core.application.CityService;
import io.github.ilyaslabs.foodstack.geo.core.application.PeakWindow;
import io.github.ilyaslabs.foodstack.geo.web.dto.CityRequest;
import io.github.ilyaslabs.foodstack.geo.web.dto.CityResponse;
import io.github.ilyaslabs.foodstack.geo.web.dto.LatLng;
import io.github.ilyaslabs.foodstack.geo.web.dto.PeakWindowDto;
import io.github.ilyaslabs.foodstack.platform.core.EpochSeconds;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.web.ETags;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's cities (PRD DR-1, DR-1b). Anyone signed in to the back office can list them (store
 * and rider forms pick one); operator staff with {@code geo:manage} add and change them.
 */
@RestController
@RequestMapping("/api/v1/geo/cities")
class CitiesController {

    private static final String CITY = "city";

    private final CityService cityService;

    CitiesController(CityService cityService) {
        this.cityService = cityService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    List<CityResponse> list() {
        return this.cityService.list().stream().map(CitiesController::toResponse).toList();
    }

    /** The active city nearest to a point, to fill in a new branch's or rider's city; 204 when none is near. */
    @GetMapping("/nearest")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<CityResponse> nearest(@RequestParam double lat, @RequestParam double lng) {
        City city = this.cityService.nearest(new GeoPoint(lat, lng));
        return (city != null) ? ResponseEntity.ok(toResponse(city)) : ResponseEntity.noContent().build();
    }

    @GetMapping("/{cityId}")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<CityResponse> get(@PathVariable String cityId) {
        City city = this.cityService.get(cityId);
        return ResponseEntity.ok().eTag(ETags.of(CITY, city.version())).body(toResponse(city));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('geo:manage')")
    ResponseEntity<CityResponse> create(@Valid @RequestBody CityRequest request) {
        City city = this.cityService.create(toInput(request), CurrentUser.id());
        return ResponseEntity.status(HttpStatus.CREATED).eTag(ETags.of(CITY, city.version())).body(toResponse(city));
    }

    @PutMapping("/{cityId}")
    @PreAuthorize("hasAuthority('geo:manage')")
    ResponseEntity<CityResponse> update(@PathVariable String cityId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CityRequest request) {
        Long version = ETags.versionFromIfMatch(CITY, ifMatch);
        if (version == null) {
            throw new ModuleException.BadRequest("Send If-Match with the ETag you edited, so changes are not lost",
                    "IF_MATCH_REQUIRED");
        }
        City city = this.cityService.update(cityId, toInput(request), version, CurrentUser.id());
        return ResponseEntity.ok().eTag(ETags.of(CITY, city.version())).body(toResponse(city));
    }

    private static CityInput toInput(CityRequest request) {
        CityRequest.Pricing p = request.pricing();
        CityPricing pricing = (p != null) ? new CityPricing(p.baseChargeMinor(), p.perKmMinor(), p.maxDistanceMeters(),
                (p.minOrderMinor() != null) ? p.minOrderMinor() : 0, p.freeDeliveryAboveMinor(),
                (p.peakMultiplierPercent() != null) ? p.peakMultiplierPercent() : 100,
                (p.peakWindows() != null)
                        ? p.peakWindows().stream().map((w) -> new PeakWindow(w.days(), w.start(), w.end())).toList()
                        : List.of())
                : null;
        return new CityInput(request.name(), new GeoPoint(request.centre().lat(), request.centre().lng()), request.active(),
                pricing);
    }

    static CityResponse toResponse(City city) {
        CityPricing p = city.pricing();
        CityResponse.Pricing pricing = (p != null) ? new CityResponse.Pricing(p.baseChargeMinor(), p.perKmMinor(),
                p.maxDistanceMeters(), p.minOrderMinor(), p.freeDeliveryAboveMinor(), p.peakMultiplierPercent(),
                p.peakWindows().stream().map((w) -> new PeakWindowDto(w.days(), w.start(), w.end())).toList()) : null;
        return new CityResponse(city.id(), city.name(), new LatLng(city.centre().lat(), city.centre().lng()), city.active(),
                pricing, city.version(), EpochSeconds.of(city.updatedAt()));
    }
}

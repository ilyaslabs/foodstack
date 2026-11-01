package io.github.ilyaslabs.foodstack.geo.web;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.core.application.AddressSuggestion;
import io.github.ilyaslabs.foodstack.geo.core.application.GeocodingService;
import io.github.ilyaslabs.foodstack.geo.core.application.OperatorPricing;
import io.github.ilyaslabs.foodstack.geo.core.application.OperatorPricingService;
import io.github.ilyaslabs.foodstack.geo.core.application.UpdateOperatorPricing;
import io.github.ilyaslabs.foodstack.geo.web.dto.AddressResponse;
import io.github.ilyaslabs.foodstack.geo.web.dto.OperatorPricingRequest;
import io.github.ilyaslabs.foodstack.geo.web.dto.OperatorPricingResponse;
import io.github.ilyaslabs.foodstack.platform.core.EpochSeconds;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.web.ETags;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator delivery pricing (admin) and address search (public). */
@RestController
@RequestMapping("/api/v1/geo")
class GeoController {

    private static final String PRICING = "operator-pricing";

    private final OperatorPricingService pricingService;

    private final GeocodingService geocodingService;

    GeoController(OperatorPricingService pricingService, GeocodingService geocodingService) {
        this.pricingService = pricingService;
        this.geocodingService = geocodingService;
    }

    @GetMapping("/operator-pricing")
    @PreAuthorize("hasAnyAuthority('geo:manage', 'store:manage', 'store:portal')")
    ResponseEntity<OperatorPricingResponse> pricing() {
        OperatorPricing pricing = this.pricingService.get();
        return ResponseEntity.ok().eTag(ETags.of(PRICING, pricing.version())).body(toResponse(pricing));
    }

    @PutMapping("/operator-pricing")
    @PreAuthorize("hasAuthority('geo:manage')")
    ResponseEntity<OperatorPricingResponse> updatePricing(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody OperatorPricingRequest request) {
        Long version = ETags.versionFromIfMatch(PRICING, ifMatch);
        if (version == null) {
            throw new ModuleException.BadRequest("Send If-Match with the ETag you edited, so changes are not lost",
                    "IF_MATCH_REQUIRED");
        }
        OperatorPricing updated = this.pricingService.update(new UpdateOperatorPricing(request.enabled(),
                request.baseChargeMinor(), request.perKmMinor(), request.maxDistanceMeters()), version, CurrentUser.id());
        return ResponseEntity.ok().eTag(ETags.of(PRICING, updated.version())).body(toResponse(updated));
    }

    /** Address search for the location picker (PRD SE-6). */
    @GetMapping("/addresses")
    List<AddressResponse> search(@RequestParam("q") String query,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String language) {
        return toResponse(this.geocodingService.search(query, language));
    }

    /** The address at a map pin or the device location. */
    @GetMapping("/addresses/reverse")
    List<AddressResponse> reverse(@RequestParam double lat, @RequestParam double lng,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String language) {
        return toResponse(this.geocodingService.reverse(lat, lng, language));
    }

    /** Whether address search is configured, so the apps can hide the search box. */
    @GetMapping("/addresses/availability")
    Map<String, Boolean> availability() {
        return Map.of("available", this.geocodingService.isAvailable());
    }

    private static List<AddressResponse> toResponse(List<AddressSuggestion> suggestions) {
        return suggestions.stream().map((s) -> new AddressResponse(s.label(), s.lat(), s.lng())).toList();
    }

    private static OperatorPricingResponse toResponse(OperatorPricing p) {
        return new OperatorPricingResponse(p.enabled(), p.baseChargeMinor(), p.perKmMinor(), p.maxDistanceMeters(),
                p.version(), EpochSeconds.of(p.updatedAt()));
    }
}

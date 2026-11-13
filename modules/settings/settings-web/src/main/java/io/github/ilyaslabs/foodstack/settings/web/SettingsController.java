package io.github.ilyaslabs.foodstack.settings.web;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.foodstack.platform.core.EpochSeconds;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.web.ETags;
import io.github.ilyaslabs.foodstack.settings.core.application.BrandingDetails;
import io.github.ilyaslabs.foodstack.settings.core.application.DeliveryDetails;
import io.github.ilyaslabs.foodstack.settings.core.application.DeliverySettingsService;
import io.github.ilyaslabs.foodstack.settings.core.application.LocaleDetails;
import io.github.ilyaslabs.foodstack.settings.core.application.OrderingDetails;
import io.github.ilyaslabs.foodstack.settings.core.application.OrderingSettingsService;
import io.github.ilyaslabs.foodstack.settings.core.application.SettingsService;
import io.github.ilyaslabs.foodstack.settings.core.application.UpdateBranding;
import io.github.ilyaslabs.foodstack.settings.core.application.UpdateDelivery;
import io.github.ilyaslabs.foodstack.settings.core.application.UpdateLocale;
import io.github.ilyaslabs.foodstack.settings.core.application.UpdateOrdering;
import io.github.ilyaslabs.foodstack.settings.web.dto.BrandingRequest;
import io.github.ilyaslabs.foodstack.settings.web.dto.BrandingResponse;
import io.github.ilyaslabs.foodstack.settings.web.dto.DeliveryRequest;
import io.github.ilyaslabs.foodstack.settings.web.dto.DeliveryResponse;
import io.github.ilyaslabs.foodstack.settings.web.dto.LocaleRequest;
import io.github.ilyaslabs.foodstack.settings.web.dto.LocaleResponse;
import io.github.ilyaslabs.foodstack.settings.web.dto.OrderingRequest;
import io.github.ilyaslabs.foodstack.settings.web.dto.OrderingResponse;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime branding, locale and ordering rules. Reads are public and carry an {@code ETag}; edits need
 * {@code settings:manage} and the {@code If-Match} of the version the editor saw (TDD 8.1).
 */
@EVL.Component(id = "settings.controller", name = "Settings Controller", module = "settings",
        layer = EVL.Layer.PRESENTATION,
        responsibilities = { "REST API under /api/v1/settings", "ETag and Cache-Control on reads",
                "If-Match on every change" })
@RestController
@RequestMapping("/api/v1/settings")
class SettingsController {

    private static final Set<String> RTL_LANGUAGES = Set.of("ar", "fa", "he", "ur", "ps", "sd", "ug", "yi", "dv", "ku");

    private static final CacheControl PUBLIC_CACHE = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final SettingsService settingsService;

    private final OrderingSettingsService orderingService;

    private final DeliverySettingsService deliveryService;

    SettingsController(SettingsService settingsService, OrderingSettingsService orderingService,
            DeliverySettingsService deliveryService) {
        this.deliveryService = deliveryService;
        this.settingsService = settingsService;
        this.orderingService = orderingService;
    }

    @EVL.Endpoint(id = "settings.get-branding", name = "Get Branding", protocol = EVL.Protocol.HTTP, method = "GET",
            path = "/api/v1/settings/branding", responseRef = "settings.branding-response",
            auth = "Public; ETag, max-age 60 s")
    @GetMapping("/branding")
    ResponseEntity<BrandingResponse> branding(@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        BrandingDetails branding = this.settingsService.getBranding();
        String etag = ETags.of("branding", branding.version());
        if (ETags.matches(etag, ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(PUBLIC_CACHE).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(PUBLIC_CACHE).body(toResponse(branding));
    }

    @EVL.Endpoint(id = "settings.update-branding", name = "Update Branding", protocol = EVL.Protocol.HTTP,
            method = "PUT", path = "/api/v1/settings/branding", requestRef = "settings.branding-request",
            responseRef = "settings.branding-response", flow = "settings.branding-update",
            auth = "settings:manage; If-Match required",
            errors = { "settings.error.if-match-required", "settings.error.invalid", "settings.error.version-conflict",
                    "media.error.not-found", "media.error.not-ready", "media.error.purpose-mismatch",
                    "media.error.already-claimed" })
    @PutMapping("/branding")
    @PreAuthorize("hasAuthority('settings:manage')")
    ResponseEntity<BrandingResponse> updateBranding(@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody BrandingRequest request) {
        BrandingDetails updated = this.settingsService.updateBranding(new UpdateBranding(request.name(), request.tagline(),
                request.primaryColor(), request.secondaryColor(), request.fontFamily(), request.logoMediaId()),
                requireIfMatch("branding", ifMatch), CurrentUser.id());
        return ResponseEntity.ok().eTag(ETags.of("branding", updated.version())).body(toResponse(updated));
    }

    @EVL.Endpoint(id = "settings.get-locale", name = "Get Locale", protocol = EVL.Protocol.HTTP, method = "GET",
            path = "/api/v1/settings/locale", responseRef = "settings.locale-response",
            auth = "Public; ETag, max-age 60 s")
    @GetMapping("/locale")
    ResponseEntity<LocaleResponse> locale(@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        LocaleDetails locale = this.settingsService.getLocale();
        String etag = ETags.of("locale", locale.version());
        if (ETags.matches(etag, ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(PUBLIC_CACHE).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(PUBLIC_CACHE).body(toResponse(locale));
    }

    @EVL.Endpoint(id = "settings.update-locale", name = "Update Locale", protocol = EVL.Protocol.HTTP,
            method = "PUT", path = "/api/v1/settings/locale", requestRef = "settings.locale-request",
            responseRef = "settings.locale-response", auth = "settings:manage; If-Match required",
            errors = { "settings.error.if-match-required", "settings.error.invalid",
                    "settings.error.version-conflict" })
    @PutMapping("/locale")
    @PreAuthorize("hasAuthority('settings:manage')")
    ResponseEntity<LocaleResponse> updateLocale(@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody LocaleRequest request) {
        LocaleDetails updated = this.settingsService.updateLocale(
                new UpdateLocale(request.defaultLanguage(), request.languages(), request.currency(), request.timezone()),
                requireIfMatch("locale", ifMatch), CurrentUser.id());
        return ResponseEntity.ok().eTag(ETags.of("locale", updated.version())).body(toResponse(updated));
    }

    @EVL.Endpoint(id = "settings.get-ordering", name = "Get Ordering Rules", protocol = EVL.Protocol.HTTP,
            method = "GET", path = "/api/v1/settings/ordering", responseRef = "settings.ordering-response",
            auth = "Public; ETag")
    @GetMapping("/ordering")
    ResponseEntity<OrderingResponse> ordering() {
        OrderingDetails ordering = this.orderingService.get();
        return ResponseEntity.ok().eTag(ETags.of("ordering", ordering.version())).body(toResponse(ordering));
    }

    @EVL.Endpoint(id = "settings.update-ordering", name = "Update Ordering Rules", protocol = EVL.Protocol.HTTP,
            method = "PUT", path = "/api/v1/settings/ordering", requestRef = "settings.ordering-request",
            responseRef = "settings.ordering-response", auth = "settings:manage; If-Match required",
            errors = { "settings.error.if-match-required", "settings.error.invalid",
                    "settings.error.version-conflict" })
    @PutMapping("/ordering")
    @PreAuthorize("hasAuthority('settings:manage')")
    ResponseEntity<OrderingResponse> updateOrdering(@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody OrderingRequest request) {
        OrderingDetails current = this.orderingService.get();
        OrderingDetails updated = this.orderingService.update(new UpdateOrdering(request.cashEnabled(), request.cardEnabled(),
                request.serviceFeeMinor(), request.serviceFeeBasisPoints(), request.taxBasisPoints(), request.tipsEnabled(),
                request.acceptTimeoutSeconds(),
                (request.priceApprovalRequired() != null) ? request.priceApprovalRequired() : current.priceApprovalRequired(),
                (request.directCollectionEnabled() != null) ? request.directCollectionEnabled() : current.directCollectionEnabled(),
                (request.creditLimitMinor() != null) ? request.creditLimitMinor() : current.creditLimitMinor(),
                (request.cardStyle() != null) ? request.cardStyle() : current.cardStyle(),
                (request.savedCardsEnabled() != null) ? request.savedCardsEnabled() : current.savedCardsEnabled()),
                requireIfMatch("ordering", ifMatch), CurrentUser.id());
        return ResponseEntity.ok().eTag(ETags.of("ordering", updated.version())).body(toResponse(updated));
    }

    /** Rider pay and dispatch rules are for operator staff only, unlike the other sections. */
    @EVL.Endpoint(id = "settings.get-delivery", name = "Get Delivery Rules", protocol = EVL.Protocol.HTTP,
            method = "GET", path = "/api/v1/settings/delivery", responseRef = "settings.delivery-response",
            auth = "settings:manage (operator staff only); ETag")
    @GetMapping("/delivery")
    @PreAuthorize("hasAuthority('settings:manage')")
    ResponseEntity<DeliveryResponse> delivery() {
        DeliveryDetails delivery = this.deliveryService.get();
        return ResponseEntity.ok().eTag(ETags.of("delivery", delivery.version())).body(toResponse(delivery));
    }

    @EVL.Endpoint(id = "settings.update-delivery", name = "Update Delivery Rules", protocol = EVL.Protocol.HTTP,
            method = "PUT", path = "/api/v1/settings/delivery", requestRef = "settings.delivery-request",
            responseRef = "settings.delivery-response", auth = "settings:manage; If-Match required",
            errors = { "settings.error.if-match-required", "settings.error.invalid",
                    "settings.error.version-conflict" })
    @PutMapping("/delivery")
    @PreAuthorize("hasAuthority('settings:manage')")
    ResponseEntity<DeliveryResponse> updateDelivery(@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody DeliveryRequest request) {
        DeliveryDetails updated = this.deliveryService.update(new UpdateDelivery(request.riderPayBasisPoints(),
                request.riderPayMinMinor(), request.offerTimeoutSeconds(), request.searchRadiusMeters(),
                request.searchLeadMinutes(), request.noRiderAlertSeconds(), request.proofCodeAboveMinor(),
                request.locationRetentionDays()), requireIfMatch("delivery", ifMatch), CurrentUser.id());
        return ResponseEntity.ok().eTag(ETags.of("delivery", updated.version())).body(toResponse(updated));
    }

    private static long requireIfMatch(String resource, String ifMatch) {
        Long version = ETags.versionFromIfMatch(resource, ifMatch);
        if (version == null) {
            throw new ModuleException.BadRequest("Send If-Match with the ETag you edited, so changes are not lost",
                    "IF_MATCH_REQUIRED");
        }
        return version;
    }

    private static BrandingResponse toResponse(BrandingDetails b) {
        BrandingResponse.Logo logo = (b.logoMediaId() != null) ? new BrandingResponse.Logo(b.logoMediaId(), b.logoUrls()) : null;
        return new BrandingResponse(b.name(), b.tagline(), b.primaryColor(), b.secondaryColor(), b.fontFamily(), logo,
                b.version(), EpochSeconds.of(b.updatedAt()));
    }

    private static LocaleResponse toResponse(LocaleDetails l) {
        List<String> rtl = l.languages()
            .stream()
            .filter((tag) -> RTL_LANGUAGES.contains(Locale.forLanguageTag(tag).getLanguage()))
            .toList();
        return new LocaleResponse(l.defaultLanguage(), l.languages(), rtl, l.currency(), l.timezone(), l.version());
    }

    private static OrderingResponse toResponse(OrderingDetails o) {
        return new OrderingResponse(o.cashEnabled(), o.cardEnabled(), o.serviceFeeMinor(), o.serviceFeeBasisPoints(),
                o.taxBasisPoints(), o.tipsEnabled(), o.acceptTimeoutSeconds(), o.version(), EpochSeconds.of(o.updatedAt()),
                o.priceApprovalRequired(), o.directCollectionEnabled(), o.creditLimitMinor(), o.cardStyle(),
                o.savedCardsEnabled());
    }

    private static DeliveryResponse toResponse(DeliveryDetails d) {
        return new DeliveryResponse(d.riderPayBasisPoints(), d.riderPayMinMinor(), d.offerTimeoutSeconds(),
                d.searchRadiusMeters(), d.searchLeadMinutes(), d.noRiderAlertSeconds(), d.proofCodeAboveMinor(),
                d.locationRetentionDays(), d.version(), EpochSeconds.of(d.updatedAt()));
    }
}

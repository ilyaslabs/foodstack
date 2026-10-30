package io.github.ilyaslabs.foodstack.settings.web;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.ilyaslabs.foodstack.platform.core.EpochSeconds;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.web.ETags;
import io.github.ilyaslabs.foodstack.settings.core.application.BrandingDetails;
import io.github.ilyaslabs.foodstack.settings.core.application.LocaleDetails;
import io.github.ilyaslabs.foodstack.settings.core.application.SettingsService;
import io.github.ilyaslabs.foodstack.settings.core.application.UpdateBranding;
import io.github.ilyaslabs.foodstack.settings.core.application.UpdateLocale;
import io.github.ilyaslabs.foodstack.settings.web.dto.BrandingRequest;
import io.github.ilyaslabs.foodstack.settings.web.dto.BrandingResponse;
import io.github.ilyaslabs.foodstack.settings.web.dto.LocaleRequest;
import io.github.ilyaslabs.foodstack.settings.web.dto.LocaleResponse;
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
 * Runtime branding and locale. Reads are public and carry an {@code ETag}; edits need
 * {@code settings:manage} and the {@code If-Match} of the version the editor saw (TDD 8.1).
 */
@RestController
@RequestMapping("/api/v1/settings")
class SettingsController {

    private static final Set<String> RTL_LANGUAGES = Set.of("ar", "fa", "he", "ur", "ps", "sd", "ug", "yi", "dv", "ku");

    private static final CacheControl PUBLIC_CACHE = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final SettingsService settingsService;

    SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/branding")
    ResponseEntity<BrandingResponse> branding(@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        BrandingDetails branding = this.settingsService.getBranding();
        String etag = ETags.of("branding", branding.version());
        if (ETags.matches(etag, ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(PUBLIC_CACHE).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(PUBLIC_CACHE).body(toResponse(branding));
    }

    @PutMapping("/branding")
    @PreAuthorize("hasAuthority('settings:manage')")
    ResponseEntity<BrandingResponse> updateBranding(@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody BrandingRequest request) {
        BrandingDetails updated = this.settingsService.updateBranding(new UpdateBranding(request.name(), request.tagline(),
                request.primaryColor(), request.secondaryColor(), request.fontFamily(), request.logoMediaId()),
                requireIfMatch("branding", ifMatch), CurrentUser.id());
        return ResponseEntity.ok().eTag(ETags.of("branding", updated.version())).body(toResponse(updated));
    }

    @GetMapping("/locale")
    ResponseEntity<LocaleResponse> locale(@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        LocaleDetails locale = this.settingsService.getLocale();
        String etag = ETags.of("locale", locale.version());
        if (ETags.matches(etag, ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(PUBLIC_CACHE).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(PUBLIC_CACHE).body(toResponse(locale));
    }

    @PutMapping("/locale")
    @PreAuthorize("hasAuthority('settings:manage')")
    ResponseEntity<LocaleResponse> updateLocale(@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody LocaleRequest request) {
        LocaleDetails updated = this.settingsService.updateLocale(
                new UpdateLocale(request.defaultLanguage(), request.languages(), request.currency(), request.timezone()),
                requireIfMatch("locale", ifMatch), CurrentUser.id());
        return ResponseEntity.ok().eTag(ETags.of("locale", updated.version())).body(toResponse(updated));
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
}

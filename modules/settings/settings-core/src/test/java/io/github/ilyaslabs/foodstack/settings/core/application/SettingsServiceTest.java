package io.github.ilyaslabs.foodstack.settings.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.media.api.MediaAssetView;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaStatus;
import io.github.ilyaslabs.foodstack.media.api.MediaVariantView;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.events.SettingsChanged;
import io.github.ilyaslabs.foodstack.settings.core.persistence.BrandingDocument;
import io.github.ilyaslabs.foodstack.settings.core.persistence.BrandingRepository;
import io.github.ilyaslabs.foodstack.settings.core.persistence.LocaleDocument;
import io.github.ilyaslabs.foodstack.settings.core.persistence.LocaleRepository;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SettingsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    private final BrandingRepository brandingRepository = mock(BrandingRepository.class);

    private final LocaleRepository localeRepository = mock(LocaleRepository.class);

    private final MediaApi mediaApi = mock(MediaApi.class);

    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    private final SettingsCache cache = mock(SettingsCache.class);

    private SettingsService service;

    private BrandingDocument branding;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        this.branding = new BrandingDocument();
        this.branding.setName("FoodStack");
        this.branding.setPrimaryColor("#D84315");
        this.branding.setSecondaryColor("#00695C");
        this.branding.setFontFamily("Roboto");
        this.branding.setVersion(4L);
        given(this.brandingRepository.findById(BrandingDocument.ID)).willReturn(Optional.of(this.branding));
        given(this.brandingRepository.save(any())).willAnswer((invocation) -> {
            BrandingDocument document = invocation.getArgument(0);
            document.setVersion(document.getVersion() + 1);
            return document;
        });
        given(this.transaction.execute(any())).willAnswer(
                (invocation) -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        given(this.cache.get(any(), any(), any())).willAnswer((invocation) -> ((Supplier<?>) invocation.getArgument(2)).get());
        this.service = new SettingsService(this.brandingRepository, this.localeRepository, this.mediaApi, this.outbox,
                this.transaction, this.cache, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("resolves the logo to its variant URLs through the media module")
    void brandingWithLogo() {
        this.branding.setLogoMediaId("logo-1");
        given(this.mediaApi.getAsset("logo-1")).willReturn(logo("logo-1"));

        BrandingDetails details = this.service.getBranding();

        assertThat(details.logoUrls()).containsEntry("thumb", "https://cdn/logo-1/thumb.webp");
        assertThat(details.version()).isEqualTo(4);
    }

    @Test
    @DisplayName("saves new branding with SettingsChanged in one transaction, then evicts the cache")
    void updateBranding() {
        // when
        BrandingDetails updated = this.service.updateBranding(
                new UpdateBranding(" Karachi Eats ", "Fast", "#c62828", "#2e7d32", "Inter", null), 4, "admin-1");

        // then
        assertThat(updated.name()).isEqualTo("Karachi Eats");
        assertThat(updated.primaryColor()).isEqualTo("#C62828");
        assertThat(updated.version()).isEqualTo(5);
        assertThat(updated.updatedAt()).isEqualTo(NOW);
        var order = inOrder(this.brandingRepository, this.outbox, this.cache);
        order.verify(this.brandingRepository).save(this.branding);
        order.verify(this.outbox).append(new SettingsChanged("branding", 5, "admin-1"));
        order.verify(this.cache).evict("branding");
    }

    @Test
    @DisplayName("claims a new logo for the branding and releases the previous one")
    void replacesLogo() {
        this.branding.setLogoMediaId("old-logo");
        given(this.mediaApi.getAsset("new-logo")).willReturn(logo("new-logo"));

        this.service.updateBranding(new UpdateBranding("FoodStack", null, "#D84315", "#00695C", "Roboto", "new-logo"), 4,
                "admin-1");

        verify(this.mediaApi).claim("new-logo", MediaPurpose.BRANDING, SettingsService.LOGO_OWNER);
        verify(this.mediaApi).release("old-logo", SettingsService.LOGO_OWNER);
    }

    @Test
    @DisplayName("does not save when the logo cannot be claimed")
    void logoClaimFails() {
        given(this.mediaApi.claim(eq("pending"), any(), any()))
            .willThrow(new ModuleException.Conflict("Media pending is not ready", "MEDIA_NOT_READY"));

        assertThatThrownBy(() -> this.service.updateBranding(
                new UpdateBranding("FoodStack", null, "#D84315", "#00695C", "Roboto", "pending"), 4, "admin-1"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("MEDIA_NOT_READY"));
        verify(this.brandingRepository, never()).save(any());
    }

    @Test
    @DisplayName("refuses an edit based on an older version (lost update)")
    void versionConflict() {
        assertThatThrownBy(() -> this.service.updateBranding(
                new UpdateBranding("X", null, "#D84315", "#00695C", "Roboto", null), 3, "admin-1"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> {
                assertThat(ex.code()).isEqualTo("SETTINGS_VERSION_CONFLICT");
                assertThat(ex.details()).containsEntry("currentVersion", 4L);
            });
    }

    @Test
    @DisplayName("lists every invalid branding field")
    void invalidBranding() {
        assertThatThrownBy(() -> this.service.updateBranding(
                new UpdateBranding("", null, "red", "#00695C", "Roboto; } body {", null), 4, "admin-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> {
                assertThat(ex.code()).isEqualTo("SETTINGS_INVALID");
                assertThat(errors(ex)).containsKeys("name", "primaryColor", "fontFamily");
            });
    }

    @Test
    @DisplayName("normalizes languages with the default first and validates currency and timezone")
    void updateLocale() {
        LocaleDocument locale = new LocaleDocument();
        locale.setVersion(0L);
        given(this.localeRepository.findById(LocaleDocument.ID)).willReturn(Optional.of(locale));
        given(this.localeRepository.save(any())).willAnswer((invocation) -> invocation.getArgument(0));

        LocaleDetails updated = this.service.updateLocale(new UpdateLocale("ur", List.of("en", "ar", "ur"), "pkr", "Asia/Karachi"),
                0, "admin-1");

        assertThat(updated.languages()).containsExactly("ur", "en", "ar");
        assertThat(updated.currency()).isEqualTo("PKR");
        verify(this.outbox).append(any(SettingsChanged.class));
        verify(this.cache).evict("locale");
    }

    @Test
    @DisplayName("rejects unknown currencies, zones and a default language that is not enabled")
    void invalidLocale() {
        assertThatThrownBy(() -> this.service.updateLocale(new UpdateLocale("fr", List.of("en"), "XXXX", "Mars/Base"), 0, "a"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class,
                    (ex) -> assertThat(errors(ex)).containsKeys("defaultLanguage", "currency",
                            "timezone"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> errors(ModuleException ex) {
        return (Map<String, String>) ex.details().get("errors");
    }

    private static MediaAssetView logo(String id) {
        return new MediaAssetView(id, MediaPurpose.BRANDING, MediaStatus.READY,
                Map.of("thumb", new MediaVariantView("https://cdn/" + id + "/thumb.webp", 160, 80, 99)), null, 0);
    }
}

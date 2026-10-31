package io.github.ilyaslabs.foodstack.settings.impl;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.settings.api.BrandingView;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.core.application.BrandingDetails;
import io.github.ilyaslabs.foodstack.settings.core.application.LocaleDetails;
import io.github.ilyaslabs.foodstack.settings.core.application.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SettingsApiImplTest {

    private final SettingsService service = mock(SettingsService.class);

    private final SettingsApiImpl api = new SettingsApiImpl(this.service);

    @Test
    @DisplayName("maps branding to the API record with epoch seconds")
    void branding() {
        given(this.service.getBranding()).willReturn(new BrandingDetails("FoodStack", "t", "#111111", "#222222", "Roboto",
                "logo-1", Map.of("thumb", "u"), 3, Instant.ofEpochSecond(1_800_000_000L)));

        assertThat(this.api.getBranding()).isEqualTo(new BrandingView("FoodStack", "t", "#111111", "#222222", "Roboto",
                "logo-1", Map.of("thumb", "u"), 3, 1_800_000_000L));
    }

    @Test
    @DisplayName("maps never-changed branding to a null updatedAt")
    void neverChanged() {
        given(this.service.getBranding()).willReturn(new BrandingDetails("FoodStack", null, "#111111", "#222222", "Roboto",
                null, Map.of(), 0, null));

        assertThat(this.api.getBranding().updatedAt()).isNull();
    }

    @Test
    @DisplayName("maps locale to the API record")
    void locale() {
        given(this.service.getLocale()).willReturn(new LocaleDetails("en", List.of("en", "ur"), "PKR", "Asia/Karachi", 2, null));

        assertThat(this.api.getLocale()).isEqualTo(new LocaleView("en", List.of("en", "ur"), "PKR", "Asia/Karachi", 2));
    }
}

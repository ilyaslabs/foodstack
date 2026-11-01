package io.github.ilyaslabs.foodstack.media.impl;

import java.time.Instant;
import java.util.Map;

import io.github.ilyaslabs.foodstack.media.api.MediaAssetView;
import io.github.ilyaslabs.foodstack.media.api.MediaOwner;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaStatus;
import io.github.ilyaslabs.foodstack.media.api.MediaVariantView;
import io.github.ilyaslabs.foodstack.media.core.application.MediaAssetDetails;
import io.github.ilyaslabs.foodstack.media.core.application.MediaService;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MediaApiImplTest {

    private final MediaService service = mock(MediaService.class);

    private final MediaApiImpl api = new MediaApiImpl(this.service);

    @org.junit.jupiter.api.BeforeEach
    void signIn() {
        callerWith("admin", "settings:manage");
    }

    @org.junit.jupiter.api.AfterEach
    void signOut() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    /** Signs in a caller with these permissions, as the forwarded token would. */
    private static void callerWith(String subject, String... permissions) {
        org.springframework.security.oauth2.jwt.Jwt jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t")
            .header("alg", "ES256")
            .subject(subject)
            .claim("perms", java.util.List.of(permissions))
            .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(new io.github.ilyaslabs.foodstack.platform.security.FoodstackJwtAuthenticationConverter().convert(jwt));
    }

    @Test
    @DisplayName("maps a core asset to the API record with epoch-second timestamps")
    void getAsset() {
        given(this.service.get("m1")).willReturn(details("settings", "branding"));

        MediaAssetView view = this.api.getAsset("m1");

        assertThat(view).isEqualTo(new MediaAssetView("m1", MediaPurpose.BRANDING, MediaStatus.READY,
                Map.of("thumb", new MediaVariantView("https://cdn/t.webp", 160, 80, 99)), new MediaOwner("settings", "branding"),
                1_761_732_000L));
    }

    @Test
    @DisplayName("passes claims and releases through with the owner")
    void claimAndRelease() {
        given(this.service.claim("m1", MediaPurpose.BRANDING, "settings", "branding")).willReturn(details("settings", "branding"));

        assertThat(this.api.claim("m1", MediaPurpose.BRANDING, new MediaOwner("settings", "branding")).owner())
            .isEqualTo(new MediaOwner("settings", "branding"));
        this.api.release("m1", new MediaOwner("settings", "branding"));
        verify(this.service).release("m1", "settings", "branding");
    }

    @Test
    @DisplayName("lets module errors through unchanged, so callers see the same code locally and remotely")
    void errors() {
        given(this.service.get("x")).willThrow(new ModuleException.NotFound("Media x not found", "MEDIA_NOT_FOUND"));

        assertThatThrownBy(() -> this.api.getAsset("x"))
            .isInstanceOfSatisfying(ModuleException.NotFound.class, (ex) -> assertThat(ex.code()).isEqualTo("MEDIA_NOT_FOUND"));
    }

    @Test
    @DisplayName("maps an unclaimed asset to a null owner")
    void unclaimed() {
        given(this.service.get("m1")).willReturn(details(null, null));

        assertThat(this.api.getAsset("m1").owner()).isNull();
    }

    @Test
    @DisplayName("customers cannot claim or release images, but anyone may read one (TDD 9.1)")
    void permissions() {
        callerWith("customer");
        assertThatThrownBy(() -> this.api.claim("m1", MediaPurpose.BRANDING, new MediaOwner("settings", "branding")))
            .isInstanceOf(ModuleException.Forbidden.class);
        assertThatThrownBy(() -> this.api.release("m1", new MediaOwner("settings", "branding")))
            .isInstanceOf(ModuleException.Forbidden.class);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        given(this.service.get("m1")).willReturn(details("settings", "branding"));
        assertThat(this.api.getAsset("m1").id()).isEqualTo("m1");
    }

    private static MediaAssetDetails details(String module, String entity) {
        return new MediaAssetDetails("m1", MediaPurpose.BRANDING, MediaStatus.READY,
                Map.of("thumb", new MediaAssetDetails.Variant("https://cdn/t.webp", 160, 80, 99)), module, entity,
                Instant.ofEpochSecond(1_761_732_000L));
    }
}

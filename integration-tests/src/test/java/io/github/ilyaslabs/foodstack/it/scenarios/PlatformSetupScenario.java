package io.github.ilyaslabs.foodstack.it.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.it.support.ApiClient;
import io.github.ilyaslabs.foodstack.it.support.BothTopologies;
import io.github.ilyaslabs.foodstack.it.support.FoodstackPlatform;
import io.github.ilyaslabs.foodstack.it.support.PlatformEvents;
import io.github.ilyaslabs.foodstack.it.support.Storage;
import io.github.ilyaslabs.foodstack.it.support.TestImages;
import io.github.ilyaslabs.foodstack.it.support.Topology;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M0 journey: a fresh install, the administrator signs in, brands the platform with a logo, and
 * the public sees it; a customer can sign up but not change settings; revoked tokens stop working
 * on every module at once.
 */
@BothTopologies
class PlatformSetupScenario {

    private final FoodstackPlatform platform;

    PlatformSetupScenario(Topology topology) {
        this.platform = FoodstackPlatform.of(topology);
    }

    @Test
    @DisplayName("the administrator signs in, uploads a logo and rebrands the platform; everyone sees the new branding")
    void administratorBrandsThePlatform() {
        // given
        ApiClient admin = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);
        assertThat(admin.get("/api/v1/me").then().statusCode(200).extract().<List<String>>path("roles")).contains("ADMIN");

        // when: the logo goes straight to storage with the upload ticket, then is processed
        byte[] logo = TestImages.png(640, 640);
        Response ticket = admin.postJson("/api/v1/media/uploads",
                Map.of("purpose", "BRANDING", "contentType", "image/png", "sizeBytes", logo.length));
        ticket.then().statusCode(201);
        String mediaId = ticket.path("media.id");
        assertThat(Storage.put(ticket.path("uploadUrl"), ticket.path("headers"), logo)).isEqualTo(200);
        admin.request("/api/v1/media/uploads/" + mediaId + "/complete").post("/api/v1/media/uploads/" + mediaId + "/complete")
            .then().log().ifValidationFails().statusCode(200);
        assertThat(admin.get("/api/v1/media/" + mediaId).<String>path("status")).isEqualTo("READY");

        String etag = this.platform.client().get("/api/v1/settings/branding").header("ETag");
        Response updated = admin.request("/api/v1/settings/branding").header("If-Match", etag).contentType(ContentType.JSON)
            .body(Map.of("name", "Spice Route", "tagline", "Dinner from around the corner", "primaryColor", "#6A1B9A",
                    "secondaryColor", "#00838F", "fontFamily", "Roboto", "logoMediaId", mediaId))
            .put("/api/v1/settings/branding");

        // then
        updated.then().statusCode(200);
        Response branding = this.platform.client().get("/api/v1/settings/branding");
        branding.then().statusCode(200);
        assertThat(branding.<String>path("name")).isEqualTo("Spice Route");
        assertThat(branding.<String>path("logo.mediaId")).isEqualTo(mediaId);
        String logoUrl = branding.path("logo.urls.card");
        assertThat(RestAssured.get(logoUrl).then().statusCode(200).extract().contentType()).isEqualTo("image/webp");
        assertThat(this.platform.client().request("/api/v1/settings/branding").header("If-None-Match", branding.header("ETag"))
            .get("/api/v1/settings/branding").statusCode()).isEqualTo(304);

        assertThat(PlatformEvents.awaitEvent(this.platform, "media", "foodstack.media.MediaReady.v1", mediaId)).isNotBlank();
        assertThat(PlatformEvents.awaitEvent(this.platform, "settings", "foodstack.settings.SettingsChanged.v1", "branding"))
            .isNotBlank();
    }

    @Test
    @DisplayName("a customer signs up and signs in, but cannot change the platform settings")
    void customerCannotChangeSettings() {
        // given
        String email = "customer-" + System.nanoTime() + "@it.foodstack.local";
        this.platform.client().postJson("/api/v1/auth/register",
                Map.of("email", email, "password", "customer-password-1", "displayName", "Sam Customer"))
            .then().statusCode(201);

        // when
        ApiClient customer = this.platform.client().logIn(email, "customer-password-1");
        String etag = this.platform.client().get("/api/v1/settings/branding").header("ETag");
        Response attempt = customer.request("/api/v1/settings/branding").header("If-Match", etag).contentType(ContentType.JSON)
            .body(Map.of("name", "Not allowed", "primaryColor", "#000000", "secondaryColor", "#000000", "fontFamily", "Roboto"))
            .put("/api/v1/settings/branding");

        // then
        assertThat(customer.get("/api/v1/me").<List<String>>path("roles")).containsExactly("CUSTOMER");
        attempt.then().statusCode(403);
        assertThat(customer.postJson("/api/v1/media/uploads",
                Map.of("purpose", "BRANDING", "contentType", "image/png", "sizeBytes", 100)).statusCode()).isEqualTo(403);
        String userId = customer.get("/api/v1/me").path("id");
        assertThat(PlatformEvents.awaitEvent(this.platform, "identity", "foodstack.identity.UserRegistered.v1", userId))
            .contains("CUSTOMER");
    }

    @Test
    @DisplayName("after the user revokes their tokens, the old token is refused by every module")
    void revokedTokensAreRefusedEverywhere() {
        // given
        ApiClient admin = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);
        ApiClient oldToken = admin.withToken(admin.accessToken());
        assertThat(oldToken.get("/api/v1/users").statusCode()).isEqualTo(200);

        // when
        admin.request("/api/v1/me/revoke-tokens").post("/api/v1/me/revoke-tokens").then().statusCode(204);

        // then: identity, settings and media all refuse it (each module checks the token version itself)
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(oldToken.get("/api/v1/me").statusCode()).isEqualTo(401);
            assertThat(oldToken.request("/api/v1/settings/branding").header("If-Match", "\"branding-1\"")
                .contentType(ContentType.JSON).body(Map.of()).put("/api/v1/settings/branding").statusCode()).isEqualTo(401);
            assertThat(oldToken.postJson("/api/v1/media/uploads",
                    Map.of("purpose", "BRANDING", "contentType", "image/png", "sizeBytes", 100)).statusCode()).isEqualTo(401);
        });
        assertThat(PlatformEvents.awaitEvent(this.platform, "identity", "foodstack.identity.UserTokensRevoked.v1", "")).isNotBlank();

        // and a fresh sign-in works again
        ApiClient again = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);
        assertThat(again.get("/api/v1/users").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("signing out ends the identity session, so the next sign-in asks for the password again")
    void signOutEndsTheSession() {
        // given
        ApiClient admin = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);
        assertThat(admin.hasIdentitySession()).isTrue();

        // when
        Response logout = admin.logOut(FoodstackPlatform.BACKOFFICE_POST_LOGOUT);

        // then
        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(logout.header("Location")).isEqualTo(FoodstackPlatform.BACKOFFICE_POST_LOGOUT);
        assertThat(admin.hasIdentitySession()).isFalse();
    }
}

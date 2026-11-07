package io.github.ilyaslabs.foodstack.it.scenarios;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.core.security.Totp;
import io.github.ilyaslabs.foodstack.it.support.ApiClient;
import io.github.ilyaslabs.foodstack.it.support.BothTopologies;
import io.github.ilyaslabs.foodstack.it.support.FoodstackPlatform;
import io.github.ilyaslabs.foodstack.it.support.SocialProviderStub;
import io.github.ilyaslabs.foodstack.it.support.Topology;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 sign-in hardening (PRD ID-2, ID-5): a finance user must set up an authenticator app at the
 * first sign-in, then signs in with codes or a recovery code; an administrator removes it after a
 * lost phone. A customer signs in with Google, and the same Google account finds the same customer.
 */
@BothTopologies
class TwoStepAndSocialScenario {

    private final FoodstackPlatform platform;

    TwoStepAndSocialScenario(Topology topology) {
        this.platform = FoodstackPlatform.of(topology);
    }

    @Test
    @DisplayName("a finance user sets up an authenticator app, signs in with codes, and an admin resets it")
    void twoStepSignIn() {
        ApiClient admin = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);
        String email = "farah-" + System.nanoTime() + "@it.foodstack.local";
        String userId = admin.postJson("/api/v1/users", Map.of("email", email, "password", "finance-password-1",
                "displayName", "Farah Finance", "roles", Set.of("FINANCE")))
            .then().log().ifValidationFails().statusCode(201).extract().path("id");

        // when: the first sign-in proves the password; FINANCE must set up an app first
        Response first = login(email);
        assertThat(first.<String>path("next")).isEqualTo("TOTP_ENROLL");
        assertThat(first.<String>path("accessToken")).isNull();
        String challenge = first.path("challenge");
        Response enroll = post("/api/v1/auth/two-step/enroll", Map.of("challenge", challenge));
        enroll.then().log().ifValidationFails().statusCode(200);
        String secret = enroll.path("secret");
        assertThat(enroll.<String>path("uri")).startsWith("otpauth://totp/").contains("secret=" + secret);
        Response wrong = post("/api/v1/auth/two-step/confirm", Map.of("challenge", challenge, "code", "000000"));
        assertThat(wrong.statusCode()).isEqualTo(401);
        Response confirmed = post("/api/v1/auth/two-step/confirm",
                Map.of("challenge", challenge, "code", Totp.code(secret, Instant.now())));

        // then: signed in, with ten recovery codes shown once
        confirmed.then().log().ifValidationFails().statusCode(200);
        List<String> recovery = confirmed.path("recoveryCodes");
        assertThat(recovery).hasSize(10);
        ApiClient finance = this.platform.client().withToken(confirmed.path("accessToken"));
        finance.get("/api/v1/me/two-step").then().statusCode(200);
        assertThat(finance.get("/api/v1/me/two-step").<Boolean>path("enabled")).isTrue();
        assertThat(finance.get("/api/v1/me/two-step").<Boolean>path("required")).isTrue();

        // when: she signs in again; the code from the app was just used, so a recovery code it is
        Response second = login(email);
        assertThat(second.<String>path("next")).isEqualTo("TOTP");
        assertThat(post("/api/v1/auth/two-step", Map.of("challenge", second.path("challenge"),
                "code", Totp.code(secret, Instant.now().minusSeconds(30)))).statusCode()).isEqualTo(401);
        Response withRecovery = post("/api/v1/auth/two-step", Map.of("challenge", second.path("challenge"), "code", recovery.get(0)));
        withRecovery.then().log().ifValidationFails().statusCode(200);
        assertThat(withRecovery.<String>path("accessToken")).isNotBlank();
        assertThat(post("/api/v1/auth/two-step", Map.of("challenge", second.path("challenge"), "code", recovery.get(0)))
            .statusCode()).as("a recovery code works once").isEqualTo(401);
        assertThat(finance.postJson("/api/v1/me/two-step/disable", Map.of("code", recovery.get(1))).statusCode())
            .as("FINANCE cannot turn it off").isEqualTo(409);

        // when: she lost her phone; an administrator removes two-step sign-in
        admin.request("/api/v1/users/" + userId + "/two-step").delete("/api/v1/users/" + userId + "/two-step")
            .then().log().ifValidationFails().statusCode(204);

        // then: her sign-ins end, and the next one sets up a new app
        finance.get("/api/v1/me").then().statusCode(401);
        assertThat(login(email).<String>path("next")).isEqualTo("TOTP_ENROLL");
    }

    @Test
    @DisplayName("a customer signs in with Google once the operator switched it on")
    void googleSignIn() {
        ApiClient admin = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);
        String clientId = "it-web.apps.googleusercontent.com";
        String email = "gul-" + System.nanoTime() + "@it.foodstack.local";
        String subject = String.valueOf(System.nanoTime());
        SocialProviderStub google = SocialProviderStub.get();

        // given: Google is off at first
        assertThat(social(google.idToken(clientId, subject, email, "Gul Customer")).statusCode()).isEqualTo(403);
        admin.request("/api/v1/auth/providers").contentType("application/json")
            .body(Map.of("googleClientIds", List.of(clientId), "appleClientIds", List.of()))
            .put("/api/v1/auth/providers")
            .then().log().ifValidationFails().statusCode(200);
        assertThat(this.platform.client().get("/api/v1/auth/providers").<List<String>>path("googleClientIds")).containsExactly(clientId);

        // when: she signs in with Google twice
        Response first = social(google.idToken(clientId, subject, email, "Gul Customer"));
        first.then().log().ifValidationFails().statusCode(200);
        ApiClient customer = this.platform.client().withToken(first.path("accessToken"));
        Response me = customer.get("/api/v1/me");
        me.then().statusCode(200);
        Response second = social(google.idToken(clientId, subject, email, "Gul Customer"));

        // then: one customer account with her Google name; a token for another app is refused
        assertThat(me.<String>path("displayName")).isEqualTo("Gul Customer");
        assertThat(me.<List<String>>path("roles")).containsExactly("CUSTOMER");
        assertThat(this.platform.client().withToken(second.path("accessToken")).get("/api/v1/me").<String>path("id"))
            .isEqualTo(me.<String>path("id"));
        assertThat(social(google.idToken("someone-else.apps", subject, email, "Gul")).statusCode()).isEqualTo(401);
    }

    private Response login(String email) {
        Response response = post("/api/v1/auth/login", Map.of("client", "mobile", "email", email, "password", "finance-password-1"));
        response.then().log().ifValidationFails().statusCode(200);
        return response;
    }

    private Response social(String idToken) {
        return post("/api/v1/auth/social", Map.of("client", "mobile", "provider", "google", "idToken", idToken));
    }

    private Response post(String path, Map<String, ?> body) {
        return this.platform.client().postJson(path, body);
    }
}

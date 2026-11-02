package io.github.ilyaslabs.foodstack.it.scenarios;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.ilyaslabs.foodstack.it.support.ApiClient;
import io.github.ilyaslabs.foodstack.it.support.BothTopologies;
import io.github.ilyaslabs.foodstack.it.support.FoodstackPlatform;
import io.github.ilyaslabs.foodstack.it.support.Mailbox;
import io.github.ilyaslabs.foodstack.it.support.Topology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2 sign-in by email (PRD ID-1, TDD 9.1): a customer signs in with a code from their inbox, then
 * forgets their password and sets a new one with a reset code. The reset ends every old token.
 */
@BothTopologies
class AccountRecoveryScenario {

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    private final FoodstackPlatform platform;

    AccountRecoveryScenario(Topology topology) {
        this.platform = FoodstackPlatform.of(topology);
    }

    @Test
    @DisplayName("a customer signs in with an emailed code, and resets a forgotten password")
    void signInWithCodeAndResetPassword() {
        String email = "nadia-" + System.nanoTime() + "@it.foodstack.local";
        this.platform.client().postJson("/api/v1/auth/register",
                Map.of("email", email, "password", "first-password-1", "displayName", "Nadia Customer"))
            .then().log().ifValidationFails().statusCode(201);
        ApiClient withPassword = this.platform.client().logIn(email, "first-password-1");

        // when: she asks for a sign-in code and types the one from her email
        ApiClient withCode = this.platform.client().logInWithEmailCode(email, () -> code(email, "sign-in code"));
        withCode.get("/api/v1/me").then().log().ifValidationFails().statusCode(200);

        // when: she forgets her password and chooses a new one with a reset code
        ApiClient afterReset = this.platform.client().resetPassword(email, "second-password-2",
                () -> code(email, "password reset code"));
        afterReset.get("/api/v1/me").then().log().ifValidationFails().statusCode(200);

        // then: tokens from before the reset stop working, and only the new password signs in
        withPassword.get("/api/v1/me").then().log().ifValidationFails().statusCode(401);
        this.platform.client().logIn(email, "second-password-2");
        assertThatThrownBy(() -> this.platform.client().logIn(email, "first-password-1")).isInstanceOf(AssertionError.class);
    }

    private static String code(String email, String subject) {
        Matcher matcher = CODE.matcher(Mailbox.awaitEmail(email, subject));
        assertThat(matcher.find()).as("a 6-digit code in the email").isTrue();
        return matcher.group(1);
    }
}

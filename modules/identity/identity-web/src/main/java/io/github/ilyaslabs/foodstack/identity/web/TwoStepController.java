package io.github.ilyaslabs.foodstack.identity.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.identity.core.application.TwoFactorService;
import io.github.ilyaslabs.foodstack.identity.web.dto.CodeOnlyRequest;
import io.github.ilyaslabs.foodstack.identity.web.dto.EnrollmentResponse;
import io.github.ilyaslabs.foodstack.identity.web.dto.RecoveryCodesResponse;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two-step sign-in from the account page (PRD ID-5): turn it on with an authenticator app, new
 * recovery codes, turn it off (where the role allows). An administrator can remove it from an
 * account whose owner lost the phone and the codes.
 */
@RestController
class TwoStepController {

    private final TwoFactorService twoFactor;

    TwoStepController(TwoFactorService twoFactor) {
        this.twoFactor = twoFactor;
    }

    @Flow(value = "two-step-status", label = "Two-step sign-in status", description = "For the signed-in user")
    @EVL.Endpoint(method = "GET", path = "/api/v1/me/two-step", caller = "Back office")
    @GetMapping("/api/v1/me/two-step")
    TwoFactorService.Status status() {
        return this.twoFactor.status(CurrentUser.id());
    }

    /** A new secret for the app; nothing changes until a code from it confirms it. */
    @Flow(value = "two-step-enroll", label = "Set up an authenticator app", description = "Nothing changes until a code confirms it")
    @EVL.Endpoint(method = "POST", path = "/api/v1/me/two-step/enroll", caller = "Back office")
    @PostMapping("/api/v1/me/two-step/enroll")
    EnrollmentResponse enroll() {
        TwoFactorService.Enrollment enrollment = this.twoFactor.startEnrollment(CurrentUser.id());
        return new EnrollmentResponse(enrollment.secret(), enrollment.uri());
    }

    @Flow(value = "two-step-confirm", label = "Confirm the authenticator app", description = "Turns two-step on; recovery codes shown once")
    @EVL.Endpoint(method = "POST", path = "/api/v1/me/two-step/confirm", caller = "Back office")
    @PostMapping("/api/v1/me/two-step/confirm")
    RecoveryCodesResponse confirm(@Valid @RequestBody CodeOnlyRequest request) {
        return new RecoveryCodesResponse(null, null, null, null, null,
                this.twoFactor.confirmEnrollment(CurrentUser.id(), request.code()));
    }

    @Flow(value = "two-step-recovery-codes", label = "New recovery codes", description = "After a current code")
    @EVL.Endpoint(method = "POST", path = "/api/v1/me/two-step/recovery-codes", caller = "Back office")
    @PostMapping("/api/v1/me/two-step/recovery-codes")
    RecoveryCodesResponse recoveryCodes(@Valid @RequestBody CodeOnlyRequest request) {
        return new RecoveryCodesResponse(null, null, null, null, null,
                this.twoFactor.regenerateRecoveryCodes(CurrentUser.id(), request.code()));
    }

    /** Turns it off with a current code; refused where the role requires it. */
    @Flow(value = "two-step-disable", label = "Turn two-step sign-in off", description = "With a current code; not where the role requires it")
    @EVL.Endpoint(method = "POST", path = "/api/v1/me/two-step/disable", caller = "Back office")
    @PostMapping("/api/v1/me/two-step/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disable(@Valid @RequestBody CodeOnlyRequest request) {
        this.twoFactor.disable(CurrentUser.id(), request.code());
    }

    /** An administrator removes it from an account; every sign-in of that account ends. */
    @Flow(value = "two-step-reset", label = "Reset someone's two-step sign-in", description = "Lost phone and codes; every sign-in of the account ends")
    @EVL.Endpoint(method = "DELETE", path = "/api/v1/users/{userId}/two-step", caller = "Back office (admin)")
    @DeleteMapping("/api/v1/users/{userId}/two-step")
    @PreAuthorize("hasAuthority('identity:users:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@PathVariable String userId) {
        this.twoFactor.reset(userId);
    }
}

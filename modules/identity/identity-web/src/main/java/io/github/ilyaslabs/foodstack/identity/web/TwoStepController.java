package io.github.ilyaslabs.foodstack.identity.web;

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

    @GetMapping("/api/v1/me/two-step")
    TwoFactorService.Status status() {
        return this.twoFactor.status(CurrentUser.id());
    }

    /** A new secret for the app; nothing changes until a code from it confirms it. */
    @PostMapping("/api/v1/me/two-step/enroll")
    EnrollmentResponse enroll() {
        TwoFactorService.Enrollment enrollment = this.twoFactor.startEnrollment(CurrentUser.id());
        return new EnrollmentResponse(enrollment.secret(), enrollment.uri());
    }

    @PostMapping("/api/v1/me/two-step/confirm")
    RecoveryCodesResponse confirm(@Valid @RequestBody CodeOnlyRequest request) {
        return new RecoveryCodesResponse(null, null, null, null, null,
                this.twoFactor.confirmEnrollment(CurrentUser.id(), request.code()));
    }

    @PostMapping("/api/v1/me/two-step/recovery-codes")
    RecoveryCodesResponse recoveryCodes(@Valid @RequestBody CodeOnlyRequest request) {
        return new RecoveryCodesResponse(null, null, null, null, null,
                this.twoFactor.regenerateRecoveryCodes(CurrentUser.id(), request.code()));
    }

    /** Turns it off with a current code; refused where the role requires it. */
    @PostMapping("/api/v1/me/two-step/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disable(@Valid @RequestBody CodeOnlyRequest request) {
        this.twoFactor.disable(CurrentUser.id(), request.code());
    }

    /** An administrator removes it from an account; every sign-in of that account ends. */
    @DeleteMapping("/api/v1/users/{userId}/two-step")
    @PreAuthorize("hasAuthority('identity:users:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@PathVariable String userId) {
        this.twoFactor.reset(userId);
    }
}

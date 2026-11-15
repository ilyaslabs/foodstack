package io.github.ilyaslabs.foodstack.identity.web;

import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.identity.core.application.AccountDeletion;
import io.github.ilyaslabs.foodstack.identity.core.application.AddressBook;
import io.github.ilyaslabs.foodstack.identity.core.application.UserAccount;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.identity.web.dto.AddressResponse;
import io.github.ilyaslabs.foodstack.identity.web.dto.UserResponse;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user. */
@RestController
@EVL.Endpoint(path = "/api/v1/me")
@RequestMapping("/api/v1/me")
class MeController {

    private final UserService userService;

    private final AddressBook addressBook;

    private final AccountDeletion accountDeletion;

    MeController(UserService userService, AddressBook addressBook, AccountDeletion accountDeletion) {
        this.userService = userService;
        this.addressBook = addressBook;
        this.accountDeletion = accountDeletion;
    }

    @Flow(value = "me-view", label = "My account", description = "The signed-in user")
    @EVL.Endpoint(method = "GET", path = "", caller = "Customer app, back office, rider app")
    @GetMapping
    UserResponse me() {
        return IdentityWebMapper.toResponse(this.userService.get(CurrentUser.id()));
    }

    record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    /** Changes the password; the user proves they know the current one. */
    @Flow(value = "me-password", label = "Change my password", description = "Proves the current one")
    @EVL.Endpoint(method = "PUT", path = "/password", caller = "Customer app, back office, rider app")
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@RequestBody ChangePasswordRequest request) {
        this.userService.changePassword(CurrentUser.id(), request.currentPassword(), request.newPassword());
    }

    /** Logs out everywhere: every token of this user stops working in every module at once. */
    @Flow(value = "me-revoke-tokens", label = "Sign out everywhere", description = "Every token stops working in every module at once")
    @EVL.Endpoint(method = "POST", path = "/revoke-tokens", caller = "Customer app, back office, rider app")
    @PostMapping("/revoke-tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeTokens() {
        this.userService.revokeTokens(CurrentUser.id(), "LOGOUT_EVERYWHERE");
    }

    /**
     * This module's part of the customer's data export (PRD ID-7): the account and saved
     * addresses. The other parts come from {@code /orders/mine/export},
     * {@code /reviews/mine/export} and {@code /notifications/mine/export}.
     */
    record ExportResponse(Account account, List<AddressResponse> addresses) {

        record Account(String id, String email, String displayName, List<String> roles, long createdAt) {
        }
    }

    @Flow(value = "me-export", label = "Export my account", description = "Account and saved addresses (identity's part of the data export)")
    @EVL.Endpoint(method = "GET", path = "/export", caller = "Customer app")
    @GetMapping("/export")
    ExportResponse export() {
        String userId = CurrentUser.id();
        UserAccount user = this.userService.get(userId);
        return new ExportResponse(
                new ExportResponse.Account(user.id(), user.email(), user.displayName(),
                        user.roles().stream().map(Enum::name).sorted().toList(), user.createdAt().getEpochSecond()),
                this.addressBook.list(userId).stream().map(AddressesController::toResponse).toList());
    }

    record DeletionResponse(Long deleteAfter, long graceDays) {
    }

    /** How deletion works here: the grace period, so the account screen can say it before anyone asks. */
    @Flow(value = "me-deletion-view", label = "How account deletion works", description = "The grace period")
    @EVL.Endpoint(method = "GET", path = "/deletion", caller = "Customer app")
    @GetMapping("/deletion")
    DeletionResponse deletion() {
        CurrentUser.id();
        return new DeletionResponse(null, this.accountDeletion.grace().toDays());
    }

    /**
     * Asks to delete the account (PRD ID-7). The customer is signed out everywhere; signing in
     * again before {@code deleteAfter} cancels it.
     */
    @Flow(value = "me-deletion-request", label = "Delete my account", description = "Signed out everywhere; signing in again within the grace period cancels it")
    @EVL.Endpoint(method = "POST", path = "/deletion", caller = "Customer app")
    @PostMapping("/deletion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DeletionResponse requestDeletion() {
        Instant deleteAfter = this.accountDeletion.request(CurrentUser.id());
        return new DeletionResponse(deleteAfter.getEpochSecond(), this.accountDeletion.grace().toDays());
    }
}

package io.github.ilyaslabs.foodstack.identity.web;

import java.time.Instant;
import java.util.List;

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

    @GetMapping
    UserResponse me() {
        return IdentityWebMapper.toResponse(this.userService.get(CurrentUser.id()));
    }

    record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    /** Changes the password; the user proves they know the current one. */
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@RequestBody ChangePasswordRequest request) {
        this.userService.changePassword(CurrentUser.id(), request.currentPassword(), request.newPassword());
    }

    /** Logs out everywhere: every token of this user stops working in every module at once. */
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

    @GetMapping("/export")
    ExportResponse export() {
        String userId = CurrentUser.id();
        UserAccount user = this.userService.get(userId);
        return new ExportResponse(
                new ExportResponse.Account(user.id(), user.email(), user.displayName(),
                        user.roles().stream().map(Enum::name).sorted().toList(), user.createdAt().getEpochSecond()),
                this.addressBook.list(userId).stream().map(AddressesController::toResponse).toList());
    }

    record DeletionResponse(long deleteAfter, long graceDays) {
    }

    /**
     * Asks to delete the account (PRD ID-7). The customer is signed out everywhere; signing in
     * again before {@code deleteAfter} cancels it.
     */
    @PostMapping("/deletion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DeletionResponse requestDeletion() {
        Instant deleteAfter = this.accountDeletion.request(CurrentUser.id());
        return new DeletionResponse(deleteAfter.getEpochSecond(), this.accountDeletion.grace().toDays());
    }
}

package io.github.ilyaslabs.foodstack.identity.web;

import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.identity.web.dto.UserResponse;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user. */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final UserService userService;

    MeController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    UserResponse me() {
        return IdentityWebMapper.toResponse(this.userService.get(CurrentUser.id()));
    }

    /** Logs out everywhere: every token of this user stops working in every module at once. */
    @PostMapping("/revoke-tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeTokens() {
        this.userService.revokeTokens(CurrentUser.id(), "LOGOUT_EVERYWHERE");
    }
}

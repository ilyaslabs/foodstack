package io.github.ilyaslabs.foodstack.identity.web;

import io.github.ilyaslabs.foodstack.identity.core.application.NewUser;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.identity.web.dto.CreateUserRequest;
import io.github.ilyaslabs.foodstack.identity.web.dto.UserListResponse;
import io.github.ilyaslabs.foodstack.identity.web.dto.UserResponse;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Back-office accounts. */
@RestController
@RequestMapping("/api/v1/users")
class UsersController {

    private final UserService userService;

    UsersController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('identity:users:read')")
    UserListResponse list(@RequestParam(defaultValue = "50") int limit) {
        return new UserListResponse(this.userService.listBackOffice(limit).stream().map(IdentityWebMapper::toResponse).toList(),
                null);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('identity:users:manage')")
    UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return IdentityWebMapper.toResponse(this.userService.create(
                new NewUser(request.email(), request.password(), request.displayName(), request.roles()), CurrentUser.id()));
    }
}

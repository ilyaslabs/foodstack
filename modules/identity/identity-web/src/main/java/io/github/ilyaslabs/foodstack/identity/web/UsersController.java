package io.github.ilyaslabs.foodstack.identity.web;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.core.application.NewUser;
import io.github.ilyaslabs.foodstack.identity.core.application.UserDirectory;
import io.github.ilyaslabs.foodstack.identity.web.dto.BlockUserRequest;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.identity.web.dto.CreateUserRequest;
import io.github.ilyaslabs.foodstack.identity.web.dto.UserListResponse;
import io.github.ilyaslabs.foodstack.identity.web.dto.UserResponse;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The operator's Users page: every account, back-office accounts created by admins, and blocking (PRD ID-4). */
@RestController
@RequestMapping("/api/v1/users")
class UsersController {

    private final UserService userService;

    private final UserDirectory directory;

    UsersController(UserService userService, UserDirectory directory) {
        this.userService = userService;
        this.directory = directory;
    }

    /** Newest first; {@code q} is a full email or part of a name. */
    @GetMapping
    @PreAuthorize("hasAuthority('identity:users:read')")
    UserListResponse list(@RequestParam(required = false) String q, @RequestParam(required = false) Role role,
            @RequestParam(required = false) UserStatus status, @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "50") int limit) {
        UserDirectory.UserPage page = this.directory.search(q, role, status, after, limit);
        return new UserListResponse(page.items().stream().map(IdentityWebMapper::toResponse).toList(), page.nextCursor());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('identity:users:read')")
    UserResponse get(@PathVariable String id) {
        return IdentityWebMapper.toResponse(this.userService.get(id));
    }

    @PostMapping("/{id}/block")
    @PreAuthorize("hasAuthority('identity:users:manage')")
    UserResponse block(@PathVariable String id, @Valid @RequestBody BlockUserRequest request) {
        return IdentityWebMapper.toResponse(this.directory.block(id, request.reason(), CurrentUser.id()));
    }

    @PostMapping("/{id}/unblock")
    @PreAuthorize("hasAuthority('identity:users:manage')")
    UserResponse unblock(@PathVariable String id) {
        return IdentityWebMapper.toResponse(this.directory.unblock(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('identity:users:manage')")
    UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return IdentityWebMapper.toResponse(this.userService.create(
                new NewUser(request.email(), request.password(), request.displayName(), request.roles()), CurrentUser.id()));
    }
}

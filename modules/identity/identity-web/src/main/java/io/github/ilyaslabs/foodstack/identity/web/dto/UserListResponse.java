package io.github.ilyaslabs.foodstack.identity.web.dto;

import java.util.List;

/**
 * {@code GET /api/v1/users}.
 *
 * @param items users, newest first
 * @param nextCursor always {@code null} until paging arrives with larger back offices
 */
public record UserListResponse(List<UserResponse> items, String nextCursor) {
}

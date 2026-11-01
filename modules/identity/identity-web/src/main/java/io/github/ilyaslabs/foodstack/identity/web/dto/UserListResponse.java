package io.github.ilyaslabs.foodstack.identity.web.dto;

import java.util.List;

/**
 * {@code GET /api/v1/users}.
 *
 * @param items users, newest first
 * @param nextCursor pass as {@code after} for the next page; {@code null} on the last page
 */
public record UserListResponse(List<UserResponse> items, String nextCursor) {
}

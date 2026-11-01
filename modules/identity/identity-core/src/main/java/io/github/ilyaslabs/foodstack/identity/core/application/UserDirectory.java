package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserDocument;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserRepository;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The operator's Users page (PRD ID-4): every account, customers included, searchable by full email
 * or by part of the name, and blocking. Emails are stored encrypted, so only an exact email is found
 * (through its blind index); names are matched case-insensitively. Listing and searching scatter
 * across shards, which is fine for this back-office screen (TDD 6.3).
 */
public class UserDirectory {

    private static final int MAX_REASON = 500;

    private final UserService users;

    private final UserRepository repository;

    private final MongoTemplate template;

    private final Clock clock;

    public UserDirectory(UserService users, UserRepository repository, MongoTemplate template, Clock clock) {
        this.users = users;
        this.repository = repository;
        this.template = template;
        this.clock = clock;
    }

    /**
     * @param text full email, or part of a name; blank for everyone
     * @param role only users with this role, or {@code null}
     * @param status only users in this state, or {@code null}
     * @param afterId the last id of the previous page, or {@code null}
     * @param limit page size, 1 to 100
     */
    public UserPage search(String text, Role role, UserStatus status, String afterId, int limit) {
        int size = Math.min(Math.max(limit, 1), 100);
        Criteria criteria = new Criteria();
        List<Criteria> and = new java.util.ArrayList<>();
        String clean = (text != null) ? text.trim() : "";
        if (clean.length() > 100) {
            throw new ModuleException.BadRequest("The search text must be at most 100 characters", "USER_SEARCH_INVALID");
        }
        if (clean.contains("@")) {
            String id = this.users.findByEmail(clean).map(UserDocument::getId).orElse(null);
            if (id == null) {
                return new UserPage(List.of(), null);
            }
            and.add(Criteria.where("_id").is(id));
        }
        else if (!clean.isEmpty()) {
            and.add(Criteria.where("displayName").regex(Pattern.quote(clean), "i"));
        }
        if (role != null) {
            and.add(Criteria.where("roles").is(role.name()));
        }
        if (status != null) {
            and.add(Criteria.where("status").is(status.name()));
        }
        if (afterId != null && !afterId.isBlank()) {
            and.add(Criteria.where("_id").lt(afterId));
        }
        if (!and.isEmpty()) {
            criteria.andOperator(and.toArray(Criteria[]::new));
        }
        Query query = Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "_id")).limit(size + 1);
        List<UserDocument> found = this.template.find(query, UserDocument.class);
        boolean more = found.size() > size;
        List<UserDocument> page = more ? found.subList(0, size) : found;
        return new UserPage(page.stream().map(this.users::toAccount).toList(), more ? page.getLast().getId() : null);
    }

    /**
     * Blocks an account: it cannot sign in, gets no new tokens, and every token it holds stops
     * working at once (TDD 9.1). Nobody blocks themselves, and the last active admin stays.
     */
    public UserAccount block(String userId, String reason, String actorId) {
        String why = (reason != null) ? reason.trim() : "";
        if (why.isEmpty() || why.length() > MAX_REASON) {
            throw new ModuleException.BadRequest("Give a reason of up to " + MAX_REASON + " characters", "BLOCK_REASON_REQUIRED");
        }
        if (userId.equals(actorId)) {
            throw new ModuleException.Conflict("You cannot block your own account", "CANNOT_BLOCK_SELF");
        }
        UserDocument user = find(userId);
        if (user.getStatus() == UserStatus.BLOCKED) {
            return this.users.toAccount(user);
        }
        if (user.getRoles().contains(Role.ADMIN) && activeAdmins() <= 1) {
            throw new ModuleException.Conflict("The last active admin cannot be blocked", "LAST_ADMIN");
        }
        Instant now = this.clock.instant();
        user.setStatus(UserStatus.BLOCKED);
        user.setBlockedBy(actorId);
        user.setBlockedAt(now);
        user.setBlockedReason(why);
        user.setUpdatedAt(now);
        this.repository.save(user);
        this.users.revokeTokens(userId, "BLOCKED");
        return this.users.get(userId);
    }

    /** Lets a blocked account sign in again. */
    public UserAccount unblock(String userId) {
        UserDocument user = find(userId);
        if (user.getStatus() == UserStatus.ACTIVE) {
            return this.users.toAccount(user);
        }
        user.setStatus(UserStatus.ACTIVE);
        user.setBlockedBy(null);
        user.setBlockedAt(null);
        user.setBlockedReason(null);
        user.setUpdatedAt(this.clock.instant());
        return this.users.toAccount(this.repository.save(user));
    }

    private long activeAdmins() {
        return this.template.count(Query.query(Criteria.where("roles").is(Role.ADMIN.name())
            .and("status").is(UserStatus.ACTIVE.name())), UserDocument.class);
    }

    private UserDocument find(String userId) {
        return this.repository.findById(userId)
            .orElseThrow(() -> new ModuleException.NotFound("User " + userId + " not found", "USER_NOT_FOUND"));
    }

    /**
     * @param items users, newest first
     * @param nextCursor pass as {@code afterId} for the next page; {@code null} on the last page
     */
    public record UserPage(List<UserAccount> items, String nextCursor) {
    }
}

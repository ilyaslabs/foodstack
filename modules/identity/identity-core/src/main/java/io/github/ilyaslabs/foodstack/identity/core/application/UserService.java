package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.api.events.UserRegistered;
import io.github.ilyaslabs.foodstack.identity.api.events.UserTokensRevoked;
import io.github.ilyaslabs.foodstack.identity.core.domain.Permissions;
import io.github.ilyaslabs.foodstack.identity.core.persistence.LoginKeyDocument;
import io.github.ilyaslabs.foodstack.identity.core.persistence.LoginKeyRepository;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserDocument;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserRepository;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.TokenRevocations;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Users: sign-up, back-office accounts, lookups for login and tokens, and revocation of all of a
 * user's tokens.
 */
public class UserService {

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,63}");

    private static final int MIN_PASSWORD = 10;

    private static final int MAX_PASSWORD = 128;

    private final UserRepository users;

    private final LoginKeyRepository loginKeys;

    private final PiiCipher pii;

    private final PasswordEncoder passwordEncoder;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final TokenRevocations revocations;

    private final Clock clock;

    public UserService(UserRepository users, LoginKeyRepository loginKeys, PiiCipher pii, PasswordEncoder passwordEncoder,
            OutboxWriter outbox, TransactionTemplate transaction, TokenRevocations revocations, Clock clock) {
        this.users = users;
        this.loginKeys = loginKeys;
        this.pii = pii;
        this.passwordEncoder = passwordEncoder;
        this.outbox = outbox;
        this.transaction = transaction;
        this.revocations = revocations;
        this.clock = clock;
    }

    /** A customer signs up. */
    public UserAccount register(String email, String password, String displayName) {
        return create(new NewUser(email, password, displayName, EnumSet.of(Role.CUSTOMER)), null);
    }

    /** Creates a user with any roles; {@code createdBy} {@code null} means self sign-up. */
    public UserAccount create(NewUser user, String createdBy) {
        validate(user);
        Instant now = this.clock.instant();
        String id = Ids.newId();
        UserDocument document = new UserDocument();
        document.setId(id);
        document.setEmailCipher(this.pii.encrypt(user.email().trim()));
        document.setEmailIndex(this.pii.blindIndex(user.email()));
        document.setPasswordHash(this.passwordEncoder.encode(user.password()));
        document.setDisplayName(user.displayName().trim());
        document.setRoles(EnumSet.copyOf(user.roles()));
        document.setStatus(UserStatus.ACTIVE);
        document.setTokenVersion(1);
        document.setCreatedBy((createdBy != null) ? createdBy : id);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        try {
            UserDocument saved = this.transaction.execute((status) -> {
                this.loginKeys.insert(new LoginKeyDocument(Ids.newId(), document.getEmailIndex(), id));
                UserDocument result = this.users.insert(document);
                this.outbox.append(new UserRegistered(id, Set.copyOf(result.getRoles()), result.getCreatedBy()));
                return result;
            });
            return toAccount(saved);
        }
        catch (DuplicateKeyException ex) {
            throw new ModuleException.Conflict("An account with this email already exists", "EMAIL_TAKEN");
        }
    }

    public UserAccount get(String userId) {
        return toAccount(find(userId));
    }

    /** The user that logs in with {@code email}, if any. */
    public Optional<UserDocument> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return this.loginKeys.findByLoginKey(this.pii.blindIndex(email))
            .flatMap((key) -> this.users.findById(key.getUserId()));
    }

    public boolean emailExists(String email) {
        return findByEmail(email).isPresent();
    }

    /** Back-office users, newest first. */
    public List<UserAccount> listBackOffice(int limit) {
        List<Role> roles = List.of(Role.ADMIN, Role.FINANCE, Role.SUPPORT);
        return this.users.findByRolesInOrderByIdDesc(roles, Limit.of(Math.min(Math.max(limit, 1), 200)))
            .stream()
            .map(this::toAccount)
            .toList();
    }

    /**
     * Invalidates every token the user holds, in every module, at once (TDD 9.1).
     * @return the new minimum token version
     */
    public long revokeTokens(String userId, String reason) {
        UserDocument user = find(userId);
        long next = user.getTokenVersion() + 1;
        user.setTokenVersion(next);
        user.setUpdatedAt(this.clock.instant());
        this.transaction.executeWithoutResult((status) -> {
            this.users.save(user);
            this.outbox.append(new UserTokensRevoked(userId, next, reason));
        });
        this.revocations.revokeBefore(userId, next);
        return next;
    }

    /** Claims for a new access token: roles, permissions and the current token version. */
    public TokenClaims tokenClaims(String userId) {
        UserDocument user = find(userId);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ModuleException.Forbidden("The account is blocked", "USER_BLOCKED");
        }
        return new TokenClaims(Set.copyOf(user.getRoles()), Permissions.of(user.getRoles()), user.getTokenVersion());
    }

    /**
     * Claims of an access token.
     *
     * @param roles granted roles
     * @param permissions derived permissions
     * @param tokenVersion current token version
     */
    public record TokenClaims(Set<Role> roles, Set<String> permissions, long tokenVersion) {
    }

    private UserDocument find(String userId) {
        return this.users.findById(userId)
            .orElseThrow(() -> new ModuleException.NotFound("User " + userId + " not found", "USER_NOT_FOUND"));
    }

    private UserAccount toAccount(UserDocument user) {
        return new UserAccount(user.getId(), this.pii.decrypt(user.getEmailCipher()), user.getDisplayName(),
                Set.copyOf(user.getRoles()), Permissions.of(user.getRoles()), user.getStatus(), user.getCreatedAt());
    }

    private static void validate(NewUser user) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (user.email() == null || !EMAIL.matcher(user.email().trim()).matches()) {
            errors.put("email", "must be an email address");
        }
        if (user.password() == null || user.password().length() < MIN_PASSWORD || user.password().length() > MAX_PASSWORD) {
            errors.put("password", "must be " + MIN_PASSWORD + " to " + MAX_PASSWORD + " characters");
        }
        if (user.displayName() == null || user.displayName().isBlank() || user.displayName().trim().length() > 80) {
            errors.put("displayName", "must be 1 to 80 characters");
        }
        if (user.roles() == null || user.roles().isEmpty()) {
            errors.put("roles", "must contain at least one role");
        }
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("The user is invalid", "USER_INVALID", Map.of("errors", errors));
        }
    }
}

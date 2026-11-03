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
        List<Role> roles = List.of(Role.ADMIN, Role.FINANCE, Role.SUPPORT, Role.STORE_OWNER, Role.STORE_STAFF, Role.RIDER,
                Role.STORE_RIDER);
        return this.users.findByRolesInOrderByIdDesc(roles, Limit.of(Math.min(Math.max(limit, 1), 200)))
            .stream()
            .map(this::toAccount)
            .toList();
    }

    /** Active users holding any of these roles, for staff alerts (at most 500). */
    public List<String> activeUserIdsWithRoles(Set<Role> roles) {
        return this.users.findByRolesInOrderByIdDesc(List.copyOf(roles), Limit.of(500))
            .stream()
            .filter((user) -> user.getStatus() == null || user.getStatus() == io.github.ilyaslabs.foodstack.identity.api.UserStatus.ACTIVE)
            .map(UserDocument::getId)
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

    /**
     * Sets a new password after an emailed reset code (TDD 9.1). Every token the user held stops
     * working, in case someone else had the account.
     */
    public void resetPassword(String userId, String newPassword) {
        UserDocument user = find(userId);
        requirePassword(newPassword);
        user.setPasswordHash(this.passwordEncoder.encode(newPassword));
        user.setUpdatedAt(this.clock.instant());
        this.users.save(user);
        revokeTokens(userId, "PASSWORD_RESET");
    }

    /** A signed-in user changes their password, proving they know the current one. */
    public void changePassword(String userId, String currentPassword, String newPassword) {
        UserDocument user = find(userId);
        if (currentPassword == null || user.getPasswordHash() == null
                || !this.passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ModuleException.BadRequest("The current password is not correct", "PASSWORD_WRONG",
                    Map.of("errors", Map.of("currentPassword", "is not correct")));
        }
        requirePassword(newPassword);
        user.setPasswordHash(this.passwordEncoder.encode(newPassword));
        user.setUpdatedAt(this.clock.instant());
        this.users.save(user);
    }

    /** Throws {@code PASSWORD_INVALID} unless the password has 10 to 128 characters. */
    public static void requirePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw new ModuleException.BadRequest("The password is invalid", "PASSWORD_INVALID",
                    Map.of("errors", Map.of("password", "must be " + MIN_PASSWORD + " to " + MAX_PASSWORD + " characters")));
        }
    }

    /** Claims for a new access token: roles, permissions and the current token version. */
    public TokenClaims tokenClaims(String userId) {
        UserDocument user = find(userId);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ModuleException.Forbidden("The account is blocked", "USER_BLOCKED");
        }
        return new TokenClaims(Set.copyOf(user.getRoles()), Permissions.of(user.getRoles()), user.getTokenVersion(),
                Set.copyOf(user.getBrandIds()), Set.copyOf(user.getStoreIds()));
    }

    /**
     * Gives owner access to a brand and its branch (PRD 4.2.1). An existing account keeps its other
     * roles; a missing one is created. New tokens carry the ids; existing tokens are unaffected.
     */
    public UserAccount grantStoreAccess(String email, String displayName, String initialPassword, String brandId,
            String storeId, String grantedBy) {
        if (brandId == null || brandId.isBlank() || storeId == null || storeId.isBlank()) {
            throw new ModuleException.BadRequest("Brand and branch are required", "USER_INVALID");
        }
        Optional<UserDocument> existing = findByEmail(email);
        if (existing.isEmpty()) {
            UserAccount created = create(new NewUser(email, initialPassword, displayName, EnumSet.of(Role.STORE_OWNER)),
                    grantedBy);
            UserDocument user = find(created.id());
            user.getBrandIds().add(brandId);
            user.getStoreIds().add(storeId);
            user.setUpdatedAt(this.clock.instant());
            return toAccount(this.users.save(user));
        }
        UserDocument user = existing.get();
        user.getRoles().add(Role.STORE_OWNER);
        user.getBrandIds().add(brandId);
        user.getStoreIds().add(storeId);
        user.setUpdatedAt(this.clock.instant());
        return toAccount(this.users.save(user));
    }

    /**
     * Gives rider access (PRD DR-1): {@code RIDER} for the operator's fleet, {@code STORE_RIDER} for a
     * store's own rider. An existing account keeps its other roles; a missing one is created.
     */
    public UserAccount grantRiderAccess(String email, String displayName, String initialPassword, boolean storeRider,
            String grantedBy) {
        Role role = storeRider ? Role.STORE_RIDER : Role.RIDER;
        Optional<UserDocument> existing = findByEmail(email);
        if (existing.isEmpty()) {
            return create(new NewUser(email, initialPassword, displayName, EnumSet.of(role)), grantedBy);
        }
        UserDocument user = existing.get();
        user.getRoles().add(role);
        user.getRoles().remove(Role.CUSTOMER);
        user.setUpdatedAt(this.clock.instant());
        return toAccount(this.users.save(user));
    }

    /** Takes both rider roles away and revokes the user's tokens so it takes effect now. */
    public void revokeRiderAccess(String userId) {
        Optional<UserDocument> found = this.users.findById(userId);
        if (found.isEmpty()) {
            return;
        }
        UserDocument user = found.get();
        user.getRoles().remove(Role.RIDER);
        user.getRoles().remove(Role.STORE_RIDER);
        if (user.getRoles().isEmpty()) {
            user.getRoles().add(Role.CUSTOMER);
        }
        user.setUpdatedAt(this.clock.instant());
        this.users.save(user);
        revokeTokens(userId, "RIDER_ACCESS_REVOKED");
    }

    /** Removes a brand and branch from a user's claims and revokes their tokens so it takes effect now. */
    public void revokeStoreAccess(String userId, String brandId, String storeId) {
        Optional<UserDocument> found = this.users.findById(userId);
        if (found.isEmpty()) {
            return;
        }
        UserDocument user = found.get();
        user.getBrandIds().remove(brandId);
        user.getStoreIds().remove(storeId);
        if (user.getStoreIds().isEmpty() && user.getBrandIds().isEmpty()) {
            user.getRoles().remove(Role.STORE_OWNER);
            if (user.getRoles().isEmpty()) {
                user.getRoles().add(Role.CUSTOMER);
            }
        }
        user.setUpdatedAt(this.clock.instant());
        this.users.save(user);
        revokeTokens(userId, "STORE_ACCESS_REVOKED");
    }

    /**
     * Claims of an access token.
     *
     * @param roles granted roles
     * @param permissions derived permissions
     * @param tokenVersion current token version
     * @param brandIds brands the user owns
     * @param storeIds branches the user manages
     */
    public record TokenClaims(Set<Role> roles, Set<String> permissions, long tokenVersion, Set<String> brandIds,
            Set<String> storeIds) {
    }

    private UserDocument find(String userId) {
        return this.users.findById(userId)
            .orElseThrow(() -> new ModuleException.NotFound("User " + userId + " not found", "USER_NOT_FOUND"));
    }

    UserAccount toAccount(UserDocument user) {
        return new UserAccount(user.getId(), this.pii.decrypt(user.getEmailCipher()), user.getDisplayName(),
                Set.copyOf(user.getRoles()), Permissions.of(user.getRoles()), user.getStatus(), user.getCreatedAt(),
                Set.copyOf(user.getBrandIds()), Set.copyOf(user.getStoreIds()));
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

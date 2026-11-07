package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.api.events.UserRegistered;
import io.github.ilyaslabs.foodstack.identity.api.events.UserTokensRevoked;
import io.github.ilyaslabs.foodstack.identity.core.persistence.LoginKeyDocument;
import io.github.ilyaslabs.foodstack.identity.core.persistence.LoginKeyRepository;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserDocument;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserRepository;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.TokenRevocations;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    private final UserRepository users = mock(UserRepository.class);

    private final LoginKeyRepository loginKeys = mock(LoginKeyRepository.class);

    private final PiiCipher pii = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));

    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    private final TokenRevocations revocations = mock(TokenRevocations.class);

    private UserService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        given(this.passwordEncoder.encode(any())).willReturn("{argon2}hash");
        given(this.users.insert(any(UserDocument.class))).willAnswer((invocation) -> invocation.getArgument(0));
        given(this.transaction.execute(any())).willAnswer(
                (invocation) -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        willAnswer((invocation) -> {
            ((java.util.function.Consumer<TransactionStatus>) invocation.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).given(this.transaction).executeWithoutResult(any());
        this.service = new UserService(this.users, this.loginKeys, this.pii, this.passwordEncoder, this.outbox,
                this.transaction, this.revocations, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("signs up a customer with an encrypted email, a login key and UserRegistered")
    void register() {
        // when
        UserAccount account = this.service.register(" Owner@Example.com ", "correct horse", "Owner");

        // then
        ArgumentCaptor<UserDocument> stored = ArgumentCaptor.forClass(UserDocument.class);
        verify(this.users).insert(stored.capture());
        assertThat(stored.getValue().getEmailCipher()).doesNotContain("Owner");
        assertThat(stored.getValue().getEmailIndex()).isEqualTo(this.pii.blindIndex("owner@example.com"));
        assertThat(stored.getValue().getPasswordHash()).isEqualTo("{argon2}hash");
        assertThat(stored.getValue().getTokenVersion()).isEqualTo(1);
        ArgumentCaptor<LoginKeyDocument> key = ArgumentCaptor.forClass(LoginKeyDocument.class);
        verify(this.loginKeys).insert(key.capture());
        assertThat(key.getValue().getLoginKey()).isEqualTo(stored.getValue().getEmailIndex());
        assertThat(account.email()).isEqualTo("Owner@Example.com");
        assertThat(account.roles()).containsExactly(Role.CUSTOMER);
        verify(this.outbox).append(new UserRegistered(account.id(), Set.of(Role.CUSTOMER), account.id()));
    }

    @Test
    @DisplayName("refuses a second account for the same email")
    void emailTaken() {
        given(this.loginKeys.insert(any(LoginKeyDocument.class))).willThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> this.service.register("owner@example.com", "correct horse", "Owner"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("EMAIL_TAKEN"));
    }

    @Test
    @DisplayName("validates email, password length and name")
    @SuppressWarnings("unchecked")
    void validation() {
        assertThatThrownBy(() -> this.service.register("not-an-email", "short", " "))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class,
                    (ex) -> assertThat((Map<String, String>) ex.details().get("errors")).containsKeys("email", "password",
                            "displayName"));
    }

    @Test
    @DisplayName("finds the user of an email through its blind index")
    void findByEmail() {
        UserDocument user = user(EnumSet.of(Role.ADMIN));
        given(this.loginKeys.findByLoginKey(this.pii.blindIndex("admin@example.com")))
            .willReturn(Optional.of(new LoginKeyDocument("k", "x", user.getId())));
        given(this.users.findById(user.getId())).willReturn(Optional.of(user));

        assertThat(this.service.findByEmail("ADMIN@example.com ")).contains(user);
        assertThat(this.service.findByEmail(" ")).isEmpty();
    }

    @Test
    @DisplayName("a reset sets the new password and ends every old token; a change needs the current password")
    void passwords() {
        UserDocument user = user(EnumSet.of(Role.CUSTOMER));
        user.setTokenVersion(2);
        user.setPasswordHash("{argon2}old");
        given(this.users.findById(user.getId())).willReturn(Optional.of(user));

        assertThatThrownBy(() -> this.service.resetPassword(user.getId(), "short"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("PASSWORD_INVALID"));
        this.service.resetPassword(user.getId(), "a new long password");
        assertThat(user.getPasswordHash()).isEqualTo("{argon2}hash");
        assertThat(user.getTokenVersion()).isEqualTo(3);
        verify(this.revocations).revokeBefore(user.getId(), 3);

        given(this.passwordEncoder.matches("wrong guess", "{argon2}hash")).willReturn(false);
        assertThatThrownBy(() -> this.service.changePassword(user.getId(), "wrong guess", "another long password"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("PASSWORD_WRONG"));
        given(this.passwordEncoder.matches("a new long password", "{argon2}hash")).willReturn(true);
        given(this.passwordEncoder.encode("another long password")).willReturn("{argon2}newer");
        this.service.changePassword(user.getId(), "a new long password", "another long password");
        assertThat(user.getPasswordHash()).isEqualTo("{argon2}newer");
        assertThat(user.getTokenVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("revokes every token by raising the token version, in Redis and as an event")
    void revokeTokens() {
        UserDocument user = user(EnumSet.of(Role.CUSTOMER));
        user.setTokenVersion(3);
        given(this.users.findById(user.getId())).willReturn(Optional.of(user));

        long version = this.service.revokeTokens(user.getId(), "LOGOUT_EVERYWHERE");

        assertThat(version).isEqualTo(4);
        verify(this.users).save(user);
        verify(this.outbox).append(new UserTokensRevoked(user.getId(), 4, "LOGOUT_EVERYWHERE"));
        verify(this.revocations).revokeBefore(user.getId(), 4);
    }

    @Test
    @DisplayName("gives token claims for active users only")
    void tokenClaims() {
        UserDocument admin = user(EnumSet.of(Role.ADMIN));
        UserDocument blocked = user(EnumSet.of(Role.CUSTOMER));
        blocked.setStatus(UserStatus.BLOCKED);
        given(this.users.findById(admin.getId())).willReturn(Optional.of(admin));
        given(this.users.findById(blocked.getId())).willReturn(Optional.of(blocked));

        UserService.TokenClaims claims = this.service.tokenClaims(admin.getId());

        assertThat(claims.roles()).containsExactly(Role.ADMIN);
        assertThat(claims.permissions()).contains("settings:manage", "media:upload");
        assertThat(claims.tokenVersion()).isEqualTo(1);
        assertThatThrownBy(() -> this.service.tokenClaims(blocked.getId()))
            .isInstanceOfSatisfying(ModuleException.Forbidden.class, (ex) -> assertThat(ex.code()).isEqualTo("USER_BLOCKED"));
    }

    @Test
    @DisplayName("adds the branch owner role and the branch id to an existing account, keeping its other roles")
    void grantStoreAccessToExistingUser() {
        // given
        UserDocument user = user(EnumSet.of(Role.CUSTOMER));
        given(this.loginKeys.findByLoginKey(this.pii.blindIndex("admin@example.com")))
            .willReturn(Optional.of(new LoginKeyDocument("k", "x", user.getId())));
        given(this.users.findById(user.getId())).willReturn(Optional.of(user));
        given(this.users.save(any(UserDocument.class))).willAnswer((invocation) -> invocation.getArgument(0));

        // when
        UserAccount account = this.service.grantStoreAccess("admin@example.com", null, null, "store-1", "admin-1");

        // then
        assertThat(account.roles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.STORE_OWNER);
        assertThat(account.brandIds()).isEmpty();
        assertThat(account.storeIds()).containsExactly("store-1");
        assertThat(account.permissions()).contains("store:portal", "media:upload");
    }

    @Test
    @DisplayName("creates a store owner account when nobody has the email yet")
    void grantStoreAccessCreatesAccount() {
        // given
        given(this.loginKeys.findByLoginKey(any())).willReturn(Optional.empty());
        java.util.concurrent.atomic.AtomicReference<UserDocument> inserted = new java.util.concurrent.atomic.AtomicReference<>();
        given(this.users.insert(any(UserDocument.class))).willAnswer((invocation) -> {
            inserted.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        given(this.users.findById(any())).willAnswer((invocation) -> Optional.ofNullable(inserted.get()));
        given(this.users.save(any(UserDocument.class))).willAnswer((invocation) -> invocation.getArgument(0));

        // when
        UserAccount account = this.service.grantStoreAccess("owner@example.com", "Owner", "a-long-password", "store-1",
                "admin-1");

        // then
        assertThat(account.roles()).containsExactly(Role.STORE_OWNER);
        assertThat(account.storeIds()).containsExactly("store-1");
        assertThat(account.email()).isEqualTo("owner@example.com");
    }

    @Test
    @DisplayName("refuses a new owner account without a valid password")
    void grantStoreAccessNeedsPassword() {
        given(this.loginKeys.findByLoginKey(any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.grantStoreAccess("owner@example.com", "Owner", null, "s", "admin-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("USER_INVALID"));
    }

    @Test
    @DisplayName("revoking the last branch drops the owner role and revokes tokens")
    void revokeStoreAccess() {
        // given
        UserDocument user = user(EnumSet.of(Role.STORE_OWNER));
        user.getBrandIds().add("brand-1");
        user.getStoreIds().add("store-1");
        given(this.users.findById(user.getId())).willReturn(Optional.of(user));

        // when
        this.service.revokeStoreAccess(user.getId(), "store-1");

        // then
        assertThat(user.getRoles()).containsExactly(Role.CUSTOMER);
        assertThat(user.getStoreIds()).isEmpty();
        verify(this.revocations).revokeBefore(user.getId(), 2);
    }

    @Test
    @DisplayName("brand owner access adds BRAND_OWNER and the brand; taking the last brand away drops the role")
    void brandAccess() {
        // given
        UserDocument user = user(EnumSet.of(Role.STORE_OWNER));
        user.getStoreIds().add("store-1");
        given(this.loginKeys.findByLoginKey(this.pii.blindIndex("admin@example.com")))
            .willReturn(Optional.of(new LoginKeyDocument("k", "x", user.getId())));
        given(this.users.findById(user.getId())).willReturn(Optional.of(user));
        given(this.users.save(any(UserDocument.class))).willAnswer((invocation) -> invocation.getArgument(0));

        // when
        UserAccount owner = this.service.grantBrandAccess("admin@example.com", null, null, "brand-1", "admin-1");
        this.service.revokeBrandAccess(user.getId(), "brand-1");

        // then
        assertThat(owner.roles()).containsExactlyInAnyOrder(Role.STORE_OWNER, Role.BRAND_OWNER);
        assertThat(owner.brandIds()).containsExactly("brand-1");
        assertThat(user.getRoles()).containsExactly(Role.STORE_OWNER);
        assertThat(user.getStoreIds()).containsExactly("store-1");
        verify(this.revocations).revokeBefore(user.getId(), 2);
    }

    @Test
    @DisplayName("makes a customer an operator rider, and a new email a store rider; neither gets store claims")
    void grantRiderAccess() {
        UserDocument user = user(EnumSet.of(Role.CUSTOMER));
        given(this.loginKeys.findByLoginKey(this.pii.blindIndex("admin@example.com")))
            .willReturn(Optional.of(new LoginKeyDocument("k", "x", user.getId())));
        given(this.users.findById(user.getId())).willReturn(Optional.of(user));
        given(this.users.save(any(UserDocument.class))).willAnswer((invocation) -> invocation.getArgument(0));

        UserAccount rider = this.service.grantRiderAccess("admin@example.com", null, null, false, "admin-1");

        assertThat(rider.roles()).containsExactly(Role.RIDER);
        assertThat(rider.permissions()).containsExactlyInAnyOrder("rider:portal", "media:upload");
        assertThat(rider.storeIds()).isEmpty();
    }

    @Test
    @DisplayName("taking rider access away leaves a customer account and revokes tokens now")
    void revokeRiderAccess() {
        UserDocument user = user(EnumSet.of(Role.STORE_RIDER));
        given(this.users.findById(user.getId())).willReturn(Optional.of(user));

        this.service.revokeRiderAccess(user.getId());

        assertThat(user.getRoles()).containsExactly(Role.CUSTOMER);
        verify(this.revocations).revokeBefore(user.getId(), 2);
    }

    @Test
    @DisplayName("puts brand and branch ids into token claims")
    void tokenClaimsCarryStoreScopes() {
        UserDocument owner = user(EnumSet.of(Role.STORE_OWNER));
        owner.getBrandIds().add("brand-1");
        owner.getStoreIds().add("store-1");
        given(this.users.findById(owner.getId())).willReturn(Optional.of(owner));

        UserService.TokenClaims claims = this.service.tokenClaims(owner.getId());

        assertThat(claims.brandIds()).containsExactly("brand-1");
        assertThat(claims.storeIds()).containsExactly("store-1");
    }

    @Test
    @DisplayName("answers USER_NOT_FOUND for unknown users")
    void notFound() {
        given(this.users.findById("nobody")).willReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.get("nobody"))
            .isInstanceOfSatisfying(ModuleException.NotFound.class, (ex) -> assertThat(ex.code()).isEqualTo("USER_NOT_FOUND"));
    }

    private UserDocument user(EnumSet<Role> roles) {
        UserDocument user = new UserDocument();
        user.setId(io.github.ilyaslabs.foodstack.platform.core.Ids.newId());
        user.setEmailCipher(this.pii.encrypt("admin@example.com"));
        user.setEmailIndex(this.pii.blindIndex("admin@example.com"));
        user.setDisplayName("Someone");
        user.setRoles(roles);
        user.setTokenVersion(1);
        user.setCreatedAt(NOW);
        return user;
    }

    @Test
    @DisplayName("signing in again during the grace period cancels a requested deletion")
    void cancelDeletion() {
        // given
        UserDocument pending = new UserDocument();
        pending.setId("u1");
        pending.setStatus(UserStatus.ACTIVE);
        pending.setDeleteAfter(NOW.plusSeconds(3600));
        pending.setDeletionRequestedAt(NOW);
        given(this.users.findById("u1")).willReturn(Optional.of(pending));
        given(this.users.findById("u2")).willReturn(Optional.empty());

        // when
        boolean cancelled = this.service.cancelDeletion("u1");
        boolean nothing = this.service.cancelDeletion("u2");

        // then
        assertThat(cancelled).isTrue();
        assertThat(nothing).isFalse();
        ArgumentCaptor<UserDocument> saved = ArgumentCaptor.forClass(UserDocument.class);
        verify(this.users).save(saved.capture());
        assertThat(saved.getValue().getDeleteAfter()).isNull();
        assertThat(saved.getValue().getDeletionRequestedAt()).isNull();
    }
}

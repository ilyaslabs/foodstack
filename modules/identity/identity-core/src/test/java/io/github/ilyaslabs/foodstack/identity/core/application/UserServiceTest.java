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
}

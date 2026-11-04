package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.core.IdentityPersistenceTestApplication;
import io.github.ilyaslabs.foodstack.identity.api.events.EmailCodeIssued;
import io.github.ilyaslabs.foodstack.identity.core.application.SessionService.Client;
import io.github.ilyaslabs.foodstack.identity.core.application.SessionService.Session;
import io.github.ilyaslabs.foodstack.identity.core.persistence.RefreshTokenDocument;
import io.github.ilyaslabs.foodstack.identity.core.security.AccessTokens;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.RateLimiter;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Refresh token rotation against a real two-shard MongoDB (lowest layer, TDD 15.3). */
@SpringBootTest(classes = IdentityPersistenceTestApplication.class, properties = "spring.data.mongodb.repositories.type=none")
class SessionServiceTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ModuleMongo identityMongo;

    private final UserService users = mock(UserService.class);

    private final EmailCodeService codes = mock(EmailCodeService.class);

    private final AccessTokens accessTokens = mock(AccessTokens.class);

    private final RateLimiter rateLimiter = mock(RateLimiter.class);

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-04T10:00:00Z"));

    private SessionService sessions;

    @BeforeEach
    void setUp() {
        this.identityMongo.template().remove(new Query(), RefreshTokenDocument.class);
        PiiCipher pii = mock(PiiCipher.class);
        given(pii.blindIndex(anyString())).willAnswer((call) -> "bi:" + call.getArgument(0));
        given(this.accessTokens.lifetime()).willReturn(Duration.ofMinutes(10));
        given(this.accessTokens.mint(anyString(), anyString(), any())).willAnswer((call) -> "at:" + call.getArgument(1));
        claims("u1", 1);
        this.sessions = new SessionService(this.identityMongo.template(), this.users, this.codes, this.accessTokens,
                this.rateLimiter, pii, new MovingClock(this.now));
    }

    @Test
    @DisplayName("signs in with a password, keeps only a hash of the refresh token, and checks the try limit")
    void password() {
        given(this.users.checkPassword("a@example.com", "correct horse")).willReturn(Optional.of("u1"));

        Session session = this.sessions.signInWithPassword("a@example.com", "correct horse", Client.WEB);

        assertThat(session.userId()).isEqualTo("u1");
        assertThat(session.expiresIn()).isEqualTo(600);
        assertThat(session.refreshExpiresAt()).isEqualTo(this.now.get().plus(Duration.ofDays(30)));
        RefreshTokenDocument stored = this.identityMongo.template().findById(SessionService.hash(session.refreshToken()),
                RefreshTokenDocument.class);
        assertThat(stored.client()).isEqualTo("WEB");
        assertThat(stored.userId()).isEqualTo("u1");
        assertThat(session.accessToken()).isEqualTo("at:" + stored.familyId());
        assertThat(this.identityMongo.template().count(Query.query(Criteria.where("_id").is(session.refreshToken())),
                RefreshTokenDocument.class)).isZero();
        verify(this.rateLimiter).check(eq("identity"), eq("password"), eq("bi:a@example.com"), eq(10), any());

        assertThatThrownBy(() -> this.sessions.signInWithPassword("a@example.com", "wrong", Client.WEB))
            .isInstanceOfSatisfying(ModuleException.Unauthorized.class, (ex) -> assertThat(ex.code()).isEqualTo("LOGIN_FAILED"));
    }

    @Test
    @DisplayName("signs in with an emailed code")
    void code() {
        given(this.codes.verify("a@example.com", EmailCodeIssued.LOGIN, "123456")).willReturn(Optional.of("u1"));

        assertThat(this.sessions.signInWithCode("a@example.com", "123456", Client.MOBILE).client()).isEqualTo(Client.MOBILE);
        assertThatThrownBy(() -> this.sessions.signInWithCode("a@example.com", "000000", Client.MOBILE))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("CODE_INVALID"));
    }

    @Test
    @DisplayName("each refresh swaps the token for a new one in the same sign-in")
    void rotates() {
        Session first = this.sessions.start("u1", Client.MOBILE);
        this.now.set(this.now.get().plus(Duration.ofDays(20)));

        Session second = this.sessions.refresh(first.refreshToken(), Client.MOBILE);

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.accessToken()).isEqualTo(first.accessToken());
        assertThat(second.refreshExpiresAt()).isEqualTo(this.now.get().plus(Duration.ofDays(30)));
        // 20 more days on the new token: still inside its own 30
        this.now.set(this.now.get().plus(Duration.ofDays(20)));
        assertThat(this.sessions.refresh(second.refreshToken(), Client.MOBILE).userId()).isEqualTo("u1");
    }

    @Test
    @DisplayName("a used token presented again ends the whole sign-in, except right after use (two tabs)")
    void reuse() {
        Session first = this.sessions.start("u1", Client.WEB);
        Session second = this.sessions.refresh(first.refreshToken(), Client.WEB);

        assertThatThrownBy(() -> this.sessions.refresh(first.refreshToken(), Client.WEB))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("REFRESH_RACE"));
        assertThat(this.sessions.find(second.refreshToken())).isPresent();

        this.now.set(this.now.get().plusSeconds(60));
        assertThatThrownBy(() -> this.sessions.refresh(first.refreshToken(), Client.WEB))
            .isInstanceOfSatisfying(ModuleException.Unauthorized.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("REFRESH_REUSED"));
        assertThat(this.sessions.find(second.refreshToken())).isEmpty();
        assertThatThrownBy(() -> this.sessions.refresh(second.refreshToken(), Client.WEB))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("REFRESH_INVALID"));
    }

    @Test
    @DisplayName("a token works only in the channel it was issued for")
    void channel() {
        Session web = this.sessions.start("u1", Client.WEB);

        assertThatThrownBy(() -> this.sessions.refresh(web.refreshToken(), Client.MOBILE))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("REFRESH_WRONG_CHANNEL"));
        // refused before use: the browser can still refresh with it
        assertThat(this.sessions.refresh(web.refreshToken(), Client.WEB).client()).isEqualTo(Client.WEB);
    }

    @Test
    @DisplayName("expired tokens, a newer token version or a blocked account end the sign-in")
    void ends() {
        Session expired = this.sessions.start("u1", Client.MOBILE);
        this.now.set(this.now.get().plus(Duration.ofDays(31)));
        assertThatThrownBy(() -> this.sessions.refresh(expired.refreshToken(), Client.MOBILE))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("REFRESH_INVALID"));

        Session revoked = this.sessions.start("u1", Client.MOBILE);
        claims("u1", 2);
        assertThatThrownBy(() -> this.sessions.refresh(revoked.refreshToken(), Client.MOBILE))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("REFRESH_INVALID"));
        assertThat(this.sessions.find(revoked.refreshToken())).isEmpty();

        Session blocked = this.sessions.start("u1", Client.MOBILE);
        given(this.users.tokenClaims("u1")).willThrow(new ModuleException.Forbidden("blocked", "USER_BLOCKED"));
        assertThatThrownBy(() -> this.sessions.refresh(blocked.refreshToken(), Client.MOBILE))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("REFRESH_INVALID"));
    }

    @Test
    @DisplayName("signing out ends only that sign-in")
    void signOut() {
        Session phone = this.sessions.start("u1", Client.MOBILE);
        Session laptop = this.sessions.start("u1", Client.WEB);
        String phoneSession = this.sessions.find(phone.refreshToken()).orElseThrow().familyId();

        this.sessions.end("someone-else", phoneSession);
        assertThat(this.sessions.find(phone.refreshToken())).isPresent();
        this.sessions.end("u1", phoneSession);

        assertThat(this.sessions.find(phone.refreshToken())).isEmpty();
        assertThat(this.sessions.find(laptop.refreshToken())).isPresent();
    }

    @Test
    @DisplayName("a password reset needs a valid new password and a right code, then signs in")
    void reset() {
        assertThatThrownBy(() -> this.sessions.resetPassword("a@example.com", "123456", "short", Client.WEB))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("PASSWORD_INVALID"));
        given(this.codes.verify("a@example.com", EmailCodeIssued.PASSWORD_RESET, "123456")).willReturn(Optional.of("u1"));

        Session session = this.sessions.resetPassword("a@example.com", "123456", "a new long password", Client.WEB);

        verify(this.users).resetPassword("u1", "a new long password");
        assertThat(session.userId()).isEqualTo("u1");
    }

    private void claims(String userId, long tokenVersion) {
        given(this.users.tokenClaims(userId)).willReturn(
                new UserService.TokenClaims(Set.of(Role.CUSTOMER), Set.of(), tokenVersion, Set.of(), Set.of()));
    }

    private static final class MovingClock extends Clock {

        private final AtomicReference<Instant> now;

        MovingClock(AtomicReference<Instant> now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now.get();
        }
    }
}

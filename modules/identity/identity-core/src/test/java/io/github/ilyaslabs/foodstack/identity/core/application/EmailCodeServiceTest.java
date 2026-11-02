package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Consumer;

import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.api.events.EmailCodeIssued;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserDocument;
import io.github.ilyaslabs.foodstack.platform.core.TooManyRequestsException;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.RateLimiter;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Codes in a real Redis; the user lookup and the outbox are mocked. */
class EmailCodeServiceTest {

    private final UserService users = mock(UserService.class);

    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    private final PiiCipher pii = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));

    private LettuceConnectionFactory connectionFactory;

    private EmailCodeService codes;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        this.connectionFactory = new LettuceConnectionFactory(FoodstackContainers.redis().getHost(),
                FoodstackContainers.redis().getMappedPort(6379));
        this.connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(this.connectionFactory);
        String namespace = FoodstackContainers.uniqueName();
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);
        willAnswer((invocation) -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).given(this.transaction).executeWithoutResult(any());
        this.codes = new EmailCodeService(this.users, redis, new RateLimiter(redis, namespace, clock), this.pii, this.outbox,
                this.transaction, namespace, clock);
        given(this.users.findByEmail("sara@example.com")).willReturn(Optional.of(user("u1", UserStatus.ACTIVE)));
        given(this.users.findByEmail("blocked@example.com")).willReturn(Optional.of(user("u2", UserStatus.BLOCKED)));
        given(this.users.findByEmail("nobody@example.com")).willReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        this.connectionFactory.destroy();
    }

    @Test
    @DisplayName("a code is emailed encrypted, works once, and only for its purpose")
    void issueAndVerify() {
        String code = issue("sara@example.com", EmailCodeIssued.LOGIN);

        assertThat(code).matches("\\d{6}");
        assertThat(this.codes.verify("sara@example.com", EmailCodeIssued.PASSWORD_RESET, code)).isEmpty();
        assertThat(this.codes.verify("SARA@example.com ", EmailCodeIssued.LOGIN, code)).isEmpty();
        given(this.users.findByEmail("SARA@example.com ")).willReturn(Optional.of(user("u1", UserStatus.ACTIVE)));
        assertThat(this.codes.verify("SARA@example.com ", EmailCodeIssued.LOGIN, " " + code + " ")).contains("u1");
        assertThat(this.codes.verify("sara@example.com", EmailCodeIssued.LOGIN, code)).isEmpty();
    }

    @Test
    @DisplayName("five wrong tries throw the code away, and a new code replaces the last")
    void attemptsAndReplacement() {
        String first = issue("sara@example.com", EmailCodeIssued.LOGIN);
        String second = issue("sara@example.com", EmailCodeIssued.LOGIN);
        if (!first.equals(second)) {
            assertThat(this.codes.verify("sara@example.com", EmailCodeIssued.LOGIN, first)).isEmpty();
        }
        String wrong = second.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 4; i++) {
            assertThat(this.codes.verify("sara@example.com", EmailCodeIssued.LOGIN, wrong)).isEmpty();
        }
        // that was the fifth try when the first code differed; either way the next ones must fail
        assertThat(this.codes.verify("sara@example.com", EmailCodeIssued.LOGIN, wrong)).isEmpty();
        assertThat(this.codes.verify("sara@example.com", EmailCodeIssued.LOGIN, second)).isEmpty();
    }

    @Test
    @DisplayName("unknown and blocked emails get no code, without any difference in the answer")
    void noAccount() {
        this.codes.issue("nobody@example.com", EmailCodeIssued.LOGIN);
        this.codes.issue("blocked@example.com", EmailCodeIssued.LOGIN);

        verify(this.outbox, never()).append(any());
    }

    @Test
    @DisplayName("an email can ask for five codes per 15 minutes, whether or not it has an account")
    void rateLimited() {
        for (int i = 0; i < 5; i++) {
            this.codes.issue("nobody@example.com", EmailCodeIssued.PASSWORD_RESET);
        }
        assertThatThrownBy(() -> this.codes.issue("nobody@example.com", EmailCodeIssued.PASSWORD_RESET))
            .isInstanceOf(TooManyRequestsException.class);
    }

    private String issue(String email, String purpose) {
        ArgumentCaptor<EmailCodeIssued> event = ArgumentCaptor.forClass(EmailCodeIssued.class);
        this.codes.issue(email, purpose);
        verify(this.outbox, org.mockito.Mockito.atLeastOnce()).append(event.capture());
        EmailCodeIssued issued = event.getValue();
        assertThat(issued.purpose()).isEqualTo(purpose);
        assertThat(issued.expiresAt()).isEqualTo(Instant.parse("2026-11-02T10:05:00Z"));
        assertThat(issued.codeCipher()).doesNotMatch("\\d{6}");
        return this.pii.decrypt(issued.codeCipher());
    }

    private static UserDocument user(String id, UserStatus status) {
        UserDocument user = new UserDocument();
        user.setId(id);
        user.setStatus(status);
        return user;
    }
}

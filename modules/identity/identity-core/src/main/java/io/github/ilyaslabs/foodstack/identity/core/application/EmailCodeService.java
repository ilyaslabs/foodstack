package io.github.ilyaslabs.foodstack.identity.core.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.api.events.EmailCodeIssued;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserDocument;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.RateLimiter;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Email one-time codes for signing in and for resetting a password (TDD 9.1, PRD ID-1). A code is
 * six digits, lives five minutes in Redis as a hash only, allows five tries and works once. Asking
 * for a code never tells whether the email has an account.
 */
public class EmailCodeService {

    public static final Duration TTL = Duration.ofMinutes(5);

    static final int MAX_ATTEMPTS = 5;

    static final int CODES_PER_WINDOW = 5;

    static final Duration CODE_WINDOW = Duration.ofMinutes(15);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserService users;

    private final StringRedisTemplate redis;

    private final RateLimiter rateLimiter;

    private final PiiCipher pii;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final String prefix;

    private final Clock clock;

    public EmailCodeService(UserService users, StringRedisTemplate redis, RateLimiter rateLimiter, PiiCipher pii,
            OutboxWriter outbox, TransactionTemplate transaction, String namespace, Clock clock) {
        this.users = users;
        this.redis = redis;
        this.rateLimiter = rateLimiter;
        this.pii = pii;
        this.outbox = outbox;
        this.transaction = transaction;
        this.prefix = namespace + ":identity:code:";
        this.clock = clock;
    }

    /**
     * Emails a new code to the account with this email, if there is an active one; a new code
     * replaces the last. Too many requests for one email answer 429.
     */
    public void issue(String email, String purpose) {
        requirePurpose(purpose);
        if (email == null || email.isBlank()) {
            return;
        }
        this.rateLimiter.check("identity", "email-code", this.pii.blindIndex(email), CODES_PER_WINDOW, CODE_WINDOW);
        Optional<UserDocument> user = this.users.findByEmail(email).filter((u) -> u.getStatus() == UserStatus.ACTIVE);
        if (user.isEmpty()) {
            return;
        }
        String userId = user.get().getId();
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        Instant expiresAt = this.clock.instant().plus(TTL);
        String key = key(purpose, userId);
        this.redis.delete(key);
        this.redis.opsForHash().putAll(key, Map.of("hash", hash(userId, code), "attempts", "0"));
        this.redis.expire(key, TTL);
        this.transaction.executeWithoutResult(
                (status) -> this.outbox.append(new EmailCodeIssued(userId, purpose, this.pii.encrypt(code), expiresAt)));
    }

    /**
     * Checks a typed code. A right code is used up; five wrong ones throw the code away.
     * @return the user it belongs to, when the code is right and the account active
     */
    public Optional<String> verify(String email, String purpose, String code) {
        requirePurpose(purpose);
        if (code == null || !code.trim().matches("\\d{6}")) {
            return Optional.empty();
        }
        Optional<UserDocument> user = this.users.findByEmail(email).filter((u) -> u.getStatus() == UserStatus.ACTIVE);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        String userId = user.get().getId();
        String key = key(purpose, userId);
        Object stored = this.redis.opsForHash().get(key, "hash");
        if (stored == null) {
            return Optional.empty();
        }
        long attempts = this.redis.opsForHash().increment(key, "attempts", 1);
        if (attempts > MAX_ATTEMPTS) {
            this.redis.delete(key);
            return Optional.empty();
        }
        boolean right = MessageDigest.isEqual(stored.toString().getBytes(StandardCharsets.US_ASCII),
                hash(userId, code.trim()).getBytes(StandardCharsets.US_ASCII));
        // deleting is what uses the code up, so two quick tries cannot both succeed
        if (!right || !Boolean.TRUE.equals(this.redis.delete(key))) {
            return Optional.empty();
        }
        return Optional.of(userId);
    }

    private String key(String purpose, String userId) {
        return this.prefix + purpose + ":" + userId;
    }

    private static void requirePurpose(String purpose) {
        if (!EmailCodeIssued.LOGIN.equals(purpose) && !EmailCodeIssued.PASSWORD_RESET.equals(purpose)) {
            throw new IllegalArgumentException("Unknown code purpose " + purpose);
        }
    }

    private static String hash(String userId, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((userId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

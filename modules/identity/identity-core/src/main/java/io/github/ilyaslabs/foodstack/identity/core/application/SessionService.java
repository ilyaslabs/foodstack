package io.github.ilyaslabs.foodstack.identity.core.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.github.ilyaslabs.foodstack.identity.api.events.EmailCodeIssued;
import io.github.ilyaslabs.foodstack.identity.core.persistence.RefreshTokenDocument;
import io.github.ilyaslabs.foodstack.identity.core.security.AccessTokens;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.RateLimiter;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Stateless sign-in (TDD 9.1, gap 1): no server session and no redirect. Signing in (password,
 * emailed code, or a password reset) returns a 10 minute access token and a 30 day refresh token.
 * Each refresh swaps the refresh token for a new one in the same family; a used token presented
 * again (outside a short grace for two tabs refreshing at once) means it leaked, so the whole
 * family is removed and the sign-in ends. A token works only in the channel it was issued for:
 * {@code WEB} (httpOnly cookie) or {@code MOBILE} (request body). Raising the account's token
 * version (block, password reset, sign out everywhere) also ends every sign-in at its next refresh.
 */
public class SessionService {

    /** Where the client keeps its refresh token. */
    public enum Client {
        /** Browser: httpOnly cookie, never readable by scripts. */
        WEB,
        /** Native app: request body, kept in the Keychain or Keystore. */
        MOBILE;

        public static Client parse(String value) {
            if (value != null) {
                try {
                    return valueOf(value.trim().toUpperCase(Locale.ROOT));
                }
                catch (IllegalArgumentException ex) {
                    // answered below
                }
            }
            throw new ModuleException.BadRequest("client must be web or mobile", "AUTH_CLIENT_INVALID",
                    Map.of("errors", Map.of("client", "must be web or mobile")));
        }
    }

    /**
     * A signed-in session's tokens.
     *
     * @param accessToken bearer token for the API
     * @param expiresIn seconds until the access token runs out
     * @param refreshToken swaps for new tokens at {@code /auth/refresh}
     * @param refreshExpiresAt when the refresh token runs out
     * @param client the channel the refresh token works in
     * @param userId the account
     */
    public record Session(String accessToken, long expiresIn, String refreshToken, Instant refreshExpiresAt, Client client,
            String userId) {
    }

    public static final Duration REFRESH_LIFETIME = Duration.ofDays(30);

    /** A token used this recently was most likely swapped by another tab of the same browser. */
    static final Duration REUSE_GRACE = Duration.ofSeconds(10);

    static final int PASSWORD_TRIES = 10;

    static final Duration PASSWORD_WINDOW = Duration.ofMinutes(15);

    private static final Log logger = LogFactory.getLog(SessionService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoTemplate mongo;

    private final UserService users;

    private final EmailCodeService codes;

    private final AccessTokens accessTokens;

    private final RateLimiter rateLimiter;

    private final PiiCipher pii;

    private final Clock clock;

    private final AuditLog audit;

    public SessionService(MongoTemplate mongo, UserService users, EmailCodeService codes, AccessTokens accessTokens,
            RateLimiter rateLimiter, PiiCipher pii, Clock clock) {
        this(mongo, users, codes, accessTokens, rateLimiter, pii, clock, null);
    }

    /** As above, recording back-office sign-ins in the audit log (PRD ID-8). */
    public SessionService(MongoTemplate mongo, UserService users, EmailCodeService codes, AccessTokens accessTokens,
            RateLimiter rateLimiter, PiiCipher pii, Clock clock, AuditLog audit) {
        this.audit = audit;
        this.mongo = mongo;
        this.users = users;
        this.codes = codes;
        this.accessTokens = accessTokens;
        this.rateLimiter = rateLimiter;
        this.pii = pii;
        this.clock = clock;
    }

    /** Signs in with email and password. Ten wrong tries per email in 15 minutes answer 429. */
    public Session signInWithPassword(String email, String password, Client client) {
        String tries = (email != null && !email.isBlank()) ? this.pii.blindIndex(email.trim()) : null;
        if (tries != null) {
            this.rateLimiter.refuseIfReached("identity", "password", tries, PASSWORD_TRIES, PASSWORD_WINDOW);
        }
        Optional<String> userId = this.users.checkPassword(email, password);
        if (userId.isEmpty()) {
            if (tries != null) {
                this.rateLimiter.check("identity", "password", tries, PASSWORD_TRIES, PASSWORD_WINDOW);
            }
            auditFailure(email, "password");
            throw new ModuleException.Unauthorized("The email or password is not correct", "LOGIN_FAILED");
        }
        return signedIn(userId.get(), client, "password");
    }

    /** Signs in with a code emailed by {@code POST /auth/code}. */
    public Session signInWithCode(String email, String code, Client client) {
        Optional<String> verified = this.codes.verify(email, EmailCodeIssued.LOGIN, code);
        if (verified.isEmpty()) {
            auditFailure(email, "email code");
            throw new ModuleException.Unauthorized("The code is not correct or has expired", "CODE_INVALID");
        }
        return signedIn(verified.get(), client, "email code");
    }

    /** Sets a new password with an emailed reset code and signs in; every older sign-in ends. */
    public Session resetPassword(String email, String code, String newPassword, Client client) {
        UserService.requirePassword(newPassword);
        String userId = this.codes.verify(email, EmailCodeIssued.PASSWORD_RESET, code)
            .orElseThrow(() -> new ModuleException.Unauthorized("The code is not correct or has expired", "CODE_INVALID"));
        this.users.resetPassword(userId, newPassword);
        return signedIn(userId, client, "password reset");
    }

    private Session signedIn(String userId, Client client, String how) {
        Session session = start(userId, client);
        if (this.audit != null) {
            try {
                UserAccount user = this.users.get(userId);
                this.audit.signedIn(userId, user.displayName(), user.roles(), how, clientAddress());
            }
            catch (RuntimeException ex) {
                // the sign-in stands; the audit line is best effort
            }
        }
        return session;
    }

    private void auditFailure(String email, String how) {
        if (this.audit == null) {
            return;
        }
        try {
            this.users.findByEmail(email).ifPresent((user) -> this.audit.signInFailed(user.getId(), user.getDisplayName(),
                    user.getRoles(), how, clientAddress()));
        }
        catch (RuntimeException ex) {
            // the refusal stands; the audit line is best effort
        }
    }

    private static String clientAddress() {
        return (org.springframework.web.context.request.RequestContextHolder
            .getRequestAttributes() instanceof org.springframework.web.context.request.ServletRequestAttributes attributes)
                ? attributes.getRequest().getRemoteAddr() : null;
    }

    /** Starts a sign-in for an account that proved who it is. */
    public Session start(String userId, Client client) {
        UserService.TokenClaims claims = claims(userId);
        String familyId = Ids.newId();
        return issue(userId, familyId, client, claims.tokenVersion(), claims);
    }

    /**
     * Swaps a refresh token for new tokens.
     * @param channel where the token came from: the cookie ({@code WEB}) or the body ({@code MOBILE})
     */
    public Session refresh(String refreshToken, Client channel) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw invalid();
        }
        String id = hash(refreshToken);
        Instant now = this.clock.instant();
        RefreshTokenDocument token = this.mongo.findById(id, RefreshTokenDocument.class);
        if (token == null || !token.expiresAt().isAfter(now)) {
            throw invalid();
        }
        if (!token.client().equals(channel.name())) {
            throw new ModuleException.BadRequest("This refresh token belongs to the other client type",
                    "REFRESH_WRONG_CHANNEL");
        }
        RefreshTokenDocument claimed = this.mongo.findAndModify(
                Query.query(Criteria.where("_id").is(id).and("usedAt").is(null)), new Update().set("usedAt", now),
                FindAndModifyOptions.options().returnNew(true), RefreshTokenDocument.class);
        if (claimed == null) {
            RefreshTokenDocument used = this.mongo.findById(id, RefreshTokenDocument.class);
            if (used != null && used.usedAt() != null && used.usedAt().plus(REUSE_GRACE).isAfter(now)) {
                throw new ModuleException.Conflict("The session was just refreshed elsewhere; try again",
                        "REFRESH_RACE");
            }
            logger.warn("A used refresh token came back; ending sign-in " + token.familyId() + " of " + token.userId());
            endFamily(token.familyId());
            throw new ModuleException.Unauthorized("The session has ended; sign in again", "REFRESH_REUSED");
        }
        UserService.TokenClaims claims;
        try {
            claims = claims(token.userId());
        }
        catch (ModuleException ex) {
            endFamily(token.familyId());
            throw invalid();
        }
        if (claims.tokenVersion() > token.tokenVersion()) {
            endFamily(token.familyId());
            throw invalid();
        }
        return issue(token.userId(), token.familyId(), channel, token.tokenVersion(), claims);
    }

    /** Ends one sign-in (sign out on this device). */
    public void end(String userId, String sessionId) {
        if (sessionId == null) {
            return;
        }
        this.mongo.remove(Query.query(Criteria.where("familyId").is(sessionId).and("userId").is(userId)),
                RefreshTokenDocument.class);
    }

    /** The sign-in a refresh token belongs to, when it is still known. */
    public Optional<RefreshTokenDocument> find(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.mongo.findById(hash(refreshToken), RefreshTokenDocument.class));
    }

    private Session issue(String userId, String familyId, Client client, long tokenVersion, UserService.TokenClaims claims) {
        Instant now = this.clock.instant();
        String refreshToken = newToken();
        Instant refreshExpiresAt = now.plus(REFRESH_LIFETIME);
        this.mongo.insert(new RefreshTokenDocument(hash(refreshToken), familyId, userId, client.name(), tokenVersion, now,
                refreshExpiresAt, null));
        String accessToken = this.accessTokens.mint(userId, familyId, claims);
        return new Session(accessToken, this.accessTokens.lifetime().toSeconds(), refreshToken, refreshExpiresAt, client, userId);
    }

    private UserService.TokenClaims claims(String userId) {
        try {
            return this.users.tokenClaims(userId);
        }
        catch (ModuleException.Forbidden ex) {
            throw new ModuleException.Forbidden("The account is blocked", "USER_BLOCKED");
        }
    }

    private void endFamily(String familyId) {
        this.mongo.remove(Query.query(Criteria.where("familyId").is(familyId)), RefreshTokenDocument.class);
    }

    private static ModuleException invalid() {
        return new ModuleException.Unauthorized("The session has ended; sign in again", "REFRESH_INVALID");
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

package io.github.ilyaslabs.foodstack.identity.core.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserDocument;
import io.github.ilyaslabs.foodstack.identity.core.security.ChallengeTokens.Step;
import io.github.ilyaslabs.foodstack.identity.core.security.Totp;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.RateLimiter;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Two-step sign-in with an authenticator app (PRD ID-5): optional for back-office users, required
 * for the roles in {@code foodstack.identity.totp.required-roles} (ADMIN and FINANCE by default).
 * The secret is stored encrypted; each code works once; ten one-time recovery codes cover a lost
 * phone, and an administrator can remove two-step sign-in from an account.
 */
public class TwoFactorService {

    static final int CODE_TRIES = 5;

    static final Duration CODE_WINDOW = Duration.ofMinutes(15);

    static final int RECOVERY_CODES = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** What an authenticator app needs to add the account. */
    public record Enrollment(String secret, String uri) {
    }

    /** Whether the account has two-step sign-in, and whether its role requires it. */
    public record Status(boolean enabled, boolean required, int recoveryCodesLeft) {
    }

    private final MongoTemplate mongo;

    private final UserService users;

    private final PiiCipher pii;

    private final RateLimiter rateLimiter;

    private final Clock clock;

    private final Set<Role> requiredRoles;

    private final String issuer;

    public TwoFactorService(MongoTemplate mongo, UserService users, PiiCipher pii, RateLimiter rateLimiter, Clock clock,
            Collection<Role> requiredRoles, String issuer) {
        this.mongo = mongo;
        this.users = users;
        this.pii = pii;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.requiredRoles = requiredRoles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(requiredRoles);
        this.issuer = issuer;
    }

    /** The second step this account must take after proving who it is, if any. */
    public Optional<Step> secondStep(String userId) {
        UserDocument user = this.users.document(userId);
        if (user.getTotpSecretCipher() != null) {
            return Optional.of(Step.TOTP);
        }
        return required(user.getRoles()) ? Optional.of(Step.TOTP_ENROLL) : Optional.empty();
    }

    public Status status(String userId) {
        UserDocument user = this.users.document(userId);
        return new Status(user.getTotpSecretCipher() != null, required(user.getRoles()), user.getRecoveryCodeHashes().size());
    }

    public boolean required(Collection<Role> roles) {
        return roles.stream().anyMatch(this.requiredRoles::contains);
    }

    /** Back-office accounts may turn two-step sign-in on; customers and riders sign in as before. */
    static boolean allowed(Collection<Role> roles) {
        return roles.stream().anyMatch((role) -> role != Role.CUSTOMER);
    }

    /** Starts setting up an authenticator app: a new secret, kept aside until a code confirms it. */
    public Enrollment startEnrollment(String userId) {
        UserDocument user = this.users.document(userId);
        if (!allowed(user.getRoles())) {
            throw new ModuleException.Forbidden("Two-step sign-in is for back-office accounts", "TOTP_NOT_ALLOWED");
        }
        String secret = Totp.newSecret();
        this.mongo.updateFirst(byId(userId), new Update().set("totpPendingCipher", this.pii.encrypt(secret))
            .set("updatedAt", this.clock.instant()), UserDocument.class);
        String account = this.users.get(userId).email();
        return new Enrollment(secret, Totp.uri(this.issuer, account, secret));
    }

    /**
     * Confirms the app with a code from it, turns two-step sign-in on and returns the recovery
     * codes (shown once). A second confirmation replaces the secret and the recovery codes.
     */
    public List<String> confirmEnrollment(String userId, String code) {
        UserDocument user = this.users.document(userId);
        if (user.getTotpPendingCipher() == null) {
            throw new ModuleException.BadRequest("Start setting up the authenticator app first", "TOTP_NOT_STARTED");
        }
        refuseIfLocked(userId);
        String secret = this.pii.decrypt(user.getTotpPendingCipher());
        OptionalLong step = Totp.verify(secret, code, this.clock.instant(), 0);
        if (step.isEmpty()) {
            wrongCode(userId);
        }
        List<String> recovery = newRecoveryCodes();
        this.mongo.updateFirst(byId(userId), new Update().set("totpSecretCipher", user.getTotpPendingCipher())
            .unset("totpPendingCipher")
            .set("totpEnabledAt", this.clock.instant())
            .set("totpLastStep", step.getAsLong())
            .set("recoveryCodeHashes", recovery.stream().map(TwoFactorService::hash).toList())
            .set("updatedAt", this.clock.instant()), UserDocument.class);
        return recovery;
    }

    /**
     * Checks a code from the app, or one recovery code (used up). Five wrong codes per account in
     * 15 minutes answer 429.
     */
    public void verify(String userId, String code) {
        refuseIfLocked(userId);
        UserDocument user = this.users.document(userId);
        if (user.getTotpSecretCipher() == null) {
            throw new ModuleException.BadRequest("Two-step sign-in is not on for this account", "TOTP_NOT_ENABLED");
        }
        String secret = this.pii.decrypt(user.getTotpSecretCipher());
        OptionalLong step = Totp.verify(secret, code, this.clock.instant(), user.getTotpLastStep());
        if (step.isPresent()) {
            // only one request can move the step forward, so a code is never accepted twice
            boolean moved = this.mongo.updateFirst(byId(userId).addCriteria(new Criteria().orOperator(
                    Criteria.where("totpLastStep").lt(step.getAsLong()), Criteria.where("totpLastStep").exists(false))),
                    new Update().set("totpLastStep", step.getAsLong()), UserDocument.class).getModifiedCount() == 1;
            if (moved) {
                return;
            }
        }
        else if (useRecoveryCode(userId, code)) {
            return;
        }
        wrongCode(userId);
    }

    /** The user turns two-step sign-in off with a current code; not allowed where the role requires it. */
    public void disable(String userId, String code) {
        UserDocument user = this.users.document(userId);
        if (required(user.getRoles())) {
            throw new ModuleException.Conflict("Your role requires two-step sign-in", "TOTP_REQUIRED");
        }
        verify(userId, code);
        clear(userId);
    }

    /** New recovery codes, after a current code; the old ones stop working. */
    public List<String> regenerateRecoveryCodes(String userId, String code) {
        verify(userId, code);
        List<String> recovery = newRecoveryCodes();
        this.mongo.updateFirst(byId(userId), new Update().set("recoveryCodeHashes",
                recovery.stream().map(TwoFactorService::hash).toList()), UserDocument.class);
        return recovery;
    }

    /**
     * An administrator removes two-step sign-in from an account (lost phone and codes). Every
     * sign-in of the account ends; if its role requires it, it sets up a new app at the next sign-in.
     */
    public void reset(String userId) {
        this.users.document(userId);
        clear(userId);
        this.users.revokeTokens(userId, "TOTP_RESET");
    }

    private void clear(String userId) {
        this.mongo.updateFirst(byId(userId), new Update().unset("totpSecretCipher")
            .unset("totpPendingCipher")
            .unset("totpEnabledAt")
            .unset("totpLastStep")
            .unset("recoveryCodeHashes")
            .set("updatedAt", this.clock.instant()), UserDocument.class);
    }

    private boolean useRecoveryCode(String userId, String code) {
        if (code == null) {
            return false;
        }
        String normalized = code.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9]{10}")) {
            return false;
        }
        // pull is atomic: one request uses the code
        return this.mongo.updateFirst(byId(userId).addCriteria(Criteria.where("recoveryCodeHashes").is(hash(normalized))),
                new Update().pull("recoveryCodeHashes", hash(normalized)), UserDocument.class).getModifiedCount() == 1;
    }

    private void refuseIfLocked(String userId) {
        this.rateLimiter.refuseIfReached("identity", "totp", userId, CODE_TRIES, CODE_WINDOW);
    }

    private void wrongCode(String userId) {
        this.rateLimiter.check("identity", "totp", userId, CODE_TRIES, CODE_WINDOW);
        throw new ModuleException.Unauthorized("The code is not correct", "TOTP_INVALID",
                Map.of("errors", Map.of("code", "is not correct")));
    }

    /** Ten codes like {@code k3f9-x2m7qa}: 10 characters from a 32-letter alphabet (50 bits). */
    private static List<String> newRecoveryCodes() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder code = new StringBuilder();
            for (int j = 0; j < 10; j++) {
                code.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            }
            codes.add(code.substring(0, 5) + "-" + code.substring(5));
        }
        return codes;
    }

    static String hash(String code) {
        String normalized = code.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Query byId(String userId) {
        return Query.query(Criteria.where("_id").is(userId));
    }
}

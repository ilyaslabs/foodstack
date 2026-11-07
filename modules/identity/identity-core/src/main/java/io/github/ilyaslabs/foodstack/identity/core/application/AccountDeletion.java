package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.api.events.AccountDeletionScheduled;
import io.github.ilyaslabs.foodstack.identity.api.events.UserDeleted;
import io.github.ilyaslabs.foodstack.identity.core.persistence.AddressDocument;
import io.github.ilyaslabs.foodstack.identity.core.persistence.LoginKeyDocument;
import io.github.ilyaslabs.foodstack.identity.core.persistence.RefreshTokenDocument;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserDocument;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.TokenRevocations;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Account deletion at the customer's request (PRD ID-7, BR-11). The request signs the customer out
 * everywhere and schedules the deletion after a grace period; signing in again before then cancels
 * it ({@link UserService#cancelDeletion}). When it runs, the account keeps only its id: the email,
 * password, name, login key, saved addresses and refresh tokens go, and {@link UserDeleted} tells
 * the other modules to erase or anonymise what they hold. Back-office accounts are closed by an
 * admin instead.
 */
public class AccountDeletion {

    private final MongoTemplate mongo;

    private final TransactionTemplate transaction;

    private final OutboxWriter outbox;

    private final UserService users;

    private final TokenRevocations revocations;

    private final Duration grace;

    private final Clock clock;

    public AccountDeletion(MongoTemplate mongo, TransactionTemplate transaction, OutboxWriter outbox, UserService users,
            TokenRevocations revocations, Duration grace, Clock clock) {
        this.mongo = mongo;
        this.transaction = transaction;
        this.outbox = outbox;
        this.users = users;
        this.revocations = revocations;
        this.grace = grace;
        this.clock = clock;
    }

    public Duration grace() {
        return this.grace;
    }

    /**
     * Schedules the deletion and signs the customer out everywhere. Asking again keeps the first
     * date.
     * @return when the account is deleted
     */
    public Instant request(String userId) {
        UserDocument user = this.mongo.findById(userId, UserDocument.class);
        if (user == null) {
            throw new ModuleException.NotFound("User " + userId + " not found", "USER_NOT_FOUND");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ModuleException.Conflict("The account is not active", "ACCOUNT_NOT_ACTIVE");
        }
        if (!EnumSet.of(Role.CUSTOMER).containsAll(user.getRoles())) {
            throw new ModuleException.Conflict(
                    "This account has back-office access; ask the operator to close it", "ACCOUNT_HAS_STAFF_ROLES");
        }
        if (user.getDeleteAfter() != null) {
            return user.getDeleteAfter();
        }
        Instant now = this.clock.instant();
        Instant deleteAfter = now.plus(this.grace);
        this.transaction.executeWithoutResult((status) -> {
            this.mongo.updateFirst(
                    Query.query(Criteria.where("_id").is(userId).and("deleteAfter").is(null)),
                    new Update().set("deletionRequestedAt", now)
                        .set("deleteAfter", deleteAfter)
                        .set("updatedAt", now)
                        .inc("version", 1),
                    UserDocument.class);
            this.outbox.append(new AccountDeletionScheduled(userId, deleteAfter));
        });
        this.users.revokeTokens(userId, "DELETION_REQUESTED");
        return deleteAfter;
    }

    /**
     * Deletes the accounts whose grace period is over, up to {@code limit} per run. Safe on several
     * replicas at once: each account is taken by a conditional update.
     * @return how many accounts were deleted
     */
    public int runDue(int limit) {
        Instant now = this.clock.instant();
        Query due = Query
            .query(Criteria.where("status").is(UserStatus.ACTIVE).and("deleteAfter").lte(now))
            .limit(limit);
        due.fields().include("_id");
        List<UserDocument> users = this.mongo.find(due, UserDocument.class);
        int deleted = 0;
        for (UserDocument user : users) {
            if (erase(user.getId())) {
                deleted++;
            }
        }
        return deleted;
    }

    /** Erases one account whose grace period is over; {@code false} when it was cancelled or done already. */
    boolean erase(String userId) {
        Instant now = this.clock.instant();
        UserDocument before = this.transaction.execute((status) -> {
            UserDocument user = this.mongo.findAndModify(
                    Query.query(Criteria.where("_id").is(userId)
                        .and("status").is(UserStatus.ACTIVE)
                        .and("deleteAfter").lte(now)),
                    new Update().set("status", UserStatus.DELETED)
                        .set("deletedAt", now)
                        .set("updatedAt", now)
                        .set("roles", List.of())
                        .set("brandIds", List.of())
                        .set("storeIds", List.of())
                        .unset("emailCipher")
                        .unset("emailIndex")
                        .unset("passwordHash")
                        .unset("displayName")
                        .unset("deleteAfter")
                        .inc("tokenVersion", 1)
                        .inc("version", 1),
                    FindAndModifyOptions.options().returnNew(false), UserDocument.class);
            if (user == null) {
                return null;
            }
            if (user.getEmailIndex() != null) {
                this.mongo.remove(Query.query(Criteria.where("loginKey").is(user.getEmailIndex())), LoginKeyDocument.class);
            }
            this.mongo.remove(Query.query(Criteria.where("userId").is(userId)), AddressDocument.class);
            this.mongo.remove(Query.query(Criteria.where("userId").is(userId)), RefreshTokenDocument.class);
            this.outbox.append(new UserDeleted(userId));
            return user;
        });
        if (before == null) {
            return false;
        }
        this.revocations.revokeBefore(userId, before.getTokenVersion() + 1);
        return true;
    }
}

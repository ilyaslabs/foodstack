package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.core.domain.Permissions;
import io.github.ilyaslabs.foodstack.identity.core.persistence.AuditLogDocument;
import io.github.ilyaslabs.foodstack.platform.core.AuditEntry;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The audit log (PRD AD-3, ID-8): back-office writes sent by every module's web layer, and
 * back-office sign-ins, kept in {@code identity_audit_log}. Each write keeps the state before it,
 * taken from the previous entry for the same resource, so a reader sees before and after side by
 * side. Only operator staff with {@code identity:audit:read} read it.
 */
public class AuditLog {

    /** Most entries one page holds. */
    static final int MAX_PAGE = 200;

    private final MongoTemplate mongo;

    private final UserService users;

    private final Clock clock;

    public AuditLog(MongoTemplate mongo, UserService users, Clock clock) {
        this.mongo = mongo;
        this.users = users;
        this.clock = clock;
    }

    /** Keeps a write sent by a module; a repeated delivery of the same entry is ignored. */
    public void record(AuditEntry entry) {
        AuditLogDocument previous = this.mongo.findOne(Query.query(Criteria.where("resource").is(entry.resource())
            .and("kind").is(AuditLogDocument.WRITE)
            .and("at").lte(entry.at())).with(Sort.by(Sort.Direction.DESC, "at")).limit(1), AuditLogDocument.class);
        String name = (entry.actorName() != null) ? entry.actorName() : nameOf(entry.actorId());
        AuditLogDocument line = new AuditLogDocument(entry.id(), AuditLogDocument.WRITE, entry.at(), entry.actorId(), name,
                entry.roles(), entry.area(), entry.action(), entry.resource(), entry.status(), entry.request(),
                (previous != null && !previous.id().equals(entry.id())) ? previous.after() : null, entry.after(), entry.ip(),
                entry.traceId());
        insert(line);
    }

    /** Records a sign-in to the back office; customers' sign-ins are not kept. */
    public void signedIn(String userId, String name, java.util.Collection<Role> roles, String how, String ip) {
        if (!Permissions.isBackOffice(roles)) {
            return;
        }
        insert(new AuditLogDocument(Ids.newId(), AuditLogDocument.SIGN_IN, this.clock.instant(), userId, name,
                roles.stream().map(Role::name).sorted().toList(), "auth", how, null, 200, null, null, null, ip, null));
    }

    /** Records a refused sign-in to a back-office account (wrong password or code). */
    public void signInFailed(String userId, String name, java.util.Collection<Role> roles, String how, String ip) {
        if (!Permissions.isBackOffice(roles)) {
            return;
        }
        insert(new AuditLogDocument(Ids.newId(), AuditLogDocument.SIGN_IN_FAILED, this.clock.instant(), userId, name,
                roles.stream().map(Role::name).sorted().toList(), "auth", how, null, 401, null, null, null, ip, null));
    }

    /**
     * Entries newest first, for operator staff.
     *
     * @param actorId only this user's, or {@code null}
     * @param kind only this kind, or {@code null}
     * @param text only entries whose endpoint or path contains this, or {@code null}
     * @param beforeId only entries older than this one (the last of the previous page; ids are
     *        time-ordered), or {@code null}
     * @param limit most entries
     */
    public List<AuditLogDocument> search(String actorId, String kind, String text, String beforeId, int limit) {
        if (!CurrentUser.hasAuthority(Permissions.AUDIT_READ)) {
            throw new ModuleException.Forbidden("Only operator staff read the audit log", "AUDIT_ACCESS_DENIED");
        }
        List<Criteria> filters = new ArrayList<>();
        if (actorId != null && !actorId.isBlank()) {
            filters.add(Criteria.where("actorId").is(actorId));
        }
        if (kind != null && !kind.isBlank()) {
            filters.add(Criteria.where("kind").is(kind));
        }
        if (text != null && !text.isBlank()) {
            String quoted = Pattern.quote(text.strip());
            filters.add(new Criteria().orOperator(Criteria.where("action").regex(quoted, "i"),
                    Criteria.where("resource").regex(quoted, "i"), Criteria.where("actorName").regex(quoted, "i")));
        }
        if (beforeId != null && !beforeId.isBlank()) {
            filters.add(Criteria.where("_id").lt(beforeId));
        }
        Criteria criteria = filters.isEmpty() ? new Criteria() : new Criteria().andOperator(filters.toArray(Criteria[]::new));
        Query query = Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "_id")).limit(Math.clamp(limit, 1, MAX_PAGE));
        return this.mongo.find(query, AuditLogDocument.class);
    }

    private void insert(AuditLogDocument line) {
        try {
            this.mongo.insert(line);
        }
        catch (DuplicateKeyException ex) {
            // delivered again: kept once
        }
    }

    private String nameOf(String userId) {
        if (userId == null) {
            return null;
        }
        try {
            return this.users.get(userId).displayName();
        }
        catch (ModuleException ex) {
            return null;
        }
    }
}

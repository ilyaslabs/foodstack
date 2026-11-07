package io.github.ilyaslabs.foodstack.ledger.core.application;

import java.time.Clock;
import java.time.Instant;

import io.github.ilyaslabs.foodstack.ledger.api.events.StoreCreditLimitExceeded;
import io.github.ilyaslabs.foodstack.ledger.api.events.StoreCreditRestored;
import io.github.ilyaslabs.foodstack.ledger.core.domain.Accounts;
import io.github.ilyaslabs.foodstack.ledger.core.persistence.CreditDocument;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The credit limit of branches that collect card payments themselves (PRD DP-7, TDD 5.4). After
 * anything that moves a branch's balance, its debt to the operator is compared with its limit (its
 * own, else the operator's default); crossing it either way flips {@code exceeded} with a
 * conditional update and announces it in the same transaction, so payment suspends or resumes the
 * branch's direct collection once. Branches the operator collects for are never suspended.
 */
public class CreditLimits {

    private final MongoTemplate mongo;

    private final TransactionTemplate transactions;

    private final OutboxWriter outbox;

    private final SettingsApi settings;

    private final Clock clock;

    public CreditLimits(MongoTemplate mongo, TransactionTemplate transactions, OutboxWriter outbox, SettingsApi settings,
            Clock clock) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.outbox = outbox;
        this.settings = settings;
        this.clock = clock;
    }

    /** Finance changed who collects for the branch or its limit; then it is checked. */
    public void collectionChanged(String storeId, String brandId, String storeName, java.util.Set<String> ownerUserIds,
            String mode, Long creditLimitMinor) {
        this.mongo.upsert(Query.query(Criteria.where("_id").is(storeId)),
                new Update().set("brandId", brandId).set("storeName", storeName)
                    .set("ownerUserIds", (ownerUserIds != null) ? ownerUserIds : java.util.Set.of()).set("mode", mode).set("creditLimitMinor", creditLimitMinor)
                    .setOnInsert("exceeded", false).setOnInsert("owedMinor", 0L),
                CreditDocument.class);
        check(storeId);
    }

    /** The operator's default limit changed: every direct-collecting branch is checked again. */
    public void defaultChanged() {
        for (CreditDocument credit : this.mongo.find(Query.query(Criteria.where("mode").is(CreditDocument.DIRECT)),
                CreditDocument.class)) {
            check(credit.id());
        }
    }

    /** Compares the branch's debt with its limit and announces a crossing. A no-op for branches that do not collect. */
    public void check(String storeId) {
        CreditDocument credit = this.mongo.findById(storeId, CreditDocument.class);
        if (credit == null) {
            return;
        }
        long owed = Math.max(0, -Rollups.balance(this.mongo, Accounts.store(storeId)));
        long limit = limit(credit);
        boolean over = CreditDocument.DIRECT.equals(credit.mode()) && owed > limit;
        Instant now = this.clock.instant();
        if (over == credit.exceeded()) {
            this.mongo.updateFirst(Query.query(Criteria.where("_id").is(storeId)),
                    new Update().set("owedMinor", owed).set("checkedAt", now), CreditDocument.class);
            return;
        }
        String currency = this.settings.getLocale().currency();
        this.transactions.executeWithoutResult((status) -> {
            CreditDocument before = this.mongo.findAndModify(
                    Query.query(Criteria.where("_id").is(storeId).and("exceeded").is(!over)),
                    new Update().set("exceeded", over).set("owedMinor", owed).set("checkedAt", now), CreditDocument.class);
            if (before == null) {
                return;
            }
            if (over) {
                this.outbox.append(new StoreCreditLimitExceeded(storeId, credit.brandId(), credit.storeName(),
                        owners(credit), owed, limit, currency));
            }
            else {
                this.outbox.append(new StoreCreditRestored(storeId, credit.brandId(), credit.storeName(), owners(credit),
                        owed, limit, currency));
            }
        });
    }

    private static java.util.Set<String> owners(CreditDocument credit) {
        return (credit.ownerUserIds() != null) ? credit.ownerUserIds() : java.util.Set.of();
    }

    private long limit(CreditDocument credit) {
        return (credit.creditLimitMinor() != null) ? credit.creditLimitMinor() : this.settings.getOrdering().creditLimitMinor();
    }
}

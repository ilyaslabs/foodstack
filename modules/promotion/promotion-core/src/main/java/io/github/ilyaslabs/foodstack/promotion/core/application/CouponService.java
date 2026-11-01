package io.github.ilyaslabs.foodstack.promotion.core.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.promotion.api.CouponEvaluation;
import io.github.ilyaslabs.foodstack.promotion.api.CouponRequest;
import io.github.ilyaslabs.foodstack.promotion.api.events.CouponRedeemed;
import io.github.ilyaslabs.foodstack.promotion.api.events.CouponReleased;
import io.github.ilyaslabs.foodstack.promotion.core.domain.CouponRules;
import io.github.ilyaslabs.foodstack.promotion.core.persistence.PromotionDocument;
import io.github.ilyaslabs.foodstack.promotion.core.persistence.RedemptionDocument;
import org.bson.Document;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Coupon checks and uses (TDD 5.5). A use is held at placement with conditional counter updates in
 * one transaction, so a total or per-customer limit holds even when orders race; it is redeemed when
 * the order is confirmed and given back when the order is cancelled, rejected or fails.
 */
public class CouponService {

    /** Counter updates go to the raw collection, so a coupon use never changes the promotion's edit version. */
    static final String PROMOTIONS = "promotion_promotions";

    static final String CODES = "promotion_codes";

    static final String USAGE = "promotion_customer_usage";

    static final String CUSTOMERS = "promotion_customers";

    private final MongoTemplate mongo;

    private final TransactionTemplate transaction;

    private final OutboxWriter outbox;

    private final Clock clock;

    public CouponService(MongoTemplate mongo, TransactionTemplate transaction, OutboxWriter outbox, Clock clock) {
        this.mongo = mongo;
        this.transaction = transaction;
        this.outbox = outbox;
        this.clock = clock;
    }

    public CouponEvaluation evaluate(CouponRequest request) {
        String code = CouponRules.normalize(request.code());
        PromotionDocument promotion = findByCode(code);
        if (promotion == null) {
            return CouponEvaluation.rejected(code, null, CouponEvaluation.NOT_FOUND, Map.of());
        }
        return CouponRules.evaluate(promotion, request, customerUses(promotion.id(), request.customerId()),
                confirmedOrders(request.customerId()), this.clock.instant());
    }

    /** Holds one use for the order, or returns why the coupon does not apply. Repeat calls return the same hold. */
    public CouponEvaluation reserve(String orderId, CouponRequest request) {
        RedemptionDocument existing = this.mongo.findById(orderId, RedemptionDocument.class);
        if (existing != null && !RedemptionDocument.RELEASED.equals(existing.status())) {
            return evaluation(existing);
        }
        CouponEvaluation evaluation = evaluate(request);
        if (!evaluation.applies()) {
            return evaluation;
        }
        PromotionDocument promotion = this.mongo.findById(evaluation.promotionId(), PromotionDocument.class);
        Instant now = this.clock.instant();
        try {
            return this.transaction.execute((status) -> {
                Criteria limit = Criteria.where("_id").is(promotion.id());
                if (promotion.totalLimit() != null) {
                    limit = limit.and("used").lt(promotion.totalLimit());
                }
                if (this.mongo.findAndModify(Query.query(limit), new Update().inc("used", 1), Document.class, PROMOTIONS) == null) {
                    throw new LimitReached(CouponEvaluation.USED_UP);
                }
                Criteria usage = Criteria.where("_id").is(usageKey(promotion.id(), request.customerId()));
                if (promotion.perCustomerLimit() != null) {
                    usage = usage.and("count").lt(promotion.perCustomerLimit());
                }
                try {
                    this.mongo.upsert(Query.query(usage), new Update().inc("count", 1), USAGE);
                }
                catch (DuplicateKeyException ex) {
                    throw new LimitReached(CouponEvaluation.CUSTOMER_LIMIT);
                }
                this.mongo.save(new RedemptionDocument(orderId, promotion.id(), evaluation.code(), evaluation.title(),
                        request.customerId(), request.storeId(), RedemptionDocument.RESERVED, evaluation.itemsDiscountMinor(),
                        evaluation.deliveryDiscountMinor(), evaluation.operatorShareMinor(), request.currency(), now, null, null));
                return evaluation;
            });
        }
        catch (LimitReached ex) {
            return CouponEvaluation.rejected(evaluation.code(), promotion.id(), ex.reason, Map.of());
        }
    }

    /** The order was confirmed: the held use becomes a redemption. */
    public void redeem(String orderId) {
        this.transaction.executeWithoutResult((status) -> {
            RedemptionDocument redeemed = this.mongo.findAndModify(
                    Query.query(Criteria.where("_id").is(orderId).and("status").is(RedemptionDocument.RESERVED)),
                    new Update().set("status", RedemptionDocument.REDEEMED).set("redeemedAt", this.clock.instant()),
                    FindAndModifyOptions.options().returnNew(true), RedemptionDocument.class);
            if (redeemed != null) {
                this.outbox.append(new CouponRedeemed(orderId, redeemed.promotionId(), redeemed.code(), redeemed.customerId(),
                        redeemed.storeId(), redeemed.itemsDiscountMinor() + redeemed.deliveryDiscountMinor(),
                        redeemed.operatorShareMinor(), redeemed.currency()));
            }
        });
    }

    /** Gives the use back (placement failed, or the order was cancelled, rejected or failed). */
    public void release(String orderId) {
        this.transaction.executeWithoutResult((status) -> {
            RedemptionDocument before = this.mongo.findAndModify(
                    Query.query(Criteria.where("_id").is(orderId).and("status").ne(RedemptionDocument.RELEASED)),
                    new Update().set("status", RedemptionDocument.RELEASED).set("releasedAt", this.clock.instant()),
                    RedemptionDocument.class);
            if (before == null) {
                return;
            }
            this.mongo.updateFirst(Query.query(Criteria.where("_id").is(before.promotionId()).and("used").gt(0)),
                    new Update().inc("used", -1), PROMOTIONS);
            this.mongo.updateFirst(Query.query(Criteria.where("_id").is(usageKey(before.promotionId(), before.customerId()))
                .and("count").gt(0)), new Update().inc("count", -1), USAGE);
            this.outbox.append(new CouponReleased(orderId, before.promotionId(),
                    RedemptionDocument.REDEEMED.equals(before.status())));
        });
    }

    /** Customer who holds a use for the order, or {@code null} when none. */
    public String customerOf(String orderId) {
        RedemptionDocument redemption = this.mongo.findById(orderId, RedemptionDocument.class);
        return (redemption != null) ? redemption.customerId() : null;
    }

    /** Counts a confirmed order for first-order-only coupons. */
    public void recordConfirmedOrder(String customerId) {
        this.mongo.upsert(Query.query(Criteria.where("_id").is(customerId)),
                new Update().inc("confirmedOrders", 1).set("updatedAt", Date.from(this.clock.instant())), CUSTOMERS);
    }

    PromotionDocument findByCode(String code) {
        if (code.isEmpty()) {
            return null;
        }
        Document entry = this.mongo.findById(code, Document.class, CODES);
        return (entry != null) ? this.mongo.findById(entry.getString("promotionId"), PromotionDocument.class) : null;
    }

    private long customerUses(String promotionId, String customerId) {
        Document usage = this.mongo.findById(usageKey(promotionId, customerId), Document.class, USAGE);
        return (usage != null) ? ((Number) usage.get("count")).longValue() : 0;
    }

    private long confirmedOrders(String customerId) {
        Document customer = this.mongo.findById(customerId, Document.class, CUSTOMERS);
        return (customer != null) ? ((Number) customer.get("confirmedOrders")).longValue() : 0;
    }

    static String usageKey(String promotionId, String customerId) {
        return promotionId + ":" + customerId;
    }

    private static CouponEvaluation evaluation(RedemptionDocument r) {
        return new CouponEvaluation(true, null, Map.of(), r.promotionId(), r.code(), r.title(), r.itemsDiscountMinor(),
                r.deliveryDiscountMinor(), r.operatorShareMinor());
    }

    /** Ends the reservation transaction when a limit was reached between the check and the hold. */
    private static final class LimitReached extends RuntimeException {

        private final String reason;

        LimitReached(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }
}

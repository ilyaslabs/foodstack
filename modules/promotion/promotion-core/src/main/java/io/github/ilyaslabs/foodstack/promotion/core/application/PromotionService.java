package io.github.ilyaslabs.foodstack.promotion.core.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.promotion.core.domain.CouponRules;
import io.github.ilyaslabs.foodstack.promotion.core.persistence.PromotionDocument;
import io.github.ilyaslabs.foodstack.promotion.core.persistence.RedemptionDocument;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.bson.Document;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creating and editing promotions (PRD PR-1 to PR-3a). The operator's promotions may target any
 * branches or cities, payment methods and the operator's merchant account, and say how much the
 * operator funds; a store owner's promotions apply to their own brand only and are always funded by
 * the store (TDD 5.5). Edits never touch the use counter, which coupon uses change concurrently;
 * typed updates bump the version (Spring Data), counter updates do not.
 */
public class PromotionService {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9-]{2,19}");

    private static final List<String> TYPES = List.of(PromotionDocument.PERCENT, PromotionDocument.FIXED,
            PromotionDocument.FREE_DELIVERY);

    private final MongoTemplate mongo;

    private final TransactionTemplate transaction;

    private final Clock clock;

    public PromotionService(MongoTemplate mongo, TransactionTemplate transaction, Clock clock) {
        this.mongo = mongo;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Creates an operator promotion ({@code brandId == null}) or a store promotion for {@code brandId}. */
    public PromotionDetails create(String brandId, PromotionInput input, String userId) {
        boolean operator = brandId == null;
        String code = validate(input, operator);
        Instant now = this.clock.instant();
        PromotionDocument promotion = document(Ids.newId(), brandId, code, input, true, now, userId, now, userId, null);
        try {
            PromotionDocument saved = this.transaction.execute((status) -> {
                this.mongo.insert(new Document("_id", code).append("promotionId", promotion.id()), CouponService.CODES);
                return this.mongo.insert(promotion);
            });
            return details(saved);
        }
        catch (DuplicateKeyException ex) {
            throw codeTaken(code);
        }
    }

    public PromotionDetails update(String promotionId, PromotionInput input, long expectedVersion, String userId) {
        PromotionDocument current = get(promotionId);
        boolean operator = PromotionDocument.OPERATOR.equals(current.owner());
        String code = validate(input, operator);
        Instant now = this.clock.instant();
        PromotionDocument changed = document(current.id(), current.brandId(), code, input, current.active(), current.createdAt(),
                current.createdBy(), now, userId, current.version());
        try {
            PromotionDocument saved = this.transaction.execute((status) -> {
                if (!code.equals(current.code())) {
                    this.mongo.insert(new Document("_id", code).append("promotionId", current.id()), CouponService.CODES);
                    this.mongo.remove(Query.query(Criteria.where("_id").is(current.code())), CouponService.CODES);
                }
                return replaceFields(changed, expectedVersion);
            });
            return details(saved);
        }
        catch (DuplicateKeyException ex) {
            throw codeTaken(code);
        }
    }

    public PromotionDetails setActive(String promotionId, boolean active, String userId) {
        PromotionDocument saved = this.mongo.findAndModify(Query.query(Criteria.where("_id").is(promotionId)),
                new Update().set("active", active).set("updatedAt", this.clock.instant()).set("updatedBy", userId),
                FindAndModifyOptions.options().returnNew(true), PromotionDocument.class);
        if (saved == null) {
            throw notFound(promotionId);
        }
        return details(saved);
    }

    /** Promotions newest first: the operator's and every store's ({@code brandIds == null}), or those of these brands. */
    public List<PromotionDetails> list(List<String> brandIds, int limit) {
        return listIn((brandIds == null) ? null : new PromotionAccess.Scope(brandIds, List.of()), limit);
    }

    /**
     * Promotions newest first within a scope ({@code null}: all): every promotion of the scope's
     * brands, and the promotions of its branches' brands that reach those branches (PRD BB-11).
     */
    public List<PromotionDetails> listIn(PromotionAccess.Scope scope, int limit) {
        Query query;
        if (scope == null) {
            query = new Query();
        }
        else {
            List<Criteria> reach = new java.util.ArrayList<>();
            if (!scope.brandIds().isEmpty()) {
                reach.add(Criteria.where("brandId").in(scope.brandIds()));
            }
            java.util.Set<String> branchBrands = new java.util.LinkedHashSet<>();
            for (String storeId : scope.branchIds()) {
                io.github.ilyaslabs.foodstack.platform.security.CurrentUser.brandOf(storeId).ifPresent(branchBrands::add);
            }
            if (!branchBrands.isEmpty()) {
                reach.add(new Criteria().andOperator(Criteria.where("brandId").in(branchBrands),
                        new Criteria().orOperator(Criteria.where("storeIds").size(0), Criteria.where("storeIds").in(scope.branchIds()))));
            }
            if (reach.isEmpty()) {
                return List.of();
            }
            query = Query.query(new Criteria().andOperator(Criteria.where("owner").is(PromotionDocument.STORE),
                    new Criteria().orOperator(reach.toArray(Criteria[]::new))));
        }
        query.with(Sort.by(Sort.Direction.DESC, "_id")).limit(Math.clamp(limit, 1, 500));
        List<PromotionDocument> found = this.mongo.find(query, PromotionDocument.class);
        Map<String, Long> unlimitedUses = activeUses(found.stream()
            .filter((p) -> p.totalLimit() == null)
            .map(PromotionDocument::id)
            .toList());
        return found.stream().map((p) -> details(p, unlimitedUses)).toList();
    }

    public PromotionDetails details(String promotionId) {
        return details(get(promotionId));
    }

    private PromotionDetails details(PromotionDocument p) {
        return details(p, (p.totalLimit() == null) ? activeUses(List.of(p.id())) : Map.of());
    }

    /**
     * Uses held or redeemed, counted from the redemptions. Only limited coupons keep a counter on
     * their own document (TDD 7.5), so unlimited ones are counted here, for the back office only.
     */
    private Map<String, Long> activeUses(List<String> promotionIds) {
        if (promotionIds.isEmpty()) {
            return Map.of();
        }
        Aggregation count = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("promotionId").in(promotionIds).and("status").ne(RedemptionDocument.RELEASED)),
                Aggregation.group("promotionId").count().as("uses"));
        Map<String, Long> uses = new LinkedHashMap<>();
        for (Document row : this.mongo.aggregate(count, RedemptionDocument.class, Document.class)) {
            uses.put(row.getString("_id"), ((Number) row.get("uses")).longValue());
        }
        return uses;
    }

    PromotionDocument get(String promotionId) {
        PromotionDocument promotion = this.mongo.findById(promotionId, PromotionDocument.class);
        if (promotion == null) {
            throw notFound(promotionId);
        }
        return promotion;
    }

    private PromotionDocument replaceFields(PromotionDocument p, long expectedVersion) {
        Update update = new Update().set("code", p.code()).set("title", p.title()).set("type", p.type())
            .set("percentBasisPoints", p.percentBasisPoints()).set("maxDiscountMinor", p.maxDiscountMinor())
            .set("amountMinor", p.amountMinor()).set("minOrderMinor", p.minOrderMinor()).set("startsAt", p.startsAt())
            .set("endsAt", p.endsAt()).set("totalLimit", p.totalLimit()).set("perCustomerLimit", p.perCustomerLimit())
            .set("firstOrderOnly", p.firstOrderOnly()).set("storeIds", p.storeIds()).set("cities", p.cities())
            .set("paymentMethods", p.paymentMethods()).set("operatorMerchantOnly", p.operatorMerchantOnly())
            .set("operatorFundingBasisPoints", p.operatorFundingBasisPoints()).set("updatedAt", p.updatedAt())
            .set("updatedBy", p.updatedBy());
        PromotionDocument current = this.mongo.findById(p.id(), PromotionDocument.class);
        if (p.totalLimit() != null && current != null && current.totalLimit() == null) {
            // an unlimited coupon gets a limit: start its counter from the uses so far
            update.set("used", activeUses(List.of(p.id())).getOrDefault(p.id(), 0L));
        }
        PromotionDocument saved = this.mongo.findAndModify(
                Query.query(Criteria.where("_id").is(p.id()).and("version").is(expectedVersion)), update,
                FindAndModifyOptions.options().returnNew(true), PromotionDocument.class);
        if (saved == null) {
            throw new ModuleException.Conflict("The promotion was changed by someone else; reload and retry",
                    "PROMOTION_VERSION_CONFLICT");
        }
        return saved;
    }

    private static PromotionDocument document(String id, String brandId, String code, PromotionInput in, boolean active,
            Instant createdAt, String createdBy, Instant updatedAt, String updatedBy, Long version) {
        boolean operator = brandId == null;
        return new PromotionDocument(id, operator ? PromotionDocument.OPERATOR : PromotionDocument.STORE, brandId, code,
                Map.copyOf(in.title()), in.type(), PromotionDocument.PERCENT.equals(in.type()) ? in.percentBasisPoints() : 0,
                PromotionDocument.PERCENT.equals(in.type()) ? in.maxDiscountMinor() : null,
                PromotionDocument.FIXED.equals(in.type()) ? in.amountMinor() : 0, in.minOrderMinor(), in.startsAt(), in.endsAt(),
                in.totalLimit(), in.perCustomerLimit(), in.firstOrderOnly(), list(in.storeIds()),
                operator ? list(in.cities()) : List.of(), operator ? List.copyOf(Objects.requireNonNullElse(in.paymentMethods(),
                        List.of())) : List.of(),
                operator && in.operatorMerchantOnly(), operator ? in.operatorFundingBasisPoints() : 0, active, 0, createdAt,
                createdBy, updatedAt, updatedBy, version);
    }

    private static List<String> list(List<String> values) {
        return (values == null) ? List.of()
                : values.stream().filter((v) -> v != null && !v.isBlank()).map(String::strip).distinct().toList();
    }

    private static String validate(PromotionInput in, boolean operator) {
        Map<String, String> errors = new LinkedHashMap<>();
        String code = CouponRules.normalize(in.code());
        if (!CODE.matcher(code).matches()) {
            errors.put("code", "must be 3 to 20 letters, digits or hyphens");
        }
        if (in.title() == null || in.title().values().stream().allMatch((t) -> t == null || t.isBlank())
                || in.title().values().stream().anyMatch((t) -> t != null && t.length() > 80)) {
            errors.put("title", "needs a title of up to 80 characters in at least one language");
        }
        if (!TYPES.contains(in.type())) {
            errors.put("type", "must be PERCENT, FIXED or FREE_DELIVERY");
        }
        else if (PromotionDocument.PERCENT.equals(in.type())
                && (in.percentBasisPoints() < 1 || in.percentBasisPoints() > 10000)) {
            errors.put("percentBasisPoints", "must be between 0.01% and 100%");
        }
        else if (PromotionDocument.FIXED.equals(in.type()) && in.amountMinor() <= 0) {
            errors.put("amountMinor", "must be more than 0");
        }
        if (in.maxDiscountMinor() != null && in.maxDiscountMinor() <= 0) {
            errors.put("maxDiscountMinor", "must be more than 0");
        }
        if (in.minOrderMinor() < 0) {
            errors.put("minOrderMinor", "must be 0 or more");
        }
        if (in.startsAt() == null) {
            errors.put("startsAt", "is required");
        }
        else if (in.endsAt() != null && !in.endsAt().isAfter(in.startsAt())) {
            errors.put("endsAt", "must be after the start");
        }
        if (in.totalLimit() != null && in.totalLimit() < 1) {
            errors.put("totalLimit", "must be 1 or more");
        }
        if (in.perCustomerLimit() != null && in.perCustomerLimit() < 1) {
            errors.put("perCustomerLimit", "must be 1 or more");
        }
        if (operator && (in.operatorFundingBasisPoints() < 0 || in.operatorFundingBasisPoints() > 10000)) {
            errors.put("operatorFundingBasisPoints", "must be between 0% and 100%");
        }
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("The promotion is invalid", "PROMOTION_INVALID", Map.of("errors", errors));
        }
        return code;
    }

    private static PromotionDetails details(PromotionDocument p, Map<String, Long> unlimitedUses) {
        long used = (p.totalLimit() == null) ? unlimitedUses.getOrDefault(p.id(), 0L) : p.used();
        return new PromotionDetails(p.id(), p.owner(), p.brandId(), p.code(), p.title(), p.type(), p.percentBasisPoints(),
                p.maxDiscountMinor(), p.amountMinor(), p.minOrderMinor(), p.startsAt(), p.endsAt(), p.totalLimit(),
                p.perCustomerLimit(), p.firstOrderOnly(), p.storeIds(), p.cities(), p.paymentMethods(), p.operatorMerchantOnly(),
                p.operatorFundingBasisPoints(), p.active(), used, (p.version() != null) ? p.version() : 0, p.updatedAt());
    }

    private static ModuleException codeTaken(String code) {
        return new ModuleException.Conflict("The code " + code + " is already used by another promotion", "COUPON_CODE_TAKEN");
    }

    private static ModuleException notFound(String promotionId) {
        return new ModuleException.NotFound("Promotion " + promotionId + " not found", "PROMOTION_NOT_FOUND");
    }
}

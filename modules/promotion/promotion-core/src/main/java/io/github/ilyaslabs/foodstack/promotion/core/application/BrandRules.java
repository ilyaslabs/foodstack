package io.github.ilyaslabs.foodstack.promotion.core.application;

import org.bson.Document;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Each brand's switch for branch promotions (PRD BB-11), kept in {@code promotion_brand_rules}
 * from the store module's {@code BranchPromotionsChanged} events, so access checks never call the
 * store module. A brand with no document has the switch off, the store module's default.
 */
public class BrandRules {

    static final String COLLECTION = "promotion_brand_rules";

    private final MongoTemplate mongo;

    public BrandRules(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Whether the brand lets its branch managers create promotions for their own branch. */
    public boolean branchPromotionsAllowed(String brandId) {
        if (brandId == null) {
            return false;
        }
        Document rule = this.mongo.findById(brandId, Document.class, COLLECTION);
        return rule != null && Boolean.TRUE.equals(rule.getBoolean("branchPromotionsAllowed"));
    }

    /** Records the switch at the brand's {@code version}; an older version than the one kept is ignored. */
    public void apply(String brandId, boolean allowed, long version) {
        Document current = this.mongo.findById(brandId, Document.class, COLLECTION);
        if (current != null && current.get("version") instanceof Number kept && kept.longValue() > version) {
            return;
        }
        this.mongo.upsert(Query.query(Criteria.where("_id").is(brandId)),
                new Update().set("branchPromotionsAllowed", allowed).set("version", version), COLLECTION);
    }
}

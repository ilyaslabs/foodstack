package io.github.ilyaslabs.foodstack.promotion.core.persistence;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database(value = "foodstack_promotion", table = "promotion_redemptions")
public interface RedemptionRepository extends MongoRepository<RedemptionDocument, String> {
}

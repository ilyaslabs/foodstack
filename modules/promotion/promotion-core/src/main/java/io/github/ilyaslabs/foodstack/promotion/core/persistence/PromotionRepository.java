package io.github.ilyaslabs.foodstack.promotion.core.persistence;

import io.github.ilyaslabs.evl.EVL;
import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database("foodstack_promotion")
public interface PromotionRepository extends MongoRepository<PromotionDocument, String> {
}

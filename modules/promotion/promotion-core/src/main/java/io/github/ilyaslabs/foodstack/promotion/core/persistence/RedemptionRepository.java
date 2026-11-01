package io.github.ilyaslabs.foodstack.promotion.core.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface RedemptionRepository extends MongoRepository<RedemptionDocument, String> {
}

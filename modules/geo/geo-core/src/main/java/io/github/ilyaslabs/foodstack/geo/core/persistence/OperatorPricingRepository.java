package io.github.ilyaslabs.foodstack.geo.core.persistence;

import io.github.ilyaslabs.evl.EVL;
import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database("foodstack_geo")
public interface OperatorPricingRepository extends MongoRepository<OperatorPricingDocument, String> {
}

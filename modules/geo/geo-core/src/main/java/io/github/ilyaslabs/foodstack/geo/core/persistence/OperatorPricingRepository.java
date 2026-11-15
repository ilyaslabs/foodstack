package io.github.ilyaslabs.foodstack.geo.core.persistence;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database(value = "foodstack_geo", table = "geo_settings")
public interface OperatorPricingRepository extends MongoRepository<OperatorPricingDocument, String> {
}

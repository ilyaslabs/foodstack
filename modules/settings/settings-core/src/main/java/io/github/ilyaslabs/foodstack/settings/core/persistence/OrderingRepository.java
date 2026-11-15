package io.github.ilyaslabs.foodstack.settings.core.persistence;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database(value = "foodstack_settings", table = "settings_settings")
public interface OrderingRepository extends MongoRepository<OrderingDocument, String> {
}

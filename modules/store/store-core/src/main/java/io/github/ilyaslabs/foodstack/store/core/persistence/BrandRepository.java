package io.github.ilyaslabs.foodstack.store.core.persistence;

import io.github.ilyaslabs.evl.EVL;
import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database("foodstack_store")
public interface BrandRepository extends MongoRepository<BrandDocument, String> {
}

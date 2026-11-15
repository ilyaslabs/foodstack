package io.github.ilyaslabs.foodstack.store.core.persistence;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database(value = "foodstack_store", table = "store_slugs")
public interface StoreSlugRepository extends MongoRepository<StoreSlugDocument, String> {
}

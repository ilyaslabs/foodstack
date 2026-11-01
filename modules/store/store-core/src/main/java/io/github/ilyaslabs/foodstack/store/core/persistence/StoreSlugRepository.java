package io.github.ilyaslabs.foodstack.store.core.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface StoreSlugRepository extends MongoRepository<StoreSlugDocument, String> {
}

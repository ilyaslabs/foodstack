package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ItemOverrideRepository extends MongoRepository<ItemOverrideDocument, String> {

    List<ItemOverrideDocument> findByStoreId(String storeId);
}

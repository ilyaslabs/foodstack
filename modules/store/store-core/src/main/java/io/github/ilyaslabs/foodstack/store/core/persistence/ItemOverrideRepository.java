package io.github.ilyaslabs.foodstack.store.core.persistence;

import io.github.ilyaslabs.evl.EVL;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database("foodstack_store")
public interface ItemOverrideRepository extends MongoRepository<ItemOverrideDocument, String> {

    List<ItemOverrideDocument> findByStoreId(String storeId);
}

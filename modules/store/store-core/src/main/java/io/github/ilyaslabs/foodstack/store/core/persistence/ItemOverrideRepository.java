package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.util.List;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database(value = "foodstack_store", table = "store_item_overrides")
public interface ItemOverrideRepository extends MongoRepository<ItemOverrideDocument, String> {

    List<ItemOverrideDocument> findByStoreId(String storeId);
}

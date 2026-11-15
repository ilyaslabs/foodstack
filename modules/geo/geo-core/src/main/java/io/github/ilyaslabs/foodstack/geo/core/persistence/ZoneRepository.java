package io.github.ilyaslabs.foodstack.geo.core.persistence;

import java.util.List;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database(value = "foodstack_geo", table = "geo_zones")
public interface ZoneRepository extends MongoRepository<ZoneDocument, String> {

    List<ZoneDocument> findByStoreIdOrderBySortOrderAsc(String storeId);

    long deleteByStoreId(String storeId);
}

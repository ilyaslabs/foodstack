package io.github.ilyaslabs.foodstack.geo.core.persistence;

import io.github.ilyaslabs.evl.EVL;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database("foodstack_geo")
public interface ZoneRepository extends MongoRepository<ZoneDocument, String> {

    List<ZoneDocument> findByStoreIdOrderBySortOrderAsc(String storeId);

    long deleteByStoreId(String storeId);
}

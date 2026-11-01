package io.github.ilyaslabs.foodstack.geo.core.persistence;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ZoneRepository extends MongoRepository<ZoneDocument, String> {

    List<ZoneDocument> findByStoreIdOrderBySortOrderAsc(String storeId);

    long deleteByStoreId(String storeId);
}

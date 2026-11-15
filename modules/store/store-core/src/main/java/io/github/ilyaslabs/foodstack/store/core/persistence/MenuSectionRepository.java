package io.github.ilyaslabs.foodstack.store.core.persistence;

import io.github.ilyaslabs.evl.EVL;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database("foodstack_store")
public interface MenuSectionRepository extends MongoRepository<MenuSectionDocument, String> {

    List<MenuSectionDocument> findByBrandIdOrderBySortOrderAsc(String brandId);

    long countByBrandId(String brandId);
}

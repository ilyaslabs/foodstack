package io.github.ilyaslabs.foodstack.store.core.persistence;

import io.github.ilyaslabs.evl.EVL;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database("foodstack_store")
public interface MenuItemRepository extends MongoRepository<MenuItemDocument, String> {

    List<MenuItemDocument> findByBrandIdAndDeletedAtIsNullOrderBySortOrderAsc(String brandId);

    long countByBrandIdAndSectionIdAndDeletedAtIsNull(String brandId, String sectionId);

    long countByBrandIdAndDeletedAtIsNull(String brandId);
}

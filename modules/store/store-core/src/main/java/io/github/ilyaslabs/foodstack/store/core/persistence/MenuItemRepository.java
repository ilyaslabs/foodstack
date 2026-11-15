package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.util.List;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database(value = "foodstack_store", table = "store_menu_items")
public interface MenuItemRepository extends MongoRepository<MenuItemDocument, String> {

    List<MenuItemDocument> findByBrandIdAndDeletedAtIsNullOrderBySortOrderAsc(String brandId);

    long countByBrandIdAndSectionIdAndDeletedAtIsNull(String brandId, String sectionId);

    long countByBrandIdAndDeletedAtIsNull(String brandId);
}

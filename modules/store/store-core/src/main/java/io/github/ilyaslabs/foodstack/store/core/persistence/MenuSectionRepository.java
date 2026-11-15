package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.util.List;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database(value = "foodstack_store", table = "store_menu_sections")
public interface MenuSectionRepository extends MongoRepository<MenuSectionDocument, String> {

    List<MenuSectionDocument> findByBrandIdOrderBySortOrderAsc(String brandId);

    long countByBrandId(String brandId);
}

package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MenuSectionRepository extends MongoRepository<MenuSectionDocument, String> {

    List<MenuSectionDocument> findByBrandIdOrderBySortOrderAsc(String brandId);

    long countByBrandId(String brandId);
}

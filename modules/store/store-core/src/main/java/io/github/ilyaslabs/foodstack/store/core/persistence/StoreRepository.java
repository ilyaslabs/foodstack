package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface StoreRepository extends MongoRepository<StoreDocument, String> {

    List<StoreDocument> findByBrandId(String brandId);

    List<StoreDocument> findByIdGreaterThanOrderByIdAsc(String afterId, Limit limit);

    List<StoreDocument> findAllByOrderByIdDesc(Limit limit);

    List<StoreDocument> findByIdInOrderByIdDesc(Collection<String> ids);
}

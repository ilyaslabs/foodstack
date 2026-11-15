package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.util.Collection;
import java.util.List;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database(value = "foodstack_store", table = "store_stores")
public interface StoreRepository extends MongoRepository<StoreDocument, String> {

    List<StoreDocument> findByBrandId(String brandId);

    List<StoreDocument> findByIdGreaterThanOrderByIdAsc(String afterId, Limit limit);

    List<StoreDocument> findAllByOrderByIdDesc(Limit limit);

    List<StoreDocument> findByIdInOrderByIdDesc(Collection<String> ids);

    /** Branches that have no web address yet (onboarded before slugs, or written by the demo seeder). */
    List<StoreDocument> findBySlugIsNull(Limit limit);
}

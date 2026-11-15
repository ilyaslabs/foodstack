package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.util.List;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database(value = "foodstack_store", table = "store_price_changes")
public interface PriceChangeRepository extends MongoRepository<PriceChangeDocument, String> {

    List<PriceChangeDocument> findByStatusOrderByRequestedAtAsc(String status, Pageable page);

    List<PriceChangeDocument> findByBrandIdAndStatus(String brandId, String status);
}

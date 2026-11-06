package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PriceChangeRepository extends MongoRepository<PriceChangeDocument, String> {

    List<PriceChangeDocument> findByStatusOrderByRequestedAtAsc(String status, Pageable page);

    List<PriceChangeDocument> findByBrandIdAndStatus(String brandId, String status);
}

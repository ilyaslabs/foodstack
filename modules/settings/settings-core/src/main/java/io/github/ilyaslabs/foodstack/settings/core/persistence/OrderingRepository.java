package io.github.ilyaslabs.foodstack.settings.core.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderingRepository extends MongoRepository<OrderingDocument, String> {
}

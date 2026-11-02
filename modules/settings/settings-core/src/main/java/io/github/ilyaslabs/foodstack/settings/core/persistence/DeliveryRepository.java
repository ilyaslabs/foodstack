package io.github.ilyaslabs.foodstack.settings.core.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface DeliveryRepository extends MongoRepository<DeliveryDocument, String> {
}

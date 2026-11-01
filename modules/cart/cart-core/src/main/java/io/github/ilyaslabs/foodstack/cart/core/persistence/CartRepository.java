package io.github.ilyaslabs.foodstack.cart.core.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface CartRepository extends MongoRepository<CartDocument, String> {
}

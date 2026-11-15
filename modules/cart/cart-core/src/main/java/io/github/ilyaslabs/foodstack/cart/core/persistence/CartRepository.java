package io.github.ilyaslabs.foodstack.cart.core.persistence;

import io.github.ilyaslabs.evl.EVL;
import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database("foodstack_cart")
public interface CartRepository extends MongoRepository<CartDocument, String> {
}

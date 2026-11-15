package io.github.ilyaslabs.foodstack.cart.core.persistence;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database(value = "foodstack_cart", table = "cart_carts")
public interface CartRepository extends MongoRepository<CartDocument, String> {
}

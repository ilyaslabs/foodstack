package io.github.ilyaslabs.foodstack.settings.core.persistence;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Repository(id = "settings.ordering-repository", name = "Ordering Rules Repository",
        models = "settings.ordering", storage = "MongoDB foodstack_settings")
public interface OrderingRepository extends MongoRepository<OrderingDocument, String> {
}

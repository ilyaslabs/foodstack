package io.github.ilyaslabs.foodstack.settings.core.persistence;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Repository(id = "settings.delivery-repository", name = "Delivery Rules Repository",
        models = "settings.delivery", storage = "MongoDB foodstack_settings")
public interface DeliveryRepository extends MongoRepository<DeliveryDocument, String> {
}

package io.github.ilyaslabs.foodstack.settings.core.persistence;

import io.github.ilyaslabs.evl.EVL;
import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database("foodstack_settings")
public interface BrandingRepository extends MongoRepository<BrandingDocument, String> {
}

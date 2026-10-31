package io.github.ilyaslabs.foodstack.settings.core.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface BrandingRepository extends MongoRepository<BrandingDocument, String> {
}

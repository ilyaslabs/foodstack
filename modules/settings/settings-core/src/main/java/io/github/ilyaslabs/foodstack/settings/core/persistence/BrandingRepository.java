package io.github.ilyaslabs.foodstack.settings.core.persistence;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Repository(id = "settings.branding-repository", name = "Branding Repository", models = "settings.branding",
        storage = "MongoDB foodstack_settings")
public interface BrandingRepository extends MongoRepository<BrandingDocument, String> {
}

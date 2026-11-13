package io.github.ilyaslabs.foodstack.settings.core.persistence;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Repository(id = "settings.locale-repository", name = "Locale Repository", models = "settings.locale",
        storage = "MongoDB foodstack_settings")
public interface LocaleRepository extends MongoRepository<LocaleDocument, String> {
}

package io.github.ilyaslabs.foodstack.settings.core.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface LocaleRepository extends MongoRepository<LocaleDocument, String> {
}

package io.github.ilyaslabs.foodstack.identity.core.persistence;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface LoginKeyRepository extends MongoRepository<LoginKeyDocument, String> {

    Optional<LoginKeyDocument> findByLoginKey(String loginKey);
}

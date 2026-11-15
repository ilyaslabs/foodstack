package io.github.ilyaslabs.foodstack.identity.core.persistence;

import io.github.ilyaslabs.evl.EVL;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database("foodstack_identity")
public interface LoginKeyRepository extends MongoRepository<LoginKeyDocument, String> {

    Optional<LoginKeyDocument> findByLoginKey(String loginKey);
}

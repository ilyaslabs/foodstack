package io.github.ilyaslabs.foodstack.identity.core.persistence;

import java.util.Optional;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database(value = "foodstack_identity", table = "identity_login_keys")
public interface LoginKeyRepository extends MongoRepository<LoginKeyDocument, String> {

    Optional<LoginKeyDocument> findByLoginKey(String loginKey);
}

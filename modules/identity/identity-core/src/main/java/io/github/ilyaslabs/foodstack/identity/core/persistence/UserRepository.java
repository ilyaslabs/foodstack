package io.github.ilyaslabs.foodstack.identity.core.persistence;

import java.util.List;

import io.github.ilyaslabs.foodstack.identity.api.Role;

import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRepository extends MongoRepository<UserDocument, String> {

    /** Back-office listing; scatter-gather is fine for this rare admin query (TDD 6.3). */
    List<UserDocument> findByRolesInOrderByIdDesc(List<Role> roles, Limit limit);

    boolean existsByRolesContaining(Role role);
}

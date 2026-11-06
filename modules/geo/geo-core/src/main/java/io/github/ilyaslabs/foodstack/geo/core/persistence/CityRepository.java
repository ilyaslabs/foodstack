package io.github.ilyaslabs.foodstack.geo.core.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface CityRepository extends MongoRepository<CityDocument, String> {
}

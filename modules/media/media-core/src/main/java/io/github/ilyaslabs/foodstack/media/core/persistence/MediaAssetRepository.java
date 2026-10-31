package io.github.ilyaslabs.foodstack.media.core.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Assets by id. Bound to {@code mediaMongoTemplate}, the media module's own database. */
public interface MediaAssetRepository extends MongoRepository<MediaAssetDocument, String> {
}

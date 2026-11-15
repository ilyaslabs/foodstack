package io.github.ilyaslabs.foodstack.media.core.persistence;

import io.github.ilyaslabs.evl.EVL;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Assets by id. Bound to {@code mediaMongoTemplate}, the media module's own database. */
@EVL.Database("foodstack_media")
public interface MediaAssetRepository extends MongoRepository<MediaAssetDocument, String> {
}

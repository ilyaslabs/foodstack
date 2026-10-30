package io.github.ilyaslabs.foodstack.media.core.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaStatus;
import io.github.ilyaslabs.foodstack.media.core.MediaPersistenceConfiguration;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@SpringBootTest(classes = MediaAssetRepositoryTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class MediaAssetRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MediaPersistenceConfiguration.class)
    static class App {
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    MediaAssetRepository repository;

    @Autowired
    MongoTemplate mediaMongoTemplate;

    @Test
    @DisplayName("stores assets in media_assets of the media database, sharded on hashed _id")
    void storesInOwnDatabase() {
        // given
        MediaAssetDocument asset = asset();
        asset.setVariants(Map.of("thumb", new MediaAssetDocument.Variant("b", "k", 160, 80, 1234)));
        asset.setOwner(new MediaAssetDocument.Owner("settings", "branding"));

        // when
        this.repository.save(asset);
        MediaAssetDocument found = this.repository.findById(asset.getId()).orElseThrow();

        // then
        assertThat(found.getVariants().get("thumb").width()).isEqualTo(160);
        assertThat(found.getOwner()).isEqualTo(new MediaAssetDocument.Owner("settings", "branding"));
        assertThat(this.mediaMongoTemplate.getDb().getName()).endsWith("_media");
        Document sharding = this.mediaMongoTemplate.getMongoDatabaseFactory().getMongoDatabase("config")
            .getCollection("collections")
            .find(new Document("_id", this.mediaMongoTemplate.getDb().getName() + ".media_assets"))
            .first();
        assertThat(sharding.get("key", Document.class)).isEqualTo(new Document("_id", "hashed"));
    }

    @Test
    @DisplayName("rejects a write based on a stale version")
    void optimisticLocking() {
        // given
        MediaAssetDocument saved = this.repository.save(asset());
        MediaAssetDocument first = this.repository.findById(saved.getId()).orElseThrow();
        MediaAssetDocument second = this.repository.findById(saved.getId()).orElseThrow();
        first.setStatus(MediaStatus.READY);
        this.repository.save(first);

        // when / then
        second.setStatus(MediaStatus.FAILED);
        assertThatExceptionOfType(OptimisticLockingFailureException.class).isThrownBy(() -> this.repository.save(second));
    }

    private static MediaAssetDocument asset() {
        MediaAssetDocument asset = new MediaAssetDocument();
        asset.setId(Ids.newId());
        asset.setPurpose(MediaPurpose.BRANDING);
        asset.setStatus(MediaStatus.PENDING_UPLOAD);
        asset.setContentType("image/png");
        asset.setDeclaredBytes(100);
        asset.setUploadKey("uploads/" + asset.getId());
        asset.setCreatedBy("user-1");
        asset.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        asset.setUpdatedAt(asset.getCreatedAt());
        return asset;
    }
}

package io.github.ilyaslabs.foodstack.media.core;

import io.github.ilyaslabs.foodstack.media.core.persistence.MediaAssetRepository;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/** The media module's own database {@code foodstack_media}, its migrations and repositories. */
@Configuration(proxyBeanMethods = false)
@EnableMongoRepositories(basePackageClasses = MediaAssetRepository.class, mongoTemplateRef = "mediaMongoTemplate")
public class MediaPersistenceConfiguration {

    public static final String MODULE = "media";

    @Bean
    ModuleMongo mediaMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTemplate mediaMongoTemplate(ModuleMongo mediaMongo) {
        return mediaMongo.template();
    }

    @Bean
    MongoTransactionManager mediaTransactionManager(ModuleMongo mediaMongo) {
        return mediaMongo.transactionManager();
    }

    @Bean
    ModuleMigrations mediaMigrations(ModuleMongo mediaMongo, ModuleMongoFactory factory,
            FoodstackMongoProperties properties) {
        return new ModuleMigrations(mediaMongo, factory.connectionString(MODULE),
                ShardKeys.builder().hashed("media_assets", "_id"), properties);
    }
}

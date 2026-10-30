package io.github.ilyaslabs.foodstack.settings.core.persistence;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.mongodb.core.MongoTemplate;

import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@SpringBootTest(classes = SettingsRepositoriesTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class SettingsRepositoriesTest {

    /** The persistence part of SettingsConfiguration, without Kafka and Redis. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMongoRepositories(basePackageClasses = BrandingRepository.class, mongoTemplateRef = "settingsMongoTemplate")
    static class App {

        @Bean
        ModuleMongo settingsMongo(ModuleMongoFactory factory) {
            return factory.create("settings");
        }

        @Bean
        MongoTemplate settingsMongoTemplate(ModuleMongo settingsMongo) {
            return settingsMongo.template();
        }

        @Bean
        ModuleMigrations settingsMigrations(ModuleMongo settingsMongo, ModuleMongoFactory factory,
                FoodstackMongoProperties properties) {
            return new ModuleMigrations(settingsMongo, factory.connectionString("settings"), ShardKeys.none(), properties);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    BrandingRepository brandingRepository;

    @Autowired
    LocaleRepository localeRepository;

    @Test
    @DisplayName("the changelog creates default branding and locale")
    void defaults() {
        BrandingDocument branding = this.brandingRepository.findById(BrandingDocument.ID).orElseThrow();
        LocaleDocument locale = this.localeRepository.findById(LocaleDocument.ID).orElseThrow();

        assertThat(branding.getName()).isEqualTo("FoodStack");
        assertThat(branding.getPrimaryColor()).isEqualTo("#D84315");
        assertThat(branding.getVersion()).isNotNull();
        assertThat(locale.getLanguages()).containsExactly("en");
        assertThat(locale.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("versions every change and rejects writes based on a stale version")
    void versioning() {
        BrandingDocument first = this.brandingRepository.findById(BrandingDocument.ID).orElseThrow();
        BrandingDocument stale = this.brandingRepository.findById(BrandingDocument.ID).orElseThrow();
        long before = first.getVersion();

        first.setTagline("Changed");
        BrandingDocument saved = this.brandingRepository.save(first);

        assertThat(saved.getVersion()).isEqualTo(before + 1);
        stale.setTagline("Lost update");
        assertThatExceptionOfType(OptimisticLockingFailureException.class).isThrownBy(() -> this.brandingRepository.save(stale));
    }
}

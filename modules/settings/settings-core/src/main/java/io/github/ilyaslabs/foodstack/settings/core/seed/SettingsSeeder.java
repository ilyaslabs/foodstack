package io.github.ilyaslabs.foodstack.settings.core.seed;

import java.time.Instant;

import io.github.ilyaslabs.foodstack.settings.core.application.SettingsService;
import io.github.ilyaslabs.foodstack.settings.core.application.UpdateBranding;
import io.github.ilyaslabs.foodstack.settings.core.application.UpdateLocale;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.Document;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Demo branding and languages for the {@code test-seed} profile (TDD 16). Writes only the settings
 * module's own data, once (marker in {@code settings_seed}).
 */
@Component
@Profile("test-seed")
class SettingsSeeder {

    private static final Log logger = LogFactory.getLog(SettingsSeeder.class);

    private final SettingsService settings;

    private final MongoTemplate mongo;

    SettingsSeeder(SettingsService settings, MongoTemplate settingsMongoTemplate) {
        this.settings = settings;
        this.mongo = settingsMongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(20)
    void seed() {
        try {
            this.mongo.insert(new Document("_id", "v1").append("seededAt", Instant.now()), "settings_seed");
        }
        catch (DuplicateKeyException ex) {
            return;
        }
        long brandingVersion = this.settings.getBranding().version();
        this.settings.updateBranding(new UpdateBranding("FoodStack Demo", "Tasty food from local kitchens", "#C62828",
                "#2E7D32", "Roboto", null), brandingVersion, "seed");
        long localeVersion = this.settings.getLocale().version();
        this.settings.updateLocale(new UpdateLocale("en", java.util.List.of("en", "ar", "ur"), "PKR", "Asia/Karachi"),
                localeVersion, "seed");
        logger.info("Seeded demo branding and languages (en, ar, ur)");
    }
}

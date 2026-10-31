package io.github.ilyaslabs.foodstack.platform.mongo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code foodstack.mongodb}. The cluster itself is configured with
 * {@code spring.mongodb.uri}; each module uses its own database on it, {@code <prefix><module>}.
 * <p>
 * A module can be pointed at a dedicated cluster with {@code foodstack.modules.<module>.mongodb.uri}.
 */
@ConfigurationProperties("foodstack.mongodb")
public class FoodstackMongoProperties {

    /** Prefix of every module database: {@code foodstack_} gives {@code foodstack_order}. */
    private String databasePrefix = "foodstack_";

    private final Sharding sharding = new Sharding();

    private final Migrations migrations = new Migrations();

    public String getDatabasePrefix() {
        return this.databasePrefix;
    }

    public void setDatabasePrefix(String databasePrefix) {
        this.databasePrefix = databasePrefix;
    }

    public Sharding getSharding() {
        return this.sharding;
    }

    public Migrations getMigrations() {
        return this.migrations;
    }

    public static class Sharding {

        /**
         * Apply every module's declared shard keys after migrations. Requires a connection
         * through {@code mongos}. Tests always switch this on.
         */
        private boolean enabled;

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Migrations {

        /** Run each local module's Liquibase changelog at startup. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}

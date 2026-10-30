package io.github.ilyaslabs.foodstack.platform.mongo;

import java.util.Map;

import com.mongodb.client.MongoDatabase;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.ext.mongodb.database.MongoLiquibaseDatabase;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.Document;

import org.springframework.beans.factory.InitializingBean;

/**
 * Runs a module's Liquibase changelog {@code db/changelog/<module>/db.changelog-master.yaml}
 * against its own database, with tracking collections {@code <module>_databasechangelog} and
 * {@code <module>_databasechangeloglock} (TDD 6.4), then applies its shard keys when sharding is
 * switched on.
 * <p>
 * Registered by each module's configuration class, so only {@code local} modules migrate.
 */
public class ModuleMigrations implements InitializingBean {

    private static final Log logger = LogFactory.getLog(ModuleMigrations.class);

    private final ModuleMongo mongo;

    private final String connectionString;

    private final ShardKeys shardKeys;

    private final FoodstackMongoProperties properties;

    public ModuleMigrations(ModuleMongo mongo, String connectionString, ShardKeys shardKeys,
            FoodstackMongoProperties properties) {
        this.mongo = mongo;
        this.connectionString = connectionString;
        this.shardKeys = shardKeys;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (this.properties.getMigrations().isEnabled()) {
            migrate();
        }
        if (this.properties.getSharding().isEnabled()) {
            applyShardKeys();
        }
    }

    public static String changelogPath(String module) {
        return "db/changelog/" + module + "/db.changelog-master.yaml";
    }

    /** Applies pending change sets. Liquibase opens and closes its own connection. */
    public void migrate() throws Exception {
        String module = this.mongo.module();
        MongoLiquibaseDatabase database = (MongoLiquibaseDatabase) DatabaseFactory.getInstance()
            .openDatabase(this.connectionString, null, null, null, new ClassLoaderResourceAccessor());
        database.setDatabaseChangeLogTableName(module + "_databasechangelog");
        database.setDatabaseChangeLogLockTableName(module + "_databasechangeloglock");
        try (Liquibase liquibase = new Liquibase(changelogPath(module), new ClassLoaderResourceAccessor(), database)) {
            liquibase.setChangeLogParameter("module", module);
            liquibase.update();
        }
        logger.info("Migrated module '" + module + "' (database " + this.mongo.databaseName() + ")");
    }

    /** Shards each declared collection that is not sharded yet. Requires {@code mongos}. */
    public void applyShardKeys() {
        Map<String, Document> keys = this.shardKeys.asMap();
        if (keys.isEmpty()) {
            return;
        }
        String db = this.mongo.databaseName();
        MongoDatabase admin = this.mongo.databaseFactory().getMongoDatabase("admin");
        admin.runCommand(new Document("enableSharding", db));
        MongoDatabase config = this.mongo.databaseFactory().getMongoDatabase("config");
        keys.forEach((collection, key) -> {
            String namespace = db + "." + collection;
            boolean sharded = config.getCollection("collections")
                .find(new Document("_id", namespace).append("unsplittable", new Document("$ne", true)))
                .first() != null;
            if (!sharded) {
                admin.runCommand(new Document("shardCollection", namespace).append("key", key));
                logger.info("Sharded " + namespace + " on " + key.toJson());
            }
        });
    }
}

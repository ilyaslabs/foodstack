package io.github.ilyaslabs.foodstack.platform.mongo;

import java.util.ArrayList;
import java.util.List;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = MongoTestApplication.class, properties = "spring.data.mongodb.repositories.type=none")
class ModuleMigrationsTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ModuleMongo testmodMongo;

    @Autowired
    ModuleMigrations testmodMigrations;

    @Test
    @DisplayName("creates the module's collections in its own database with module-prefixed tracking collections")
    void migratesIntoOwnDatabase() {
        // when (migrations ran at startup)
        List<String> collections = this.testmodMongo.template().getDb().listCollectionNames().into(new ArrayList<>());

        // then
        assertThat(this.testmodMongo.databaseName()).endsWith("_testmod");
        assertThat(collections).contains("testmod_things", "testmod_outbox", "testmod_inbox",
                "testmod_databasechangelog", "testmod_databasechangeloglock");
    }

    @Test
    @DisplayName("shards declared collections on their shard key and leaves the others unsharded")
    void appliesShardKeys() {
        // given
        Document config = this.testmodMongo.databaseFactory().getMongoDatabase("config")
            .getCollection("collections")
            .find(new Document("_id", this.testmodMongo.databaseName() + ".testmod_things"))
            .first();

        // then
        assertThat(config).isNotNull();
        assertThat(config.get("key", Document.class)).isEqualTo(new Document("_id", "hashed"));
    }

    @Test
    @DisplayName("shards the platform outbox by work bucket and the inbox by hashed id (TDD 7.5)")
    void shardsOutboxAndInbox() {
        var collections = this.testmodMongo.databaseFactory().getMongoDatabase("config").getCollection("collections");
        String db = this.testmodMongo.databaseName();

        Document outbox = collections.find(new Document("_id", db + ".testmod_outbox")).first();
        Document inbox = collections.find(new Document("_id", db + ".testmod_inbox")).first();

        assertThat(outbox).isNotNull();
        assertThat(outbox.get("key", Document.class)).isEqualTo(new Document("bucket", 1).append("_id", 1));
        assertThat(inbox).isNotNull();
        assertThat(inbox.get("key", Document.class)).isEqualTo(new Document("_id", "hashed"));
    }

    @Test
    @DisplayName("shards a collection that already holds data, as when a replica set becomes a sharded cluster")
    void shardsPopulatedCollection(@Autowired ModuleMongoFactory factory, @Autowired FoodstackMongoProperties properties) {
        // given: rows written before sharding, with no index on the shard key
        var things = this.testmodMongo.template().getCollection("testmod_late");
        things.insertMany(List.of(new Document("_id", "a").append("storeId", "s1"),
                new Document("_id", "b").append("storeId", "s2")));
        ModuleMigrations later = new ModuleMigrations(this.testmodMongo, factory.connectionString("testmod"),
                ShardKeys.builder().hashed("testmod_late", "storeId"), properties);

        // when
        later.applyShardKeys();

        // then
        Document config = this.testmodMongo.databaseFactory().getMongoDatabase("config")
            .getCollection("collections")
            .find(new Document("_id", this.testmodMongo.databaseName() + ".testmod_late"))
            .first();
        assertThat(config).isNotNull();
        assertThat(config.get("key", Document.class)).isEqualTo(new Document("storeId", "hashed"));
        assertThat(things.countDocuments()).isEqualTo(2);
    }

    @Test
    @DisplayName("running the migrations again changes nothing")
    void idempotent() throws Exception {
        // when
        this.testmodMigrations.migrate();
        this.testmodMigrations.applyShardKeys();

        // then
        long changeSets = this.testmodMongo.template().getCollection("testmod_databasechangelog").countDocuments();
        assertThat(changeSets).isEqualTo(5);
    }
}

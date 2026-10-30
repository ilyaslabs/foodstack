package io.github.ilyaslabs.foodstack.platform.mongo;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@SpringBootTest(classes = MongoTestApplication.class, properties = "spring.data.mongodb.repositories.type=none")
class OutboxWriterTest {

    record ThingRenamed(String thingId, String name) implements ModuleEvent {

        @Override
        public String subject() {
            return this.thingId;
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ModuleMongo testmodMongo;

    @Autowired
    OutboxWriter testmodOutbox;

    @Test
    @DisplayName("stores the event with the aggregate write in one transaction")
    void storesWithAggregate() {
        // when
        String eventId = this.testmodMongo.transactionTemplate().execute((status) -> {
            this.testmodMongo.template().insert(new Document("_id", "thing-1").append("name", "Soup"), "testmod_things");
            return this.testmodOutbox.append(new ThingRenamed("thing-1", "Soup"));
        });

        // then
        Document stored = this.testmodMongo.template().findById(eventId, Document.class, "testmod_outbox");
        assertThat(stored).isNotNull();
        assertThat(stored.getString("type")).isEqualTo("foodstack.testmod.ThingRenamed.v1");
        assertThat(stored.getString("source")).isEqualTo("foodstack/testmod");
        assertThat(stored.getString("subject")).isEqualTo("thing-1");
        assertThat(stored.getString("status")).isEqualTo("NEW");
        assertThat(stored.getString("data")).isEqualTo("{\"thingId\":\"thing-1\",\"name\":\"Soup\"}");
    }

    @Test
    @DisplayName("stores nothing when the transaction rolls back")
    void rollsBackWithAggregate() {
        // when
        long before = this.testmodMongo.template().getCollection("testmod_outbox").countDocuments();
        try {
            this.testmodMongo.transactionTemplate().executeWithoutResult((status) -> {
                this.testmodOutbox.append(new ThingRenamed("thing-2", "Tea"));
                throw new IllegalStateException("boom");
            });
        }
        catch (IllegalStateException expected) {
            // rolled back
        }

        // then
        assertThat(this.testmodMongo.template().getCollection("testmod_outbox").countDocuments()).isEqualTo(before);
    }

    @Test
    @DisplayName("refuses to write outside a transaction")
    void requiresTransaction() {
        assertThatIllegalStateException().isThrownBy(() -> this.testmodOutbox.append(new ThingRenamed("x", "y")));
    }
}

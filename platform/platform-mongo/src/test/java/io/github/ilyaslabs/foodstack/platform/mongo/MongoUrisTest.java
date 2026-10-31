package io.github.ilyaslabs.foodstack.platform.mongo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MongoUrisTest {

    @Test
    @DisplayName("puts the module database into the path and keeps the options")
    void replacesDatabase() {
        assertThat(MongoUris.withDatabase("mongodb://h1:27017,h2:27017/?replicaSet=rs0", "foodstack_order"))
            .isEqualTo("mongodb://h1:27017,h2:27017/foodstack_order?replicaSet=rs0");
        assertThat(MongoUris.withDatabase("mongodb://localhost:27017", "foodstack_order"))
            .isEqualTo("mongodb://localhost:27017/foodstack_order");
    }

    @Test
    @DisplayName("keeps authenticating against the original database when credentials are present")
    void keepsAuthSource() {
        assertThat(MongoUris.withDatabase("mongodb://u:p@h:27017/foodstack?replicaSet=rs0", "foodstack_order"))
            .isEqualTo("mongodb://u:p@h:27017/foodstack_order?replicaSet=rs0&authSource=foodstack");
        assertThat(MongoUris.withDatabase("mongodb+srv://u:p@cluster.example.com", "foodstack_order"))
            .isEqualTo("mongodb+srv://u:p@cluster.example.com/foodstack_order?authSource=admin");
        assertThat(MongoUris.withDatabase("mongodb://u:p@h/?authSource=other", "foodstack_order"))
            .isEqualTo("mongodb://u:p@h/foodstack_order?authSource=other");
    }
}

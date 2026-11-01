package io.github.ilyaslabs.foodstack.cart.core.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.foodstack.cart.core.CartConfiguration;
import io.github.ilyaslabs.foodstack.cart.core.application.CartService;
import io.github.ilyaslabs.foodstack.cart.core.persistence.CartDocument.CartLine;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.foodstack.store.api.StoreApi;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = CartRepositoryTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class CartRepositoryTest {

    /** The persistence part of CartConfiguration. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMongoRepositories(basePackageClasses = CartRepository.class, mongoTemplateRef = "cartMongoTemplate")
    static class App {

        @Bean
        ModuleMongo cartMongo(ModuleMongoFactory factory) {
            return factory.create("cart");
        }

        @Bean
        MongoTemplate cartMongoTemplate(ModuleMongo cartMongo) {
            return cartMongo.template();
        }

        @Bean
        ModuleMigrations cartMigrations(ModuleMongo cartMongo, ModuleMongoFactory factory, FoodstackMongoProperties properties) {
            return new ModuleMigrations(cartMongo, factory.connectionString("cart"), CartConfiguration.shardKeys(), properties);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    CartRepository carts;

    @Autowired
    MongoTemplate cartMongoTemplate;

    @Test
    @DisplayName("keeps one versioned cart per customer and refuses a write based on a stale version")
    void versioned() {
        String customer = Ids.newId();
        CartDocument first = this.carts.save(cart(customer, null));
        assertThat(first.version()).isZero();

        CartDocument second = this.carts.save(new CartDocument(customer, "s1", List.of(line(), line()), Instant.now(),
                first.version()));
        assertThat(second.version()).isEqualTo(1);
        assertThat(this.carts.findById(customer).orElseThrow().lines()).hasSize(2);
        assertThatExceptionOfType(OptimisticLockingFailureException.class)
            .isThrownBy(() -> this.carts.save(cart(customer, first.version())));
    }

    @Test
    @DisplayName("empties the cart after an order only while it is still the version that was priced")
    void clearIfVersion() {
        CartService service = new CartService(this.carts, this.cartMongoTemplate, mock(StoreApi.class), Clock.systemUTC());
        String customer = Ids.newId();
        CartDocument saved = this.carts.save(cart(customer, null));
        CartDocument changed = this.carts.save(cart(customer, saved.version()));

        service.clearIfVersion(customer, saved.version());
        assertThat(this.carts.findById(customer)).isPresent();
        service.clearIfVersion(customer, changed.version());
        assertThat(this.carts.findById(customer)).isEmpty();
    }

    @Test
    @DisplayName("carts expire 30 days after their last change")
    void expiry() {
        Document index = this.cartMongoTemplate.getCollection("cart_carts")
            .listIndexes()
            .into(new java.util.ArrayList<>())
            .stream()
            .filter((i) -> "expiry".equals(i.getString("name")))
            .findFirst()
            .orElseThrow();
        assertThat(index.get("expireAfterSeconds", Number.class).longValue()).isEqualTo(30L * 24 * 3600);
    }

    private static CartDocument cart(String customer, Long version) {
        return new CartDocument(customer, "s1", List.of(line()), Instant.now(), version);
    }

    private static CartLine line() {
        return new CartLine(Ids.newId(), "i1", 1, List.of("o1"), null);
    }
}

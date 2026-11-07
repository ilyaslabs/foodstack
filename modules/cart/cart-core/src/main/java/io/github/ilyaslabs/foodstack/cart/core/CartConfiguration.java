package io.github.ilyaslabs.foodstack.cart.core;

import java.time.Clock;

import io.github.ilyaslabs.foodstack.cart.core.application.CartService;
import io.github.ilyaslabs.foodstack.cart.core.application.CheckoutService;
import io.github.ilyaslabs.foodstack.cart.core.persistence.CartRepository;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.payment.api.PaymentApi;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;
import io.github.ilyaslabs.foodstack.promotion.api.PromotionApi;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.foodstack.store.api.StoreApi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Everything the cart module runs on: database {@code foodstack_cart} with its shard key and
 * migrations. The cart publishes no events and keeps no copy of prices, so it needs no outbox and
 * listens to nothing: every read prices the lines through the store module.
 */
@Configuration(proxyBeanMethods = false)
@EnableMongoRepositories(basePackageClasses = CartRepository.class, mongoTemplateRef = "cartMongoTemplate")
public class CartConfiguration {

    public static final String MODULE = "cart";

    /** Shard keys of TDD 6.3, applied when sharding is switched on. */
    public static ShardKeys shardKeys() {
        return ShardKeys.builder().hashed("cart_carts", "_id");
    }

    @Bean
    ModuleMongo cartMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTemplate cartMongoTemplate(ModuleMongo cartMongo) {
        return cartMongo.template();
    }

    @Bean
    MongoTransactionManager cartTransactionManager(ModuleMongo cartMongo) {
        return cartMongo.transactionManager();
    }

    @Bean
    ModuleMigrations cartMigrations(ModuleMongo cartMongo, ModuleMongoFactory factory, FoodstackMongoProperties properties) {
        return new ModuleMigrations(cartMongo, factory.connectionString(MODULE), shardKeys(), properties);
    }

    @Bean
    CartService cartService(CartRepository cartRepository, ModuleMongo cartMongo, StoreApi storeApi, Clock clock) {
        return new CartService(cartRepository, cartMongo.template(), storeApi, clock);
    }

    @Bean
    CheckoutService cartCheckoutService(CartService cartService, GeoApi geoApi, PromotionApi promotionApi,
            SettingsApi settingsApi, PaymentApi paymentApi) {
        return new CheckoutService(cartService, geoApi, promotionApi, settingsApi, paymentApi);
    }
}

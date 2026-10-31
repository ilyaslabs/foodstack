package io.github.ilyaslabs.foodstack;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;

import org.springframework.boot.SpringApplication;

/**
 * Runs the whole server on a developer machine with Docker as the only prerequisite (TDD 15.1):
 * MongoDB, Redis, Kafka and S3-compatible storage start in reusable containers, profiles
 * {@code local} and {@code test-seed} are active, and the demo accounts from the seeders exist.
 * <p>
 * Run {@code main} from the IDE, or {@code mvn -pl app/foodstack-server spring-boot:test-run}.
 */
public final class LocalFoodstackApplication {

    private LocalFoodstackApplication() {
    }

    public static void main(String[] args) {
        System.setProperty("spring.mongodb.uri", FoodstackContainers.mongo().connectionString());
        System.setProperty("spring.data.redis.url",
            "redis://" + FoodstackContainers.redis().getHost() + ":" + FoodstackContainers.redis().getMappedPort(6379));
        System.setProperty("spring.kafka.bootstrap-servers", FoodstackContainers.kafka().getBootstrapServers());
        System.setProperty("foodstack.storage.endpoint", FoodstackContainers.s3Endpoint());
        System.setProperty("foodstack.storage.public-endpoint", FoodstackContainers.s3Endpoint());
        System.setProperty("foodstack.storage.access-key", FoodstackContainers.S3_ACCESS_KEY);
        System.setProperty("foodstack.storage.secret-key", FoodstackContainers.S3_SECRET_KEY);
        SpringApplication application = new SpringApplication(FoodstackApplication.class);
        application.setAdditionalProfiles("local", "test-seed");
        application.run(args);
    }
}

package io.github.ilyaslabs.foodstack;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;

import org.springframework.boot.SpringApplication;

/**
 * Runs the whole server on a developer machine with Docker as the only prerequisite (TDD 15.1):
 * MongoDB, Redis, Kafka, Elasticsearch and S3-compatible storage start in reusable containers, profiles
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
        System.setProperty("spring.mail.host", FoodstackContainers.mailpit().getHost());
        System.setProperty("spring.mail.port", String.valueOf(FoodstackContainers.mailpit().getMappedPort(1025)));
        System.setProperty("foodstack.notification.from-address", "no-reply@foodstack.localhost");
        System.setProperty("spring.elasticsearch.uris", "http://" + FoodstackContainers.elasticsearch().getHttpHostAddress());
        System.setProperty("spring.elasticsearch.username", "elastic");
        System.setProperty("spring.elasticsearch.password", FoodstackContainers.ELASTICSEARCH_PASSWORD);
        SpringApplication application = new SpringApplication(FoodstackApplication.class);
        application.setAdditionalProfiles("local", "test-seed");
        application.run(args);
        System.out.println("Emails sent by this run: " + FoodstackContainers.mailpitUrl());
        // What the manual QA checks (qa/README.md) open to look at the stored records
        System.out.println("MongoDB shell: docker exec -it " + FoodstackContainers.mongo().getContainerId().substring(0, 12)
                + " mongosh --port 27017");
        System.out.println("Redis shell: docker exec -it " + FoodstackContainers.redis().getContainerId().substring(0, 12)
                + " redis-cli");
        System.out.println("Kafka: " + FoodstackContainers.kafka().getBootstrapServers() + " (container "
                + FoodstackContainers.kafka().getContainerId().substring(0, 12) + ")");
        System.out.println("Elasticsearch: http://" + FoodstackContainers.elasticsearch().getHttpHostAddress()
                + " (user elastic, password " + FoodstackContainers.ELASTICSEARCH_PASSWORD + ")");
    }
}

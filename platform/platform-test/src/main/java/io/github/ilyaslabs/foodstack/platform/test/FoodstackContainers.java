package io.github.ilyaslabs.foodstack.platform.test;

import java.util.Locale;
import java.util.UUID;

import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * One shared set of infrastructure containers per test run (TDD 15.3, Testing Strategy 8).
 * <p>
 * Containers start on first use and are shared by every test class in the JVM. Isolation is by
 * name: each registration picks fresh database names, key and topic namespaces and buckets.
 */
public final class FoodstackContainers {

    public static final String REDIS_IMAGE = "redis:8.2-alpine";

    /**
     * The JVM image, as in the unit deployer. The GraalVM native image starts faster but crashed
     * intermittently on CI runners (segfault before the broker was ready).
     */
    public static final String KAFKA_IMAGE = "apache/kafka:4.1.1";

    /** S3-compatible object storage (Apache-2.0); the unit deployer runs the same server. */
    public static final String S3_IMAGE = "rustfs/rustfs:1.0.4";

    /** Search engine of the search module; the unit deployer runs the same version. */
    public static final String ELASTICSEARCH_IMAGE = "elasticsearch:9.4.0";

    public static final String ELASTICSEARCH_PASSWORD = "foodstack-test";

    public static final String S3_ACCESS_KEY = "foodstack-test";

    public static final String S3_SECRET_KEY = "foodstack-test-secret";

    private static ShardedMongoContainer mongo;

    private static GenericContainer<?> redis;

    private static KafkaContainer kafka;

    private static GenericContainer<?> s3;

    private static ElasticsearchContainer elasticsearch;

    private FoodstackContainers() {
    }

    public static synchronized ShardedMongoContainer mongo() {
        if (mongo == null) {
            mongo = new ShardedMongoContainer().withReuse(true);
            mongo.start();
        }
        return mongo;
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379).withReuse(true);
            redis.start();
        }
        return redis;
    }

    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            kafka = new KafkaContainer(KAFKA_IMAGE).withReuse(true);
            kafka.start();
        }
        return kafka;
    }

    public static synchronized GenericContainer<?> s3() {
        if (s3 == null) {
            s3 = new GenericContainer<>(S3_IMAGE).withExposedPorts(9000)
                .withEnv("RUSTFS_ACCESS_KEY", S3_ACCESS_KEY)
                .withEnv("RUSTFS_SECRET_KEY", S3_SECRET_KEY)
                // The dev servers upload straight to storage from another origin; installs go through the proxy instead.
                .withEnv("RUSTFS_CORS_ALLOWED_ORIGINS", "http://localhost:4200,http://localhost:4300")
                .waitingFor(Wait.forHttp("/health").forPort(9000))
                .withReuse(true);
            s3.start();
        }
        return s3;
    }

    public static synchronized ElasticsearchContainer elasticsearch() {
        if (elasticsearch == null) {
            elasticsearch = new ElasticsearchContainer(DockerImageName.parse(ELASTICSEARCH_IMAGE)
                .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
                .withPassword(ELASTICSEARCH_PASSWORD)
                .withEnv("xpack.security.http.ssl.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withEnv("node.store.allow_mmap", "false")
                .withReuse(true);
            elasticsearch.start();
        }
        return elasticsearch;
    }

    /** Elasticsearch with a fresh index prefix. Returns the prefix. */
    public static String registerElasticsearch(DynamicPropertyRegistry registry) {
        String prefix = uniqueName() + "_";
        registry.add("spring.elasticsearch.uris", () -> "http://" + elasticsearch().getHttpHostAddress());
        registry.add("spring.elasticsearch.username", () -> "elastic");
        registry.add("spring.elasticsearch.password", () -> ELASTICSEARCH_PASSWORD);
        registry.add("foodstack.search.index-prefix", () -> prefix);
        return prefix;
    }

    /** Endpoint URL of the S3-compatible container. */
    public static String s3Endpoint() {
        return "http://" + s3().getHost() + ":" + s3().getMappedPort(9000);
    }

    /** A short random name part, unique per call, safe for databases, topics and buckets. */
    public static String uniqueName() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toLowerCase(Locale.ROOT);
    }

    /**
     * MongoDB on the two-shard cluster with a fresh database prefix, and sharding switched on so
     * every module applies its shard keys.
     */
    public static String registerMongo(DynamicPropertyRegistry registry) {
        String prefix = uniqueName() + "_";
        registry.add("spring.mongodb.uri", () -> mongo().connectionString());
        registry.add("foodstack.mongodb.database-prefix", () -> prefix);
        registry.add("spring.mongodb.database", () -> prefix + "default");
        registry.add("foodstack.mongodb.sharding.enabled", () -> "true");
        return prefix;
    }

    /** Redis with a fresh key namespace. Returns the namespace. */
    public static String registerRedis(DynamicPropertyRegistry registry) {
        String namespace = uniqueName();
        registry.add("spring.data.redis.url", () -> "redis://" + redis().getHost() + ":" + redis().getMappedPort(6379));
        registry.add("foodstack.namespace", () -> namespace);
        return namespace;
    }

    /** Kafka. Topic isolation comes from {@code foodstack.namespace} (see {@link #registerRedis}). */
    public static void registerKafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> kafka().getBootstrapServers());
        registry.add("foodstack.kafka.topic-partitions", () -> "3");
    }

    /** S3-compatible storage with fresh bucket names. */
    public static void registerS3(DynamicPropertyRegistry registry) {
        String prefix = uniqueName();
        registry.add("foodstack.storage.endpoint", FoodstackContainers::s3Endpoint);
        registry.add("foodstack.storage.public-endpoint", FoodstackContainers::s3Endpoint);
        registry.add("foodstack.storage.access-key", () -> S3_ACCESS_KEY);
        registry.add("foodstack.storage.secret-key", () -> S3_SECRET_KEY);
        registry.add("foodstack.storage.public-bucket", () -> prefix + "-public");
        registry.add("foodstack.storage.private-bucket", () -> prefix + "-private");
        registry.add("foodstack.storage.create-buckets", () -> "true");
    }

    /** Everything a module needs: MongoDB, Redis and Kafka. */
    public static void registerAll(DynamicPropertyRegistry registry) {
        registerMongo(registry);
        registerRedis(registry);
        registerKafka(registry);
    }
}

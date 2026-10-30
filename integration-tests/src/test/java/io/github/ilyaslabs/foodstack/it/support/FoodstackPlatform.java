package io.github.ilyaslabs.foodstack.it.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.FoodstackApplication;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The whole platform in one topology, started once per test run and shared by every scenario.
 * Both topologies share the containers but use their own database prefix, Redis and Kafka
 * namespace and buckets, so they never see each other's data.
 */
public final class FoodstackPlatform {

    public static final String[] MODULES = { "identity", "settings", "media" };

    public static final String ADMIN_EMAIL = "admin@it.foodstack.local";

    public static final String ADMIN_PASSWORD = "integration-admin-password";

    public static final String BACKOFFICE_CLIENT = "foodstack-backoffice";

    public static final String BACKOFFICE_REDIRECT = "http://backoffice.it.foodstack.local/admin/auth/callback";

    public static final String BACKOFFICE_POST_LOGOUT = "http://backoffice.it.foodstack.local/admin/";

    private static final String INTERNAL_TOKEN = "integration-internal-token";

    private static final String PII_KEY = "aW50ZWdyYXRpb24tdGVzdHMtcGlpLWtleS1ub3Qtc2VjcmV0";

    private static final Map<Topology, FoodstackPlatform> RUNNING = new EnumMap<>(Topology.class);

    private final Topology topology;

    private final String namespace;

    /** Public base URL of the context that owns each module. */
    private final Map<String, String> moduleUrls;

    private final List<ConfigurableApplicationContext> contexts;

    private FoodstackPlatform(Topology topology, String namespace, Map<String, String> moduleUrls,
            List<ConfigurableApplicationContext> contexts) {
        this.topology = topology;
        this.namespace = namespace;
        this.moduleUrls = moduleUrls;
        this.contexts = contexts;
    }

    public static synchronized FoodstackPlatform of(Topology topology) {
        return RUNNING.computeIfAbsent(topology, FoodstackPlatform::start);
    }

    public Topology topology() {
        return this.topology;
    }

    /** The Kafka topic and Redis key namespace of this run. */
    public String namespace() {
        return this.namespace;
    }

    public String urlOf(String module) {
        return this.moduleUrls.get(module);
    }

    /** Base URL of the context that serves the path, as the gateway would route it. */
    public String baseUrlFor(String path) {
        return urlOf(ModuleRoutes.moduleOf(path));
    }

    /** The issuer: the public URL of the identity module. */
    public String issuer() {
        return urlOf("identity");
    }

    public ApiClient client() {
        return new ApiClient(this);
    }

    private static FoodstackPlatform start(Topology topology) {
        String namespace = FoodstackContainers.uniqueName();
        Map<String, String> shared = sharedProperties(namespace);
        Map<String, String> moduleUrls = new LinkedHashMap<>();
        List<ConfigurableApplicationContext> contexts = new ArrayList<>();
        if (topology == Topology.MONOLITH) {
            int port = freePort();
            String url = "http://localhost:" + port;
            for (String module : MODULES) {
                moduleUrls.put(module, url);
            }
            Map<String, String> properties = new LinkedHashMap<>(shared);
            properties.put("server.port", String.valueOf(port));
            properties.put("foodstack.security.issuer", url);
            contexts.add(run("monolith", properties));
        }
        else {
            Map<String, Integer> ports = new LinkedHashMap<>();
            Map<String, Integer> internalPorts = new LinkedHashMap<>();
            for (String module : MODULES) {
                ports.put(module, freePort());
                internalPorts.put(module, freePort());
                moduleUrls.put(module, "http://localhost:" + ports.get(module));
            }
            String issuer = moduleUrls.get("identity");
            // Start in dependency order so a module's callers find it running.
            for (String module : List.of("media", "settings", "identity")) {
                Map<String, String> properties = new LinkedHashMap<>(shared);
                properties.put("server.port", String.valueOf(ports.get(module)));
                properties.put("foodstack.security.issuer", issuer);
                if (!module.equals("identity")) {
                    properties.put("foodstack.security.jwk-set-uri", issuer + "/oauth2/jwks");
                }
                properties.put("module-switch.expose.port", String.valueOf(internalPorts.get(module)));
                properties.put("module-switch.expose.modules", module);
                properties.put("module-switch.expose.token", INTERNAL_TOKEN);
                for (String other : MODULES) {
                    if (other.equals(module)) {
                        continue;
                    }
                    properties.put("module-switch.modules." + other + ".mode", "http");
                    properties.put("module-switch.modules." + other + ".url", "http://localhost:" + internalPorts.get(other));
                    properties.put("module-switch.modules." + other + ".token", INTERNAL_TOKEN);
                }
                contexts.add(run(module, properties));
            }
        }
        FoodstackPlatform platform = new FoodstackPlatform(topology, namespace, moduleUrls, contexts);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> platform.contexts.reversed().forEach(ConfigurableApplicationContext::close)));
        return platform;
    }

    private static Map<String, String> sharedProperties(String namespace) {
        String bucketPrefix = namespace;
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.mongodb.uri", FoodstackContainers.mongo().connectionString());
        properties.put("spring.mongodb.database", namespace + "_default");
        properties.put("foodstack.mongodb.database-prefix", namespace + "_");
        properties.put("foodstack.mongodb.sharding.enabled", "true");
        properties.put("spring.data.redis.url",
                "redis://" + FoodstackContainers.redis().getHost() + ":" + FoodstackContainers.redis().getMappedPort(6379));
        properties.put("spring.kafka.bootstrap-servers", FoodstackContainers.kafka().getBootstrapServers());
        properties.put("foodstack.namespace", namespace);
        properties.put("foodstack.kafka.topic-partitions", "3");
        properties.put("foodstack.security.pii-key", PII_KEY);
        properties.put("foodstack.security.revocation-cache-ttl", "1s");
        properties.put("foodstack.identity.bootstrap-admin.email", ADMIN_EMAIL);
        properties.put("foodstack.identity.bootstrap-admin.password", ADMIN_PASSWORD);
        properties.put("foodstack.identity.clients." + BACKOFFICE_CLIENT + ".redirect-uris", BACKOFFICE_REDIRECT);
        properties.put("foodstack.identity.clients." + BACKOFFICE_CLIENT + ".post-logout-redirect-uris", BACKOFFICE_POST_LOGOUT);
        properties.put("foodstack.identity.clients.foodstack-web.redirect-uris", "http://web.it.foodstack.local/auth/callback");
        properties.put("foodstack.storage.endpoint", FoodstackContainers.s3Endpoint());
        properties.put("foodstack.storage.public-endpoint", FoodstackContainers.s3Endpoint());
        properties.put("foodstack.storage.access-key", FoodstackContainers.S3_ACCESS_KEY);
        properties.put("foodstack.storage.secret-key", FoodstackContainers.S3_SECRET_KEY);
        properties.put("foodstack.storage.public-bucket", bucketPrefix + "-public");
        properties.put("foodstack.storage.private-bucket", bucketPrefix + "-private");
        properties.put("foodstack.storage.create-buckets", "true");
        return properties;
    }

    private static ConfigurableApplicationContext run(String name, Map<String, String> properties) {
        String[] args = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(FoodstackApplication.class)
            .properties("spring.application.name=foodstack-" + name)
            .run(args);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}

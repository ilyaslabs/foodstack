package io.github.ilyaslabs.foodstack.platform.test;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * A MongoDB cluster with two shards behind one {@code mongos}, in a single container.
 * <p>
 * Repository tests run against it so that a query, index or write that would not work on a
 * sharded cluster fails in the build (TDD 6.3).
 */
public class ShardedMongoContainer extends GenericContainer<ShardedMongoContainer> {

    /** 8.2: MongoDB 8.0 refuses to start on Linux 6.19+ kernels (SERVER-121912), as on current CI runners. */
    public static final String IMAGE = "mongo:8.2";

    private static final int MONGOS_PORT = 27017;

    public ShardedMongoContainer() {
        super(IMAGE);
        withCopyFileToContainer(MountableFile.forClasspathResource("foodstack-test/sharded-mongo.sh", 0755),
                "/sharded-mongo.sh");
        withCreateContainerCmdModifier((cmd) -> cmd.withEntrypoint("/bin/bash"));
        withCommand("/sharded-mongo.sh");
        withExposedPorts(MONGOS_PORT);
        waitingFor(Wait.forLogMessage(".*FOODSTACK SHARDED MONGODB READY.*", 1)
            .withStartupTimeout(Duration.ofMinutes(3)));
    }

    /** Connection string of the {@code mongos} router. */
    public String connectionString() {
        return "mongodb://" + getHost() + ":" + getMappedPort(MONGOS_PORT) + "/?directConnection=true";
    }
}

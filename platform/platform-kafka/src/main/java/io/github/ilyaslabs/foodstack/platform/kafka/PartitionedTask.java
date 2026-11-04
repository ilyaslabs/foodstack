package io.github.ilyaslabs.foodstack.platform.kafka;

import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;

import io.github.ilyaslabs.foodstack.platform.core.PeriodicTask;

/**
 * A {@link PeriodicTask} that runs on every replica but only over the buckets this replica holds
 * (TDD 7.5): the dispatch matcher (jobs by store) and the timeout sweeps (orders by id). More
 * replicas share the buckets; one replica alone runs them all. Spring closes it with the context.
 */
public final class PartitionedTask implements AutoCloseable {

    private final BucketLeases leases;

    private final PeriodicTask task;

    private PartitionedTask(BucketLeases leases, Duration initialDelay, Duration delay, Consumer<Set<Integer>> job) {
        this.leases = leases;
        this.task = PeriodicTask.start(leases.name(), initialDelay, delay, () -> {
            Set<Integer> buckets = leases.current();
            if (!buckets.isEmpty()) {
                job.accept(buckets);
            }
        });
    }

    /** Starts {@code job} after {@code initialDelay}, then every {@code delay}, with this replica's buckets. */
    public static PartitionedTask start(BucketLeases leases, Duration initialDelay, Duration delay,
            Consumer<Set<Integer>> job) {
        return new PartitionedTask(leases, initialDelay, delay, job);
    }

    @Override
    public void close() {
        this.task.close();
        this.leases.release();
    }
}

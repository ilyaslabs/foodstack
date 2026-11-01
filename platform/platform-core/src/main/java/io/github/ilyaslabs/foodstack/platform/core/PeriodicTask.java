package io.github.ilyaslabs.foodstack.platform.core;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a job on every replica at a fixed delay, on its own daemon thread, until closed. Jobs must
 * be safe to run on several replicas at once (conditional updates), which is how the sweepers of
 * payment and order are written. Spring closes it with the context (inferred {@code close}).
 */
public final class PeriodicTask implements AutoCloseable {

    private static final Logger log = Logger.getLogger(PeriodicTask.class.getName());

    private final ScheduledExecutorService executor;

    private PeriodicTask(String name, Duration initialDelay, Duration delay, Runnable job) {
        this.executor = Executors.newSingleThreadScheduledExecutor((runnable) -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
        this.executor.scheduleWithFixedDelay(() -> {
            try {
                job.run();
            }
            catch (RuntimeException ex) {
                log.log(Level.WARNING, "Periodic task " + name + " failed; it runs again later", ex);
            }
        }, initialDelay.toMillis(), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Starts {@code job} after {@code initialDelay}, then every {@code delay} after the previous run ends. */
    public static PeriodicTask start(String name, Duration initialDelay, Duration delay, Runnable job) {
        return new PeriodicTask(name, initialDelay, delay, job);
    }

    @Override
    public void close() {
        this.executor.shutdownNow();
    }
}

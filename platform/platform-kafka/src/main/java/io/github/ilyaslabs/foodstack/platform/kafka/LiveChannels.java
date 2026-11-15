package io.github.ilyaslabs.foodstack.platform.kafka;

import java.util.function.Consumer;

import io.github.ilyaslabs.evl.EVL;

/**
 * Small live messages between replicas (TDD 8.3): a change is published on a channel and the
 * replica holding a browser's stream forwards it. On standalone or Sentinel Redis this is plain
 * pub/sub; on Redis Cluster it is sharded pub/sub ({@code SPUBLISH}/{@code SSUBSCRIBE}), so each
 * channel lives on the node owning its slot and adding nodes adds capacity (TDD 7.5). Chosen by
 * configuration only: the same code runs on one machine and on a cluster. Lost messages only
 * delay a screen until its next refresh.
 */
@EVL.Queue(value = "Redis pub/sub", description = "Small live messages between replicas for SSE streams; sharded pub/sub on Redis Cluster")
public interface LiveChannels {

    /** Sends {@code message} to whoever listens on {@code channel} now; never fails the caller. */
    void publish(String channel, String message);

    /** Receives the messages of {@code channel} until the returned handle is closed. */
    AutoCloseable subscribe(String channel, Consumer<String> receiver);
}

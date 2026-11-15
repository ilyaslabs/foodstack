package io.github.ilyaslabs.foodstack.order.core.application;

import java.util.function.Consumer;

import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.platform.kafka.LiveChannels;

/**
 * Live order changes across replicas (TDD 8.3): after each change the order module publishes a
 * small message (order id, status, revision; no personal data) on live channels
 * {@code <ns>:order:rt:order:<orderId>} and {@code <ns>:order:rt:store:<storeId>}; the replica
 * holding a customer's or a store's stream forwards it, and the screen reloads what it shows.
 */
public class OrderLiveUpdates {

    private final LiveChannels live;

    private final String prefix;

    public OrderLiveUpdates(LiveChannels live, String namespace) {
        this.live = live;
        this.prefix = namespace + ":order:rt:";
    }

    /** Tells streams about a change. A lost message only delays the screen until its next refresh. */
    public void publish(String orderId, String storeId, String status, long revision) {
        String message = "{\"orderId\":\"" + orderId + "\",\"status\":\"" + status + "\",\"revision\":" + revision + "}";
        this.live.publish(this.prefix + "order:" + orderId, message);
        this.live.publish(this.prefix + "store:" + storeId, message);
    }

    /** Receives changes of one order until the returned handle is closed. */
    @Flow(value = "order-watch>subscribe", label = "Subscribe to the order's channel")
    public AutoCloseable onOrder(String orderId, Consumer<String> receiver) {
        return this.live.subscribe(this.prefix + "order:" + orderId, receiver);
    }

    /** Receives changes of a branch's orders until the returned handle is closed. */
    @Flow(value = "order-board-stream>subscribe", label = "Subscribe to the branch's channel")
    public AutoCloseable onStore(String storeId, Consumer<String> receiver) {
        return this.live.subscribe(this.prefix + "store:" + storeId, receiver);
    }
}

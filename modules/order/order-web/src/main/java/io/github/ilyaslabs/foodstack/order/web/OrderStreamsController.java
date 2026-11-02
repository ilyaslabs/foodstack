package io.github.ilyaslabs.foodstack.order.web;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ilyaslabs.foodstack.order.core.application.OrderLiveUpdates;
import io.github.ilyaslabs.foodstack.order.core.application.OrderService;
import jakarta.annotation.PreDestroy;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live order streams (TDD 8.3, server-sent events): one order for its customer, and a branch's
 * board for its people. Each message says which order changed; the screen reloads it. A comment
 * every 15 seconds keeps proxies from closing the stream; clients reconnect after 30 minutes.
 */
@RestController
class OrderStreamsController {

    static final Duration STREAM_LIFETIME = Duration.ofMinutes(30);

    private final OrderService orders;

    private final OrderLiveUpdates live;

    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor((runnable) -> {
        Thread thread = new Thread(runnable, "order-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    OrderStreamsController(OrderService orders, OrderLiveUpdates live) {
        this.orders = orders;
        this.live = live;
    }

    @GetMapping(value = "/api/v1/orders/{orderId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    SseEmitter order(@PathVariable String orderId) {
        this.orders.requireWatch(orderId);
        return stream((emitter) -> this.live.onOrder(orderId, (message) -> send(emitter, message)));
    }

    @GetMapping(value = "/api/v1/orders/stores/{storeId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    SseEmitter store(@PathVariable String storeId) {
        this.orders.requireBoard(storeId);
        return stream((emitter) -> this.live.onStore(storeId, (message) -> send(emitter, message)));
    }

    @PreDestroy
    void stop() {
        this.heartbeats.shutdownNow();
    }

    interface Subscriber {

        AutoCloseable subscribe(SseEmitter emitter);
    }

    private SseEmitter stream(Subscriber subscriber) {
        SseEmitter emitter = new SseEmitter(STREAM_LIFETIME.toMillis());
        AutoCloseable subscription = subscriber.subscribe(emitter);
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        Runnable close = () -> {
            ScheduledFuture<?> beat = heartbeat.get();
            if (beat != null) {
                beat.cancel(false);
            }
            try {
                subscription.close();
            }
            catch (Exception ex) {
                // already closed
            }
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError((ex) -> close.run());
        heartbeat.set(this.heartbeats.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            }
            catch (IOException | IllegalStateException ex) {
                emitter.completeWithError(ex);
            }
        }, 15, 15, TimeUnit.SECONDS));
        send(emitter, "{\"ready\":true}");
        return emitter;
    }

    private static void send(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("order").data(message, MediaType.APPLICATION_JSON));
        }
        catch (IOException | IllegalStateException ex) {
            emitter.completeWithError(ex);
        }
    }
}

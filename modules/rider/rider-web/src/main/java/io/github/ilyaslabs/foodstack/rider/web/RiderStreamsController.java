package io.github.ilyaslabs.foodstack.rider.web;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ilyaslabs.foodstack.rider.core.application.RiderActivity;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderPresence;
import jakarta.annotation.PreDestroy;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live rider positions (TDD 8.3, server-sent events): every rider for the operator's live map, and
 * the rider of one delivery for its customer and store. A comment every 15 seconds keeps proxies from
 * closing the stream; clients reconnect after 30 minutes.
 */
@RestController
class RiderStreamsController {

    static final Duration STREAM_LIFETIME = Duration.ofMinutes(30);

    private final RiderActivity activity;

    private final RiderPresence presence;

    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor((runnable) -> {
        Thread thread = new Thread(runnable, "rider-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    RiderStreamsController(RiderActivity activity, RiderPresence presence) {
        this.activity = activity;
        this.presence = presence;
    }

    @GetMapping(value = "/api/v1/riders/live/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    SseEmitter live() {
        this.activity.requireLive();
        return stream((emitter) -> this.presence.onAll((message) -> send(emitter, message)));
    }

    @GetMapping(value = "/api/v1/riders/tracking/{orderId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    SseEmitter tracking(@PathVariable String orderId) {
        this.activity.requireFollow(orderId);
        return stream((emitter) -> this.presence.onOrder(orderId, (message) -> send(emitter, message)));
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
            emitter.send(SseEmitter.event().name("rider").data(message, MediaType.APPLICATION_JSON));
        }
        catch (IOException | IllegalStateException ex) {
            emitter.completeWithError(ex);
        }
    }
}

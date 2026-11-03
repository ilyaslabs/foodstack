package io.github.ilyaslabs.foodstack.dispatch.web;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ilyaslabs.foodstack.dispatch.core.application.DispatchLive;
import io.github.ilyaslabs.foodstack.dispatch.core.application.DispatchService;
import jakarta.annotation.PreDestroy;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live job changes (TDD 8.3, server-sent events): offers and job changes for the rider app, and
 * every job change for support's dispatch board. A comment every 15 seconds keeps proxies from
 * closing the stream; clients reconnect after 30 minutes.
 */
@RestController
class DispatchStreamsController {

    static final Duration STREAM_LIFETIME = Duration.ofMinutes(30);

    private final DispatchService dispatch;

    private final DispatchLive live;

    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor((runnable) -> {
        Thread thread = new Thread(runnable, "dispatch-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    DispatchStreamsController(DispatchService dispatch, DispatchLive live) {
        this.dispatch = dispatch;
        this.live = live;
    }

    @GetMapping(value = "/api/v1/dispatch/rider/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('rider:portal')")
    SseEmitter rider() {
        String riderId = this.dispatch.requireRider();
        return stream((emitter) -> this.live.onRider(riderId, (message) -> send(emitter, message)));
    }

    @GetMapping(value = "/api/v1/dispatch/live/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('dispatch:manage')")
    SseEmitter board() {
        this.dispatch.requireBoard();
        return stream((emitter) -> this.live.onAll((message) -> send(emitter, message)));
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
            emitter.send(SseEmitter.event().name("job").data(message, MediaType.APPLICATION_JSON));
        }
        catch (IOException | IllegalStateException ex) {
            emitter.completeWithError(ex);
        }
    }
}

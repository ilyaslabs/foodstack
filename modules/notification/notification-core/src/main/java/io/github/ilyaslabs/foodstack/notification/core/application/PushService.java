package io.github.ilyaslabs.foodstack.notification.core.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.notification.core.persistence.DeviceDocument;
import io.github.ilyaslabs.foodstack.notification.core.push.PushTransport;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Push messages to people's phones and browsers (TDD 8.3, FCM): devices register their token,
 * messages go to every device of the user for that app, and tokens FCM no longer knows are
 * forgotten. Push is best effort; the notification centre keeps the message either way. Off when no
 * FCM service account is set.
 */
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    static final Set<String> PLATFORMS = Set.of("WEB", "ANDROID", "IOS");

    static final Set<String> APPS = Set.of("web", "backoffice");

    /** Most devices kept per user; the oldest go first. */
    static final int MAX_DEVICES = 10;

    private final MongoTemplate mongo;

    private final PushTransport transport;

    private final PiiCipher pii;

    private final Clock clock;

    /** @param transport FCM, or {@code null} when push is off */
    public PushService(MongoTemplate mongo, PushTransport transport, PiiCipher pii, Clock clock) {
        this.mongo = mongo;
        this.transport = transport;
        this.pii = pii;
        this.clock = clock;
    }

    public boolean enabled() {
        return this.transport != null;
    }

    public void register(String userId, String token, String platform, String app) {
        if (token == null || token.isBlank() || token.length() > 4096) {
            throw new ModuleException.BadRequest("A device token is required", "DEVICE_INVALID");
        }
        String kind = (platform != null) ? platform.toUpperCase(Locale.ROOT) : "WEB";
        String which = (app != null) ? app.toLowerCase(Locale.ROOT) : "web";
        if (!PLATFORMS.contains(kind) || !APPS.contains(which)) {
            throw new ModuleException.BadRequest("Unknown platform or app", "DEVICE_INVALID");
        }
        Instant now = this.clock.instant();
        String id = hash(token.strip());
        // a token moves with the device: whoever registers it last receives its messages
        this.mongo.remove(Query.query(Criteria.where("_id").is(id).and("userId").ne(userId)), DeviceDocument.class);
        DeviceDocument existing = this.mongo.findOne(Query.query(Criteria.where("userId").is(userId).and("_id").is(id)),
                DeviceDocument.class);
        this.mongo.save(new DeviceDocument(id, userId, this.pii.encrypt(token.strip()), kind, which,
                (existing != null) ? existing.createdAt() : now, now));
        List<DeviceDocument> all = devices(userId);
        if (all.size() > MAX_DEVICES) {
            all.stream()
                .sorted(java.util.Comparator.comparing(DeviceDocument::seenAt))
                .limit(all.size() - MAX_DEVICES)
                .forEach((old) -> remove(old));
        }
    }

    public void unregister(String userId, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        this.mongo.remove(Query.query(Criteria.where("userId").is(userId).and("_id").is(hash(token.strip()))),
                DeviceDocument.class);
    }

    /** Sends to every device of the user registered for {@code app}; returns how many got it. */
    public int send(String userId, String app, String title, String body, String link, Map<String, String> data) {
        if (this.transport == null || userId == null) {
            return 0;
        }
        int sent = 0;
        for (DeviceDocument device : devices(userId)) {
            if (!device.app().equals(app)) {
                continue;
            }
            String token;
            try {
                token = this.pii.decrypt(device.tokenCipher());
            }
            catch (RuntimeException ex) {
                remove(device);
                continue;
            }
            PushTransport.Result result = this.transport.send(token, title, body, link, data);
            if (result == PushTransport.Result.SENT) {
                sent++;
            }
            else if (result == PushTransport.Result.INVALID_TOKEN) {
                log.debug("Forgetting a device of user {} that FCM no longer knows", userId);
                remove(device);
            }
        }
        return sent;
    }

    List<DeviceDocument> devices(String userId) {
        return this.mongo.find(Query.query(Criteria.where("userId").is(userId)), DeviceDocument.class);
    }

    private void remove(DeviceDocument device) {
        this.mongo.remove(Query.query(Criteria.where("userId").is(device.userId()).and("_id").is(device.id())),
                DeviceDocument.class);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

package io.github.ilyaslabs.foodstack.rider.core.application;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Where riders are right now, in Redis (TDD 8.3, 5.6): the GEO set {@code <ns>:rider:geo:online} of
 * online operator riders for matching, each rider's last position under {@code <ns>:rider:pos:<id>},
 * and live channels {@code <ns>:rider:rt:all} (the operator's live map) and
 * {@code <ns>:rider:rt:order:<orderId>} (the customer and the store following one delivery). Lost
 * messages only delay a map until the next ping.
 */
public class RiderPresence {

    private static final Logger log = LoggerFactory.getLogger(RiderPresence.class);

    /** How long a last position counts as current without a new ping. */
    static final Duration POSITION_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    private final RedisMessageListenerContainer listeners;

    private final String prefix;

    public RiderPresence(StringRedisTemplate redis, RedisMessageListenerContainer listeners, String namespace) {
        this.redis = redis;
        this.listeners = listeners;
        this.prefix = namespace + ":rider:";
    }

    /** A rider's last position. */
    public record Position(double lat, double lng, long at) {

        String encode() {
            return String.format(Locale.ROOT, "%.6f,%.6f,%d", this.lat, this.lng, this.at);
        }

        static Position decode(String value) {
            if (value == null) {
                return null;
            }
            String[] parts = value.split(",");
            return new Position(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Long.parseLong(parts[2]));
        }
    }

    /** A rider near a point, from the GEO set. */
    public record Nearby(String riderId, long distanceMeters) {
    }

    /** Stores the position, and for a matchable rider puts it in the GEO set. */
    public void moved(String riderId, Position position, boolean matchable) {
        try {
            this.redis.opsForValue().set(this.prefix + "pos:" + riderId, position.encode(), POSITION_TTL);
            if (matchable) {
                this.redis.opsForGeo().add(geoKey(), new Point(position.lng(), position.lat()), riderId);
            }
            else {
                this.redis.opsForGeo().remove(geoKey(), riderId);
            }
        }
        catch (DataAccessException ex) {
            log.warn("Position of rider {} not stored: {}", riderId, ex.getMessage());
        }
    }

    /** Takes a rider out of matching (offline, suspended, busy). */
    public void unmatchable(String riderId) {
        try {
            this.redis.opsForGeo().remove(geoKey(), riderId);
        }
        catch (DataAccessException ex) {
            log.warn("Rider {} not taken out of matching: {}", riderId, ex.getMessage());
        }
    }

    /** Puts a free rider with a known position back into matching. */
    public void matchable(String riderId) {
        Position position = position(riderId);
        if (position != null) {
            try {
                this.redis.opsForGeo().add(geoKey(), new Point(position.lng(), position.lat()), riderId);
            }
            catch (DataAccessException ex) {
                log.warn("Rider {} not put back into matching: {}", riderId, ex.getMessage());
            }
        }
    }

    /** Forgets a rider's position (offline). */
    public void forget(String riderId) {
        unmatchable(riderId);
        try {
            this.redis.delete(this.prefix + "pos:" + riderId);
        }
        catch (DataAccessException ex) {
            // expires on its own
        }
    }

    public Position position(String riderId) {
        try {
            return Position.decode(this.redis.opsForValue().get(this.prefix + "pos:" + riderId));
        }
        catch (DataAccessException ex) {
            return null;
        }
    }

    public List<Position> positions(List<String> riderIds) {
        if (riderIds.isEmpty()) {
            return List.of();
        }
        try {
            List<String> values = this.redis.opsForValue().multiGet(riderIds.stream().map((id) -> this.prefix + "pos:" + id).toList());
            List<Position> positions = new ArrayList<>(riderIds.size());
            for (int i = 0; i < riderIds.size(); i++) {
                positions.add((values != null) ? Position.decode(values.get(i)) : null);
            }
            return positions;
        }
        catch (DataAccessException ex) {
            return riderIds.stream().map((id) -> (Position) null).toList();
        }
    }

    /** Matchable riders within a radius, nearest first. */
    public List<Nearby> near(double lat, double lng, int radiusMeters, int limit) {
        try {
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = this.redis.opsForGeo()
                .search(geoKey(), GeoReference.fromCoordinate(lng, lat), new Distance(radiusMeters, RedisGeoCommands.DistanceUnit.METERS),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(limit));
            List<Nearby> nearby = new ArrayList<>();
            if (results != null) {
                for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
                    nearby.add(new Nearby(result.getContent().getName(), Math.round(result.getDistance().getValue())));
                }
            }
            return nearby;
        }
        catch (DataAccessException ex) {
            log.warn("Rider search failed: {}", ex.getMessage());
            return List.of();
        }
    }

    /** Lets this ping through at most once per period, for sampling (SET NX with expiry). */
    public boolean firstIn(String riderId, String purpose, Duration period) {
        try {
            Boolean set = this.redis.opsForValue().setIfAbsent(this.prefix + "gate:" + purpose + ":" + riderId, "1", period);
            return Boolean.TRUE.equals(set);
        }
        catch (DataAccessException ex) {
            return true;
        }
    }

    /** Tells the live map and the people following the rider's deliveries. */
    public void publish(String riderId, Position position, boolean busy, String kind, String storeId, List<String> orderIds) {
        String message = String.format(Locale.ROOT,
                "{\"riderId\":\"%s\",\"lat\":%.6f,\"lng\":%.6f,\"at\":%d,\"busy\":%s,\"kind\":\"%s\"%s}", riderId,
                position.lat(), position.lng(), position.at(), busy, kind,
                (storeId != null) ? ",\"storeId\":\"" + storeId + "\"" : "");
        send(this.prefix + "rt:all", message);
        for (String orderId : orderIds) {
            send(this.prefix + "rt:order:" + orderId, message);
        }
    }

    /** Tells the live map a rider went offline or changed state. */
    public void publishState(String riderId, boolean online, boolean busy) {
        send(this.prefix + "rt:all",
                "{\"riderId\":\"" + riderId + "\",\"online\":" + online + ",\"busy\":" + busy + ",\"state\":true}");
    }

    public AutoCloseable onAll(Consumer<String> receiver) {
        return subscribe(this.prefix + "rt:all", receiver);
    }

    public AutoCloseable onOrder(String orderId, Consumer<String> receiver) {
        return subscribe(this.prefix + "rt:order:" + orderId, receiver);
    }

    private void send(String channel, String message) {
        try {
            this.redis.convertAndSend(channel, message);
        }
        catch (DataAccessException ex) {
            log.debug("Live message on {} not sent: {}", channel, ex.getMessage());
        }
    }

    private AutoCloseable subscribe(String channel, Consumer<String> receiver) {
        ChannelTopic topic = new ChannelTopic(channel);
        MessageListener listener = (message, pattern) -> receiver.accept(new String(message.getBody(), StandardCharsets.UTF_8));
        this.listeners.addMessageListener(listener, topic);
        return () -> this.listeners.removeMessageListener(listener, topic);
    }

    private String geoKey() {
        return this.prefix + "geo:online";
    }

    static long epoch(Instant at) {
        return at.getEpochSecond();
    }
}

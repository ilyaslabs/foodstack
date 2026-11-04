package io.github.ilyaslabs.foodstack.rider.core.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import io.github.ilyaslabs.foodstack.platform.core.GeoCells;
import io.github.ilyaslabs.foodstack.platform.kafka.LiveChannels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;

/**
 * Where riders are right now, in Redis (TDD 8.3, 5.6, 7.5). Online operator riders sit in one GEO
 * set per {@link GeoCells map cell}, {@code <ns>:rider:geo:<cell>}, so no single key holds a whole
 * country; a search reads the cells its circle touches. Each rider's last position is under
 * {@code <ns>:rider:pos:<id>} and its cell under {@code <ns>:rider:cell:<id>}. Live messages go to
 * {@code <ns>:rider:rt:cell:<cell>} (the operator's and stores' live maps, which follow the cells
 * they show) and {@code <ns>:rider:rt:order:<orderId>} (the customer and the store following one
 * delivery). Lost messages only delay a map until the next ping.
 */
public class RiderPresence {

    private static final Logger log = LoggerFactory.getLogger(RiderPresence.class);

    /** How long a last position counts as current without a new ping. */
    static final Duration POSITION_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    private final LiveChannels live;

    private final String prefix;

    public RiderPresence(StringRedisTemplate redis, LiveChannels live, String namespace) {
        this.redis = redis;
        this.live = live;
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

        String cell() {
            return GeoCells.of(this.lat, this.lng);
        }
    }

    /** A rider near a point, from the GEO sets. */
    public record Nearby(String riderId, long distanceMeters) {
    }

    /** Stores the position, and for a matchable rider puts it in its cell's GEO set (leaving the old cell). */
    public void moved(String riderId, Position position, boolean matchable) {
        try {
            String cell = position.cell();
            String previous = this.redis.opsForValue().get(cellKey(riderId));
            if (previous != null && !previous.equals(cell)) {
                this.redis.opsForGeo().remove(geoKey(previous), riderId);
            }
            this.redis.opsForValue().set(this.prefix + "pos:" + riderId, position.encode(), POSITION_TTL);
            this.redis.opsForValue().set(cellKey(riderId), cell, POSITION_TTL);
            if (matchable) {
                this.redis.opsForGeo().add(geoKey(cell), new Point(position.lng(), position.lat()), riderId);
            }
            else {
                this.redis.opsForGeo().remove(geoKey(cell), riderId);
            }
        }
        catch (DataAccessException ex) {
            log.warn("Position of rider {} not stored: {}", riderId, ex.getMessage());
        }
    }

    /** Takes a rider out of matching (offline, suspended, busy). */
    public void unmatchable(String riderId) {
        try {
            String cell = this.redis.opsForValue().get(cellKey(riderId));
            if (cell != null) {
                this.redis.opsForGeo().remove(geoKey(cell), riderId);
            }
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
                this.redis.opsForGeo().add(geoKey(position.cell()), new Point(position.lng(), position.lat()), riderId);
            }
            catch (DataAccessException ex) {
                log.warn("Rider {} not put back into matching: {}", riderId, ex.getMessage());
            }
        }
    }

    /** Forgets a rider's position (offline). The cell stays a while so the live maps hear the rider left. */
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

    /** Matchable riders within a radius, nearest first, from every cell the circle touches. */
    public List<Nearby> near(double lat, double lng, int radiusMeters, int limit) {
        List<Nearby> nearby = new ArrayList<>();
        try {
            for (String cell : GeoCells.around(lat, lng, radiusMeters)) {
                GeoResults<RedisGeoCommands.GeoLocation<String>> results = this.redis.opsForGeo()
                    .search(geoKey(cell), GeoReference.fromCoordinate(lng, lat),
                            new Distance(radiusMeters, RedisGeoCommands.DistanceUnit.METERS),
                            RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(limit));
                if (results != null) {
                    for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
                        nearby.add(new Nearby(result.getContent().getName(), Math.round(result.getDistance().getValue())));
                    }
                }
            }
        }
        catch (DataAccessException | IllegalArgumentException ex) {
            log.warn("Rider search failed: {}", ex.getMessage());
        }
        nearby.sort(Comparator.comparingLong(Nearby::distanceMeters));
        return (nearby.size() > limit) ? List.copyOf(nearby.subList(0, limit)) : nearby;
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

    /** Tells the live maps showing the rider's cell, and the people following the rider's deliveries. */
    public void publish(String riderId, Position position, boolean busy, String kind, String storeId, List<String> orderIds) {
        String message = String.format(Locale.ROOT,
                "{\"riderId\":\"%s\",\"lat\":%.6f,\"lng\":%.6f,\"at\":%d,\"busy\":%s,\"kind\":\"%s\"%s}", riderId,
                position.lat(), position.lng(), position.at(), busy, kind,
                (storeId != null) ? ",\"storeId\":\"" + storeId + "\"" : "");
        this.live.publish(cellChannel(position.cell()), message);
        for (String orderId : orderIds) {
            this.live.publish(this.prefix + "rt:order:" + orderId, message);
        }
    }

    /** Tells the live maps of the rider's last cell that the rider went offline or changed state. */
    public void publishState(String riderId, boolean online, boolean busy) {
        String cell;
        try {
            cell = this.redis.opsForValue().get(cellKey(riderId));
        }
        catch (DataAccessException ex) {
            return;
        }
        if (cell != null) {
            this.live.publish(cellChannel(cell),
                    "{\"riderId\":\"" + riderId + "\",\"online\":" + online + ",\"busy\":" + busy + ",\"state\":true}");
        }
    }

    /** Receives the riders of these cells (a live map's view) until the returned handle is closed. */
    public AutoCloseable onCells(List<String> cells, Consumer<String> receiver) {
        List<AutoCloseable> subscriptions = new ArrayList<>(cells.size());
        for (String cell : cells) {
            subscriptions.add(this.live.subscribe(cellChannel(cell), receiver));
        }
        return () -> {
            for (AutoCloseable subscription : subscriptions) {
                subscription.close();
            }
        };
    }

    public AutoCloseable onOrder(String orderId, Consumer<String> receiver) {
        return this.live.subscribe(this.prefix + "rt:order:" + orderId, receiver);
    }

    private String geoKey(String cell) {
        return this.prefix + "geo:" + cell;
    }

    private String cellKey(String riderId) {
        return this.prefix + "cell:" + riderId;
    }

    private String cellChannel(String cell) {
        return this.prefix + "rt:cell:" + cell;
    }
}

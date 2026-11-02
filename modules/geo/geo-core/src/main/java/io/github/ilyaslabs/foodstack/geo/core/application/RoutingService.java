package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Duration;
import java.util.Locale;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.RouteView;
import io.github.ilyaslabs.foodstack.geo.core.routing.RoutingProvider;
import io.github.ilyaslabs.foodstack.geo.core.routing.StraightLineRoutingProvider;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Road distance and driving time (TDD 14.1): the configured provider, cached in Redis under
 * {@code <ns>:geo:cache:route:*} by points rounded to about 10 m, with the straight-line estimate
 * when the provider is off or does not answer. Quotes, dispatch and live ETAs all use it.
 */
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final RoutingProvider provider;

    private final StraightLineRoutingProvider estimate;

    private final StringRedisTemplate redis;

    private final String prefix;

    private final Duration cacheTtl;

    /**
     * @param provider the routing service, or {@code null} to always estimate
     * @param redis cache, or {@code null} for none
     */
    public RoutingService(RoutingProvider provider, StraightLineRoutingProvider estimate, StringRedisTemplate redis,
            String namespace, Duration cacheTtl) {
        this.provider = provider;
        this.estimate = estimate;
        this.redis = redis;
        this.prefix = namespace + ":geo:cache:route:";
        this.cacheTtl = cacheTtl;
    }

    /** Straight-line estimates only, no cache: for tests and deployments without routing. */
    public static RoutingService estimating(double roadFactor) {
        return new RoutingService(null, new StraightLineRoutingProvider(roadFactor, 25), null, "foodstack", Duration.ZERO);
    }

    public RouteView route(GeoPoint from, GeoPoint to) {
        if (from == null || to == null || !from.isValid() || !to.isValid()) {
            throw new ModuleException.BadRequest("Both points must be valid coordinates", "LOCATION_INVALID");
        }
        if (this.provider == null) {
            RoutingProvider.Route route = this.estimate.route(from, to);
            return new RouteView(route.distanceMeters(), route.durationSeconds(), true);
        }
        String key = this.prefix + String.format(Locale.ROOT, "%.4f,%.4f:%.4f,%.4f", from.lat(), from.lng(), to.lat(), to.lng());
        String cached = read(key);
        if (cached != null) {
            String[] parts = cached.split(",");
            return new RouteView(Long.parseLong(parts[0]), Long.parseLong(parts[1]), false);
        }
        try {
            RoutingProvider.Route route = this.provider.route(from, to);
            if (route != null) {
                write(key, route.distanceMeters() + "," + route.durationSeconds());
                return new RouteView(route.distanceMeters(), route.durationSeconds(), false);
            }
        }
        catch (RuntimeException ex) {
            log.warn("Routing provider did not answer, estimating instead: {}", ex.getMessage());
        }
        RoutingProvider.Route route = this.estimate.route(from, to);
        return new RouteView(route.distanceMeters(), route.durationSeconds(), true);
    }

    private String read(String key) {
        if (this.redis == null) {
            return null;
        }
        try {
            return this.redis.opsForValue().get(key);
        }
        catch (DataAccessException ex) {
            return null;
        }
    }

    private void write(String key, String value) {
        if (this.redis == null || this.cacheTtl.isZero()) {
            return;
        }
        try {
            this.redis.opsForValue().set(key, value, this.cacheTtl);
        }
        catch (DataAccessException ex) {
            // the next call asks the provider again
        }
    }
}

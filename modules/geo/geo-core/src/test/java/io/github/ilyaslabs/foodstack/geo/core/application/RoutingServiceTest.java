package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Duration;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.RouteView;
import io.github.ilyaslabs.foodstack.geo.core.routing.RoutingProvider;
import io.github.ilyaslabs.foodstack.geo.core.routing.StraightLineRoutingProvider;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RoutingServiceTest {

    private static final GeoPoint STORE = new GeoPoint(31.52, 74.35);

    private static final GeoPoint HOME = new GeoPoint(31.5, 74.3);

    private final RoutingProvider provider = mock(RoutingProvider.class);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final StraightLineRoutingProvider estimate = new StraightLineRoutingProvider(1.3, 25);

    @Test
    @DisplayName("asks the provider once per pair of points, then answers from the cache")
    void cached() {
        given(this.redis.opsForValue()).willReturn(this.values);
        given(this.provider.route(STORE, HOME)).willReturn(new RoutingProvider.Route(6_100, 700));
        RoutingService service = new RoutingService(this.provider, this.estimate, this.redis, "fs", Duration.ofHours(1));

        assertThat(service.route(STORE, HOME)).isEqualTo(new RouteView(6_100, 700, false));
        verify(this.values).set("fs:geo:cache:route:31.5200,74.3500:31.5000,74.3000", "6100,700", Duration.ofHours(1));

        given(this.values.get("fs:geo:cache:route:31.5200,74.3500:31.5000,74.3000")).willReturn("6100,700");
        RoutingProvider unused = mock(RoutingProvider.class);
        RoutingService again = new RoutingService(unused, this.estimate, this.redis, "fs", Duration.ofHours(1));
        assertThat(again.route(STORE, HOME).distanceMeters()).isEqualTo(6_100);
        verify(unused, never()).route(any(), any());
    }

    @Test
    @DisplayName("estimates from the straight line when the provider is off or fails, and refuses bad points")
    void fallback() {
        RouteView off = RoutingService.estimating(1.3).route(STORE, HOME);
        assertThat(off.estimated()).isTrue();
        assertThat(off.distanceMeters()).isBetween(6_700L, 6_950L);
        assertThat(off.durationSeconds()).isEqualTo(Math.round(off.distanceMeters() / (25 / 3.6)));

        given(this.provider.route(STORE, HOME)).willThrow(new IllegalStateException("down"));
        RoutingService failing = new RoutingService(this.provider, this.estimate, null, "fs", Duration.ofHours(1));
        assertThat(failing.route(STORE, HOME)).isEqualTo(off);

        assertThatThrownBy(() -> failing.route(new GeoPoint(91, 0), HOME))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("LOCATION_INVALID"));
    }
}

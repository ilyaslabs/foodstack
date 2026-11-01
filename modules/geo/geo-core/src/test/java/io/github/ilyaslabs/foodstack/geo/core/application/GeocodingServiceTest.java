package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Duration;
import java.util.List;

import io.github.ilyaslabs.foodstack.geo.core.geocoding.DisabledGeocodingProvider;
import io.github.ilyaslabs.foodstack.geo.core.geocoding.GeocodingProvider;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GeocodingServiceTest {

    private final GeocodingProvider provider = mock(GeocodingProvider.class);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("asks the provider once, then answers from the cache")
    void cached() {
        // given
        given(this.redis.opsForValue()).willReturn(this.values);
        given(this.provider.isAvailable()).willReturn(true);
        List<AddressSuggestion> found = List.of(new AddressSuggestion("Gulberg III, Lahore", 31.52, 74.35));
        given(this.provider.search("gulberg", "ur")).willReturn(found);
        GeocodingService service = new GeocodingService(this.provider, this.redis, this.jsonMapper, "fs", Duration.ofDays(1));

        // when
        List<AddressSuggestion> first = service.search(" gulberg ", "ur-PK,en;q=0.8");

        // then
        assertThat(first).isEqualTo(found);
        verify(this.values).set("fs:geo:cache:geocode:q:ur:gulberg", this.jsonMapper.writeValueAsString(found),
                Duration.ofDays(1));

        // and given the cache now has it
        given(this.values.get("fs:geo:cache:geocode:q:en:gulberg")).willReturn(this.jsonMapper.writeValueAsString(found));
        assertThat(service.search("Gulberg", null)).isEqualTo(found);
        verify(this.provider, never()).search("Gulberg", "en");
    }

    @Test
    @DisplayName("rejects short queries and answers 503 when search is switched off")
    void rules() {
        GeocodingService off = new GeocodingService(new DisabledGeocodingProvider(), this.redis, this.jsonMapper, "fs",
                Duration.ofDays(1));

        assertThatThrownBy(() -> off.search("ab", "en")).isInstanceOf(ModuleException.BadRequest.class);
        assertThatThrownBy(() -> off.search("gulberg", "en"))
            .isInstanceOfSatisfying(ModuleException.Unavailable.class, (ex) -> assertThat(ex.code()).isEqualTo("GEOCODING_DISABLED"));
        assertThat(off.isAvailable()).isFalse();
        verify(this.redis, never()).opsForValue();
        assertThatThrownBy(() -> off.reverse(91, 0, "en")).isInstanceOf(ModuleException.BadRequest.class);
    }
}

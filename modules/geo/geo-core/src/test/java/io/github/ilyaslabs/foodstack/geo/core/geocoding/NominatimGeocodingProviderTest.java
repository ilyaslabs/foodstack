package io.github.ilyaslabs.foodstack.geo.core.geocoding;

import io.github.ilyaslabs.foodstack.geo.core.application.AddressSuggestion;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NominatimGeocodingProviderTest {

    private final RestClient.Builder builder = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(this.builder).build();

    private final NominatimGeocodingProvider provider = new NominatimGeocodingProvider(this.builder,
            "https://nominatim.example", "FoodStack test", "pk");

    @Test
    @DisplayName("searches with the user agent, language and country filter, and maps the results")
    void search() {
        this.server.expect(requestTo("https://nominatim.example/search?format=jsonv2&limit=5&q=gulberg&countrycodes=pk"))
            .andExpect(header("User-Agent", "FoodStack test"))
            .andExpect(header("Accept-Language", "ur"))
            .andRespond(withSuccess("""
                    [{"display_name":"Gulberg III, Lahore","lat":"31.5204","lon":"74.3587"}]""", MediaType.APPLICATION_JSON));

        assertThat(this.provider.search("gulberg", "ur"))
            .containsExactly(new AddressSuggestion("Gulberg III, Lahore", 31.5204, 74.3587));
    }

    @Test
    @DisplayName("answers nothing for a pin with no address and 503 when the provider fails")
    void reverse() {
        this.server.expect(requestTo("https://nominatim.example/reverse?format=jsonv2&lat=0.0&lon=0.0"))
            .andRespond(withSuccess("{\"error\":\"Unable to geocode\"}", MediaType.APPLICATION_JSON));
        this.server.expect(requestTo("https://nominatim.example/reverse?format=jsonv2&lat=1.0&lon=1.0"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(this.provider.reverse(0, 0, "en")).isEmpty();
        assertThatThrownBy(() -> this.provider.reverse(1, 1, "en")).isInstanceOf(ModuleException.Unavailable.class);
    }
}

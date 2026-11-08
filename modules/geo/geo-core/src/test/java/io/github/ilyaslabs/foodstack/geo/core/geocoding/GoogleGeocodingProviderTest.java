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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleGeocodingProviderTest {

    private final RestClient.Builder builder = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(this.builder).build();

    private final GoogleGeocodingProvider provider = new GoogleGeocodingProvider(this.builder, "https://google.example",
            "test-key", "pk, ae");

    @Test
    @DisplayName("searches in the deployment's country and language with the key, and maps the results")
    void search() {
        this.server.expect(requestTo(
                "https://google.example/maps/api/geocode/json?address=gulberg&language=ur&components=country:PK&key=test-key"))
            .andRespond(withSuccess("""
                    {"status":"OK","results":[
                      {"formatted_address":"Gulberg III, Lahore, Pakistan","geometry":{"location":{"lat":31.5204,"lng":74.3587}}},
                      {"formatted_address":"Gulberg II, Lahore, Pakistan","geometry":{"location":{"lat":31.53,"lng":74.35}}}]}""",
                    MediaType.APPLICATION_JSON));

        assertThat(this.provider.search("gulberg", "ur")).containsExactly(
                new AddressSuggestion("Gulberg III, Lahore, Pakistan", 31.5204, 74.3587),
                new AddressSuggestion("Gulberg II, Lahore, Pakistan", 31.53, 74.35));
    }

    @Test
    @DisplayName("a pin gives its first address; no address is empty; a refused key or a failure is 503")
    void reverse() {
        this.server.expect(requestTo("https://google.example/maps/api/geocode/json?latlng=31.520400,74.358700&language=en"
                + "&key=test-key"))
            .andRespond(withSuccess("""
                    {"status":"OK","results":[
                      {"formatted_address":"12 Main Boulevard, Lahore","geometry":{"location":{"lat":31.5204,"lng":74.3587}}},
                      {"formatted_address":"Lahore","geometry":{"location":{"lat":31.55,"lng":74.34}}}]}""",
                    MediaType.APPLICATION_JSON));
        this.server.expect(requestTo("https://google.example/maps/api/geocode/json?latlng=0.000000,0.000000&language=en"
                + "&key=test-key"))
            .andRespond(withSuccess("{\"status\":\"ZERO_RESULTS\",\"results\":[]}", MediaType.APPLICATION_JSON));
        this.server.expect(requestTo("https://google.example/maps/api/geocode/json?latlng=1.000000,1.000000&language=en"
                + "&key=test-key"))
            .andRespond(withSuccess("{\"status\":\"REQUEST_DENIED\",\"error_message\":\"The provided API key is invalid.\"}",
                    MediaType.APPLICATION_JSON));
        this.server.expect(requestTo("https://google.example/maps/api/geocode/json?latlng=2.000000,2.000000&language=en"
                + "&key=test-key"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(this.provider.reverse(31.5204, 74.3587, "en"))
            .containsExactly(new AddressSuggestion("12 Main Boulevard, Lahore", 31.5204, 74.3587));
        assertThat(this.provider.reverse(0, 0, "en")).isEmpty();
        assertThatThrownBy(() -> this.provider.reverse(1, 1, "en")).isInstanceOf(ModuleException.Unavailable.class);
        assertThatThrownBy(() -> this.provider.reverse(2, 2, "en")).isInstanceOf(ModuleException.Unavailable.class);
    }
}

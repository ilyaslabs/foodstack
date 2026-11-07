package io.github.ilyaslabs.foodstack.geo.web;

import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.core.application.AddressSuggestion;
import io.github.ilyaslabs.foodstack.geo.core.application.GeocodingService;
import io.github.ilyaslabs.foodstack.geo.core.application.OperatorPricing;
import io.github.ilyaslabs.foodstack.geo.core.application.OperatorPricingService;
import io.github.ilyaslabs.foodstack.geo.core.application.PeakWindow;
import io.github.ilyaslabs.foodstack.geo.core.application.UpdateOperatorPricing;
import io.github.ilyaslabs.foodstack.geo.core.application.ZoneDetails;
import io.github.ilyaslabs.foodstack.geo.core.application.ZoneInput;
import io.github.ilyaslabs.foodstack.geo.core.application.ZoneService;
import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = { GeoController.class, ZonesController.class, CitiesController.class }, properties = "module-switch.modules.geo.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class, GeoWebConfiguration.class })
class GeoControllersTest {

    private static final String ZONES = """
            {"zones":[{"name":"Up to 3 km","center":{"lat":31.52,"lng":74.35},"radiusMeters":3000,
              "chargeMinor":10000,"minOrderMinor":50000,"freeDeliveryAboveMinor":null}]}""";

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    ZoneService zoneService;

    @MockitoBean
    OperatorPricingService pricingService;

    @MockitoBean
    GeocodingService geocodingService;

    @MockitoBean
    io.github.ilyaslabs.foodstack.geo.core.application.CityService cityService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("address search is public")
    void addresses() throws Exception {
        given(this.geocodingService.search("gulberg", "ur"))
            .willReturn(List.of(new AddressSuggestion("Gulberg III, Lahore", 31.52, 74.35)));

        this.mvc.perform(get("/api/v1/geo/addresses").param("q", "gulberg").header("Accept-Language", "ur"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].label").value("Gulberg III, Lahore"))
            .andExpect(jsonPath("$[0].lat").value(31.52));
    }

    @Test
    @DisplayName("the branch owner saves the branch's zones; other users get 403 STORE_ACCESS_DENIED")
    void zones() throws Exception {
        given(this.zoneService.replaceZones(eq("s1"), anyList(), eq("owner-1"))).willReturn(List.of(new ZoneDetails("z1",
                "Up to 3 km", new GeoPoint(31.52, 74.35), 3000, List.of(new GeoPoint(31.5, 74.3)), 10_000, 50_000, null,
                Instant.now())));

        this.mvc.perform(put("/api/v1/zones/stores/s1").with(owner("s1")).contentType(MediaType.APPLICATION_JSON).content(ZONES))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.zones[0].id").value("z1"))
            .andExpect(jsonPath("$.zones[0].center.lat").value(31.52));
        verify(this.zoneService).replaceZones(eq("s1"), eq(List.of(new ZoneInput("Up to 3 km", new GeoPoint(31.52, 74.35),
                3000, null, 10_000, 50_000, null))), eq("owner-1"));

        this.mvc.perform(put("/api/v1/zones/stores/s2").with(owner("s1")).contentType(MediaType.APPLICATION_JSON).content(ZONES))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));
        this.mvc.perform(get("/api/v1/zones/stores/s1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("operator staff with geo:manage see any branch's zones")
    void operatorSeesZones() throws Exception {
        given(this.zoneService.getZones("s9")).willReturn(List.of());

        this.mvc.perform(get("/api/v1/zones/stores/s9").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.storeId").value("s9"));
    }

    @Test
    @DisplayName("operator pricing is edited with If-Match by geo:manage only")
    void pricing() throws Exception {
        String body = "{\"enabled\":true,\"baseChargeMinor\":15000,\"perKmMinor\":4000,\"maxDistanceMeters\":10000}";
        given(this.pricingService.update(new UpdateOperatorPricing(true, 15_000, 4_000, 10_000), 2, "admin-1"))
            .willReturn(new OperatorPricing(true, 15_000, 4_000, 10_000, 0, null, 100, List.of(), 3,
                    Instant.ofEpochSecond(1_800_000_000L)));

        this.mvc.perform(put("/api/v1/geo/operator-pricing").with(admin()).header("If-Match", "\"operator-pricing-2\"")
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"operator-pricing-3\""))
            .andExpect(jsonPath("$.updatedAt").value(1_800_000_000L));
        this.mvc.perform(put("/api/v1/geo/operator-pricing").with(owner("s1")).header("If-Match", "\"operator-pricing-2\"")
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        this.mvc.perform(put("/api/v1/geo/operator-pricing").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));
    }

    @Test
    @DisplayName("minimum order, free threshold and peak hours go through to the pricing service (DR-1b)")
    void pricingExtras() throws Exception {
        PeakWindow evening = new PeakWindow(List.of(java.time.DayOfWeek.FRIDAY), "19:00", "23:00");
        UpdateOperatorPricing update = new UpdateOperatorPricing(true, 15_000, 4_000, 10_000, 50_000, 200_000L, 150,
                List.of(evening));
        given(this.pricingService.update(update, 2, "admin-1"))
            .willReturn(new OperatorPricing(true, 15_000, 4_000, 10_000, 50_000, 200_000L, 150, List.of(evening), 3, null));

        this.mvc.perform(put("/api/v1/geo/operator-pricing").with(admin()).header("If-Match", "\"operator-pricing-2\"")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":true,\"baseChargeMinor\":15000,\"perKmMinor\":4000,\"maxDistanceMeters\":10000,"
                    + "\"minOrderMinor\":50000,\"freeDeliveryAboveMinor\":200000,\"peakMultiplierPercent\":150,"
                    + "\"peakWindows\":[{\"days\":[\"FRIDAY\"],\"start\":\"19:00\",\"end\":\"23:00\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.minOrderMinor").value(50_000))
            .andExpect(jsonPath("$.freeDeliveryAboveMinor").value(200_000))
            .andExpect(jsonPath("$.peakMultiplierPercent").value(150))
            .andExpect(jsonPath("$.peakWindows[0].days[0]").value("FRIDAY"))
            .andExpect(jsonPath("$.peakWindows[0].start").value("19:00"));
    }

    @Test
    @DisplayName("cities: listed for anyone signed in, added and changed with If-Match by geo:manage only")
    void cities() throws Exception {
        io.github.ilyaslabs.foodstack.geo.core.application.City lahore = new io.github.ilyaslabs.foodstack.geo.core.application.City(
                "c1", java.util.Map.of("en", "Lahore"), new GeoPoint(31.52, 74.35), true,
                new io.github.ilyaslabs.foodstack.geo.core.application.CityPricing(20_000, 0, 12_000, 0, null, 100, List.of()), 4,
                null);
        given(this.cityService.list()).willReturn(List.of(lahore));
        given(this.cityService.update(eq("c1"), org.mockito.ArgumentMatchers.any(), eq(3L), eq("admin-1"))).willReturn(lahore);
        String body = "{\"name\":{\"en\":\"Lahore\"},\"centre\":{\"lat\":31.52,\"lng\":74.35},\"active\":true,"
                + "\"pricing\":{\"baseChargeMinor\":20000,\"perKmMinor\":0,\"maxDistanceMeters\":12000}}";

        this.mvc.perform(get("/api/v1/geo/cities").with(owner("s1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name.en").value("Lahore"))
            .andExpect(jsonPath("$[0].pricing.maxDistanceMeters").value(12_000));
        this.mvc.perform(get("/api/v1/geo/cities")).andExpect(status().isUnauthorized());
        this.mvc.perform(put("/api/v1/geo/cities/c1").with(admin()).header("If-Match", "\"city-3\"")
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"city-4\""));
        verify(this.cityService).update(eq("c1"), eq(new io.github.ilyaslabs.foodstack.geo.core.application.CityInput(
                java.util.Map.of("en", "Lahore"), new GeoPoint(31.52, 74.35), true,
                new io.github.ilyaslabs.foodstack.geo.core.application.CityPricing(20_000, 0, 12_000, 0, null, 100, List.of()))),
                eq(3L), eq("admin-1"));
        this.mvc.perform(post("/api/v1/geo/cities").with(owner("s1")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt((token) -> token.subject("admin-1"))
            .authorities(List.of(new SimpleGrantedAuthority("geo:manage"), new SimpleGrantedAuthority("store:manage")));
    }

    private static JwtRequestPostProcessor owner(String storeId) {
        return jwt().jwt((token) -> token.subject("owner-1").claim("storeIds", List.of(storeId)))
            .authorities(List.of(new SimpleGrantedAuthority("store:portal")));
    }
}

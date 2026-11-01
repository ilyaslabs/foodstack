package io.github.ilyaslabs.foodstack.store.web;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import io.github.ilyaslabs.foodstack.store.core.application.Address;
import io.github.ilyaslabs.foodstack.store.core.application.BrandDetails;
import io.github.ilyaslabs.foodstack.store.core.application.Commission;
import io.github.ilyaslabs.foodstack.store.core.application.DeliveryQuote;
import io.github.ilyaslabs.foodstack.store.core.application.EffectiveMenu;
import io.github.ilyaslabs.foodstack.store.core.application.MenuDetails;
import io.github.ilyaslabs.foodstack.store.core.application.MenuItemInput;
import io.github.ilyaslabs.foodstack.store.core.application.MenuService;
import io.github.ilyaslabs.foodstack.store.core.application.NewStore;
import io.github.ilyaslabs.foodstack.store.core.application.StoreDetails;
import io.github.ilyaslabs.foodstack.store.core.application.StoreService;
import io.github.ilyaslabs.foodstack.store.core.application.StoreSummary;
import io.github.ilyaslabs.foodstack.store.core.application.UpdateBrand;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = { StoresController.class, BrandsController.class },
        properties = "module-switch.modules.store.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class, StoreWebConfiguration.class })
class StoreControllersTest {

    private static final String ONBOARD = """
            {"brandName":"Spice Route","branchName":"Gulberg","legalName":"Spice Route (Pvt) Ltd",
             "description":{"en":"Biryani"},"cuisines":["pakistani"],
             "address":{"line1":"1 Main Boulevard","area":"Gulberg","city":"Lahore"},
             "location":{"lat":31.52,"lng":74.35},"pickupEnabled":true,"prepMinutes":25,
             "commission":{"percentBasisPoints":1500,"fixedMinor":0}}""";

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    StoreService storeService;

    @MockitoBean
    MenuService menuService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("operator staff onboard a restaurant; store owners cannot")
    void onboard() throws Exception {
        given(this.storeService.onboard(any(NewStore.class), eq("admin-1"))).willReturn(store(StoreStatus.DRAFT));

        this.mvc.perform(post("/api/v1/stores").with(admin()).contentType(MediaType.APPLICATION_JSON).content(ONBOARD))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"store-1\""))
            .andExpect(jsonPath("$.id").value("s1"))
            .andExpect(jsonPath("$.displayName").value("Spice Route - Gulberg"))
            .andExpect(jsonPath("$.openingHours.MONDAY[0].open").value("11:00"))
            .andExpect(jsonPath("$.brand.commission.percentBasisPoints").value(1500))
            .andExpect(jsonPath("$.status").value("DRAFT"));
        verify(this.storeService).onboard(eq(new NewStore("Spice Route", "Gulberg", "Spice Route (Pvt) Ltd", null,
                Map.of("en", "Biryani"), List.of("pakistani"), new Address("1 Main Boulevard", "Gulberg", "Lahore", null),
                new GeoPoint(31.52, 74.35), DeliveryMode.SELF, true, 25, new Commission(1500, 0))), eq("admin-1"));

        this.mvc.perform(post("/api/v1/stores").with(owner()).contentType(MediaType.APPLICATION_JSON).content(ONBOARD))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a store owner lists only their own branches; operator staff list all")
    void list() throws Exception {
        StoreSummary summary = new StoreSummary("s1", "b1", "Spice Route", "Gulberg", "Lahore", StoreStatus.LIVE, false,
                DeliveryMode.SELF, Instant.ofEpochSecond(1_800_000_000L), Map.of("thumb", "https://cdn.example/logo.webp"));
        given(this.storeService.list(Set.of("s1"), null, 50)).willReturn(List.of(summary));
        given(this.storeService.list(null, "spice", 50)).willReturn(List.of(summary));

        this.mvc.perform(get("/api/v1/stores").with(owner()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value("s1"))
            .andExpect(jsonPath("$.items[0].createdAt").value(1_800_000_000L))
            .andExpect(jsonPath("$.items[0].logoUrls.thumb").value("https://cdn.example/logo.webp"));
        this.mvc.perform(get("/api/v1/stores").param("q", "spice").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].brandName").value("Spice Route"));
    }

    @Test
    @DisplayName("hours need If-Match and access to the branch")
    void hours() throws Exception {
        String body = "{\"openingHours\":{\"MONDAY\":[{\"open\":\"12:00\",\"close\":\"23:00\"}]},\"closures\":[]}";
        given(this.storeService.updateHours(eq("s1"), eq(Map.of(DayOfWeek.MONDAY, List.of(new TimeRange("12:00", "23:00")))),
                eq(List.of()), eq(1L), eq("owner-1")))
            .willReturn(store(StoreStatus.LIVE));

        this.mvc.perform(put("/api/v1/stores/s1/hours").with(owner()).header("If-Match", "\"store-1\"")
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        this.mvc.perform(put("/api/v1/stores/s1/hours").with(owner()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));
        this.mvc.perform(put("/api/v1/stores/s2/hours").with(owner()).header("If-Match", "\"store-1\"")
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));
        this.mvc.perform(put("/api/v1/stores/s1/hours").with(owner()).header("If-Match", "\"store-1\"")
            .contentType(MediaType.APPLICATION_JSON).content("{\"openingHours\":{\"FUNDAY\":[]}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("HOURS_INVALID"));
    }

    @Test
    @DisplayName("only operator staff change status; owners use the temporarily-closed switch")
    void statusAndAvailability() throws Exception {
        this.mvc.perform(put("/api/v1/stores/s1/status").with(owner()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"LIVE\"}"))
            .andExpect(status().isForbidden());
        given(this.storeService.setTemporarilyClosed("s1", true, "Busy", "owner-1")).willReturn(store(StoreStatus.LIVE));
        this.mvc.perform(put("/api/v1/stores/s1/availability").with(owner()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"temporarilyClosed\":true,\"reason\":\"Busy\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("only the operator changes a branch's web address, with If-Match")
    void slug() throws Exception {
        given(this.storeService.updateSlug("s1", "spice-route", 1, "admin-1")).willReturn(store(StoreStatus.LIVE));

        this.mvc.perform(put("/api/v1/stores/s1/slug").with(admin()).header("If-Match", "\"store-1\"")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"slug\":\"spice-route\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.slug").exists());
        this.mvc.perform(put("/api/v1/stores/s1/slug").with(owner()).header("If-Match", "\"store-1\"")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"slug\":\"spice-route\"}")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("customers read a live branch, its menu and a delivery quote without logging in; drafts are hidden")
    void customers() throws Exception {
        given(this.storeService.get("s1")).willReturn(store(StoreStatus.LIVE));
        given(this.storeService.get("s2")).willReturn(store(StoreStatus.DRAFT));
        given(this.storeService.resolve("spice-route-gulberg")).willReturn("s1");
        given(this.storeService.resolve("s1")).willReturn("s1");
        given(this.storeService.resolve("s2")).willReturn("s2");
        given(this.menuService.effectiveMenu("s1")).willReturn(new EffectiveMenu("s1", "b1", "PKR",
                List.of(new EffectiveMenu.Section("sec1", Map.of("en", "Rice", "ar", "أرز"),
                        List.of(new EffectiveMenu.Item("i1", Map.of("en", "Biryani", "ar", "برياني"), Map.of(), 85_000,
                                Map.of(), List.of("halal"), false, List.of()))))));
        given(this.storeService.quote("s1", new GeoPoint(31.5, 74.33)))
            .willReturn(new DeliveryQuote(true, null, 10_000, 50_000, null, 2500, 28, "PKR"));

        this.mvc.perform(get("/api/v1/stores/spice-route-gulberg"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.slug").value("spice-route-gulberg"))
            .andExpect(jsonPath("$.address").value("1 Main Boulevard, Gulberg, Lahore"))
            .andExpect(jsonPath("$.legalName").doesNotExist());
        this.mvc.perform(get("/api/v1/stores/spice-route-gulberg/menu"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections[0].items[0].name.ar").value("برياني"));
        this.mvc.perform(get("/api/v1/stores/s1/delivery-quote").param("lat", "31.5").param("lng", "74.33"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.etaMinutes").value(28));
        this.mvc.perform(get("/api/v1/stores/s2"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        this.mvc.perform(get("/api/v1/stores/s2").with(admin())).andExpect(status().isOk());
        this.mvc.perform(get("/api/v1/stores/s1/details")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the brand owner edits the brand with If-Match and the menu; other brands are refused")
    void brandAndMenu() throws Exception {
        given(this.storeService.updateBrand("b1", new UpdateBrand("Spice Route", Map.of(), List.of("bbq"), null, null), 1,
                "owner-1"))
            .willReturn(brand());
        given(this.menuService.createItem(eq("b1"), any(MenuItemInput.class), eq("owner-1")))
            .willReturn(new MenuDetails.Item("i1", "sec1", Map.of("en", "Biryani"), Map.of(), 85_000, null, Map.of(),
                    List.of(), List.of(), null, 0, 1));

        this.mvc.perform(put("/api/v1/brands/b1").with(owner()).header("If-Match", "\"brand-1\"")
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Spice Route\",\"description\":{},\"cuisines\":[\"bbq\"]}"))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"brand-2\""));
        this.mvc.perform(post("/api/v1/brands/b1/menu/items").with(owner()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sectionId\":\"sec1\",\"name\":{\"en\":\"Biryani\"},\"priceMinor\":85000,\"sortOrder\":0}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("i1"));
        this.mvc.perform(post("/api/v1/brands/b2/menu/items").with(owner()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sectionId\":\"sec1\",\"name\":{\"en\":\"Biryani\"},\"priceMinor\":85000,\"sortOrder\":0}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("BRAND_ACCESS_DENIED"));
        this.mvc.perform(put("/api/v1/brands/b1/commission").with(owner()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"percentBasisPoints\":0,\"fixedMinor\":0}"))
            .andExpect(status().isForbidden());
        verify(this.storeService, never()).updateCommission(any(), any(), any());
    }

    private static BrandDetails brand() {
        return new BrandDetails("b1", "Spice Route", Map.of("en", "Biryani"), List.of("pakistani"), null, Map.of(), null,
                Map.of(), new Commission(1500, 0), 1, 2, Instant.ofEpochSecond(1_800_000_000L));
    }

    private static StoreDetails store(StoreStatus status) {
        Map<DayOfWeek, List<TimeRange>> hours = new java.util.EnumMap<>(DayOfWeek.class);
        hours.put(DayOfWeek.MONDAY, List.of(new TimeRange("11:00", "23:00")));
        return new StoreDetails("s1", "spice-route-gulberg", new BrandDetails("b1", "Spice Route", Map.of("en", "Biryani"), List.of("pakistani"),
                null, Map.of(), null, Map.of(), new Commission(1500, 0), 1, 1, Instant.ofEpochSecond(1_800_000_000L)),
                "Gulberg", "Spice Route (Pvt) Ltd", null, Set.of(), new Address("1 Main Boulevard", "Gulberg", "Lahore", null),
                new GeoPoint(31.52, 74.35), hours, List.of(), false, null, 25, DeliveryMode.SELF, false, true,
                new Commission(1500, 0), status, "Asia/Karachi", true, 1, Instant.ofEpochSecond(1_800_000_000L),
                Instant.ofEpochSecond(1_800_000_000L));
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt((token) -> token.subject("admin-1"))
            .authorities(List.of(new SimpleGrantedAuthority("store:manage")));
    }

    private static JwtRequestPostProcessor owner() {
        return jwt().jwt((token) -> token.subject("owner-1").claim("storeIds", List.of("s1")).claim("brandIds", List.of("b1")))
            .authorities(List.of(new SimpleGrantedAuthority("store:portal")));
    }
}

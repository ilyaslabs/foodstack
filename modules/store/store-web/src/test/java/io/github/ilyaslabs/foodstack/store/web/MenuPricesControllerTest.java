package io.github.ilyaslabs.foodstack.store.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import io.github.ilyaslabs.foodstack.store.core.application.MenuPrices;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MenuPricesController.class, properties = "module-switch.modules.store.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class, StoreWebConfiguration.class })
class MenuPricesControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    MenuPrices prices;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("a branch manager sets their branch's price and hears whether it waits for approval")
    void setBranchPrice() throws Exception {
        given(this.prices.setBranchPrice("s1", "i1", "full", 45_000L, "owner-1")).willReturn(MenuPrices.Outcome.PENDING);

        this.mvc.perform(put("/api/v1/stores/s1/menu/items/i1/price").with(owner())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceMinor\":45000,\"optionId\":\"full\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("PENDING"));
        this.mvc.perform(put("/api/v1/stores/s2/menu/items/i1/price").with(owner())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceMinor\":45000}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("lists a branch's own prices with what waits for approval")
    void branchPrices() throws Exception {
        given(this.prices.branchPrices("s1")).willReturn(new MenuPrices.BranchPrices(
                List.of(new MenuPrices.BranchPrice("i1", null, 55_000)), List.of(change())));

        this.mvc.perform(get("/api/v1/stores/s1/menu/prices").with(owner()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.prices[0].priceMinor").value(55_000))
            .andExpect(jsonPath("$.pending[0].proposedMinor").value(60_000))
            .andExpect(jsonPath("$.pending[0].requestedAt").value(1_800_000_000L));
    }

    @Test
    @DisplayName("only operator staff list and decide price changes")
    void decide() throws Exception {
        given(this.prices.pending()).willReturn(List.of(change()));
        given(this.prices.decide("c1", true, "admin-1")).willReturn(change());

        this.mvc.perform(get("/api/v1/price-changes").with(owner())).andExpect(status().isForbidden());
        this.mvc.perform(post("/api/v1/price-changes/c1/approve").with(owner())).andExpect(status().isForbidden());
        verify(this.prices, never()).decide(any(), any(Boolean.class), any());

        this.mvc.perform(get("/api/v1/price-changes").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].itemName.en").value("Biryani"));
        this.mvc.perform(post("/api/v1/price-changes/c1/approve").with(admin())).andExpect(status().isOk());
        verify(this.prices).decide("c1", true, "admin-1");
    }

    private static MenuPrices.PriceChange change() {
        return new MenuPrices.PriceChange("c1", "b1", "Spice Route", "s1", "Gulberg", "i1", Map.of("en", "Biryani"), null,
                null, 50_000, 60_000L, "owner-1", Instant.ofEpochSecond(1_800_000_000L), "PENDING");
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt((token) -> token.subject("admin-1"))
            .authorities(List.of(new SimpleGrantedAuthority("store:manage")));
    }

    private static JwtRequestPostProcessor owner() {
        return jwt().jwt((token) -> token.subject("owner-1").claim("storeIds", List.of("s1")))
            .authorities(List.of(new SimpleGrantedAuthority("store:portal")));
    }
}

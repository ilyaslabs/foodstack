package io.github.ilyaslabs.foodstack.promotion.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import io.github.ilyaslabs.foodstack.promotion.core.application.PromotionAccess;
import io.github.ilyaslabs.foodstack.promotion.core.application.PromotionDetails;
import io.github.ilyaslabs.foodstack.promotion.core.application.PromotionService;
import io.github.ilyaslabs.moduleswitch.ModuleException;

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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PromotionsController.class, properties = "module-switch.modules.promotion.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class })
class PromotionsControllerTest {

    private static final String BODY = """
            {"code":"WELCOME","title":{"en":"20% off"},"type":"PERCENT","percentBasisPoints":2000,"startsAt":1793500000}""";

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    PromotionService promotions;

    @MockitoBean
    PromotionAccess access;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("creating checks access for the brand and branches first; a refusal is a 403 and nothing is created")
    void create() throws Exception {
        given(this.promotions.create(eq(null), any(), eq("admin-1"))).willReturn(details(null));
        given(this.promotions.create(eq("b1"), any(), eq("owner-1"))).willReturn(details("b1"));

        this.mvc.perform(post("/api/v1/promotions").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"promotion-0\""))
            .andExpect(jsonPath("$.startsAt").value(1_793_500_000L));
        verify(this.access).requireCreate(null, null);

        willThrow(new ModuleException.Forbidden("off", "BRANCH_PROMOTIONS_OFF")).given(this.access)
            .requireCreate("b1", List.of("s2"));
        this.mvc.perform(post("/api/v1/promotions").with(owner("b1")).contentType(MediaType.APPLICATION_JSON)
            .content(BODY.replace("{\"code\"", "{\"brandId\":\"b1\",\"storeIds\":[\"s2\"],\"code\"")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("BRANCH_PROMOTIONS_OFF"));
        verify(this.promotions, never()).create(eq("b1"), any(), any());
    }

    @Test
    @DisplayName("lists within the user's scope and tells the back office whether branch promotions are on; edits need If-Match")
    void listAndEdit() throws Exception {
        PromotionAccess.Scope branch = new PromotionAccess.Scope(List.of(), List.of("s1"));
        given(this.access.listScope(null)).willReturn(branch);
        given(this.promotions.listIn(branch, 200)).willReturn(List.of(details("b1")));
        given(this.access.branchPromotionsAllowed("b1")).willReturn(true);
        given(this.promotions.details("p1")).willReturn(details("b1"));

        this.mvc.perform(get("/api/v1/promotions").with(owner("b1"))).andExpect(jsonPath("$.length()").value(1));
        this.mvc.perform(get("/api/v1/promotions/brands/b1/rules").with(owner("b1")))
            .andExpect(jsonPath("$.branchPromotionsAllowed").value(true));
        this.mvc.perform(put("/api/v1/promotions/p1").with(owner("b1")).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));
        willThrow(new ModuleException.Forbidden("no", "BRAND_ACCESS_DENIED")).given(this.access).requireEdit(any(), any());
        this.mvc.perform(put("/api/v1/promotions/p1").with(owner("b2")).header("If-Match", "\"promotion-0\"")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isForbidden());
        verify(this.promotions, never()).update(any(), any(), anyLong(), any());
    }

    private static PromotionDetails details(String brandId) {
        return new PromotionDetails("p1", (brandId == null) ? "OPERATOR" : "STORE", brandId, "WELCOME", Map.of("en", "20% off"),
                "PERCENT", 2000, null, 0, 0, Instant.ofEpochSecond(1_793_500_000L), null, null, null, false, List.of(), List.of(),
                List.of(), false, 0, true, 0, 0, null);
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt((token) -> token.subject("admin-1"))
            .authorities(List.of(new SimpleGrantedAuthority("promotion:manage")));
    }

    private static JwtRequestPostProcessor owner(String brandId) {
        return jwt().jwt((token) -> token.subject("owner-1").claim("brandIds", List.of(brandId)))
            .authorities(List.of(new SimpleGrantedAuthority("store:portal")));
    }
}

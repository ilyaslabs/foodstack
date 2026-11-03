package io.github.ilyaslabs.foodstack.rider.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderOnboarding;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderOnboarding.DocumentInput;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderOnboarding.DocumentView;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderOnboarding.Papers;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderOnboarding.PayoutInput;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderOnboarding.PayoutView;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RiderPapersController.class, properties = "module-switch.modules.rider.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class })
class RiderPapersControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    RiderOnboarding onboarding;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    private static Papers papers() {
        return new Papers("r1", List.of(new DocumentView("d1", "NATIONAL_ID", "4567", "https://media/x", null, null, false,
                1_800_000_000L, true, 1_800_000_100L)), new PayoutView("BANK", "Ali Khan", "Meezan", "1234", 1_800_000_000L),
                true, true, true, 3);
    }

    @Test
    @DisplayName("papers need rider:manage and show numbers only by their last 4 characters")
    void read() throws Exception {
        given(this.onboarding.get("r1")).willReturn(papers());
        this.mvc.perform(get("/api/v1/riders/r1/papers").with(jwt())).andExpect(status().isForbidden());
        this.mvc.perform(get("/api/v1/riders/r1/papers").with(manage()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.complete").value(true))
            .andExpect(jsonPath("$.documents[0].numberLast4").value("4567"))
            .andExpect(jsonPath("$.payout.accountLast4").value("1234"));

        given(this.onboarding.completeRiders()).willReturn(List.of("r1"));
        this.mvc.perform(get("/api/v1/riders/papers/complete").with(manage()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("r1"));
    }

    @Test
    @DisplayName("adding a document answers 201; verify, remove and payout go to the onboarding service")
    void change() throws Exception {
        given(this.onboarding.addDocument(any(), any())).willReturn(papers());
        this.mvc.perform(post("/api/v1/riders/r1/papers/documents").with(manage()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"NATIONAL_ID\",\"number\":\"35202-1234567-1\",\"frontMediaId\":\"m1\"}"))
            .andExpect(status().isCreated());
        verify(this.onboarding).addDocument("r1", new DocumentInput("NATIONAL_ID", "35202-1234567-1", "m1", null, null));

        this.mvc.perform(post("/api/v1/riders/r1/papers/documents/d1/verify").with(manage())).andExpect(status().isOk());
        verify(this.onboarding).verifyDocument("r1", "d1");

        this.mvc.perform(delete("/api/v1/riders/r1/papers/documents/d1").with(manage())).andExpect(status().isOk());
        verify(this.onboarding).removeDocument("r1", "d1");

        given(this.onboarding.setPayout(any(), any()))
            .willThrow(new ModuleException.BadRequest("Check the payout details", "RIDER_PAYOUT_INVALID"));
        this.mvc.perform(put("/api/v1/riders/r1/papers/payout").with(manage()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"method\":\"BANK\",\"accountName\":\"Ali\",\"provider\":\"Meezan\",\"account\":\"12\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("RIDER_PAYOUT_INVALID"));
        verify(this.onboarding).setPayout("r1", new PayoutInput("BANK", "Ali", "Meezan", "12"));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor manage() {
        return jwt().authorities(List.of(new SimpleGrantedAuthority("rider:manage")));
    }
}

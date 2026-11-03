package io.github.ilyaslabs.foodstack.dispatch.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliveryStatus;
import io.github.ilyaslabs.foodstack.dispatch.core.application.Delivered;
import io.github.ilyaslabs.foodstack.dispatch.core.application.DispatchLive;
import io.github.ilyaslabs.foodstack.dispatch.core.application.DispatchService;
import io.github.ilyaslabs.foodstack.dispatch.core.application.JobDetails;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = { DeliveriesController.class, DispatchStreamsController.class },
        properties = "module-switch.modules.dispatch.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class })
class DeliveriesControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    DispatchService dispatch;

    @MockitoBean
    DispatchLive live;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    private static JobDetails job(DeliveryStatus status) {
        return new JobDetails("o1", "AB12-CD34", DeliveryMode.OPERATOR, status, "s1", "Spice Route", 31.52, 74.35, "1 Main",
                "Sara", "+92 300 1234567", 31.5, 74.3, "12 Canal Road", null, "r1", "Ali", null, 10_500, 0, 0, "PKR", true, 2_400,
                1_800_000_000L, null, null, 3);
    }

    @Test
    @DisplayName("rider steps need rider:portal and pass the proof; problems come back as problem details")
    void rider() throws Exception {
        given(this.dispatch.accept("o1")).willReturn(job(DeliveryStatus.ASSIGNED));
        this.mvc.perform(post("/api/v1/dispatch/rider/offers/o1/accept").with(jwt())).andExpect(status().isForbidden());
        this.mvc.perform(post("/api/v1/dispatch/rider/offers/o1/accept").with(asRider()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ASSIGNED"))
            .andExpect(jsonPath("$.needsCode").value(true));

        given(this.dispatch.delivered("o1", new Delivered("1111", null, null)))
            .willThrow(new ModuleException.BadRequest("wrong", "DELIVERY_CODE_WRONG"));
        this.mvc.perform(post("/api/v1/deliveries/o1/delivered").with(asRider()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"1111\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("DELIVERY_CODE_WRONG"));

        this.mvc.perform(post("/api/v1/dispatch/rider/offers/o1/decline").with(asRider())).andExpect(status().isNoContent());
        verify(this.dispatch).decline("o1");
        this.mvc.perform(get("/api/v1/dispatch/rider/stream").with(asRider())).andExpect(status().isOk());
        verify(this.live).onRider(any(), any());
    }

    @Test
    @DisplayName("support's board, reassign and hand back need dispatch:manage")
    void support() throws Exception {
        given(this.dispatch.open(DeliveryStatus.SEARCHING)).willReturn(List.of(job(DeliveryStatus.SEARCHING)));
        this.mvc.perform(get("/api/v1/dispatch/jobs?status=SEARCHING").with(jwt())).andExpect(status().isForbidden());
        this.mvc.perform(get("/api/v1/dispatch/jobs?status=SEARCHING").with(manage()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].orderId").value("o1"));
        this.mvc.perform(post("/api/v1/dispatch/jobs/o1/reassign").with(manage()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"riderId\":\"r2\",\"reason\":\"Closer\"}")).andExpect(status().isOk());
        verify(this.dispatch).reassign("o1", "r2", "Closer");
        this.mvc.perform(post("/api/v1/dispatch/jobs/o1/hand-back").with(manage()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"No riders\"}")).andExpect(status().isOk());
        verify(this.dispatch).handBack("o1", "No riders");
        this.mvc.perform(post("/api/v1/deliveries/o1/store-rider").with(jwt()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"riderId\":\"sr1\"}")).andExpect(status().isOk());
        verify(this.dispatch).assignStoreRider("o1", "sr1");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor asRider() {
        return jwt().jwt((t) -> t.subject("r1")).authorities(List.of(new SimpleGrantedAuthority("rider:portal")));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor manage() {
        return jwt().authorities(List.of(new SimpleGrantedAuthority("dispatch:manage")));
    }
}

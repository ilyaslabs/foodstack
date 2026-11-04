package io.github.ilyaslabs.foodstack.rider.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import io.github.ilyaslabs.foodstack.rider.api.RiderKind;
import io.github.ilyaslabs.foodstack.rider.api.RiderStatus;
import io.github.ilyaslabs.foodstack.rider.api.Vehicle;
import io.github.ilyaslabs.foodstack.rider.core.application.LocationPing;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderActivity;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderDetails;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderInput;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderPresence;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = { RidersController.class, RiderStreamsController.class },
        properties = "module-switch.modules.rider.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class })
class RidersControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    RiderService riders;

    @MockitoBean
    RiderActivity activity;

    @MockitoBean
    RiderPresence presence;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    private static RiderDetails ali() {
        return new RiderDetails("r1", RiderKind.OPERATOR, null, "Ali", "+92 300 1234567", Vehicle.MOTORBIKE, null, "Lahore",
                RiderStatus.ACTIVE, true, 31.52, 74.35, 1_800_000_000L, List.of(), 100, 3, 1_799_000_000L, 2);
    }

    @Test
    @DisplayName("the rider's own endpoints need rider:portal; positions go to the activity service")
    void rider() throws Exception {
        given(this.activity.setOnline(eq(true), any())).willReturn(ali());
        this.mvc.perform(post("/api/v1/rider/me/online").with(jwt()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"online\":true}")).andExpect(status().isForbidden());
        this.mvc.perform(post("/api/v1/rider/me/online").with(portal()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"online\":true,\"lat\":31.52,\"lng\":74.35}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.online").value(true))
            .andExpect(jsonPath("$.vehicle").value("MOTORBIKE"));
        verify(this.activity).setOnline(true, new LocationPing(31.52, 74.35, null, null));

        this.mvc.perform(post("/api/v1/rider/me/locations").with(portal()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"points\":[{\"lat\":31.5,\"lng\":74.3,\"at\":1800000000}]}")).andExpect(status().isNoContent());
        verify(this.activity).locations(List.of(new LocationPing(31.5, 74.3, null, 1_800_000_000L)));

        given(this.activity.myEarnings(7, "Asia/Karachi")).willThrow(new ModuleException.NotFound("x", "RIDER_NOT_FOUND"));
        this.mvc.perform(get("/api/v1/rider/me/earnings?tz=Asia/Karachi").with(portal()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RIDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("the fleet needs rider:manage; adding answers 201; a store adds and removes its own riders")
    void operatorAndStore() throws Exception {
        given(this.riders.addOperatorRider(any())).willReturn(ali());
        String body = "{\"email\":\"ali@example.com\",\"initialPassword\":\"a-long-password\",\"name\":\"Ali\","
                + "\"phone\":\"+92 300 1234567\",\"vehicle\":\"MOTORBIKE\",\"city\":\"Lahore\"}";
        this.mvc.perform(post("/api/v1/riders").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        this.mvc.perform(post("/api/v1/riders").with(manage()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("r1"));
        verify(this.riders).addOperatorRider(new RiderInput("ali@example.com", "a-long-password", "Ali", "+92 300 1234567",
                Vehicle.MOTORBIKE, null, "Lahore", null));

        this.mvc.perform(get("/api/v1/riders?kind=OPERATOR").with(manage())).andExpect(status().isOk());
        verify(this.riders).list(RiderKind.OPERATOR, null, null, null);

        this.mvc.perform(post("/api/v1/riders/r1/status").with(manage()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SUSPENDED\"}")).andExpect(status().isOk());
        verify(this.riders).setStatus("r1", RiderStatus.SUSPENDED);

        this.mvc.perform(delete("/api/v1/riders/stores/s1/r2").with(jwt())).andExpect(status().isNoContent());
        verify(this.riders).removeStoreRider("s1", "r2");
    }

    @Test
    @DisplayName("following a delivery and the live map check access before streaming")
    void streams() throws Exception {
        given(this.activity.requireFollow("o1")).willThrow(new ModuleException.NotFound("x", "TRACKING_NOT_FOUND"));
        this.mvc.perform(get("/api/v1/riders/tracking/o1/stream").with(jwt())).andExpect(status().isNotFound());
        this.mvc.perform(get("/api/v1/riders/live/stream")).andExpect(status().isUnauthorized());
        this.mvc.perform(get("/api/v1/riders/live/stream").with(manage()).param("south", "31.3").param("west", "74.1")
            .param("north", "31.7").param("east", "74.6")).andExpect(status().isOk());
        verify(this.activity).requireLive();
        verify(this.presence).onCells(eq(List.of("485_1016", "485_1017", "485_1018", "486_1016", "486_1017", "486_1018")), any());
        // a whole country at once would subscribe to hundreds of cells: the map must zoom in
        this.mvc.perform(get("/api/v1/riders/live/stream").with(manage()).param("south", "24").param("west", "61")
            .param("north", "37").param("east", "77"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MAP_VIEW_TOO_WIDE"));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor portal() {
        return jwt().jwt((t) -> t.subject("r1")).authorities(List.of(new SimpleGrantedAuthority("rider:portal")));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor manage() {
        return jwt().authorities(List.of(new SimpleGrantedAuthority("rider:manage")));
    }
}

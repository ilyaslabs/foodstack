package io.github.ilyaslabs.foodstack.notification.web;

import io.github.ilyaslabs.foodstack.notification.core.application.PushService;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DevicesController.class,
        properties = { "module-switch.modules.notification.mode=local",
                "foodstack.notification.push.web-config={\"projectId\":\"eats-demo\",\"appId\":\"1:2:web:3\"}",
                "foodstack.notification.push.vapid-key=BPk" })
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class, NotificationWebConfiguration.class })
class DevicesControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    PushService push;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("apps read the push settings without signing in; they are empty while push is off")
    void config() throws Exception {
        this.mvc.perform(get("/api/v1/devices/push-config")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.firebase").doesNotExist());
        given(this.push.enabled()).willReturn(true);
        this.mvc.perform(get("/api/v1/devices/push-config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.firebase.projectId").value("eats-demo"))
            .andExpect(jsonPath("$.vapidKey").value("BPk"));
    }

    @Test
    @DisplayName("a signed-in person registers and removes their own device")
    void devices() throws Exception {
        String body = "{\"token\":\"tok\",\"platform\":\"WEB\",\"app\":\"backoffice\"}";
        this.mvc.perform(post("/api/v1/devices").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        this.mvc.perform(post("/api/v1/devices").with(jwt().jwt((t) -> t.subject("u1")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)).andExpect(status().isNoContent());
        verify(this.push).register("u1", "tok", "WEB", "backoffice");

        willThrow(new ModuleException.BadRequest("Unknown platform or app", "DEVICE_INVALID")).given(this.push)
            .register("u1", "tok", "FAX", "web");
        this.mvc.perform(post("/api/v1/devices").with(jwt().jwt((t) -> t.subject("u1")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"tok\",\"platform\":\"FAX\",\"app\":\"web\"}")).andExpect(status().isBadRequest());

        this.mvc.perform(post("/api/v1/devices/remove").with(jwt().jwt((t) -> t.subject("u1")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"tok\"}")).andExpect(status().isNoContent());
        verify(this.push).unregister("u1", "tok");
    }
}

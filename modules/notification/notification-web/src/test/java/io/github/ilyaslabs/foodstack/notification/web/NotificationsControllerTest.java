package io.github.ilyaslabs.foodstack.notification.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.notification.core.application.InboxService;
import io.github.ilyaslabs.foodstack.notification.core.application.NotificationService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NotificationsController.class, properties = "module-switch.modules.notification.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class })
class NotificationsControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    InboxService inbox;

    @MockitoBean
    NotificationService notifications;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("the bell shows only the signed-in user's own notifications")
    void inbox() throws Exception {
        given(this.inbox.list("u1", null, 20)).willReturn(List.of(new InboxService.Item("n1", "order-accepted",
                "Order accepted", "Your order is being prepared", "/orders/o1", 1_000L, false)));
        given(this.inbox.unread("u1")).willReturn(1L);

        this.mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        this.mvc.perform(get("/api/v1/notifications").with(jwt().jwt((t) -> t.subject("u1"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("Order accepted"))
            .andExpect(jsonPath("$[0].read").value(false));
        this.mvc.perform(get("/api/v1/notifications/unread-count").with(jwt().jwt((t) -> t.subject("u1"))))
            .andExpect(jsonPath("$.unread").value(1));
        this.mvc.perform(post("/api/v1/notifications/n1/read").with(jwt().jwt((t) -> t.subject("u1"))))
            .andExpect(status().isNoContent());
        verify(this.inbox).markRead("u1", "n1");
    }

    @Test
    @DisplayName("a partial preferences update keeps the other choice")
    void preferences() throws Exception {
        given(this.notifications.preferences("u1")).willReturn(new NotificationService.Preferences(true, true));
        given(this.notifications.updatePreferences("u1", true, false))
            .willReturn(new NotificationService.Preferences(true, false));

        this.mvc.perform(put("/api/v1/notifications/preferences").with(jwt().jwt((t) -> t.subject("u1")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"marketingEmails\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderEmails").value(true))
            .andExpect(jsonPath("$.marketingEmails").value(false));
    }

    @Test
    @DisplayName("the email delivery log and retry are for operator staff only")
    void deliveries() throws Exception {
        this.mvc.perform(get("/api/v1/notifications/deliveries").with(jwt())).andExpect(status().isForbidden());
        this.mvc.perform(post("/api/v1/notifications/deliveries/d1/retry").with(jwt())).andExpect(status().isForbidden());
        this.mvc.perform(post("/api/v1/notifications/deliveries/d1/retry")
            .with(jwt().authorities(List.of(new SimpleGrantedAuthority("settings:manage")))))
            .andExpect(status().isAccepted());
        verify(this.notifications).retry("d1");
    }
}

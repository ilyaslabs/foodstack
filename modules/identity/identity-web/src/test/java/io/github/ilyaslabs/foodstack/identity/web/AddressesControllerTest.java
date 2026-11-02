package io.github.ilyaslabs.foodstack.identity.web;

import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.foodstack.identity.core.application.AddressBook;
import io.github.ilyaslabs.foodstack.identity.core.application.AddressInput;
import io.github.ilyaslabs.foodstack.identity.core.application.SavedAddress;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AddressesController.class, properties = "module-switch.modules.identity.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class, IdentityWebConfiguration.class })
class AddressesControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    AddressBook book;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    private static SavedAddress home() {
        return new SavedAddress("a1", "HOME", null, 31.52, 74.35, "12 Main Boulevard", "Blue gate", true,
                Instant.ofEpochSecond(1_800_000_000L), 3);
    }

    @Test
    @DisplayName("GET lists only the caller's addresses and needs a sign-in")
    void list() throws Exception {
        given(this.book.list("u1")).willReturn(List.of(home()));

        this.mvc.perform(get("/api/v1/me/addresses")).andExpect(status().isUnauthorized());
        this.mvc.perform(get("/api/v1/me/addresses").with(jwt().jwt((t) -> t.subject("u1"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("a1"))
            .andExpect(jsonPath("$[0].label").value("HOME"))
            .andExpect(jsonPath("$[0].preferred").value(true))
            .andExpect(jsonPath("$[0].updatedAt").value(1_800_000_000L))
            .andExpect(jsonPath("$[0].version").value(3));
    }

    @Test
    @DisplayName("POST adds, PUT passes the edited version, DELETE removes")
    void writes() throws Exception {
        AddressInput input = new AddressInput("HOME", null, 31.52, 74.35, "12 Main Boulevard", "Blue gate", true);
        given(this.book.add("u1", input)).willReturn(home());
        given(this.book.update(eq("u1"), eq("a1"), any(), eq(3L))).willReturn(home());
        String body = "{\"label\":\"HOME\",\"lat\":31.52,\"lng\":74.35,\"addressLine\":\"12 Main Boulevard\","
                + "\"instructions\":\"Blue gate\",\"preferred\":true";

        this.mvc.perform(post("/api/v1/me/addresses").with(jwt().jwt((t) -> t.subject("u1")))
            .contentType(MediaType.APPLICATION_JSON).content(body + "}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("a1"));
        this.mvc.perform(put("/api/v1/me/addresses/a1").with(jwt().jwt((t) -> t.subject("u1")))
            .contentType(MediaType.APPLICATION_JSON).content(body + ",\"version\":3}"))
            .andExpect(status().isOk());
        this.mvc.perform(delete("/api/v1/me/addresses/a1").with(jwt().jwt((t) -> t.subject("u1"))))
            .andExpect(status().isNoContent());
        verify(this.book).remove("u1", "a1");
    }

    @Test
    @DisplayName("a second Home is refused as 409 ADDRESS_LABEL_TAKEN")
    void labelTaken() throws Exception {
        given(this.book.add(eq("u1"), any())).willThrow(new ModuleException.Conflict("taken", "ADDRESS_LABEL_TAKEN"));

        this.mvc.perform(post("/api/v1/me/addresses").with(jwt().jwt((t) -> t.subject("u1")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"HOME\",\"lat\":1,\"lng\":2,\"addressLine\":\"x\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ADDRESS_LABEL_TAKEN"));
    }
}

package io.github.ilyaslabs.foodstack.platform.web;

import io.github.ilyaslabs.moduleswitch.ModuleException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProblemDetailsAdviceTest.SampleController.class)
@Import({ ProblemDetailsAdvice.class, ProblemDetailsAdviceTest.SampleController.class })
class ProblemDetailsAdviceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
    }

    record Body(@NotBlank String name) {
    }

    @RestController
    static class SampleController {

        @GetMapping("/missing")
        String missing() {
            throw new ModuleException.NotFound("Store not found", "STORE_NOT_FOUND");
        }

        @GetMapping("/broken")
        String broken() {
            throw new ModuleException.Internal("database password is wrong", "INTERNAL");
        }

        @PostMapping("/things")
        String create(@Valid @RequestBody Body body) {
            return body.name();
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("maps a module error to problem+json with its code")
    void moduleError() throws Exception {
        this.mvc.perform(get("/missing"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"))
            .andExpect(jsonPath("$.detail").value("Store not found"));
    }

    @Test
    @DisplayName("hides internal error messages from callers")
    void internalError() throws Exception {
        this.mvc.perform(get("/broken"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("The request could not be completed."));
    }

    @Test
    @DisplayName("lists every invalid field")
    void validation() throws Exception {
        this.mvc.perform(post("/things").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[0].field").value("name"));
    }
}

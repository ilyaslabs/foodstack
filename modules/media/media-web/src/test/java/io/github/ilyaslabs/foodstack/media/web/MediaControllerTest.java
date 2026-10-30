package io.github.ilyaslabs.foodstack.media.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaStatus;
import io.github.ilyaslabs.foodstack.media.core.application.MediaAssetDetails;
import io.github.ilyaslabs.foodstack.media.core.application.MediaService;
import io.github.ilyaslabs.foodstack.media.core.application.StartUpload;
import io.github.ilyaslabs.foodstack.media.core.application.UploadTicket;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MediaController.class, properties = "module-switch.modules.media.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class })
class MediaControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    MediaService mediaService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("POST /api/v1/media/uploads returns a presigned PUT for users allowed to upload")
    void startUpload() throws Exception {
        given(this.mediaService.startUpload(new StartUpload(MediaPurpose.BRANDING, "image/png", 2048), "admin-1"))
            .willReturn(new UploadTicket(details(MediaStatus.PENDING_UPLOAD), URI.create("https://s3/put?sig"),
                    Map.of("Content-Type", "image/png"), Instant.ofEpochSecond(1_800_000_300L)));

        this.mvc.perform(post("/api/v1/media/uploads").with(uploader())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"purpose\":\"BRANDING\",\"contentType\":\"image/png\",\"sizeBytes\":2048}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.uploadUrl").value("https://s3/put?sig"))
            .andExpect(jsonPath("$.method").value("PUT"))
            .andExpect(jsonPath("$.headers.Content-Type").value("image/png"))
            .andExpect(jsonPath("$.expiresAt").value(1_800_000_300L))
            .andExpect(jsonPath("$.media.status").value("PENDING_UPLOAD"));
    }

    @Test
    @DisplayName("refuses uploads without the media:upload permission or a token")
    void uploadPermission() throws Exception {
        String body = "{\"purpose\":\"BRANDING\",\"contentType\":\"image/png\",\"sizeBytes\":2048}";
        this.mvc.perform(post("/api/v1/media/uploads").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        this.mvc.perform(post("/api/v1/media/uploads").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("validates the upload request")
    void validation() throws Exception {
        this.mvc.perform(post("/api/v1/media/uploads").with(uploader())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"contentType\":\"image/png\",\"sizeBytes\":0}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST .../complete returns the processed variants")
    void complete() throws Exception {
        given(this.mediaService.completeUpload("m1", "admin-1")).willReturn(details(MediaStatus.READY));

        this.mvc.perform(post("/api/v1/media/uploads/m1/complete").with(uploader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.variants.thumb.url").value("https://cdn/thumb.webp"))
            .andExpect(jsonPath("$.createdAt").value(1_800_000_000L));
    }

    @Test
    @DisplayName("maps media errors to problem+json")
    void errors() throws Exception {
        given(this.mediaService.get(any())).willThrow(new ModuleException.NotFound("Media x not found", "MEDIA_NOT_FOUND"));

        this.mvc.perform(get("/api/v1/media/x").with(jwt()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
    }

    private static JwtRequestPostProcessor uploader() {
        return jwt().jwt((token) -> token.subject("admin-1"))
            .authorities(List.of(new SimpleGrantedAuthority("media:upload")));
    }

    private static MediaAssetDetails details(MediaStatus status) {
        return new MediaAssetDetails("m1", MediaPurpose.BRANDING, status,
                (status == MediaStatus.READY) ? Map.of("thumb", new MediaAssetDetails.Variant("https://cdn/thumb.webp", 160, 80, 99))
                        : Map.of(),
                null, null, Instant.ofEpochSecond(1_800_000_000L));
    }
}

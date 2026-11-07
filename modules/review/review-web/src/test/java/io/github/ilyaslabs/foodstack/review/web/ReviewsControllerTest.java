package io.github.ilyaslabs.foodstack.review.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import io.github.ilyaslabs.foodstack.review.core.application.CustomerReviews;
import io.github.ilyaslabs.foodstack.review.core.application.OrderReviewState;
import io.github.ilyaslabs.foodstack.review.core.application.RatingSummary;
import io.github.ilyaslabs.foodstack.review.core.application.ReviewPage;
import io.github.ilyaslabs.foodstack.review.core.application.ReviewService;
import io.github.ilyaslabs.foodstack.review.core.application.ReviewView;
import io.github.ilyaslabs.foodstack.review.core.application.SubmitReview;
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

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The review endpoints with {@link ReviewService} mocked: routes, public reads and refusals. */
@WebMvcTest(controllers = ReviewsController.class, properties = "module-switch.modules.review.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class, ReviewWebConfiguration.class })
class ReviewsControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    ReviewService reviews;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    private static ReviewView review() {
        return new ReviewView("r1", "s1", "Napoli DHA", 4, "Great crust", null, null, "Ayesha K.", 1_762_000_000L, null,
                "PUBLISHED", null, false, null, null);
    }

    @Test
    @DisplayName("a branch's reviews and rating are public")
    void publicReads() throws Exception {
        given(this.reviews.storeReviews("s1", null, 20)).willReturn(new ReviewPage(List.of(review()), null));
        given(this.reviews.summary("s1")).willReturn(new RatingSummary("s1", 1, 4.0, List.of(0L, 0L, 0L, 1L, 0L), "b1", 3, 4.3));

        this.mvc.perform(get("/api/v1/reviews/stores/s1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].customerName").value("Ayesha K."));
        this.mvc.perform(get("/api/v1/reviews/stores/s1/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.brandAverage").value(4.3));
    }

    @Test
    @DisplayName("a customer's review export needs a signed-in user")
    void export() throws Exception {
        // given
        given(this.reviews.export()).willReturn(new CustomerReviews(List.of(review()), List.of()));

        // when / then
        this.mvc.perform(get("/api/v1/reviews/mine/export")).andExpect(status().isUnauthorized());
        this.mvc.perform(get("/api/v1/reviews/mine/export").with(jwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reviews[0].rating").exists());
    }

    @Test
    @DisplayName("customers rate when signed in; the state comes back")
    void submit() throws Exception {
        SubmitReview request = new SubmitReview("o1", 5, "Lovely", null, 4, List.of("FRIENDLY"));
        given(this.reviews.submit(request))
            .willReturn(new OrderReviewState("o1", false, false, "Hamza", 1_763_000_000L, review(), 4));

        String body = "{\"orderId\":\"o1\",\"storeRating\":5,\"text\":\"Lovely\",\"riderRating\":4,\"riderTags\":[\"FRIENDLY\"]}";
        this.mvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        this.mvc.perform(post("/api/v1/reviews").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.riderRating").value(4));
        verify(this.reviews).submit(request);
    }

    @Test
    @DisplayName("moderation needs review:moderate; service refusals become problem details")
    void moderation() throws Exception {
        given(this.reviews.hide("r1", "Abusive")).willReturn(review());
        given(this.reviews.reply("r2", "Thanks"))
            .willThrow(new ModuleException.Conflict("This review already has a reply", "REVIEW_ALREADY_REPLIED"));

        this.mvc.perform(post("/api/v1/reviews/r1/hide").with(jwt()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Abusive\"}")).andExpect(status().isForbidden());
        this.mvc.perform(post("/api/v1/reviews/r1/hide")
            .with(jwt().authorities(new SimpleGrantedAuthority("review:moderate")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Abusive\"}")).andExpect(status().isOk());
        this.mvc.perform(get("/api/v1/reviews/moderation").with(jwt())).andExpect(status().isForbidden());
        this.mvc.perform(post("/api/v1/reviews/r2/reply").with(jwt()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"Thanks\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_REPLIED"));
        this.mvc.perform(get("/api/v1/reviews/riders/r1").with(jwt())).andExpect(status().isForbidden());
        given(this.reviews.moderation("FLAGGED", null, null, 20)).willReturn(new ReviewPage(List.of(), null));
        this.mvc.perform(get("/api/v1/reviews/moderation")
            .with(jwt().authorities(new SimpleGrantedAuthority("review:moderate"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());
    }
}

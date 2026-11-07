package io.github.ilyaslabs.foodstack.review.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.review.core.application.CustomerReviews;
import io.github.ilyaslabs.foodstack.review.core.application.OrderReviewState;
import io.github.ilyaslabs.foodstack.review.core.application.RatingSummary;
import io.github.ilyaslabs.foodstack.review.core.application.ReviewPage;
import io.github.ilyaslabs.foodstack.review.core.application.ReviewService;
import io.github.ilyaslabs.foodstack.review.core.application.ReviewView;
import io.github.ilyaslabs.foodstack.review.core.application.RiderRatingSummary;
import io.github.ilyaslabs.foodstack.review.core.application.RiderTag;
import io.github.ilyaslabs.foodstack.review.core.application.SubmitReview;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ratings and reviews (PRD 4.9): customers rate finished orders, everyone reads a branch's reviews
 * and rating, the branch replies, moderators hide and show, and the operator sees rider ratings.
 * Who may do what is checked by {@link ReviewService}.
 */
@RestController
@RequestMapping("/api/v1/reviews")
class ReviewsController {

    private final ReviewService reviews;

    ReviewsController(ReviewService reviews) {
        this.reviews = reviews;
    }

    record SubmitRequest(String orderId, Integer storeRating, String text, String photoMediaId, Integer riderRating,
            List<String> riderTags) {
    }

    record TextRequest(String text) {
    }

    record ReasonRequest(String reason) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    OrderReviewState submit(@RequestBody SubmitRequest request) {
        return this.reviews.submit(new SubmitReview(request.orderId(), request.storeRating(), request.text(),
                request.photoMediaId(), request.riderRating(), request.riderTags()));
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("isAuthenticated()")
    OrderReviewState order(@PathVariable String orderId) {
        return this.reviews.orderState(orderId);
    }

    /** The customer's reviews and rider ratings, for their data export (PRD ID-7). */
    @GetMapping("/mine/export")
    @PreAuthorize("isAuthenticated()")
    CustomerReviews export() {
        return this.reviews.export();
    }

    @GetMapping("/rider-tags")
    @PreAuthorize("isAuthenticated()")
    List<RiderTag> riderTags() {
        return List.of(RiderTag.values());
    }

    @GetMapping("/stores/{storeId}")
    ReviewPage store(@PathVariable String storeId, @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "20") int limit) {
        return this.reviews.storeReviews(storeId, before, limit);
    }

    @GetMapping("/stores/{storeId}/summary")
    RatingSummary summary(@PathVariable String storeId) {
        return this.reviews.summary(storeId);
    }

    @PostMapping("/{reviewId}/reply")
    @PreAuthorize("isAuthenticated()")
    ReviewView reply(@PathVariable String reviewId, @RequestBody TextRequest request) {
        return this.reviews.reply(reviewId, request.text());
    }

    @GetMapping("/moderation")
    @PreAuthorize("hasAuthority('review:moderate')")
    ReviewPage moderation(@RequestParam(defaultValue = "FLAGGED") String filter,
            @RequestParam(required = false) String storeId, @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "20") int limit) {
        return this.reviews.moderation(filter, storeId, before, limit);
    }

    @PostMapping("/{reviewId}/hide")
    @PreAuthorize("hasAuthority('review:moderate')")
    ReviewView hide(@PathVariable String reviewId, @RequestBody ReasonRequest request) {
        return this.reviews.hide(reviewId, request.reason());
    }

    @PostMapping("/{reviewId}/show")
    @PreAuthorize("hasAuthority('review:moderate')")
    ReviewView show(@PathVariable String reviewId) {
        return this.reviews.show(reviewId);
    }

    @PostMapping("/{reviewId}/keep")
    @PreAuthorize("hasAuthority('review:moderate')")
    ReviewView keep(@PathVariable String reviewId) {
        return this.reviews.keep(reviewId);
    }

    @GetMapping("/riders/{riderId}")
    @PreAuthorize("hasAuthority('rider:manage')")
    RiderRatingSummary rider(@PathVariable String riderId) {
        return this.reviews.riderSummary(riderId);
    }
}

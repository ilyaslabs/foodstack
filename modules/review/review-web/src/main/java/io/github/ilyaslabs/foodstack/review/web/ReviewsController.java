package io.github.ilyaslabs.foodstack.review.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
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
    @Flow(value = "review-submit", label = "Rate an order", description = "Store stars with optional text and photo, and rider stars with tags")
    @EVL.Endpoint(method = "POST", path = "/api/v1/reviews", caller = "Customer web")
    OrderReviewState submit(@RequestBody SubmitRequest request) {
        return this.reviews.submit(new SubmitReview(request.orderId(), request.storeRating(), request.text(),
                request.photoMediaId(), request.riderRating(), request.riderTags()));
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "review-order-state", label = "Order rating state", description = "What the customer can still rate")
    @EVL.Endpoint(method = "GET", path = "/api/v1/reviews/orders/{orderId}", caller = "Customer web")
    OrderReviewState order(@PathVariable String orderId) {
        return this.reviews.orderState(orderId);
    }

    /** The customer's reviews and rider ratings, for their data export (PRD ID-7). */
    @GetMapping("/mine/export")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "review-export-mine", label = "Export my reviews")
    @EVL.Endpoint(method = "GET", path = "/api/v1/reviews/mine/export", caller = "Customer web")
    CustomerReviews export() {
        return this.reviews.export();
    }

    @GetMapping("/rider-tags")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "review-rider-tags", label = "Rider rating tags")
    @EVL.Endpoint(method = "GET", path = "/api/v1/reviews/rider-tags", caller = "Customer web")
    List<RiderTag> riderTags() {
        return List.of(RiderTag.values());
    }

    @GetMapping("/stores/{storeId}")
    @Flow(value = "review-store-list", label = "Branch reviews", description = "Published reviews, newest first")
    @EVL.Endpoint(method = "GET", path = "/api/v1/reviews/stores/{storeId}", caller = "Customer web")
    ReviewPage store(@PathVariable String storeId, @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "20") int limit) {
        return this.reviews.storeReviews(storeId, before, limit);
    }

    @GetMapping("/stores/{storeId}/summary")
    @Flow(value = "review-store-summary", label = "Branch rating summary", description = "Average and star counts")
    @EVL.Endpoint(method = "GET", path = "/api/v1/reviews/stores/{storeId}/summary", caller = "Customer web")
    RatingSummary summary(@PathVariable String storeId) {
        return this.reviews.summary(storeId);
    }

    @PostMapping("/{reviewId}/reply")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "review-reply", label = "Reply to review", description = "One reply per review, by the branch")
    @EVL.Endpoint(method = "POST", path = "/api/v1/reviews/{reviewId}/reply", caller = "Back office")
    ReviewView reply(@PathVariable String reviewId, @RequestBody TextRequest request) {
        return this.reviews.reply(reviewId, request.text());
    }

    @GetMapping("/moderation")
    @PreAuthorize("hasAuthority('review:moderate')")
    @Flow(value = "review-moderation", label = "Moderation queue", description = "Flagged or hidden reviews")
    @EVL.Endpoint(method = "GET", path = "/api/v1/reviews/moderation", caller = "Back office")
    ReviewPage moderation(@RequestParam(defaultValue = "FLAGGED") String filter,
            @RequestParam(required = false) String storeId, @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "20") int limit) {
        return this.reviews.moderation(filter, storeId, before, limit);
    }

    @PostMapping("/{reviewId}/hide")
    @PreAuthorize("hasAuthority('review:moderate')")
    @Flow(value = "review-hide", label = "Hide review", description = "With a reason; removed from the branch's average")
    @EVL.Endpoint(method = "POST", path = "/api/v1/reviews/{reviewId}/hide", caller = "Back office")
    ReviewView hide(@PathVariable String reviewId, @RequestBody ReasonRequest request) {
        return this.reviews.hide(reviewId, request.reason());
    }

    @PostMapping("/{reviewId}/show")
    @PreAuthorize("hasAuthority('review:moderate')")
    @Flow(value = "review-show", label = "Show hidden review")
    @EVL.Endpoint(method = "POST", path = "/api/v1/reviews/{reviewId}/show", caller = "Back office")
    ReviewView show(@PathVariable String reviewId) {
        return this.reviews.show(reviewId);
    }

    @PostMapping("/{reviewId}/keep")
    @PreAuthorize("hasAuthority('review:moderate')")
    @Flow(value = "review-keep", label = "Keep flagged review", description = "Clears the flag")
    @EVL.Endpoint(method = "POST", path = "/api/v1/reviews/{reviewId}/keep", caller = "Back office")
    ReviewView keep(@PathVariable String reviewId) {
        return this.reviews.keep(reviewId);
    }

    @GetMapping("/riders/{riderId}")
    @PreAuthorize("hasAuthority('rider:manage')")
    @Flow(value = "review-rider-summary", label = "Rider rating summary")
    @EVL.Endpoint(method = "GET", path = "/api/v1/reviews/riders/{riderId}", caller = "Back office")
    RiderRatingSummary rider(@PathVariable String riderId) {
        return this.reviews.riderSummary(riderId);
    }
}

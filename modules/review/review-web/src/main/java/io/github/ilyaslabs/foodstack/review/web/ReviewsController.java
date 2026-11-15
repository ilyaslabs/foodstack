package io.github.ilyaslabs.foodstack.review.web;

import java.util.List;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
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
@EVL.Endpoint(path = "/api/v1/reviews")
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

    @Flow(value = "review-submit", label = "Rate an order", description = "Stars for the branch (with text and photo) and the rider, once, within the window")
    @EVL.Endpoint(method = "POST", path = "", caller = "Customer app")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    OrderReviewState submit(@RequestBody SubmitRequest request) {
        return this.reviews.submit(new SubmitReview(request.orderId(), request.storeRating(), request.text(),
                request.photoMediaId(), request.riderRating(), request.riderTags()));
    }

    @Flow(value = "review-order-state", label = "Rating state of an order", description = "What the customer can still rate")
    @EVL.Endpoint(method = "GET", path = "/orders/{orderId}", caller = "Customer app")
    @GetMapping("/orders/{orderId}")
    @PreAuthorize("isAuthenticated()")
    OrderReviewState order(@PathVariable String orderId) {
        return this.reviews.orderState(orderId);
    }

    /** The customer's reviews and rider ratings, for their data export (PRD ID-7). */
    @Flow(value = "review-export", label = "Export my reviews", description = "Personal data export")
    @EVL.Endpoint(method = "GET", path = "/mine/export", caller = "Customer app")
    @GetMapping("/mine/export")
    @PreAuthorize("isAuthenticated()")
    CustomerReviews export() {
        return this.reviews.export();
    }

    @Flow(value = "review-rider-tags", label = "List rider tags", description = "Fixed list")
    @EVL.Endpoint(method = "GET", path = "/rider-tags", caller = "Customer app")
    @GetMapping("/rider-tags")
    @PreAuthorize("isAuthenticated()")
    List<RiderTag> riderTags() {
        return List.of(RiderTag.values());
    }

    @Flow(value = "review-store-list", label = "Branch reviews", description = "Published reviews, newest first, paged")
    @EVL.Endpoint(method = "GET", path = "/stores/{storeId}", caller = "Customer app")
    @GetMapping("/stores/{storeId}")
    ReviewPage store(@PathVariable String storeId, @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "20") int limit) {
        return this.reviews.storeReviews(storeId, before, limit);
    }

    @Flow(value = "review-store-summary", label = "Branch rating summary", description = "Average, stars histogram, brand average")
    @EVL.Endpoint(method = "GET", path = "/stores/{storeId}/summary", caller = "Customer app")
    @GetMapping("/stores/{storeId}/summary")
    RatingSummary summary(@PathVariable String storeId) {
        return this.reviews.summary(storeId);
    }

    @Flow(value = "review-reply", label = "Reply to a review", description = "The branch's one public reply")
    @EVL.Endpoint(method = "POST", path = "/{reviewId}/reply", caller = "Back office (store)")
    @PostMapping("/{reviewId}/reply")
    @PreAuthorize("isAuthenticated()")
    ReviewView reply(@PathVariable String reviewId, @RequestBody TextRequest request) {
        return this.reviews.reply(reviewId, request.text());
    }

    @Flow(value = "review-moderation", label = "Moderation queue", description = "Flagged, hidden or all reviews")
    @EVL.Endpoint(method = "GET", path = "/moderation", caller = "Back office (moderator)")
    @GetMapping("/moderation")
    @PreAuthorize("hasAuthority('review:moderate')")
    ReviewPage moderation(@RequestParam(defaultValue = "FLAGGED") String filter,
            @RequestParam(required = false) String storeId, @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "20") int limit) {
        return this.reviews.moderation(filter, storeId, before, limit);
    }

    @Flow(value = "review-hide", label = "Hide a review", description = "With a reason; leaves the rating")
    @EVL.Endpoint(method = "POST", path = "/{reviewId}/hide", caller = "Back office (moderator)")
    @PostMapping("/{reviewId}/hide")
    @PreAuthorize("hasAuthority('review:moderate')")
    ReviewView hide(@PathVariable String reviewId, @RequestBody ReasonRequest request) {
        return this.reviews.hide(reviewId, request.reason());
    }

    @Flow(value = "review-show", label = "Show a hidden review", description = "Counts in the rating again")
    @EVL.Endpoint(method = "POST", path = "/{reviewId}/show", caller = "Back office (moderator)")
    @PostMapping("/{reviewId}/show")
    @PreAuthorize("hasAuthority('review:moderate')")
    ReviewView show(@PathVariable String reviewId) {
        return this.reviews.show(reviewId);
    }

    @Flow(value = "review-keep", label = "Keep a flagged review", description = "Clears the flag")
    @EVL.Endpoint(method = "POST", path = "/{reviewId}/keep", caller = "Back office (moderator)")
    @PostMapping("/{reviewId}/keep")
    @PreAuthorize("hasAuthority('review:moderate')")
    ReviewView keep(@PathVariable String reviewId) {
        return this.reviews.keep(reviewId);
    }

    @Flow(value = "review-rider-summary", label = "Rider rating summary", description = "Average and tags; operator only")
    @EVL.Endpoint(method = "GET", path = "/riders/{riderId}", caller = "Back office (operator)")
    @GetMapping("/riders/{riderId}")
    @PreAuthorize("hasAuthority('rider:manage')")
    RiderRatingSummary rider(@PathVariable String riderId) {
        return this.reviews.riderSummary(riderId);
    }
}

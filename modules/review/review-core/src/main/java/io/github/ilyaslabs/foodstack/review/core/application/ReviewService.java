package io.github.ilyaslabs.foodstack.review.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.media.api.MediaAssetView;
import io.github.ilyaslabs.foodstack.media.api.MediaOwner;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaVariantView;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.review.api.StoreRating;
import io.github.ilyaslabs.foodstack.review.api.events.ReviewHidden;
import io.github.ilyaslabs.foodstack.review.api.events.ReviewPublished;
import io.github.ilyaslabs.foodstack.review.core.domain.BlockedWords;
import io.github.ilyaslabs.foodstack.review.core.persistence.RateableOrderDocument;
import io.github.ilyaslabs.foodstack.review.core.persistence.ReviewDocument;
import io.github.ilyaslabs.foodstack.review.core.persistence.RiderRatingDocument;
import io.github.ilyaslabs.foodstack.review.core.persistence.RiderStatsDocument;
import io.github.ilyaslabs.foodstack.review.core.persistence.StoreStatsDocument;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ratings and reviews (PRD 4.9): customers rate the branch and the rider of a finished order once,
 * within the review window; the branch replies once; moderators hide and show reviews with a
 * reason. A branch's rating is kept in {@code review_store_stats} with {@code $inc} in the same
 * transaction as the review and announced through the outbox, so search sees every change.
 */
public class ReviewService {

    public static final int MAX_TEXT = 1000;

    public static final int MAX_REASON = 500;

    public static final int MAX_PAGE = 50;

    /** Moderation queue: hide and show reviews (PRD RV-3). */
    public static final String MODERATE = "review:moderate";

    /** Rider ratings are the operator's (PRD RV-4). */
    public static final String RIDER_MANAGE = "rider:manage";

    /** Operator staff may act for any branch. */
    public static final String STORE_MANAGE = "store:manage";

    private static final String MODULE = "review";

    private final MongoTemplate mongo;

    private final TransactionTemplate transactions;

    private final OutboxWriter outbox;

    private final IdentityApi identity;

    private final MediaApi media;

    private final ServiceCalls serviceCalls;

    private final BlockedWords blockedWords;

    private final Duration window;

    private final Clock clock;

    public ReviewService(MongoTemplate mongo, TransactionTemplate transactions, OutboxWriter outbox, IdentityApi identity,
            MediaApi media, ServiceCalls serviceCalls, BlockedWords blockedWords, Duration window, Clock clock) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.outbox = outbox;
        this.identity = identity;
        this.media = media;
        this.serviceCalls = serviceCalls;
        this.blockedWords = blockedWords;
        this.window = window;
        this.clock = clock;
    }

    // ---- the customer -------------------------------------------------------------------------

    /** What the signed-in customer can still rate on an order. */
    public OrderReviewState orderState(String orderId) {
        RateableOrderDocument order = ownOrder(orderId);
        if (order == null) {
            return new OrderReviewState(orderId, false, false, null, null, null, null);
        }
        Instant now = this.clock.instant();
        boolean open = order.completedAt() != null && now.isBefore(order.completedAt().plus(this.window));
        ReviewView review = null;
        if (order.storeReviewId() != null) {
            ReviewDocument document = this.mongo.findOne(Query.query(Criteria.where("storeId").is(order.storeId())
                .and("_id").is(order.storeReviewId())), ReviewDocument.class);
            review = (document != null) ? view(document, Audience.CUSTOMER) : null;
        }
        Integer riderRating = null;
        if (order.isRiderRated()) {
            RiderRatingDocument rating = this.mongo.findOne(Query.query(Criteria.where("riderId").is(order.riderId())
                .and("_id").is(order.id())), RiderRatingDocument.class);
            riderRating = (rating != null) ? rating.rating() : null;
        }
        return new OrderReviewState(orderId, open && order.storeReviewId() == null,
                open && order.riderId() != null && !order.isRiderRated(), order.riderName(),
                (order.completedAt() != null) ? order.completedAt().plus(this.window).getEpochSecond() : null, review,
                riderRating);
    }

    /** Rates a finished order of the signed-in customer: the branch, the rider, or both. */
    public OrderReviewState submit(SubmitReview request) {
        String userId = CurrentUser.id();
        if (request.storeRating() == null && request.riderRating() == null) {
            throw new ModuleException.BadRequest("Rate the store or the rider", "REVIEW_EMPTY");
        }
        RateableOrderDocument order = ownOrder(request.orderId());
        if (order == null || order.completedAt() == null) {
            throw new ModuleException.Conflict("You can rate an order once it is delivered or collected",
                    "REVIEW_NOT_YET");
        }
        Instant now = this.clock.instant();
        if (!now.isBefore(order.completedAt().plus(this.window))) {
            throw new ModuleException.Conflict("Ratings for this order have closed", "REVIEW_WINDOW_CLOSED");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.storeRating() != null) {
            stars(request.storeRating(), "storeRating", errors);
            if (order.storeReviewId() != null) {
                throw new ModuleException.Conflict("You already rated this order", "REVIEW_ALREADY_RATED");
            }
        }
        else if (hasText(request.text()) || request.photoMediaId() != null) {
            errors.put("storeRating", "Give the store a rating with your review");
        }
        if (hasText(request.text()) && request.text().strip().length() > MAX_TEXT) {
            errors.put("text", "Up to " + MAX_TEXT + " characters");
        }
        List<String> tags = List.of();
        if (request.riderRating() != null) {
            stars(request.riderRating(), "riderRating", errors);
            if (order.riderId() == null) {
                errors.put("riderRating", "No rider delivered this order");
            }
            else if (order.isRiderRated()) {
                throw new ModuleException.Conflict("You already rated the rider", "REVIEW_ALREADY_RATED");
            }
            tags = tags(request.riderTags(), errors);
        }
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("The rating is not valid", "REVIEW_INVALID", Map.of("errors", errors));
        }

        ReviewDocument review = null;
        if (request.storeRating() != null) {
            String reviewId = Ids.newId();
            BlockedWords.Result text = this.blockedWords.mask(hasText(request.text()) ? request.text().strip() : null);
            ReviewDocument.Photo photo = (request.photoMediaId() != null) ? claim(request.photoMediaId(), reviewId) : null;
            review = new ReviewDocument(reviewId, order.id(), order.number(), order.storeId(), order.brandId(),
                    order.storeName(), userId, publicName(userId), request.storeRating(), text.text(),
                    text.flagged() ? request.text().strip() : null, photo, order.language(), ReviewDocument.PUBLISHED,
                    text.flagged(), null, null, null, null, now);
        }
        ReviewDocument newReview = review;
        List<String> riderTags = tags;
        this.transactions.executeWithoutResult((status) -> {
            if (newReview != null) {
                RateableOrderDocument before = this.mongo.findAndModify(
                        Query.query(Criteria.where("_id").is(order.id()).and("storeReviewId").is(null)),
                        new Update().set("storeReviewId", newReview.id()), RateableOrderDocument.class);
                if (before == null) {
                    throw new ModuleException.Conflict("You already rated this order", "REVIEW_ALREADY_RATED");
                }
                this.mongo.insert(newReview);
                StoreStatsDocument stats = count(newReview, 1);
                this.outbox.append(new ReviewPublished(newReview.id(), newReview.orderId(), newReview.storeId(),
                        newReview.brandId(), newReview.storeName(), owners(order), newReview.rating(), newReview.flagged(),
                        stats.count(), average(stats.count(), stats.sum())));
            }
            if (request.riderRating() != null) {
                RateableOrderDocument before = this.mongo.findAndModify(
                        Query.query(Criteria.where("_id").is(order.id()).and("riderRated").ne(true)),
                        new Update().set("riderRated", true), RateableOrderDocument.class);
                if (before == null) {
                    throw new ModuleException.Conflict("You already rated the rider", "REVIEW_ALREADY_RATED");
                }
                this.mongo.insert(new RiderRatingDocument(order.id(), order.riderId(), order.riderKind(), order.storeId(),
                        userId, request.riderRating(), riderTags, now));
                Update update = new Update().inc("count", 1).inc("sum", request.riderRating());
                riderTags.forEach((tag) -> update.inc("tags." + tag, 1));
                this.mongo.upsert(Query.query(Criteria.where("_id").is(order.riderId())), update, RiderStatsDocument.class);
            }
        });
        return orderState(order.id());
    }

    // ---- the branch ---------------------------------------------------------------------------

    /** The branch's one public reply (PRD RV-2). */
    public ReviewView reply(String reviewId, String text) {
        ReviewDocument review = find(reviewId);
        if (!CurrentUser.managesStore(review.storeId()) && !CurrentUser.hasAuthority(STORE_MANAGE)) {
            throw new ModuleException.Forbidden("Only the branch can reply", "REVIEW_ACCESS_DENIED");
        }
        String clean = (text == null) ? "" : text.strip();
        if (clean.isEmpty() || clean.length() > MAX_TEXT) {
            throw new ModuleException.BadRequest("Write a reply of up to " + MAX_TEXT + " characters", "REVIEW_INVALID",
                    Map.of("errors", Map.of("text", "Up to " + MAX_TEXT + " characters")));
        }
        ReviewDocument.Reply reply = new ReviewDocument.Reply(this.blockedWords.mask(clean).text(), CurrentUser.id(),
                this.clock.instant());
        ReviewDocument updated = this.mongo.findAndModify(
                Query.query(Criteria.where("storeId").is(review.storeId()).and("_id").is(reviewId).and("reply").is(null)),
                new Update().set("reply", reply), FindAndModifyOptions.options().returnNew(true), ReviewDocument.class);
        if (updated == null) {
            throw new ModuleException.Conflict("This review already has a reply", "REVIEW_ALREADY_REPLIED");
        }
        return view(updated, audience(updated.storeId()));
    }

    // ---- moderation ---------------------------------------------------------------------------

    /** Hides a review with a reason; it stops counting in the rating (PRD RV-3). */
    public ReviewView hide(String reviewId, String reason) {
        requireModerator();
        String clean = (reason == null) ? "" : reason.strip();
        if (clean.isEmpty() || clean.length() > MAX_REASON) {
            throw new ModuleException.BadRequest("Give a reason of up to " + MAX_REASON + " characters", "REVIEW_INVALID",
                    Map.of("errors", Map.of("reason", "Required, up to " + MAX_REASON + " characters")));
        }
        ReviewDocument review = find(reviewId);
        String actor = CurrentUser.id();
        ReviewDocument hidden = this.transactions.execute((status) -> {
            ReviewDocument updated = this.mongo.findAndModify(
                    Query.query(Criteria.where("storeId").is(review.storeId()).and("_id").is(reviewId)
                        .and("status").is(ReviewDocument.PUBLISHED)),
                    new Update().set("status", ReviewDocument.HIDDEN).set("flagged", false).set("hiddenReason", clean)
                        .set("hiddenBy", actor).set("hiddenAt", this.clock.instant()),
                    FindAndModifyOptions.options().returnNew(true), ReviewDocument.class);
            if (updated == null) {
                throw new ModuleException.Conflict("This review is already hidden", "REVIEW_ALREADY_HIDDEN");
            }
            StoreStatsDocument stats = count(updated, -1);
            this.outbox.append(new ReviewHidden(reviewId, updated.storeId(), updated.brandId(), clean, actor, stats.count(),
                    average(stats.count(), stats.sum())));
            return updated;
        });
        return view(hidden, Audience.MODERATOR);
    }

    /** Shows a hidden review again; it counts in the rating again. */
    public ReviewView show(String reviewId) {
        requireModerator();
        ReviewDocument review = find(reviewId);
        ReviewDocument shown = this.transactions.execute((status) -> {
            ReviewDocument updated = this.mongo.findAndModify(
                    Query.query(Criteria.where("storeId").is(review.storeId()).and("_id").is(reviewId)
                        .and("status").is(ReviewDocument.HIDDEN)),
                    new Update().set("status", ReviewDocument.PUBLISHED).set("flagged", false).unset("hiddenReason")
                        .unset("hiddenBy").unset("hiddenAt"),
                    FindAndModifyOptions.options().returnNew(true), ReviewDocument.class);
            if (updated == null) {
                throw new ModuleException.Conflict("This review is already shown", "REVIEW_ALREADY_SHOWN");
            }
            StoreStatsDocument stats = count(updated, 1);
            RateableOrderDocument order = this.mongo.findById(updated.orderId(), RateableOrderDocument.class);
            this.outbox.append(new ReviewPublished(reviewId, updated.orderId(), updated.storeId(), updated.brandId(),
                    updated.storeName(), (order != null) ? owners(order) : Set.of(), updated.rating(), false, stats.count(),
                    average(stats.count(), stats.sum())));
            return updated;
        });
        return view(shown, Audience.MODERATOR);
    }

    /** Takes a flagged review out of the queue and leaves it shown. */
    public ReviewView keep(String reviewId) {
        requireModerator();
        ReviewDocument review = find(reviewId);
        ReviewDocument updated = this.mongo.findAndModify(
                Query.query(Criteria.where("storeId").is(review.storeId()).and("_id").is(reviewId)),
                new Update().set("flagged", false), FindAndModifyOptions.options().returnNew(true), ReviewDocument.class);
        return view((updated != null) ? updated : review, Audience.MODERATOR);
    }

    /**
     * Reviews for moderators, newest first.
     *
     * @param filter {@code FLAGGED} (the queue), {@code HIDDEN} or {@code ALL}
     * @param storeId one branch, or {@code null}
     * @param before the {@code next} of the previous page
     */
    public ReviewPage moderation(String filter, String storeId, String before, int limit) {
        requireModerator();
        Criteria criteria = new Criteria();
        String which = (filter == null) ? "FLAGGED" : filter.toUpperCase(Locale.ROOT);
        switch (which) {
            case "FLAGGED" -> criteria.and("flagged").is(true);
            case "HIDDEN" -> criteria.and("status").is(ReviewDocument.HIDDEN);
            case "ALL" -> {
            }
            default -> throw new ModuleException.BadRequest("Unknown filter " + filter, "REVIEW_INVALID");
        }
        if (storeId != null && !storeId.isBlank()) {
            criteria.and("storeId").is(storeId);
        }
        return page(criteria, before, limit, Audience.MODERATOR);
    }

    // ---- reading ------------------------------------------------------------------------------

    /**
     * A branch's reviews, newest first. Everyone sees the published ones; the branch and
     * moderators also see hidden ones.
     */
    public ReviewPage storeReviews(String storeId, String before, int limit) {
        Audience audience = audience(storeId);
        Criteria criteria = Criteria.where("storeId").is(storeId);
        if (audience == Audience.CUSTOMER) {
            criteria.and("status").is(ReviewDocument.PUBLISHED);
        }
        return page(criteria, before, limit, audience);
    }

    /** A branch's rating with its brand's (PRD RV-4, BB-10). Public. */
    public RatingSummary summary(String storeId) {
        StoreStatsDocument stats = this.mongo.findById(storeId, StoreStatsDocument.class);
        if (stats == null) {
            return new RatingSummary(storeId, 0, 0, List.of(0L, 0L, 0L, 0L, 0L), null, 0, 0);
        }
        List<Long> stars = new ArrayList<>();
        for (int star = 1; star <= 5; star++) {
            Long value = (stats.stars() != null) ? stats.stars().get(String.valueOf(star)) : null;
            stars.add((value != null) ? Math.max(0, value) : 0L);
        }
        long brandCount = stats.count();
        long brandSum = stats.sum();
        if (stats.brandId() != null) {
            BrandTotals totals = this.mongo.aggregate(Aggregation.newAggregation(
                    Aggregation.match(Criteria.where("brandId").is(stats.brandId())),
                    Aggregation.group("brandId").sum("count").as("count").sum("sum").as("sum")), StoreStatsDocument.class,
                    BrandTotals.class).getUniqueMappedResult();
            if (totals != null) {
                brandCount = totals.count();
                brandSum = totals.sum();
            }
        }
        return new RatingSummary(storeId, stats.count(), average(stats.count(), stats.sum()), stars, stats.brandId(),
                brandCount, average(brandCount, brandSum));
    }

    /** For search: a branch's published rating. */
    public StoreRating storeRating(String storeId) {
        StoreStatsDocument stats = this.mongo.findById(storeId, StoreStatsDocument.class);
        return (stats == null) ? StoreRating.none(storeId)
                : new StoreRating(storeId, stats.count(), average(stats.count(), stats.sum()));
    }

    /** A rider's private rating, for the operator (PRD RV-4). */
    public RiderRatingSummary riderSummary(String riderId) {
        if (!CurrentUser.hasAuthority(RIDER_MANAGE)) {
            throw new ModuleException.Forbidden("Rider ratings are for the operator", "REVIEW_ACCESS_DENIED");
        }
        RiderStatsDocument stats = this.mongo.findById(riderId, RiderStatsDocument.class);
        if (stats == null) {
            return new RiderRatingSummary(riderId, 0, 0, Map.of());
        }
        return new RiderRatingSummary(riderId, stats.count(), average(stats.count(), stats.sum()),
                (stats.tags() != null) ? stats.tags() : Map.of());
    }

    // ---- events -------------------------------------------------------------------------------

    /** The order was delivered or collected: the customer may rate it now. */
    public void orderFinished(String orderId, String number, String customerId, String storeId, String brandId,
            String storeName, Set<String> storeOwnerIds, String language, Instant at) {
        this.mongo.upsert(Query.query(Criteria.where("_id").is(orderId)),
                new Update().set("number", number).set("customerId", customerId).set("storeId", storeId)
                    .set("brandId", brandId).set("storeName", storeName).set("storeOwnerIds", storeOwnerIds)
                    .set("language", language).set("completedAt", at),
                RateableOrderDocument.class);
    }

    /** Who delivered the order, so the customer can rate the rider. */
    public void delivered(String orderId, String riderId, String riderName, String riderKind) {
        if (riderId == null) {
            return;
        }
        this.mongo.upsert(Query.query(Criteria.where("_id").is(orderId)),
                new Update().set("riderId", riderId).set("riderName", riderName).set("riderKind", riderKind),
                RateableOrderDocument.class);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private enum Audience {
        CUSTOMER, STORE, MODERATOR
    }

    private record BrandTotals(String id, long count, long sum) {
    }

    private Audience audience(String storeId) {
        if (CurrentUser.hasAuthority(MODERATE)) {
            return Audience.MODERATOR;
        }
        if (signedIn() && (CurrentUser.managesStore(storeId) || CurrentUser.hasAuthority(STORE_MANAGE))) {
            return Audience.STORE;
        }
        return Audience.CUSTOMER;
    }

    /** The reviews and rating of a branch are public: read them without a sign-in too. */
    private static boolean signedIn() {
        try {
            return CurrentUser.id() != null;
        }
        catch (ModuleException.Unauthorized ex) {
            return false;
        }
    }

    private void requireModerator() {
        if (!CurrentUser.hasAuthority(MODERATE)) {
            throw new ModuleException.Forbidden("Only moderators can do this", "REVIEW_ACCESS_DENIED");
        }
    }

    private RateableOrderDocument ownOrder(String orderId) {
        if (!Ids.isValid(orderId)) {
            return null;
        }
        RateableOrderDocument order = this.mongo.findById(orderId, RateableOrderDocument.class);
        String userId = CurrentUser.id();
        return (order != null && userId != null && userId.equals(order.customerId())) ? order : null;
    }

    private ReviewDocument find(String reviewId) {
        ReviewDocument review = Ids.isValid(reviewId) ? this.mongo.findById(reviewId, ReviewDocument.class) : null;
        if (review == null) {
            throw new ModuleException.NotFound("No review " + reviewId, "REVIEW_NOT_FOUND");
        }
        return review;
    }

    private ReviewPage page(Criteria criteria, String before, int limit, Audience audience) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        if (before != null && !before.isBlank()) {
            criteria.and("_id").lt(before);
        }
        List<ReviewDocument> found = this.mongo.find(
                Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "_id")).limit(size + 1), ReviewDocument.class);
        boolean more = found.size() > size;
        List<ReviewView> items = found.stream().limit(size).map((review) -> view(review, audience)).toList();
        return new ReviewPage(items, more ? items.get(items.size() - 1).id() : null);
    }

    private StoreStatsDocument count(ReviewDocument review, int direction) {
        Update update = new Update().inc("count", direction)
            .inc("sum", direction * review.rating())
            .inc("stars." + review.rating(), direction)
            .setOnInsert("brandId", review.brandId());
        return this.mongo.findAndModify(Query.query(Criteria.where("_id").is(review.storeId())), update,
                FindAndModifyOptions.options().upsert(true).returnNew(true), StoreStatsDocument.class);
    }

    private ReviewDocument.Photo claim(String mediaId, String reviewId) {
        // customers may not claim media themselves; the review module does it for them
        MediaAssetView asset = this.serviceCalls
            .call(() -> this.media.claim(mediaId, MediaPurpose.REVIEW, new MediaOwner(MODULE, reviewId)));
        MediaVariantView thumb = asset.variants().get("thumb");
        MediaVariantView detail = asset.variants().getOrDefault("detail", asset.variants().get("card"));
        return new ReviewDocument.Photo(mediaId, (thumb != null) ? thumb.url() : null,
                (detail != null) ? detail.url() : (thumb != null) ? thumb.url() : null);
    }

    private String publicName(String userId) {
        UserView user = this.identity.getUser(userId);
        return shortName((user != null) ? user.displayName() : null);
    }

    /** {@code Ayesha Khan} becomes {@code Ayesha K.}; no name becomes {@code Customer}. */
    static String shortName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "Customer";
        }
        String[] parts = displayName.strip().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        String last = parts[parts.length - 1];
        return parts[0] + " " + new String(Character.toChars(last.codePointAt(0))).toUpperCase(Locale.ROOT) + ".";
    }

    static double average(long count, long sum) {
        return (count <= 0) ? 0 : Math.round(sum * 10.0 / count) / 10.0;
    }

    private static Set<String> owners(RateableOrderDocument order) {
        return (order.storeOwnerIds() != null) ? order.storeOwnerIds() : Set.of();
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static void stars(int value, String field, Map<String, String> errors) {
        if (value < 1 || value > 5) {
            errors.put(field, "From 1 to 5 stars");
        }
    }

    private static List<String> tags(List<String> requested, Map<String, String> errors) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Set<RiderTag> tags = EnumSet.noneOf(RiderTag.class);
        for (String tag : new LinkedHashSet<>(requested)) {
            try {
                tags.add(RiderTag.valueOf(tag));
            }
            catch (IllegalArgumentException | NullPointerException ex) {
                errors.put("riderTags", "Unknown tag " + tag);
                return List.of();
            }
        }
        return tags.stream().map(Enum::name).toList();
    }

    private static long epoch(Instant at) {
        return (at != null) ? at.getEpochSecond() : 0;
    }

    private static ReviewView view(ReviewDocument review, Audience audience) {
        boolean store = audience != Audience.CUSTOMER;
        boolean moderator = audience == Audience.MODERATOR;
        ReviewView.ReplyView reply = (review.reply() != null)
                ? new ReviewView.ReplyView(review.reply().text(), epoch(review.reply().at())) : null;
        return new ReviewView(review.id(), review.storeId(), review.storeName(), review.rating(), review.text(),
                (review.photo() != null) ? review.photo().thumbUrl() : null,
                (review.photo() != null) ? review.photo().url() : null, review.customerName(), epoch(review.createdAt()),
                reply, review.status(), store ? review.orderNumber() : null, moderator && review.flagged(),
                moderator ? review.originalText() : null, store ? review.hiddenReason() : null);
    }
}

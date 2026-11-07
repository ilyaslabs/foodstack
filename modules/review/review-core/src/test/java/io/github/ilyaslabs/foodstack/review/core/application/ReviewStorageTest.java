package io.github.ilyaslabs.foodstack.review.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.media.api.MediaAssetView;
import io.github.ilyaslabs.foodstack.media.api.MediaOwner;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaStatus;
import io.github.ilyaslabs.foodstack.media.api.MediaVariantView;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.FoodstackJwtAuthenticationConverter;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.foodstack.review.api.StoreRating;
import io.github.ilyaslabs.foodstack.review.core.ReviewConfiguration;
import io.github.ilyaslabs.foodstack.review.core.domain.BlockedWords;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Rating, replying and moderating against a real two-shard MongoDB (TDD 15.3), with identity, media
 * and service calls mocked. Each test uses its own orders and branches.
 */
@SpringBootTest(classes = ReviewStorageTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class ReviewStorageTest {

    private static final Instant START = Instant.parse("2026-11-07T10:00:00Z");

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        ModuleMongo reviewMongo(ModuleMongoFactory factory) {
            return factory.create("review");
        }

        @Bean
        ModuleMigrations reviewMigrations(ModuleMongo reviewMongo, ModuleMongoFactory factory,
                FoodstackMongoProperties properties) {
            return new ModuleMigrations(reviewMongo, factory.connectionString("review"), ReviewConfiguration.shardKeys(),
                    properties);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ModuleMongo reviewMongo;

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    private final IdentityApi identity = mock(IdentityApi.class);

    private final MediaApi media = mock(MediaApi.class);

    private final ServiceCalls serviceCalls = mock(ServiceCalls.class);

    private ReviewService reviews;

    private OutboxWriter outbox;

    private String store;

    private String brand;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return ReviewStorageTest.this.now.get();
            }
        };
        this.outbox = new OutboxWriter(this.reviewMongo, JsonMapper.builder().build(), clock);
        this.reviews = new ReviewService(this.reviewMongo.template(), this.reviewMongo.transactionTemplate(), this.outbox,
                this.identity, this.media, this.serviceCalls, BlockedWords.of(List.of("awful")), Duration.ofDays(14),
                clock);
        given(this.serviceCalls.call(any())).willAnswer((call) -> ((Supplier<Object>) call.getArgument(0)).get());
        this.store = Ids.newId();
        this.brand = Ids.newId();
        this.now.set(START);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a customer rates a finished order once, store and rider, and the rating and outbox follow")
    void rate() {
        String order = finished("cust-1", "rider-1");
        signIn("cust-1");
        given(this.identity.getUser("cust-1")).willReturn(user("cust-1", "Ayesha Khan"));
        String photo = Ids.newId();
        given(this.media.claim(any(), any(), any())).willAnswer((call) -> new MediaAssetView(photo, MediaPurpose.REVIEW,
                MediaStatus.READY, Map.of("thumb", new MediaVariantView("https://cdn/t.webp", 160, 160, 10),
                        "detail", new MediaVariantView("https://cdn/d.webp", 1200, 900, 100)),
                call.getArgument(2, MediaOwner.class), 1));

        OrderReviewState before = this.reviews.orderState(order);
        assertThat(before.canRateStore()).isTrue();
        assertThat(before.canRateRider()).isTrue();
        assertThat(before.riderName()).isEqualTo("Bilal");

        OrderReviewState after = this.reviews.submit(new SubmitReview(order, 4, "  Hot and fresh, awful packaging ",
                photo, 5, List.of("FRIENDLY", "ON_TIME", "FRIENDLY")));

        assertThat(after.canRateStore()).isFalse();
        assertThat(after.canRateRider()).isFalse();
        assertThat(after.riderRating()).isEqualTo(5);
        ReviewView review = after.storeReview();
        assertThat(review.text()).isEqualTo("Hot and fresh, ***** packaging");
        assertThat(review.customerName()).isEqualTo("Ayesha K.");
        assertThat(review.photoUrl()).isEqualTo("https://cdn/d.webp");
        assertThat(review.originalText()).isNull();
        assertThat(review.orderNumber()).isNull();
        assertThat(this.reviews.storeRating(this.store)).isEqualTo(new StoreRating(this.store, 1, 4.0));

        assertThatThrownBy(() -> this.reviews.submit(new SubmitReview(order, 5, null, null, null, null)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("REVIEW_ALREADY_RATED"));
        assertThatThrownBy(() -> this.reviews.submit(new SubmitReview(order, null, null, null, 3, null)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("REVIEW_ALREADY_RATED"));

        Document published = events().stream()
            .filter((e) -> e.getString("type").endsWith("ReviewPublished.v1") && this.store.equals(e.getString("subject")))
            .findFirst()
            .orElseThrow();
        assertThat(published.getString("data")).contains("\"flagged\":true", "\"count\":1", "\"average\":4.0");

        signIn("ops", null, ReviewService.RIDER_MANAGE);
        RiderRatingSummary rider = this.reviews.riderSummary("rider-1");
        assertThat(rider.count()).isEqualTo(1);
        assertThat(rider.average()).isEqualTo(5.0);
        assertThat(rider.tags()).containsEntry("FRIENDLY", 1L).containsEntry("ON_TIME", 1L);
    }

    @Test
    @DisplayName("refuses other customers, unfinished orders, closed windows and bad input")
    void refusals() {
        String order = finished("cust-2", null);
        signIn("someone-else");
        assertThat(this.reviews.orderState(order).canRateStore()).isFalse();
        assertThatThrownBy(() -> this.reviews.submit(new SubmitReview(order, 5, null, null, null, null)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("REVIEW_NOT_YET"));

        signIn("cust-2");
        assertThatThrownBy(() -> this.reviews.submit(new SubmitReview(order, null, null, null, null, null)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("REVIEW_EMPTY"));
        assertThatThrownBy(() -> this.reviews.submit(new SubmitReview(order, 6, null, null, 4, List.of("SLOW"))))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> {
                assertThat(ex.code()).isEqualTo("REVIEW_INVALID");
                assertThat(ex.details().get("errors").toString()).contains("storeRating", "riderRating", "riderTags");
            });

        this.now.set(START.plus(Duration.ofDays(14)));
        assertThat(this.reviews.orderState(order).canRateStore()).isFalse();
        assertThatThrownBy(() -> this.reviews.submit(new SubmitReview(order, 5, null, null, null, null)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("REVIEW_WINDOW_CLOSED"));
    }

    @Test
    @DisplayName("the branch replies once; moderators hide and show, and the rating follows")
    void replyAndModerate() {
        String first = review("cust-3", 5, "Best karahi in town");
        String second = review("cust-4", 2, "awful wait");

        assertThat(this.reviews.storeRating(this.store)).isEqualTo(new StoreRating(this.store, 2, 3.5));

        signIn("owner-1", this.store, "store:portal");
        ReviewView replied = this.reviews.reply(first, " Thank you! ");
        assertThat(replied.reply().text()).isEqualTo("Thank you!");
        assertThat(replied.orderNumber()).isNotNull();
        assertThatThrownBy(() -> this.reviews.reply(first, "Again"))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("REVIEW_ALREADY_REPLIED"));
        assertThatThrownBy(() -> this.reviews.hide(second, "rude"))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("REVIEW_ACCESS_DENIED"));
        signIn("owner-2", Ids.newId(), "store:portal");
        assertThatThrownBy(() -> this.reviews.reply(second, "Sorry"))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("REVIEW_ACCESS_DENIED"));

        signIn("mod-1", null, ReviewService.MODERATE);
        ReviewPage queue = this.reviews.moderation("FLAGGED", this.store, null, 20);
        assertThat(queue.items()).extracting(ReviewView::id).containsExactly(second);
        assertThat(queue.items().getFirst().originalText()).isEqualTo("awful wait");

        ReviewView hidden = this.reviews.hide(second, "Abusive language");
        assertThat(hidden.status()).isEqualTo("HIDDEN");
        assertThat(this.reviews.storeRating(this.store)).isEqualTo(new StoreRating(this.store, 1, 5.0));
        assertThat(this.reviews.moderation("FLAGGED", this.store, null, 20).items()).isEmpty();
        assertThat(this.reviews.moderation("HIDDEN", this.store, null, 20).items()).extracting(ReviewView::id)
            .containsExactly(second);
        assertThatThrownBy(() -> this.reviews.hide(second, "again"))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("REVIEW_ALREADY_HIDDEN"));

        SecurityContextHolder.clearContext();
        assertThat(this.reviews.storeReviews(this.store, null, 20).items()).extracting(ReviewView::id).containsExactly(first);
        signIn("owner-1", this.store, "store:portal");
        ReviewPage forBranch = this.reviews.storeReviews(this.store, null, 20);
        assertThat(forBranch.items()).extracting(ReviewView::id).containsExactly(second, first);
        assertThat(forBranch.items().getFirst().hiddenReason()).isEqualTo("Abusive language");

        signIn("mod-1", null, ReviewService.MODERATE);
        this.reviews.show(second);
        assertThat(this.reviews.storeRating(this.store)).isEqualTo(new StoreRating(this.store, 2, 3.5));
        assertThat(events()).filteredOn((e) -> this.store.equals(e.getString("subject")))
            .extracting((e) -> e.getString("type"))
            .anyMatch((t) -> t.endsWith("ReviewHidden.v1"));
    }

    @Test
    @DisplayName("the summary counts stars per branch and adds up every branch of the brand; pages run newest first")
    void summaryAndPages() {
        String otherStore = Ids.newId();
        review("cust-5", 5, null);
        review("cust-6", 4, null);
        review("cust-7", 1, null);
        String saved = this.store;
        this.store = otherStore;
        review("cust-8", 2, null);
        this.store = saved;

        RatingSummary summary = this.reviews.summary(this.store);
        assertThat(summary.count()).isEqualTo(3);
        assertThat(summary.average()).isEqualTo(3.3);
        assertThat(summary.stars()).containsExactly(1L, 0L, 0L, 1L, 1L);
        assertThat(summary.brandCount()).isEqualTo(4);
        assertThat(summary.brandAverage()).isEqualTo(3.0);
        assertThat(this.reviews.summary(Ids.newId()).count()).isZero();

        SecurityContextHolder.clearContext();
        ReviewPage page = this.reviews.storeReviews(this.store, null, 2);
        assertThat(page.items()).extracting(ReviewView::rating).containsExactly(1, 4);
        ReviewPage next = this.reviews.storeReviews(this.store, page.next(), 2);
        assertThat(next.items()).extracting(ReviewView::rating).containsExactly(5);
        assertThat(next.next()).isNull();
    }

    @Test
    @DisplayName("a customer exports their reviews and rider ratings; after deletion the reviews stay as Deleted user")
    void exportAndErase() {
        // given
        String customer = "cust-" + Ids.newId();
        String order = finished(customer, "rider-9");
        signIn(customer);
        given(this.identity.getUser(customer)).willReturn(user(customer, "Ayesha Khan"));
        String photo = Ids.newId();
        given(this.media.claim(any(), any(), any())).willAnswer((call) -> new MediaAssetView(photo, MediaPurpose.REVIEW,
                MediaStatus.READY, Map.of("thumb", new MediaVariantView("https://cdn/t.webp", 160, 160, 10)),
                call.getArgument(2, MediaOwner.class), 1));
        String id = this.reviews.submit(new SubmitReview(order, 5, "Lovely", photo, 4, List.of("ON_TIME")))
            .storeReview()
            .id();

        // when
        CustomerReviews mine = this.reviews.export();
        int erased = this.reviews.eraseCustomer(customer);

        // then
        assertThat(mine.reviews()).singleElement().satisfies((review) -> assertThat(review.text()).isEqualTo("Lovely"));
        assertThat(mine.riderRatings()).singleElement().satisfies((rating) -> {
            assertThat(rating.orderId()).isEqualTo(order);
            assertThat(rating.tags()).containsExactly("ON_TIME");
        });
        assertThat(erased).isEqualTo(1);
        verify(this.serviceCalls).run(any());
        signIn("someone-else");
        ReviewView after = this.reviews.storeReviews(this.store, null, 10).items().stream()
            .filter((review) -> review.id().equals(id))
            .findFirst()
            .orElseThrow();
        assertThat(after.customerName()).isEqualTo("Deleted user");
        assertThat(after.photoThumbUrl()).isNull();
        assertThat(after.text()).isEqualTo("Lovely");
        assertThat(this.reviews.storeRating(this.store).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("short public names")
    void names() {
        assertThat(ReviewService.shortName("Ayesha Khan")).isEqualTo("Ayesha K.");
        assertThat(ReviewService.shortName("Bilal")).isEqualTo("Bilal");
        assertThat(ReviewService.shortName(" ")).isEqualTo("Customer");
    }

    private String finished(String customerId, String riderId) {
        String order = Ids.newId();
        // the dispatch event may arrive first
        if (riderId != null) {
            this.reviews.delivered(order, riderId, "Bilal", "OPERATOR");
        }
        this.reviews.orderFinished(order, "7K3Q-" + order.substring(0, 4), customerId, this.store, this.brand,
                "Karachi Grill - DHA", Set.of("owner-1"), "en", this.now.get());
        return order;
    }

    private String review(String customerId, int rating, String text) {
        String order = finished(customerId, null);
        signIn(customerId);
        given(this.identity.getUser(customerId)).willReturn(user(customerId, "Customer " + customerId));
        this.now.set(this.now.get().plusSeconds(1));
        return this.reviews.submit(new SubmitReview(order, rating, text, null, null, null)).storeReview().id();
    }

    private List<Document> events() {
        return this.reviewMongo.template().getCollection(this.outbox.collection()).find().into(new ArrayList<>());
    }

    private static UserView user(String id, String name) {
        return new UserView(id, id + "@example.com", name, Set.of(Role.CUSTOMER), UserStatus.ACTIVE, 1, Set.of(), Set.of());
    }

    private static void signIn(String subject) {
        signIn(subject, null);
    }

    private static void signIn(String subject, String storeId, String... permissions) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(subject).claim("perms", List.of(permissions));
        if (storeId != null) {
            jwt.claim("storeIds", List.of(storeId));
        }
        SecurityContextHolder.getContext().setAuthentication(new FoodstackJwtAuthenticationConverter().convert(jwt.build()));
    }
}

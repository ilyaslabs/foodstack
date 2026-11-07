package io.github.ilyaslabs.foodstack.it.scenarios;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.it.support.ApiClient;
import io.github.ilyaslabs.foodstack.it.support.BothTopologies;
import io.github.ilyaslabs.foodstack.it.support.FoodstackPlatform;
import io.github.ilyaslabs.foodstack.it.support.Mailbox;
import io.github.ilyaslabs.foodstack.it.support.Topology;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M4 journeys (PRD 7, exit criterion): a customer pays in the app's own card fields and keeps the
 * card; the finished order lands in the branch's ledger and payout report; the customer reviews
 * it and the store replies. A customer downloads their data and deletes their account, getting
 * the operator's own wording of the email, and their name leaves the store's records.
 */
@BothTopologies
class MoneyAndTrustScenario {

    private final FoodstackPlatform platform;

    MoneyAndTrustScenario(Topology topology) {
        this.platform = FoodstackPlatform.of(topology);
    }

    @Test
    @DisplayName("embedded card fields and a saved card pay an order; the ledger, payouts and reviews follow")
    void payInTheAppThenLedgerAndReview() {
        ApiClient admin = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);

        // given: cards are typed in the app and may be kept for next time
        ordering(admin, Map.of("cardStyle", "EMBEDDED", "savedCardsEnabled", true));
        Response options = this.platform.client().get("/api/v1/payments/options");
        assertThat(options.<String>path("cardStyle")).isEqualTo("EMBEDDED");
        assertThat(options.<Boolean>path("savedCards")).isTrue();
        Store store = Stores.live(admin, "Tikka House", "Johar Town");
        ApiClient owner = Stores.owner(this.platform, admin, store.id());
        ApiClient customer = Stores.customer(this.platform, "Sara Customer").client();

        // when: the customer orders by card and pays in the app, keeping the card
        String orderId = cardOrder(customer, store, "card-1");
        Response checkout = customer.get("/api/v1/payments/" + orderId + "/checkout");
        checkout.then().log().ifValidationFails().statusCode(200);
        assertThat(checkout.<Boolean>path("canSaveCard")).isTrue();
        assertThat(checkout.<List<Object>>path("savedCards")).isEmpty();
        assertThat(this.platform.client().get("/api/v1/payments/" + orderId + "/checkout").statusCode()).isEqualTo(401);
        Response paid = this.platform.client().postJson("/api/v1/payments/sandbox/" + orderId + "/card",
                Map.of("clientSecret", checkout.<String>path("clientSecret"), "cardNumber", "4242 4242 4242 4242", "saveCard", true));
        paid.then().log().ifValidationFails().statusCode(200);
        assertThat(paid.<String>path("next")).isEqualTo("http://web.it.foodstack.local/orders/" + orderId);
        awaitStatus(customer, orderId, "CONFIRMED");
        Response cards = customer.get("/api/v1/payments/cards");
        assertThat(cards.<List<String>>path("last4")).containsExactly("4242");

        // and the next order offers the saved card, which pays it
        String second = cardOrder(customer, store, "card-2");
        Response next = customer.get("/api/v1/payments/" + second + "/checkout");
        assertThat(next.<List<String>>path("savedCards.last4")).containsExactly("4242");
        this.platform.client().postJson("/api/v1/payments/sandbox/" + second + "/card",
                Map.of("clientSecret", next.<String>path("clientSecret"), "savedCardId", next.<String>path("savedCards[0].id"),
                        "saveCard", false))
            .then().log().ifValidationFails().statusCode(200);
        awaitStatus(customer, second, "CONFIRMED");

        // and the store hands both over
        for (String id : List.of(orderId, second)) {
            owner.postJson("/api/v1/orders/" + id + "/actions/accept", Map.of("prepMinutes", 10)).then().statusCode(200);
            owner.postJson("/api/v1/orders/" + id + "/actions/ready", Map.of()).then().statusCode(200);
            owner.postJson("/api/v1/orders/" + id + "/actions/collected", Map.of()).then().statusCode(200);
        }

        // then: the branch is owed both orders' food less 15% commission, and sees only its own row
        String from = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        String to = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Response report = admin.get("/api/v1/ledger/payouts?party=STORE&from=" + from + "&to=" + to);
            report.then().log().ifValidationFails().statusCode(200);
            List<Map<String, Object>> rows = report.path("rows");
            Map<String, Object> row = rows.stream().filter((r) -> store.id().equals(r.get("partyId"))).findFirst().orElse(null);
            assertThat(row).isNotNull();
            // 2 orders x 85,000 food, less 15% commission
            assertThat(((Number) row.get("closingMinor")).longValue()).isEqualTo(2 * (85_000 - 12_750));
            assertThat(row.get("direction")).isEqualTo("OPERATOR_PAYS");
        });
        Response own = owner.get("/api/v1/ledger/payouts?party=STORE&from=" + from + "&to=" + to);
        own.then().log().ifValidationFails().statusCode(200);
        assertThat(own.<List<String>>path("rows.partyId")).containsExactly(store.id());
        assertThat(owner.get("/api/v1/ledger/payouts.csv?party=STORE&from=" + from + "&to=" + to).asString())
            .contains("Tikka House");

        // and the customer rates the store, a blocked word is masked and flagged, and the store replies
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> assertThat(
                customer.get("/api/v1/reviews/orders/" + orderId).<Boolean>path("canRateStore")).isTrue());
        Response review = customer.postJson("/api/v1/reviews",
                Map.of("orderId", orderId, "storeRating", 4, "text", "Great tikka but the crap naan was cold"));
        review.then().log().ifValidationFails().statusCode(201);
        String reviewId = review.path("storeReview.id");
        assertThat(review.<String>path("storeReview.text")).doesNotContain("crap").contains("*");
        assertThat(customer.postJson("/api/v1/reviews", Map.of("orderId", orderId, "storeRating", 1)).statusCode())
            .isEqualTo(409);
        owner.postJson("/api/v1/reviews/" + reviewId + "/reply", Map.of("text", "Sorry about the naan, fixed it!"))
            .then().log().ifValidationFails().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Response page = this.platform.client().get("/api/v1/reviews/stores/" + store.id());
            assertThat(page.<List<String>>path("items.id")).containsExactly(reviewId);
            assertThat(page.<String>path("items[0].customerName")).isEqualTo("Sara C.");
            assertThat(page.<String>path("items[0].reply.text")).isEqualTo("Sorry about the naan, fixed it!");
            assertThat(this.platform.client().get("/api/v1/reviews/stores/" + store.id() + "/summary").<Integer>path("count"))
                .isEqualTo(1);
        });
        assertThat(admin.get("/api/v1/reviews/moderation?filter=FLAGGED").<List<String>>path("items.id")).contains(reviewId);

        // and a removed card is gone
        customer.request("/api/v1/payments/cards/" + cards.path("[0].id"))
            .delete("/api/v1/payments/cards/" + cards.path("[0].id"))
            .then().statusCode(204);
        assertThat(customer.get("/api/v1/payments/cards").<List<Object>>path("$")).isEmpty();
        ordering(admin, Map.of("cardStyle", "HOSTED_PAGE", "savedCardsEnabled", false));
    }

    @Test
    @DisplayName("a customer downloads their data and deletes their account; the operator's email wording is used")
    void exportAndDelete() {
        ApiClient admin = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);

        // given: the operator rewords the deletion email's subject
        admin.request("/api/v1/notifications/templates/account-deletion-scheduled/en").contentType(ContentType.JSON)
            .body(Map.of("subject", "Sorry to see you go, {{name}}", "version", 0))
            .put("/api/v1/notifications/templates/account-deletion-scheduled/en")
            .then().log().ifValidationFails().statusCode(200);
        assertThat(admin.request("/api/v1/notifications/templates/login-code/en").contentType(ContentType.JSON)
            .body(Map.of("body", "Sign in now", "version", 0)).put("/api/v1/notifications/templates/login-code/en")
            .statusCode()).isEqualTo(400);

        // and a customer with an address and a finished order
        Store store = Stores.live(admin, "Biryani Bay", "Gulberg");
        ApiClient owner = Stores.owner(this.platform, admin, store.id());
        Customer signedUp = Stores.customer(this.platform, "Hina Leaving");
        ApiClient customer = signedUp.client();
        customer.postJson("/api/v1/me/addresses", Map.of("label", "HOME", "addressLine", "12 Main Boulevard, Gulberg",
                "lat", 31.52, "lng", 74.35, "preferred", true)).then().log().ifValidationFails().statusCode(201);
        customer.postJson("/api/v1/carts/me/lines", Map.of("storeId", store.id(), "itemId", store.itemId(), "quantity", 1))
            .then().statusCode(200);
        Response cash = customer.request("/api/v1/orders").header("Idempotency-Key", "leave-1").contentType(ContentType.JSON)
            .body(Map.of("fulfilment", "PICKUP", "paymentMethod", "CASH", "contactPhone", "+92 300 7654321"))
            .post("/api/v1/orders");
        cash.then().log().ifValidationFails().statusCode(201);
        String orderId = cash.path("orderId");
        owner.postJson("/api/v1/orders/" + orderId + "/actions/accept", Map.of("prepMinutes", 10)).then().statusCode(200);
        owner.postJson("/api/v1/orders/" + orderId + "/actions/ready", Map.of()).then().statusCode(200);
        owner.postJson("/api/v1/orders/" + orderId + "/actions/collected", Map.of()).then().statusCode(200);

        // when: they download their data
        Response me = customer.get("/api/v1/me/export");
        me.then().log().ifValidationFails().statusCode(200);
        assertThat(me.<String>path("account.email")).isEqualTo(signedUp.email());
        assertThat(me.<List<String>>path("addresses.addressLine")).containsExactly("12 Main Boulevard, Gulberg");
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> assertThat(
                customer.get("/api/v1/orders/mine/export").<List<String>>path("id")).containsExactly(orderId));
        customer.get("/api/v1/reviews/mine/export").then().statusCode(200);
        assertThat(customer.get("/api/v1/notifications/mine/export").<List<String>>path("messages.template"))
            .contains("welcome");

        // and ask to delete the account
        Response deletion = customer.postJson("/api/v1/me/deletion", Map.of());
        deletion.then().log().ifValidationFails().statusCode(202);
        assertThat(deletion.<Number>path("deleteAfter")).isNotNull();
        assertThat(owner.postJson("/api/v1/me/deletion", Map.of()).statusCode()).isEqualTo(409);

        // then: they are signed out everywhere and get the operator's wording of the email
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> assertThat(customer.get("/api/v1/me").statusCode()).isEqualTo(401));
        assertThat(Mailbox.awaitEmail(signedUp.email(), "Sorry to see you go, Hina")).contains("deleted");

        // and once the grace period is over their name is gone from the store's records (no sign-in
        // while waiting: signing in during the grace period would cancel the deletion)
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            Response order = owner.get("/api/v1/orders/" + orderId);
            assertThat(order.<String>path("customerName")).isEqualTo("Deleted user");
            assertThat(order.<String>path("contactPhone")).isNull();
        });
        // and the account cannot sign in any more
        assertThat(this.platform.client().postJson("/api/v1/auth/login",
                Map.of("client", "MOBILE", "email", signedUp.email(), "password", Stores.CUSTOMER_PASSWORD)).statusCode())
            .isIn(400, 401);
        // and the email address can sign up again
        this.platform.client().postJson("/api/v1/auth/register",
                Map.of("email", signedUp.email(), "password", Stores.CUSTOMER_PASSWORD, "displayName", "Hina Again"))
            .then().log().ifValidationFails().statusCode(201);
    }

    private static void ordering(ApiClient admin, Map<String, Object> changes) {
        Response current = admin.get("/api/v1/settings/ordering");
        Map<String, Object> body = new LinkedHashMap<>(current.jsonPath().getMap("$"));
        body.remove("version");
        body.put("cashEnabled", true);
        body.put("cardEnabled", true);
        body.putAll(changes);
        admin.request("/api/v1/settings/ordering").header("If-Match", current.header("ETag")).contentType(ContentType.JSON)
            .body(body).put("/api/v1/settings/ordering").then().log().ifValidationFails().statusCode(200);
    }

    private static String cardOrder(ApiClient customer, Store store, String key) {
        customer.postJson("/api/v1/carts/me/lines", Map.of("storeId", store.id(), "itemId", store.itemId(), "quantity", 1))
            .then().log().ifValidationFails().statusCode(200);
        Response placed = customer.request("/api/v1/orders").header("Idempotency-Key", key).contentType(ContentType.JSON)
            .body(Map.of("fulfilment", "PICKUP", "paymentMethod", "CARD", "contactPhone", "+92 300 1234567"))
            .post("/api/v1/orders");
        placed.then().log().ifValidationFails().statusCode(201);
        String orderId = placed.path("orderId");
        assertThat(placed.<String>path("paymentUrl")).isEqualTo("http://web.it.foodstack.local/pay/" + orderId);
        return orderId;
    }

    private static void awaitStatus(ApiClient customer, String orderId, String status) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> assertThat(customer.get("/api/v1/orders/" + orderId).<String>path("status")).isEqualTo(status));
    }

    record Store(String id, String brandId, String itemId) {
    }

    record Customer(String email, ApiClient client) {
    }

    /** A live pickup restaurant with one plain dish, its owner and a signed-up customer. */
    static final class Stores {

        static final String CUSTOMER_PASSWORD = "customer-password-1";

        private Stores() {
        }

        static Store live(ApiClient admin, String brand, String branch) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("brandName", brand + " " + System.nanoTime());
            body.put("branchName", branch);
            body.put("legalName", brand + " (Pvt) Ltd");
            body.put("description", Map.of("en", brand));
            body.put("cuisines", List.of("pakistani"));
            body.put("address", Map.of("line1", "1 Main Road", "area", branch, "city", "Lahore"));
            body.put("location", Map.of("lat", 31.47, "lng", 74.27));
            body.put("pickupEnabled", true);
            body.put("prepMinutes", 15);
            body.put("commission", Map.of("percentBasisPoints", 1500, "fixedMinor", 0));
            Response onboarded = admin.postJson("/api/v1/stores", body);
            onboarded.then().log().ifValidationFails().statusCode(201);
            String storeId = onboarded.path("id");
            String brandId = onboarded.path("brand.id");
            Response section = admin.postJson("/api/v1/brands/" + brandId + "/menu/sections", Map.of("name", Map.of("en", "Mains")));
            section.then().log().ifValidationFails().statusCode(201);
            Response item = admin.postJson("/api/v1/brands/" + brandId + "/menu/items", Map.of("sectionId", section.path("id"),
                    "name", Map.of("en", "Chicken Tikka"), "priceMinor", 85_000, "modifierGroups", List.of(), "sortOrder", 0));
            item.then().log().ifValidationFails().statusCode(201);
            Map<String, Object> hours = new LinkedHashMap<>();
            for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
                hours.put(day, List.of(Map.of("open", "00:00", "close", "23:59")));
            }
            String storeTag = admin.get("/api/v1/stores/" + storeId + "/details").header("ETag");
            admin.request("/api/v1/stores/" + storeId + "/hours").header("If-Match", storeTag).contentType(ContentType.JSON)
                .body(Map.of("openingHours", hours, "closures", List.of()))
                .put("/api/v1/stores/" + storeId + "/hours").then().log().ifValidationFails().statusCode(200);
            admin.request("/api/v1/stores/" + storeId + "/status").contentType(ContentType.JSON).body(Map.of("status", "LIVE"))
                .put("/api/v1/stores/" + storeId + "/status").then().log().ifValidationFails().statusCode(200);
            return new Store(storeId, brandId, item.path("id"));
        }

        static ApiClient owner(FoodstackPlatform platform, ApiClient admin, String storeId) {
            String email = "owner-" + System.nanoTime() + "@it.foodstack.local";
            admin.postJson("/api/v1/stores/" + storeId + "/owners",
                    Map.of("email", email, "displayName", "Omar Owner", "initialPassword", "owner-password-1"))
                .then().log().ifValidationFails().statusCode(201);
            return platform.client().logIn(email, "owner-password-1");
        }

        static Customer customer(FoodstackPlatform platform, String name) {
            String email = "customer-" + System.nanoTime() + "@it.foodstack.local";
            platform.client().postJson("/api/v1/auth/register",
                    Map.of("email", email, "password", CUSTOMER_PASSWORD, "displayName", name))
                .then().log().ifValidationFails().statusCode(201);
            return new Customer(email, platform.client().logIn(email, CUSTOMER_PASSWORD));
        }
    }
}

package io.github.ilyaslabs.foodstack.it.scenarios;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.it.support.ApiClient;
import io.github.ilyaslabs.foodstack.it.support.BothTopologies;
import io.github.ilyaslabs.foodstack.it.support.FoodstackPlatform;
import io.github.ilyaslabs.foodstack.it.support.Mailbox;
import io.github.ilyaslabs.foodstack.it.support.PlatformEvents;
import io.github.ilyaslabs.foodstack.it.support.Topology;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M2 journey (PRD 7, exit criterion): a customer signs up, fills a cart, places a pickup order and
 * pays by card on the gateway's page; the store sees it on its board, accepts it and hands it over.
 * A double tap places one order; a store can reject a cash order; a blocked customer cannot order.
 */
@BothTopologies
class OrderingScenario {

    private final FoodstackPlatform platform;

    OrderingScenario(Topology topology) {
        this.platform = FoodstackPlatform.of(topology);
    }

    @Test
    @DisplayName("a customer places and pays for a pickup order; the store accepts and completes it")
    void placeAndCompleteAPickupOrder() {
        ApiClient admin = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);

        // given: card payments are on (the sandbox gateway) and a live restaurant takes pickup orders
        String orderingTag = this.platform.client().get("/api/v1/settings/ordering").header("ETag");
        admin.request("/api/v1/settings/ordering").header("If-Match", orderingTag).contentType(ContentType.JSON)
            .body(Map.of("cashEnabled", true, "cardEnabled", true, "serviceFeeMinor", 5_000, "serviceFeeBasisPoints", 0,
                    "taxBasisPoints", 0, "tipsEnabled", true, "acceptTimeoutSeconds", 600))
            .put("/api/v1/settings/ordering").then().log().ifValidationFails().statusCode(200);
        // split into services, the others keep the settings they read for up to 30 s
        CouponsAndRefundsScenario.Settings.settle(this.platform);
        assertThat(this.platform.client().get("/api/v1/payments/options").<Boolean>path("card")).isTrue();
        Store store = liveStore(admin);
        String ownerEmail = "owner-" + System.nanoTime() + "@it.foodstack.local";
        admin.postJson("/api/v1/stores/" + store.id() + "/owners",
                Map.of("email", ownerEmail, "displayName", "Omar Owner", "initialPassword", "owner-password-1"))
            .then().log().ifValidationFails().statusCode(201);
        ApiClient owner = this.platform.client().logIn(ownerEmail, "owner-password-1");

        // and a customer signs up and fills a cart
        String customerEmail = "sara-" + System.nanoTime() + "@it.foodstack.local";
        this.platform.client().postJson("/api/v1/auth/register",
                Map.of("email", customerEmail, "password", "customer-password-1", "displayName", "Sara Customer"))
            .then().log().ifValidationFails().statusCode(201);
        ApiClient customer = this.platform.client().logIn(customerEmail, "customer-password-1");
        customer.postJson("/api/v1/carts/me/lines", Map.of("storeId", store.id(), "itemId", store.itemId(), "quantity", 2,
                "optionIds", List.of(store.fullOptionId()), "note", "Less spicy"))
            .then().log().ifValidationFails().statusCode(200);
        Response quote = customer.postJson("/api/v1/carts/me/checkout",
                Map.of("fulfilment", "PICKUP", "paymentMethod", "CARD"));
        quote.then().log().ifValidationFails().statusCode(200);
        assertThat(quote.<List<Object>>path("problems")).isEmpty();
        // 2 x (850 + 400) + 50 service fee
        int total = quote.path("totalMinor");
        assertThat(total).isEqualTo(255_000);

        // when: the customer orders (tapping twice) and pays on the card page
        Map<String, Object> order = Map.of("fulfilment", "PICKUP", "paymentMethod", "CARD", "expectedTotalMinor", total,
                "contactPhone", "+92 300 1234567");
        Response placed = customer.request("/api/v1/orders").header("Idempotency-Key", "tap-1").contentType(ContentType.JSON)
            .body(order).post("/api/v1/orders");
        placed.then().log().ifValidationFails().statusCode(201);
        String orderId = placed.path("orderId");
        Response again = customer.request("/api/v1/orders").header("Idempotency-Key", "tap-1").contentType(ContentType.JSON)
            .body(order).post("/api/v1/orders");
        assertThat(again.<String>path("orderId")).isEqualTo(orderId);
        assertThat(placed.<String>path("status")).isEqualTo("PAYMENT_PENDING");
        String paymentUrl = placed.path("paymentUrl");
        assertThat(paymentUrl).startsWith(this.platform.urlOf("payment") + "/api/v1/payments/sandbox/" + orderId);
        assertThat(RestAssured.get(paymentUrl).asString()).contains("2550.00").contains("value=\"pay\"");
        Response paid = RestAssured.given().redirects().follow(false).formParam("outcome", "pay").post(paymentUrl);
        assertThat(paid.statusCode()).isEqualTo(303);
        assertThat(paid.header("Location")).isEqualTo("http://web.it.foodstack.local/orders/" + orderId);

        // then: the order is confirmed and appears on the store's board
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> assertThat(customer.get("/api/v1/orders/" + orderId).<String>path("status")).isEqualTo("CONFIRMED"));
        assertThat(customer.get("/api/v1/carts/me").<Integer>path("itemCount")).isZero();
        Response board = owner.get("/api/v1/orders/stores/" + store.id() + "/board");
        board.then().log().ifValidationFails().statusCode(200);
        assertThat(board.<List<String>>path("id")).containsExactly(orderId);
        assertThat(board.<String>path("[0].customerName")).isEqualTo("Sara Customer");
        assertThat(board.<String>path("[0].lines[0].note")).isEqualTo("Less spicy");
        assertThat(board.<List<String>>path("[0].actions")).containsExactly("ACCEPT", "REJECT");
        String number = placed.path("number");
        assertThat(Mailbox.awaitEmail(ownerEmail, "New order " + number)).contains("/orders/board");
        assertThat(Mailbox.awaitEmail(customerEmail, "Your order " + number)).contains("/orders/" + orderId);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> assertThat(owner.get("/api/v1/notifications/unread-count").<Integer>path("unread")).isEqualTo(1));

        // and the store accepts, prepares and hands it over
        owner.postJson("/api/v1/orders/" + orderId + "/actions/accept", Map.of("prepMinutes", 15))
            .then().log().ifValidationFails().statusCode(200);
        owner.postJson("/api/v1/orders/" + orderId + "/actions/ready", Map.of()).then().statusCode(200);
        Response collected = owner.postJson("/api/v1/orders/" + orderId + "/actions/collected", Map.of());
        collected.then().log().ifValidationFails().statusCode(200);
        assertThat(collected.<String>path("status")).isEqualTo("COLLECTED");
        assertThat(collected.<List<String>>path("timeline.status")).containsExactly("PLACED", "PAYMENT_PENDING", "CONFIRMED",
                "ACCEPTED", "READY", "COLLECTED");
        assertThat(owner.get("/api/v1/orders/stores/" + store.id() + "/board").<List<String>>path("id")).isEmpty();
        assertThat(PlatformEvents.awaitEvent(this.platform, "order", "foodstack.order.OrderCollected.v1", orderId)).isNotBlank();
        assertThat(PlatformEvents.awaitEvent(this.platform, "payment", "foodstack.payment.PaymentSucceeded.v1", orderId))
            .isNotBlank();
        assertThat(Mailbox.awaitEmail(customerEmail, "Order " + number + " is ready to collect")).contains(number);
        assertThat(customer.get("/api/v1/notifications").<List<String>>path("template")).contains("order-ready-pickup",
                "order-accepted", "order-confirmed", "welcome");
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            Response history = customer.get("/api/v1/orders/mine");
            assertThat(history.<List<String>>path("items.id")).containsExactly(orderId);
            assertThat(history.<List<String>>path("items.status")).containsExactly("COLLECTED");
        });

        // and a cash order the store turns down is closed with the reason
        customer.postJson("/api/v1/carts/me/lines", Map.of("storeId", store.id(), "itemId", store.itemId(), "quantity", 1,
                "optionIds", List.of(store.fullOptionId())));
        Response cash = customer.request("/api/v1/orders").header("Idempotency-Key", "tap-2").contentType(ContentType.JSON)
            .body(Map.of("fulfilment", "PICKUP", "paymentMethod", "CASH")).post("/api/v1/orders");
        cash.then().log().ifValidationFails().statusCode(201);
        assertThat(cash.<String>path("status")).isEqualTo("CONFIRMED");
        String cashId = cash.path("orderId");
        assertThat(customer.postJson("/api/v1/orders/" + cashId + "/actions/accept", Map.of()).statusCode()).isEqualTo(403);
        Response rejected = owner.postJson("/api/v1/orders/" + cashId + "/actions/reject", Map.of("reason", "Out of rice"));
        assertThat(rejected.<String>path("status")).isEqualTo("REJECTED");
        assertThat(rejected.<String>path("closeReason")).isEqualTo("Out of rice");

        // and once blocked, the customer can no longer order
        String customerId = customer.get("/api/v1/me").path("id");
        admin.postJson("/api/v1/users/" + customerId + "/block", Map.of("reason", "Fraud"))
            .then().log().ifValidationFails().statusCode(200);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> assertThat(customer
            .request("/api/v1/orders").header("Idempotency-Key", "tap-3").contentType(ContentType.JSON)
            .body(Map.of("fulfilment", "PICKUP", "paymentMethod", "CASH")).post("/api/v1/orders").statusCode()).isEqualTo(401));
    }

    record Store(String id, String itemId, String fullOptionId) {
    }

    private static Store liveStore(ApiClient admin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brandName", "Karahi Corner");
        body.put("branchName", "Model Town");
        body.put("legalName", "Karahi Corner (Pvt) Ltd");
        body.put("description", Map.of("en", "Charcoal karahi"));
        body.put("cuisines", List.of("pakistani"));
        body.put("address", Map.of("line1", "5 Link Road", "area", "Model Town", "city", "Lahore"));
        body.put("location", Map.of("lat", 31.4840, "lng", 74.3260));
        body.put("pickupEnabled", true);
        body.put("prepMinutes", 20);
        body.put("commission", Map.of("percentBasisPoints", 1500, "fixedMinor", 0));
        Response onboarded = admin.postJson("/api/v1/stores", body);
        onboarded.then().log().ifValidationFails().statusCode(201);
        String storeId = onboarded.path("id");
        String brandId = onboarded.path("brand.id");
        Response section = admin.postJson("/api/v1/brands/" + brandId + "/menu/sections", Map.of("name", Map.of("en", "Karahi")));
        section.then().log().ifValidationFails().statusCode(201);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sectionId", section.path("id"));
        item.put("name", Map.of("en", "Chicken Karahi"));
        item.put("priceMinor", 85_000);
        item.put("modifierGroups", List.of(Map.of("name", Map.of("en", "Size"), "minSelect", 1, "maxSelect", 1,
                "options", List.of(Map.of("name", Map.of("en", "Half"), "priceDeltaMinor", 0),
                        Map.of("name", Map.of("en", "Full"), "priceDeltaMinor", 40_000)))));
        item.put("sortOrder", 0);
        Response created = admin.postJson("/api/v1/brands/" + brandId + "/menu/items", item);
        created.then().log().ifValidationFails().statusCode(201);
        Map<String, Object> hours = new LinkedHashMap<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            hours.put(day, List.of(Map.of("open", "00:00", "close", "23:59")));
        }
        admin.request("/api/v1/zones/stores/" + storeId).contentType(ContentType.JSON)
            .body(Map.of("zones", List.of(Map.of("name", "Up to 3 km", "center", Map.of("lat", 31.4840, "lng", 74.3260),
                    "radiusMeters", 3000, "chargeMinor", 10_000, "minOrderMinor", 0))))
            .put("/api/v1/zones/stores/" + storeId).then().log().ifValidationFails().statusCode(200);
        String storeTag = admin.get("/api/v1/stores/" + storeId + "/details").header("ETag");
        admin.request("/api/v1/stores/" + storeId + "/hours").header("If-Match", storeTag).contentType(ContentType.JSON)
            .body(Map.of("openingHours", hours, "closures", List.of()))
            .put("/api/v1/stores/" + storeId + "/hours").then().log().ifValidationFails().statusCode(200);
        admin.request("/api/v1/stores/" + storeId + "/status").contentType(ContentType.JSON).body(Map.of("status", "LIVE"))
            .put("/api/v1/stores/" + storeId + "/status").then().log().ifValidationFails().statusCode(200);
        String fullOptionId = created.path("modifierGroups[0].options[1].id");
        assertThat(fullOptionId).isNotBlank();
        return new Store(storeId, created.path("id"), fullOptionId);
    }
}

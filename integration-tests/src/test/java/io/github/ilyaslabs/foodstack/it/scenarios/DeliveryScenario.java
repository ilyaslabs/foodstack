package io.github.ilyaslabs.foodstack.it.scenarios;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.it.support.ApiClient;
import io.github.ilyaslabs.foodstack.it.support.FoodstackPlatform;
import io.github.ilyaslabs.foodstack.it.support.PlatformEvents;
import io.github.ilyaslabs.foodstack.it.support.Topology;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M3 journey (PRD 7, exit criterion): a store that uses the operator's riders accepts a delivery
 * order; dispatch offers it to the nearest online rider, who accepts, sends their position, picks
 * it up and hands it over with the customer's code. The customer sees the rider and the code, and
 * the rider's earnings show the job.
 */
abstract class DeliveryScenario {

    private static final Map<String, Object> STORE = Map.of("lat", 31.5132, "lng", 74.3465);
    private static final Map<String, Object> HOME = Map.of("lat", 31.5204, "lng", 74.3587);

    private final FoodstackPlatform platform;

    DeliveryScenario(Topology topology) {
        this.platform = FoodstackPlatform.of(topology);
    }

    @Test
    @DisplayName("an operator rider is offered a delivery, picks it up and hands it over with the customer's code")
    void deliverWithAnOperatorRider() {
        ApiClient admin = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);

        // given: operator delivery is priced, every handover needs the code, and the search starts at once
        String pricingTag = admin.get("/api/v1/geo/operator-pricing").header("ETag");
        admin.request("/api/v1/geo/operator-pricing").header("If-Match", pricingTag).contentType(ContentType.JSON)
            .body(Map.of("enabled", true, "baseChargeMinor", 10_000, "perKmMinor", 3_000, "maxDistanceMeters", 10_000))
            .put("/api/v1/geo/operator-pricing").then().log().ifValidationFails().statusCode(200);
        String deliveryTag = admin.get("/api/v1/settings/delivery").header("ETag");
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("riderPayBasisPoints", 8_000);
        rules.put("riderPayMinMinor", 5_000);
        rules.put("offerTimeoutSeconds", 60);
        rules.put("searchRadiusMeters", 5_000);
        rules.put("searchLeadMinutes", 60);
        rules.put("noRiderAlertSeconds", 300);
        rules.put("proofCodeAboveMinor", 0);
        rules.put("locationRetentionDays", 30);
        admin.request("/api/v1/settings/delivery").header("If-Match", deliveryTag).contentType(ContentType.JSON).body(rules)
            .put("/api/v1/settings/delivery").then().log().ifValidationFails().statusCode(200);

        // and a live restaurant delivers with the operator's riders
        Store store = operatorDeliveryStore(admin);
        String ownerEmail = "owner-" + System.nanoTime() + "@it.foodstack.local";
        admin.postJson("/api/v1/stores/" + store.id() + "/owners",
                Map.of("email", ownerEmail, "displayName", "Hina Owner", "initialPassword", "owner-password-1"))
            .then().log().ifValidationFails().statusCode(201);
        ApiClient owner = this.platform.client().logIn(ownerEmail, "owner-password-1");

        // and an operator rider goes online next to the store
        String riderEmail = "rider-" + System.nanoTime() + "@it.foodstack.local";
        Response created = admin.postJson("/api/v1/riders", Map.of("email", riderEmail, "initialPassword", "rider-password-1",
                "name", "Bilal Rider", "phone", "+92 300 7654321", "vehicle", "MOTORBIKE", "plate", "LEB-1234", "city", "Lahore"));
        created.then().log().ifValidationFails().statusCode(201);
        String riderId = created.path("id");
        ApiClient rider = this.platform.client().logIn(riderEmail, "rider-password-1");
        assertThat(rider.get("/api/v1/me").<List<String>>path("roles")).contains("RIDER");
        rider.postJson("/api/v1/rider/me/online", Map.of("online", true, "lat", STORE.get("lat"), "lng", STORE.get("lng")))
            .then().log().ifValidationFails().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(admin.get("/api/v1/riders/live").<List<String>>path("riderId")).contains(riderId));

        // and a customer orders for delivery, paying cash
        String customerEmail = "zara-" + System.nanoTime() + "@it.foodstack.local";
        this.platform.client().postJson("/api/v1/auth/register",
                Map.of("email", customerEmail, "password", "customer-password-1", "displayName", "Zara Customer"))
            .then().log().ifValidationFails().statusCode(201);
        ApiClient customer = this.platform.client().logIn(customerEmail, "customer-password-1");
        customer.postJson("/api/v1/carts/me/lines", Map.of("storeId", store.id(), "itemId", store.itemId(), "quantity", 2))
            .then().log().ifValidationFails().statusCode(200);
        Response quote = customer.postJson("/api/v1/carts/me/checkout",
                Map.of("fulfilment", "DELIVERY", "lat", HOME.get("lat"), "lng", HOME.get("lng"), "paymentMethod", "CASH"));
        quote.then().log().ifValidationFails().statusCode(200);
        assertThat(quote.<List<Object>>path("problems")).isEmpty();
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("fulfilment", "DELIVERY");
        order.put("address", Map.of("lat", HOME.get("lat"), "lng", HOME.get("lng"), "addressLine", "House 14, Street 7, Gulberg III",
                "instructions", "Green gate"));
        order.put("paymentMethod", "CASH");
        order.put("expectedTotalMinor", quote.path("totalMinor"));
        order.put("contactPhone", "+92 300 1112233");
        Response placed = customer.request("/api/v1/orders").header("Idempotency-Key", "deliver-1").contentType(ContentType.JSON)
            .body(order).post("/api/v1/orders");
        placed.then().log().ifValidationFails().statusCode(201);
        String orderId = placed.path("orderId");
        int total = quote.path("totalMinor");

        // when: the store accepts, the rider is offered the job and takes it
        owner.postJson("/api/v1/orders/" + orderId + "/actions/accept", Map.of("prepMinutes", 10))
            .then().log().ifValidationFails().statusCode(200);
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Response jobs = rider.get("/api/v1/dispatch/rider/jobs");
            assertThat(jobs.<List<String>>path("orderId")).containsExactly(orderId);
            assertThat(jobs.<List<String>>path("status")).containsExactly("OFFERED");
        });
        Response accepted = rider.postJson("/api/v1/dispatch/rider/offers/" + orderId + "/accept", Map.of());
        accepted.then().log().ifValidationFails().statusCode(200);
        assertThat(accepted.<String>path("status")).isEqualTo("ASSIGNED");
        assertThat(accepted.<Integer>path("cashToCollectMinor")).isEqualTo(total);
        assertThat(accepted.<Boolean>path("needsCode")).isTrue();

        // then: the customer sees the rider and their delivery code
        String code = await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() -> {
            Response mine = customer.get("/api/v1/orders/" + orderId);
            return (riderId.equals(mine.path("rider.riderId"))) ? mine.<String>path("deliveryCode") : null;
        }, value -> value != null);
        assertThat(code).matches("\\d{4}");
        assertThat(owner.get("/api/v1/orders/" + orderId).<String>path("deliveryCode")).isNull();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(customer.get("/api/v1/notifications").<List<String>>path("template")).contains("rider-assigned"));

        // and follows the rider, who rides to the store and picks the food up
        rider.postJson("/api/v1/rider/me/locations", Map.of("points", List.of(Map.of("lat", 31.5140, "lng", 74.3480,
                "accuracyMeters", 8.0, "at", System.currentTimeMillis())))).then().log().ifValidationFails().statusCode(204);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Response tracking = customer.get("/api/v1/riders/tracking/" + orderId);
            tracking.then().log().ifValidationFails().statusCode(200);
            assertThat(tracking.<String>path("riderName")).isEqualTo("Bilal Rider");
            assertThat(tracking.<Float>path("lat")).isEqualTo(31.5140f);
            assertThat(tracking.<Boolean>path("pickedUp")).isFalse();
        });
        rider.postJson("/api/v1/deliveries/" + orderId + "/arrived", Map.of()).then().log().ifValidationFails().statusCode(200);
        owner.postJson("/api/v1/orders/" + orderId + "/actions/ready", Map.of()).then().log().ifValidationFails().statusCode(200);
        rider.postJson("/api/v1/deliveries/" + orderId + "/picked-up", Map.of()).then().log().ifValidationFails().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(customer.get("/api/v1/orders/" + orderId).<String>path("status")).isEqualTo("PICKED_UP"));

        // and a wrong code is refused, the right one completes the delivery
        String wrong = code.equals("0000") ? "1111" : "0000";
        Response refused = rider.postJson("/api/v1/deliveries/" + orderId + "/delivered", Map.of("code", wrong));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.<String>path("code")).isEqualTo("DELIVERY_CODE_WRONG");
        Response delivered = rider.postJson("/api/v1/deliveries/" + orderId + "/delivered",
                Map.of("code", code, "cashCollectedMinor", total));
        delivered.then().log().ifValidationFails().statusCode(200);
        assertThat(delivered.<String>path("status")).isEqualTo("DELIVERED");
        assertThat(PlatformEvents.awaitEvent(this.platform, "dispatch", "foodstack.dispatch.DeliveryCompleted.v1", orderId))
            .isNotBlank();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Response done = customer.get("/api/v1/orders/" + orderId);
            assertThat(done.<String>path("status")).isEqualTo("DELIVERED");
            assertThat(done.<List<String>>path("timeline.status")).containsSubsequence("ACCEPTED", "READY", "PICKED_UP", "DELIVERED");
        });

        // and the rider's earnings show the job and the cash they hold
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Response earnings = rider.get("/api/v1/rider/me/earnings");
            assertThat(earnings.<List<String>>path("recent.orderId")).containsExactly(orderId);
            assertThat(earnings.<Integer>path("today.deliveries")).isEqualTo(1);
            assertThat(earnings.<Integer>path("today.cashCollectedMinor")).isEqualTo(total);
            assertThat(earnings.<Integer>path("today.earningMinor")).isPositive();
        });
        assertThat(rider.get("/api/v1/dispatch/rider/jobs").<List<String>>path("orderId")).isEmpty();
    }

    record Store(String id, String itemId) {
    }

    private static Store operatorDeliveryStore(ApiClient admin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brandName", "Tikka House");
        body.put("branchName", "Gulberg");
        body.put("legalName", "Tikka House (Pvt) Ltd");
        body.put("description", Map.of("en", "Charcoal tikka"));
        body.put("cuisines", List.of("pakistani"));
        body.put("address", Map.of("line1", "12 Main Boulevard", "area", "Gulberg", "city", "Lahore"));
        body.put("location", STORE);
        body.put("pickupEnabled", true);
        body.put("prepMinutes", 15);
        body.put("commission", Map.of("percentBasisPoints", 1500, "fixedMinor", 0));
        Response onboarded = admin.postJson("/api/v1/stores", body);
        onboarded.then().log().ifValidationFails().statusCode(201);
        String storeId = onboarded.path("id");
        String brandId = onboarded.path("brand.id");
        Response section = admin.postJson("/api/v1/brands/" + brandId + "/menu/sections", Map.of("name", Map.of("en", "Tikka")));
        section.then().log().ifValidationFails().statusCode(201);
        Response item = admin.postJson("/api/v1/brands/" + brandId + "/menu/items", Map.of("sectionId", section.path("id"),
                "name", Map.of("en", "Chicken Tikka"), "priceMinor", 90_000, "modifierGroups", List.of(), "sortOrder", 0));
        item.then().log().ifValidationFails().statusCode(201);

        // the operator lets the store use its riders, and the store switches to them
        String tag = admin.get("/api/v1/stores/" + storeId + "/details").header("ETag");
        admin.request("/api/v1/stores/" + storeId + "/admin").header("If-Match", tag).contentType(ContentType.JSON)
            .body(Map.of("legalName", "Tikka House (Pvt) Ltd", "operatorDeliveryAllowed", true))
            .put("/api/v1/stores/" + storeId + "/admin").then().log().ifValidationFails().statusCode(200);
        tag = admin.get("/api/v1/stores/" + storeId + "/details").header("ETag");
        admin.request("/api/v1/stores/" + storeId + "/branch").header("If-Match", tag).contentType(ContentType.JSON)
            .body(Map.of("branchName", "Gulberg", "address", Map.of("line1", "12 Main Boulevard", "area", "Gulberg", "city", "Lahore"),
                    "location", STORE, "prepMinutes", 15, "deliveryMode", "OPERATOR", "pickupEnabled", true))
            .put("/api/v1/stores/" + storeId + "/branch").then().log().ifValidationFails().statusCode(200);

        Map<String, Object> hours = new LinkedHashMap<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            hours.put(day, List.of(Map.of("open", "00:00", "close", "23:59")));
        }
        tag = admin.get("/api/v1/stores/" + storeId + "/details").header("ETag");
        admin.request("/api/v1/stores/" + storeId + "/hours").header("If-Match", tag).contentType(ContentType.JSON)
            .body(Map.of("openingHours", hours, "closures", List.of()))
            .put("/api/v1/stores/" + storeId + "/hours").then().log().ifValidationFails().statusCode(200);
        admin.request("/api/v1/stores/" + storeId + "/status").contentType(ContentType.JSON).body(Map.of("status", "LIVE"))
            .put("/api/v1/stores/" + storeId + "/status").then().log().ifValidationFails().statusCode(200);
        return new Store(storeId, item.path("id"));
    }
}

package io.github.ilyaslabs.foodstack.it.scenarios;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import io.github.ilyaslabs.foodstack.it.scenarios.MoneyAndTrustScenario.Store;
import io.github.ilyaslabs.foodstack.it.scenarios.MoneyAndTrustScenario.Stores;
import io.github.ilyaslabs.foodstack.it.support.ApiClient;
import io.github.ilyaslabs.foodstack.it.support.FoodstackPlatform;
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
 * Coupons and refunds across modules (PRD 4.8, 4.9): an operator coupon discounts a card order once
 * per customer and is counted when the order is confirmed; a customer who cancels before paying
 * gets the coupon back; support refunds part of a finished order to the card, once charged to the
 * store and once borne by the operator, and the branch's payout follows.
 */
abstract class CouponsAndRefundsScenario {

    private final FoodstackPlatform platform;

    CouponsAndRefundsScenario(Topology topology) {
        this.platform = FoodstackPlatform.of(topology);
    }

    @Test
    @DisplayName("an operator coupon discounts a card order once per customer; support refunds part of it, paid by the store or the operator")
    void couponThenRefunds() {
        ApiClient admin = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);

        // given: cards on the gateway's page, no fees or tax, so the totals are the food alone
        Map<String, Object> ordering = new LinkedHashMap<>();
        ordering.put("cashEnabled", true);
        ordering.put("cardEnabled", true);
        ordering.put("cardStyle", "HOSTED_PAGE");
        ordering.put("serviceFeeMinor", 0);
        ordering.put("serviceFeeBasisPoints", 0);
        ordering.put("taxBasisPoints", 0);
        Settings.ordering(this.platform, admin, ordering);
        Store store = Stores.live(admin, "Karahi Corner", "Model Town");
        ApiClient owner = Stores.owner(this.platform, admin, store.id());
        ApiClient customer = Stores.customer(this.platform, "Bilal Customer").client();

        // and the operator's coupon: 10% off, at most Rs 500, once per customer
        String code = "IT" + ThreadLocalRandom.current().nextInt(100_000, 999_999);
        Map<String, Object> coupon = new LinkedHashMap<>();
        coupon.put("code", code);
        coupon.put("title", Map.of("en", "10% off"));
        coupon.put("type", "PERCENT");
        coupon.put("percentBasisPoints", 1000);
        coupon.put("maxDiscountMinor", 50_000);
        coupon.put("startsAt", Instant.now().minusSeconds(60).getEpochSecond());
        coupon.put("perCustomerLimit", 1);
        coupon.put("totalLimit", 100);
        coupon.put("operatorFundingBasisPoints", 10_000);
        Response created = admin.postJson("/api/v1/promotions", coupon);
        created.then().log().ifValidationFails().statusCode(201);
        String promotionId = created.path("id");
        assertThat(admin.postJson("/api/v1/promotions", coupon).statusCode()).isEqualTo(409);

        // when: a cash order with the coupon is cancelled before the store answers, the use comes back
        String cancelled = place(customer, store, "CASH", code, "cpn-cash");
        assertThat(customer.get("/api/v1/orders/" + cancelled).<Integer>path("totals.itemsDiscountMinor")).isEqualTo(8_500);
        customer.postJson("/api/v1/orders/" + cancelled + "/cancel", Map.of("reason", "Changed my mind"))
            .then().log().ifValidationFails().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(admin.get("/api/v1/promotions/" + promotionId).<Integer>path("used")).isZero());

        // and the customer uses it on a card order and pays on the gateway's page
        String orderId = place(customer, store, "CARD", code, "cpn-card");
        Response placed = customer.get("/api/v1/orders/" + orderId);
        assertThat(placed.<Integer>path("totals.subtotalMinor")).isEqualTo(85_000);
        assertThat(placed.<Integer>path("totals.itemsDiscountMinor")).isEqualTo(8_500);
        assertThat(placed.<Integer>path("totals.totalMinor")).isEqualTo(76_500);
        assertThat(placed.<String>path("coupon.code")).isEqualTo(code);
        Response paid = RestAssured.given().redirects().follow(false).formParam("outcome", "pay")
            .post(placed.<String>path("paymentUrl"));
        assertThat(paid.statusCode()).isEqualTo(303);
        awaitStatus(customer, orderId, "CONFIRMED");
        assertThat(customer.get("/api/v1/orders/" + orderId).<Integer>path("paidMinor")).isEqualTo(76_500);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(admin.get("/api/v1/promotions/" + promotionId).<Integer>path("used")).isEqualTo(1));

        // then: the same customer cannot use it again
        customer.postJson("/api/v1/carts/me/lines", Map.of("storeId", store.id(), "itemId", store.itemId(), "quantity", 1))
            .then().statusCode(200);
        Response again = customer.request("/api/v1/orders").header("Idempotency-Key", "cpn-again").contentType(ContentType.JSON)
            .body(Map.of("fulfilment", "PICKUP", "paymentMethod", "CASH", "couponCode", code, "contactPhone", "+92 300 1112233"))
            .post("/api/v1/orders");
        assertThat(again.statusCode()).isIn(400, 409, 422);
        assertThat(again.asString()).contains("COUPON_CUSTOMER_LIMIT");
        customer.request("/api/v1/carts/me").delete("/api/v1/carts/me");

        // and the store hands it over
        owner.postJson("/api/v1/orders/" + orderId + "/actions/accept", Map.of("prepMinutes", 10)).then().statusCode(200);
        owner.postJson("/api/v1/orders/" + orderId + "/actions/ready", Map.of()).then().statusCode(200);
        owner.postJson("/api/v1/orders/" + orderId + "/actions/collected", Map.of()).then().statusCode(200);

        // when: support refunds Rs 100 charged to the store, then Rs 50 borne by the operator
        refund(admin, orderId, 10_000, "Cold naan", "STORE").then().log().ifValidationFails().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(customer.get("/api/v1/orders/" + orderId).<Integer>path("refundedMinor"))
                .isEqualTo(10_000));
        refund(admin, orderId, 5_000, "Late", "OPERATOR").then().log().ifValidationFails().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(customer.get("/api/v1/orders/" + orderId).<Integer>path("refundedMinor"))
                .isEqualTo(15_000));

        // then: no more than what is left can be refunded
        Response tooMuch = refund(admin, orderId, 70_000, "Everything", "STORE");
        assertThat(tooMuch.statusCode()).isEqualTo(400);
        assertThat(tooMuch.<String>path("code")).isEqualTo("REFUND_AMOUNT_INVALID");
        assertThat(PlatformEvents.awaitEvent(this.platform, "payment", "foodstack.payment.RefundSucceeded.v1", orderId))
            .isNotBlank();

        // and the branch is owed its food less commission less the refund charged to it; the
        // coupon and the operator's refund are the operator's
        String from = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        String to = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Response report = admin.get("/api/v1/ledger/payouts?party=STORE&from=" + from + "&to=" + to);
            report.then().log().ifValidationFails().statusCode(200);
            List<Map<String, Object>> rows = report.path("rows");
            Map<String, Object> row = rows.stream().filter((r) -> store.id().equals(r.get("partyId"))).findFirst().orElse(null);
            assertThat(row).isNotNull();
            // food 85,000, less 15% commission (12,750), less the store's refund (10,000)
            assertThat(((Number) row.get("closingMinor")).longValue()).isEqualTo(85_000 - 12_750 - 10_000);
        });
    }

    private static String place(ApiClient customer, Store store, String method, String code, String key) {
        customer.postJson("/api/v1/carts/me/lines", Map.of("storeId", store.id(), "itemId", store.itemId(), "quantity", 1))
            .then().log().ifValidationFails().statusCode(200);
        Response placed = customer.request("/api/v1/orders").header("Idempotency-Key", key).contentType(ContentType.JSON)
            .body(Map.of("fulfilment", "PICKUP", "paymentMethod", method, "couponCode", code, "contactPhone", "+92 300 1112233"))
            .post("/api/v1/orders");
        placed.then().log().ifValidationFails().statusCode(201);
        return placed.path("orderId");
    }

    private static Response refund(ApiClient admin, String orderId, long amountMinor, String reason, String fundedBy) {
        return admin.postJson("/api/v1/orders/" + orderId + "/refund",
                Map.of("amountMinor", amountMinor, "reason", reason, "fundedBy", fundedBy));
    }

    private static void awaitStatus(ApiClient customer, String orderId, String status) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> assertThat(customer.get("/api/v1/orders/" + orderId).<String>path("status")).isEqualTo(status));
    }

    /** Settings changes, waiting until every module sees them (split into services, they keep settings up to 30 s). */
    static final class Settings {

        private Settings() {
        }

        static void ordering(FoodstackPlatform platform, ApiClient admin, Map<String, Object> changes) {
            orderingNow(admin, changes);
            settle(platform);
        }

        /** Changes the ordering settings without waiting; call {@link #settle(FoodstackPlatform)} after. */
        static void orderingNow(ApiClient admin, Map<String, Object> changes) {
            Response current = admin.get("/api/v1/settings/ordering");
            Map<String, Object> body = new LinkedHashMap<>(current.jsonPath().getMap("$"));
            body.remove("version");
            body.putAll(changes);
            admin.request("/api/v1/settings/ordering").header("If-Match", current.header("ETag")).contentType(ContentType.JSON)
                .body(body).put("/api/v1/settings/ordering").then().log().ifValidationFails().statusCode(200);
        }

        static void timezone(FoodstackPlatform platform, ApiClient admin, String timezone) {
            timezoneNow(admin, timezone);
            settle(platform);
        }

        /** Changes the deployment's time zone without waiting; call {@link #settle(FoodstackPlatform)} after. */
        static void timezoneNow(ApiClient admin, String timezone) {
            Response current = admin.get("/api/v1/settings/locale");
            Map<String, Object> body = new LinkedHashMap<>(current.jsonPath().getMap("$"));
            body.remove("version");
            body.remove("rtlLanguages");
            body.put("timezone", timezone);
            admin.request("/api/v1/settings/locale").header("If-Match", current.header("ETag")).contentType(ContentType.JSON)
                .body(body).put("/api/v1/settings/locale").then().log().ifValidationFails().statusCode(200);
        }

        /**
         * Split into services, other modules keep the settings they read for a while
         * ({@link FoodstackPlatform#SETTINGS_CACHE}, 30 s in production); in one process they read
         * them directly.
         */
        static void settle(FoodstackPlatform platform) {
            if (platform.topology() == Topology.MONOLITH) {
                return;
            }
            Duration cache = FoodstackPlatform.SETTINGS_CACHE;
            await().pollDelay(cache.plusMillis(500)).atMost(cache.plusSeconds(5)).until(() -> true);
        }
    }
}

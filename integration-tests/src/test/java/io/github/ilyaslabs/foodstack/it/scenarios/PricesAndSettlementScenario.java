package io.github.ilyaslabs.foodstack.it.scenarios;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.it.scenarios.CouponsAndRefundsScenario.Settings;
import io.github.ilyaslabs.foodstack.it.scenarios.MoneyAndTrustScenario.Store;
import io.github.ilyaslabs.foodstack.it.scenarios.MoneyAndTrustScenario.Stores;
import io.github.ilyaslabs.foodstack.it.support.ApiClient;
import io.github.ilyaslabs.foodstack.it.support.BothTopologies;
import io.github.ilyaslabs.foodstack.it.support.FoodstackPlatform;
import io.github.ilyaslabs.foodstack.it.support.Topology;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Branch prices and paying branches out (PRD 4.2.1 BB-6, 4.10): with price approval on, an owner's
 * new price waits until the operator approves it and customers keep the old one until then; a
 * rejected change never reaches them. Finance then settles the branch's balance for a finished
 * day: never today, never without a reference, never by support, and only once.
 *
 * <p>Settling is allowed up to yesterday in the deployment's time zone. So that the order's day is
 * already "yesterday" when the journey settles, the order is finished with the time zone at
 * UTC-11 (Pago Pago), and settled with it at UTC+14 (Kiritimati): the second is always at least a
 * day ahead of the first.
 */
@BothTopologies
class PricesAndSettlementScenario {

    private static final String BEHIND = "Pacific/Pago_Pago";

    private static final String AHEAD = "Pacific/Kiritimati";

    private final FoodstackPlatform platform;

    PricesAndSettlementScenario(Topology topology) {
        this.platform = FoodstackPlatform.of(topology);
    }

    @Test
    @DisplayName("an owner's price waits for approval; finance settles the branch's finished day once, with a reference")
    void pricesThenSettlement() {
        ApiClient admin = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);
        String timezone = admin.get("/api/v1/settings/locale").path("timezone");

        // given: price approval on, cards on the gateway's page, no fees or tax, and the day behind UTC
        Map<String, Object> ordering = new LinkedHashMap<>();
        ordering.put("cashEnabled", true);
        ordering.put("cardEnabled", true);
        ordering.put("cardStyle", "HOSTED_PAGE");
        ordering.put("serviceFeeMinor", 0);
        ordering.put("serviceFeeBasisPoints", 0);
        ordering.put("taxBasisPoints", 0);
        ordering.put("priceApprovalRequired", true);
        Settings.orderingNow(admin, ordering);
        Settings.timezoneNow(admin, BEHIND);
        Settings.settle(this.platform);
        try {
            journey(admin);
        }
        finally {
            Settings.orderingNow(admin, Map.of("priceApprovalRequired", false));
            Settings.timezoneNow(admin, timezone);
            Settings.settle(this.platform);
        }
    }

    private void journey(ApiClient admin) {
        Store store = Stores.live(admin, "Nihari House", "Johar Town");
        ApiClient owner = Stores.owner(this.platform, admin, store.id());
        ApiClient customer = Stores.customer(this.platform, "Hina Customer").client();
        String itemPath = "/api/v1/stores/" + store.id() + "/menu/items/" + store.itemId() + "/price";

        // when: the owner raises the dish from Rs 850 to Rs 900, it waits for the operator
        Response asked = owner.request(itemPath).contentType(ContentType.JSON).body(Map.of("priceMinor", 90_000)).put(itemPath);
        asked.then().log().ifValidationFails().statusCode(200);
        assertThat(asked.<String>path("outcome")).isEqualTo("PENDING");
        Response mine = owner.get("/api/v1/stores/" + store.id() + "/menu/prices");
        assertThat(mine.<List<Object>>path("prices")).isEmpty();
        assertThat(mine.<Integer>path("pending[0].proposedMinor")).isEqualTo(90_000);

        // then: customers still pay the old price
        Response cart = customer.postJson("/api/v1/carts/me/lines",
                Map.of("storeId", store.id(), "itemId", store.itemId(), "quantity", 1));
        cart.then().log().ifValidationFails().statusCode(200);
        assertThat(cart.<Integer>path("subtotalMinor")).isEqualTo(85_000);

        // when: the operator approves it, customers see the new price
        String raise = pendingFor(admin, store.id());
        Response approved = admin.postJson("/api/v1/price-changes/" + raise + "/approve", Map.of());
        approved.then().log().ifValidationFails().statusCode(200);
        assertThat(approved.<String>path("status")).isEqualTo("APPROVED");
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(customer.get("/api/v1/carts/me").<Integer>path("subtotalMinor")).isEqualTo(90_000));
        assertThat(owner.get("/api/v1/stores/" + store.id() + "/menu/prices").<Integer>path("prices[0].priceMinor"))
            .isEqualTo(90_000);

        // and a second raise the operator rejects never reaches them
        owner.request(itemPath).contentType(ContentType.JSON).body(Map.of("priceMinor", 95_000)).put(itemPath)
            .then().statusCode(200);
        Response rejected = admin.postJson("/api/v1/price-changes/" + pendingFor(admin, store.id()) + "/reject", Map.of());
        rejected.then().log().ifValidationFails().statusCode(200);
        assertThat(rejected.<String>path("status")).isEqualTo("REJECTED");
        assertThat(owner.get("/api/v1/stores/" + store.id() + "/menu/prices").<List<Object>>path("pending")).isEmpty();
        assertThat(customer.get("/api/v1/carts/me").<Integer>path("subtotalMinor")).isEqualTo(90_000);

        // when: the customer pays Rs 900 by card and the store hands the order over
        Response placed = customer.request("/api/v1/orders").header("Idempotency-Key", "prc-card").contentType(ContentType.JSON)
            .body(Map.of("fulfilment", "PICKUP", "paymentMethod", "CARD", "contactPhone", "+92 300 1112233"))
            .post("/api/v1/orders");
        placed.then().log().ifValidationFails().statusCode(201);
        String orderId = placed.path("orderId");
        Response order = customer.get("/api/v1/orders/" + orderId);
        assertThat(order.<Integer>path("totals.totalMinor")).isEqualTo(90_000);
        Response paid = RestAssured.given().redirects().follow(false).formParam("outcome", "pay")
            .post(order.<String>path("paymentUrl"));
        assertThat(paid.statusCode()).isEqualTo(303);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> assertThat(customer.get("/api/v1/orders/" + orderId).<String>path("status"))
                .isEqualTo("CONFIRMED"));
        owner.postJson("/api/v1/orders/" + orderId + "/actions/accept", Map.of("prepMinutes", 10)).then().statusCode(200);
        owner.postJson("/api/v1/orders/" + orderId + "/actions/ready", Map.of()).then().statusCode(200);
        owner.postJson("/api/v1/orders/" + orderId + "/actions/collected", Map.of()).then().statusCode(200);

        // then: the branch is owed Rs 900 less 15% commission, on the order's day
        String accountId = accountOf(admin, store.id());
        String day = await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() -> {
            Response statement = admin.get("/api/v1/ledger/accounts/" + accountId + "/statement");
            List<Map<String, Object>> lines = statement.path("lines");
            return lines.stream().filter((l) -> orderId.equals(l.get("orderId"))).map((l) -> (String) l.get("day")).findFirst()
                .orElse(null);
        }, (found) -> found != null);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(balance(admin, accountId)).isEqualTo(90_000 - 13_500));

        // and today cannot be settled, nor without a reference, nor by support
        String today = LocalDate.now(ZoneId.of(BEHIND)).toString();
        assertRefused(settle(admin, accountId, today, "BANK-1"), 400, "SETTLEMENT_PERIOD_OPEN");
        assertRefused(settle(admin, accountId, day, " "), 400, "SETTLEMENT_REFERENCE_REQUIRED");
        assertRefused(settle(support(admin), accountId, day, "BANK-1"), 403, "LEDGER_ACCESS_DENIED");

        // when: the order's day is over, finance settles it with the bank reference
        Settings.timezone(this.platform, admin, AHEAD);
        Response settled = settle(admin, accountId, day, "BANK-20261108");
        settled.then().log().ifValidationFails().statusCode(200);
        assertThat(settled.<Integer>path("amountMinor")).isEqualTo(76_500);
        assertThat(settled.<String>path("periodTo")).isEqualTo(day);
        assertThat(balance(admin, accountId)).isZero();

        // then: the owner sees it, and the same day cannot be settled twice nor an earlier one
        Response theirs = owner.get("/api/v1/ledger/settlements?accountId=" + accountId);
        theirs.then().log().ifValidationFails().statusCode(200);
        assertThat(theirs.<List<String>>path("reference")).containsExactly("BANK-20261108");
        assertRefused(settle(admin, accountId, day, "BANK-2"), 409, "NOTHING_TO_SETTLE");
        assertRefused(settle(admin, accountId, LocalDate.parse(day).minusDays(1).toString(), "BANK-3"), 409,
                "SETTLEMENT_PERIOD_SETTLED");
    }

    private static String pendingFor(ApiClient admin, String storeId) {
        Response pending = admin.get("/api/v1/price-changes");
        pending.then().log().ifValidationFails().statusCode(200);
        List<Map<String, Object>> changes = pending.path("$");
        return changes.stream().filter((c) -> storeId.equals(c.get("storeId"))).map((c) -> (String) c.get("id")).findFirst()
            .orElseThrow();
    }

    private static String accountOf(ApiClient admin, String storeId) {
        String from = LocalDate.now(ZoneId.of(BEHIND)).minusDays(2).toString();
        String to = LocalDate.now(ZoneId.of(AHEAD)).plusDays(1).toString();
        return await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() -> {
            List<Map<String, Object>> rows = admin.get("/api/v1/ledger/payouts?party=STORE&from=" + from + "&to=" + to)
                .path("rows");
            return rows.stream().filter((r) -> storeId.equals(r.get("partyId"))).map((r) -> (String) r.get("accountId"))
                .findFirst().orElse(null);
        }, (found) -> found != null);
    }

    private static long balance(ApiClient admin, String accountId) {
        return admin.get("/api/v1/ledger/accounts/" + accountId + "/statement").<Number>path("balanceMinor").longValue();
    }

    private static Response settle(ApiClient client, String accountId, String to, String reference) {
        return client.postJson("/api/v1/ledger/settlements", Map.of("accountId", accountId, "to", to, "reference", reference));
    }

    private static void assertRefused(Response response, int status, String code) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
        assertThat(response.<String>path("code")).isEqualTo(code);
    }

    private ApiClient support(ApiClient admin) {
        String email = "support-" + System.nanoTime() + "@it.foodstack.local";
        admin.postJson("/api/v1/users", Map.of("email", email, "password", "support-password-1", "displayName", "Sana Support",
                "roles", Set.of("SUPPORT")))
            .then().log().ifValidationFails().statusCode(201);
        return this.platform.client().logIn(email, "support-password-1");
    }
}

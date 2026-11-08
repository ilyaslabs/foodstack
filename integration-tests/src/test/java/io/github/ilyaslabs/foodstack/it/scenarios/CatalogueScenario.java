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
 * M1 journey (PRD 7, exit criterion): the operator onboards a restaurant with a menu in English
 * and Arabic, draws its delivery area and makes it live; its owner signs in and manages only their
 * branch; a customer at an address inside the area finds it by an Arabic dish name and reads its
 * menu in both languages.
 */
abstract class CatalogueScenario {

    // A customer in Gulberg, Lahore, 1 km from the restaurant
    private static final double CUSTOMER_LAT = 31.5160;

    private static final double CUSTOMER_LNG = 74.3430;

    private final FoodstackPlatform platform;

    CatalogueScenario(Topology topology) {
        this.platform = FoodstackPlatform.of(topology);
    }

    @Test
    @DisplayName("a customer finds a live restaurant near them and sees its menu in English and Arabic (right to left)")
    void customerFindsAStoreAndItsMenu() {
        ApiClient admin = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);

        // given: the platform speaks English, Arabic and Urdu
        String localeTag = this.platform.client().get("/api/v1/settings/locale").header("ETag");
        admin.request("/api/v1/settings/locale").header("If-Match", localeTag).contentType(ContentType.JSON)
            .body(Map.of("defaultLanguage", "en", "languages", List.of("en", "ar", "ur"), "currency", "PKR",
                    "timezone", "Asia/Karachi"))
            .put("/api/v1/settings/locale").then().log().ifValidationFails().statusCode(200);
        assertThat(this.platform.client().get("/api/v1/settings/locale").<List<String>>path("rtlLanguages"))
            .contains("ar", "ur");
        // split into services, the others keep the settings they read for a short while (an earlier
        // journey may just have read them)
        CouponsAndRefundsScenario.Settings.settle(this.platform);

        // and the operator onboards a restaurant
        Response onboarded = admin.postJson("/api/v1/stores", onboarding());
        onboarded.then().log().ifValidationFails().statusCode(201);
        String storeId = onboarded.path("id");
        String brandId = onboarded.path("brand.id");
        assertThat(onboarded.<String>path("status")).isEqualTo("DRAFT");
        assertThat(admin.get("/api/v1/stores/" + storeId + "/readiness").<List<String>>path("missing"))
            .containsExactly("MENU");
        assertThat(this.platform.client().get("/api/v1/stores/" + storeId).statusCode()).isEqualTo(404);
        // the operator may move the brand up in search (TDD 11), 0 to 3
        admin.request("/api/v1/brands/" + brandId + "/search-boost").contentType("application/json").body(Map.of("level", 2))
            .put("/api/v1/brands/" + brandId + "/search-boost").then().log().ifValidationFails().statusCode(200);
        assertThat(admin.get("/api/v1/brands/" + brandId + "/search-boost").<Integer>path("level")).isEqualTo(2);
        assertThat(admin.request("/api/v1/brands/" + brandId + "/search-boost").contentType("application/json").body(Map.of("level", 4))
            .put("/api/v1/brands/" + brandId + "/search-boost").statusCode()).isEqualTo(400);

        // and builds its menu in two languages
        Response section = admin.postJson("/api/v1/brands/" + brandId + "/menu/sections",
                Map.of("name", Map.of("en", "Rice", "ar", "أرز")));
        section.then().log().ifValidationFails().statusCode(201);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sectionId", section.path("id"));
        item.put("name", Map.of("en", "Chicken Biryani", "ar", "برياني دجاج"));
        item.put("description", Map.of("en", "Basmati rice, chicken, saffron", "ar", "أرز بسمتي ودجاج وزعفران"));
        item.put("priceMinor", 85_000);
        item.put("dietaryTags", List.of("halal", "spicy"));
        item.put("modifierGroups", List.of(Map.of("name", Map.of("en", "Size", "ar", "الحجم"), "minSelect", 1, "maxSelect", 1,
                "options", List.of(Map.of("name", Map.of("en", "Half", "ar", "نصف"), "priceDeltaMinor", 0),
                        Map.of("name", Map.of("en", "Full", "ar", "كامل"), "priceDeltaMinor", 40_000)))));
        item.put("sortOrder", 0);
        Response created = admin.postJson("/api/v1/brands/" + brandId + "/menu/items", item);
        created.then().log().ifValidationFails().statusCode(201);
        String itemId = created.path("id");

        // and a 3 km delivery ring, hours round the clock, then makes it live
        admin.request("/api/v1/zones/stores/" + storeId).contentType(ContentType.JSON)
            .body(Map.of("zones", List.of(Map.of("name", "Up to 3 km", "center", Map.of("lat", 31.5250, "lng", 74.3430),
                    "radiusMeters", 3000, "chargeMinor", 10_000, "minOrderMinor", 50_000))))
            .put("/api/v1/zones/stores/" + storeId).then().log().ifValidationFails().statusCode(200);
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

        // when: a customer nearby searches by an Arabic dish name
        ApiClient customer = this.platform.client();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Response found = customer.request("/api/v1/search/stores").queryParam("lat", CUSTOMER_LAT)
                .queryParam("lng", CUSTOMER_LNG).queryParam("q", "برياني").get("/api/v1/search/stores");
            found.then().statusCode(200);
            assertThat(found.<List<String>>path("stores.storeId")).contains(storeId);
        });

        // then: the store page, the menu in both languages and a delivery quote
        Response page = customer.get("/api/v1/stores/" + storeId);
        page.then().statusCode(200);
        assertThat(page.<String>path("displayName")).isEqualTo("Biryani House - Gulberg");
        assertThat(page.<Boolean>path("openNow")).isTrue();
        Response menu = customer.get("/api/v1/stores/" + storeId + "/menu");
        menu.then().statusCode(200);
        assertThat(menu.<String>path("sections[0].name.ar")).isEqualTo("أرز");
        assertThat(menu.<String>path("sections[0].items[0].name.en")).isEqualTo("Chicken Biryani");
        assertThat(menu.<String>path("sections[0].items[0].name.ar")).isEqualTo("برياني دجاج");
        assertThat(menu.<String>path("sections[0].items[0].modifierGroups[0].options[1].name.ar")).isEqualTo("كامل");
        assertThat(menu.<String>path("currency")).isEqualTo("PKR");
        Response quote = customer.request("/api/v1/stores/" + storeId + "/delivery-quote").queryParam("lat", CUSTOMER_LAT)
            .queryParam("lng", CUSTOMER_LNG).get("/api/v1/stores/" + storeId + "/delivery-quote");
        assertThat(quote.<Boolean>path("deliverable")).isTrue();
        assertThat(quote.<Integer>path("chargeMinor")).isEqualTo(10_000);
        Response farAway = customer.request("/api/v1/search/stores").queryParam("lat", 24.86).queryParam("lng", 67.00)
            .get("/api/v1/search/stores");
        assertThat(farAway.<List<String>>path("stores.storeId")).doesNotContain(storeId);

        assertThat(PlatformEvents.awaitEvent(this.platform, "store", "foodstack.store.StoreChanged.v1", storeId)).isNotBlank();
        assertThat(PlatformEvents.awaitEvent(this.platform, "geo", "foodstack.geo.ZoneChanged.v1", storeId)).isNotBlank();

        // and the owner signs in, manages only their branch, marks the dish sold out and closes for a while
        String ownerEmail = "owner-" + System.nanoTime() + "@it.foodstack.local";
        admin.postJson("/api/v1/stores/" + storeId + "/owners",
                Map.of("email", ownerEmail, "displayName", "Sana Owner", "initialPassword", "owner-password-1"))
            .then().log().ifValidationFails().statusCode(201);
        ApiClient owner = this.platform.client().logIn(ownerEmail, "owner-password-1");
        assertThat(owner.get("/api/v1/me").<List<String>>path("roles")).contains("STORE_OWNER");
        assertThat(owner.get("/api/v1/stores").<List<String>>path("items.id")).containsExactly(storeId);
        assertThat(owner.postJson("/api/v1/stores", onboarding()).statusCode()).isEqualTo(403);
        owner.request("/api/v1/stores/" + storeId + "/menu/items/" + itemId + "/sold-out").contentType(ContentType.JSON)
            .body(Map.of("soldOut", true)).put("/api/v1/stores/" + storeId + "/menu/items/" + itemId + "/sold-out")
            .then().log().ifValidationFails().statusCode(204);
        assertThat(customer.get("/api/v1/stores/" + storeId + "/menu").<Boolean>path("sections[0].items[0].soldOut")).isTrue();
        owner.request("/api/v1/stores/" + storeId + "/availability").contentType(ContentType.JSON)
            .body(Map.of("temporarilyClosed", true, "reason", "Back at 7 pm"))
            .put("/api/v1/stores/" + storeId + "/availability").then().log().ifValidationFails().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Response found = customer.request("/api/v1/search/stores").queryParam("lat", CUSTOMER_LAT)
                .queryParam("lng", CUSTOMER_LNG).get("/api/v1/search/stores");
            assertThat(found.<List<Boolean>>path("stores.findAll { it.storeId == '" + storeId + "' }.openNow"))
                .containsExactly(false);
        });
    }

    private static Map<String, Object> onboarding() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brandName", "Biryani House");
        body.put("branchName", "Gulberg");
        body.put("legalName", "Biryani House (Pvt) Ltd");
        body.put("description", Map.of("en", "Slow-cooked biryani", "ar", "برياني مطبوخ على مهل"));
        body.put("cuisines", List.of("pakistani", "rice"));
        body.put("address", Map.of("line1", "12 Main Boulevard", "area", "Gulberg", "city", "Lahore"));
        body.put("location", Map.of("lat", 31.5250, "lng", 74.3430));
        body.put("pickupEnabled", true);
        body.put("prepMinutes", 25);
        body.put("commission", Map.of("percentBasisPoints", 1500, "fixedMinor", 0));
        return body;
    }
}

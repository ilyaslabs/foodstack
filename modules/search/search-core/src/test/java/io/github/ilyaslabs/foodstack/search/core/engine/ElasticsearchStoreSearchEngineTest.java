package io.github.ilyaslabs.foodstack.search.core.engine;

import java.util.List;
import java.util.Map;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.foodstack.search.api.CuisineCount;
import io.github.ilyaslabs.foodstack.search.api.Fulfilment;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchQuery;
import io.github.ilyaslabs.foodstack.search.core.SearchProperties;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** The engine against a real Elasticsearch 9 (lowest layer, real infrastructure). */
@SpringBootTest(classes = ElasticsearchStoreSearchEngineTest.App.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElasticsearchStoreSearchEngineTest {

    // Customer in Gulberg, Lahore
    private static final double LAT = 31.5160;

    private static final double LNG = 74.3430;

    @SpringBootConfiguration
    @ImportAutoConfiguration({ JacksonAutoConfiguration.class, ElasticsearchRestClientAutoConfiguration.class,
            ElasticsearchClientAutoConfiguration.class })
    @EnableConfigurationProperties(SearchProperties.class)
    static class App {

        @Bean
        ElasticsearchStoreSearchEngine engine(ElasticsearchClient client, JsonMapper jsonMapper, SearchProperties properties) {
            return new ElasticsearchStoreSearchEngine(client, jsonMapper, properties);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerElasticsearch(registry);
    }

    @Autowired
    ElasticsearchStoreSearchEngine engine;

    @Autowired
    ElasticsearchClient client;

    @Autowired
    JsonMapper jsonMapper;

    @BeforeAll
    void indexCatalogue() throws Exception {
        assertThat(this.engine.ensureIndex()).isTrue();
        assertThat(this.engine.ensureIndex()).isFalse();
        // Two branches of one brand around the customer, the nearer one 1 km away
        this.engine.index(store("spice-gulberg", "spice", "Spice Route", "Gulberg", List.of("pakistani", "bbq"),
                new GeoPoint(31.5250, 74.3430), ring(31.5250, 74.3430, 1), Map.of("en", List.of("Chicken Biryani"),
                        "ar", List.of("برياني دجاج"), "ur", List.of("چکن بریانی")), false));
        this.engine.index(store("spice-dha", "spice", "Spice Route", "DHA", List.of("pakistani", "bbq"),
                new GeoPoint(31.4800, 74.3800), ring(31.4800, 74.3800, 2.5), Map.of("en", List.of("Chicken Biryani")), false));
        this.engine.index(store("napoli-dha", "napoli", "Napoli Pizza", "DHA", List.of("italian", "pizza"),
                new GeoPoint(31.5000, 74.3500), ring(31.5000, 74.3500, 1), Map.of("en", List.of("Margherita"),
                        "ar", List.of("مارغريتا")), true));
        // Delivers elsewhere only, but offers pickup and is 2 km away
        this.engine.index(store("far-zone", "far", "Karachi Grill", null, List.of("bbq"), new GeoPoint(31.5340, 74.3430),
                List.of(List.of(new GeoPoint(24.80, 67.00), new GeoPoint(24.80, 67.10), new GeoPoint(24.90, 67.05))),
                Map.of("en", List.of("Seekh Kebab")), true));
        this.engine.delete("never-indexed");
        this.client.indices().refresh((r) -> r.index(this.engine.alias()));
    }

    @Test
    @DisplayName("an event indexed before start-up finishes creates the mapped index, and a stray unmapped index is replaced")
    void earlyWrite() throws Exception {
        // given: an older version let a write auto-create a plain index under the alias name
        ElasticsearchStoreSearchEngine early = new ElasticsearchStoreSearchEngine(this.client, this.jsonMapper,
                new SearchProperties("early_", 0, 15_000, null));
        this.client.indices().create((c) -> c.index(early.alias()));

        // when: a catalogue event arrives before the bootstrap ran
        early.index(store("early-1", "early", "Early Bird", null, List.of("bbq"), new GeoPoint(LAT, LNG),
                ring(LAT, LNG, 2), Map.of("en", List.of("Seekh Kebab")), false));
        this.client.indices().refresh((r) -> r.index(early.alias()));

        // then: the alias points at the mapped index, geo search works, and the bootstrap still rebuilds once
        assertThat(this.client.indices().existsAlias((e) -> e.name(early.alias())).value()).isTrue();
        assertThat(early.search(query(null, null, Fulfilment.DELIVERY)).hits())
            .extracting((h) -> h.store().storeId()).containsExactly("early-1");
        assertThat(early.ensureIndex()).isTrue();
        assertThat(early.ensureIndex()).isFalse();
    }

    @Test
    @DisplayName("ranks open branches before closed ones across pages, not just within one page")
    void openFirstAcrossPages() throws Exception {
        // given: a closed branch right next to the customer and an open one further away
        ElasticsearchStoreSearchEngine engine = new ElasticsearchStoreSearchEngine(this.client, this.jsonMapper,
                new SearchProperties("openfirst_", 0, 15_000, null));
        assertThat(engine.ensureIndex()).isTrue();
        IndexedStore open = store("open-far", "open", "Open Kitchen", null, List.of("bbq"), new GeoPoint(LAT + 0.02, LNG),
                ring(LAT, LNG, 2), Map.of(), false);
        IndexedStore closed = store("closed-near", "closed", "Closed Kitchen", null, List.of("bbq"), new GeoPoint(LAT, LNG),
                ring(LAT, LNG, 2), Map.of(), false);
        engine.index(open);
        engine.index(new IndexedStore(closed.storeId(), closed.slug(), closed.brandId(), closed.brandName(), closed.branchName(),
                closed.branchCount(), closed.description(), closed.cuisines(), closed.logoUrls(), closed.coverUrls(),
                closed.location(), closed.area(), closed.city(), closed.deliveryMode(), closed.pickupEnabled(), true,
                closed.prepMinutes(), closed.timezone(), closed.openingHours(), closed.closedDates(), closed.dishNames(),
                closed.dietaryTags(), closed.deliveryArea(), closed.version()));
        this.client.indices().refresh((r) -> r.index(engine.alias()));

        // when: one result per page
        StoreSearchEngine.Result first = engine.search(new StoreSearchQuery(LAT, LNG, null, null, Fulfilment.DELIVERY, 0, 1));
        StoreSearchEngine.Result second = engine.search(new StoreSearchQuery(LAT, LNG, null, null, Fulfilment.DELIVERY, 1, 1));

        // then
        assertThat(first.hits()).extracting((h) -> h.store().storeId()).containsExactly("open-far");
        assertThat(first.hits().getFirst().distanceMeters()).isBetween(2000L, 2400L);
        assertThat(second.hits()).extracting((h) -> h.store().storeId()).containsExactly("closed-near");
    }

    @Test
    @DisplayName("among open branches about as near, the better rated ranks first, and an operator boost lifts a brand")
    void ratingAndBoost() throws Exception {
        // given: three open branches 1.5 to 2 km away; one well rated with many reviews, one poorly
        ElasticsearchStoreSearchEngine engine = new ElasticsearchStoreSearchEngine(this.client, this.jsonMapper,
                new SearchProperties("ranking_", 0, 15_000, null));
        assertThat(engine.ensureIndex()).isTrue();
        IndexedStore loved = rated(store("loved", "loved", "Loved Grill", null, List.of("bbq"),
                new GeoPoint(LAT + 0.018, LNG), ring(LAT, LNG, 3), Map.of(), false), 120, 4.8, 0);
        IndexedStore poor = rated(store("poor", "poor", "Poor Grill", null, List.of("bbq"),
                new GeoPoint(LAT + 0.0135, LNG), ring(LAT, LNG, 3), Map.of(), false), 40, 2.1, 0);
        IndexedStore fresh = rated(store("fresh", "fresh", "Fresh Grill", null, List.of("bbq"),
                new GeoPoint(LAT + 0.016, LNG), ring(LAT, LNG, 3), Map.of(), false), 1, 5.0, 0);
        for (IndexedStore s : List.of(loved, poor, fresh)) {
            engine.index(s);
        }
        this.client.indices().refresh((r) -> r.index(engine.alias()));

        // when
        StoreSearchEngine.Result before = engine.search(new StoreSearchQuery(LAT, LNG, null, null, Fulfilment.DELIVERY, 0, 10));
        engine.index(rated(poor, 40, 2.1, 3));
        this.client.indices().refresh((r) -> r.index(engine.alias()));
        StoreSearchEngine.Result boosted = engine.search(new StoreSearchQuery(LAT, LNG, null, null, Fulfilment.DELIVERY, 0, 10));

        // then: many good reviews beat one perfect review; the nearest but poorly rated comes last
        assertThat(before.hits()).extracting((h) -> h.store().storeId()).containsExactly("loved", "fresh", "poor");
        assertThat(boosted.hits()).extracting((h) -> h.store().storeId()).startsWith("poor");
        assertThat(boosted.hits().getFirst().store().searchBoost()).isEqualTo(3);
    }

    @Test
    @DisplayName("a rating counts in proportion to its reviews")
    void ratingScore() {
        assertThat(ElasticsearchStoreSearchEngine.ratingScore(0, 0)).isEqualTo(4.0);
        assertThat(ElasticsearchStoreSearchEngine.ratingScore(1, 5)).isCloseTo(4.17, org.assertj.core.data.Offset.offset(0.01));
        assertThat(ElasticsearchStoreSearchEngine.ratingScore(120, 4.8)).isCloseTo(4.77, org.assertj.core.data.Offset.offset(0.01));
        assertThat(ElasticsearchStoreSearchEngine.rankFactor(5, 3)).isEqualTo(2.0);
        assertThat(ElasticsearchStoreSearchEngine.rankFactor(0, 0)).isEqualTo(0.5);
    }

    private static IndexedStore rated(IndexedStore s, long count, double average, int boost) {
        return new IndexedStore(s.storeId(), s.slug(), s.brandId(), s.brandName(), s.branchName(), s.branchCount(),
                s.description(), s.cuisines(), s.logoUrls(), s.coverUrls(), s.location(), s.area(), s.city(),
                s.deliveryMode(), s.pickupEnabled(), s.temporarilyClosed(), s.prepMinutes(), s.timezone(),
                s.openingHours(), s.closedDates(), s.dishNames(), s.dietaryTags(), s.deliveryArea(), s.version() + boost + 1,
                count, average, boost);
    }

    @Test
    @DisplayName("a new mapping version takes over the alias and drops the older index")
    void mappingUpgrade() throws Exception {
        // given: an index of the previous version holding the alias
        ElasticsearchStoreSearchEngine engine = new ElasticsearchStoreSearchEngine(this.client, this.jsonMapper,
                new SearchProperties("upgrade_", 0, 15_000, null));
        String old = engine.alias() + "_v1";
        this.client.indices().create((c) -> c.index(old).aliases(engine.alias(), (a) -> a.isWriteIndex(true)));

        // when
        assertThat(engine.ensureIndex()).isTrue();

        // then
        assertThat(this.client.indices().exists((e) -> e.index(old)).value()).isFalse();
        assertThat(this.client.indices().getAlias((g) -> g.name(engine.alias())).aliases()).containsOnlyKeys(engine.indexName());
    }

    @Test
    @DisplayName("finds branches whose delivery area contains the customer, nearest branch per brand, nearest first; chips count branches")
    void deliversHere() {
        StoreSearchEngine.Result result = this.engine.search(query(null, null, Fulfilment.DELIVERY));

        assertThat(result.hits()).extracting((h) -> h.store().storeId()).containsExactly("spice-gulberg", "napoli-dha");
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.hits().getFirst().distanceMeters()).isBetween(950L, 1050L);
        IndexedStore first = result.hits().getFirst().store();
        assertThat(first.openingHours()).containsKey("MONDAY");
        assertThat(first.openingHours().get("MONDAY")).containsExactly(new TimeRange("00:00", "23:59"));
        assertThat(first.location().lat()).isEqualTo(31.5250);
        assertThat(first.cuisines()).containsExactly("pakistani", "bbq");
        assertThat(result.cuisines()).contains(new CuisineCount("pakistani", 2), new CuisineCount("italian", 1));
    }

    @Test
    @DisplayName("matches dish names in Arabic and Urdu across both alphabets, and brand names with a typo")
    void text() {
        assertThat(this.engine.search(query("برياني", null, Fulfilment.DELIVERY)).hits())
            .extracting((h) -> h.store().storeId()).containsExactly("spice-gulberg");
        assertThat(this.engine.search(query("بریانی", null, Fulfilment.DELIVERY)).hits())
            .extracting((h) -> h.store().storeId()).containsExactly("spice-gulberg");
        // Arabic kaf typed for the Urdu keheh in "چکن": Persian/Urdu letters are normalized to Arabic ones
        assertThat(this.engine.search(query("چكن", null, Fulfilment.DELIVERY)).hits())
            .extracting((h) -> h.store().storeId()).containsExactly("spice-gulberg");
        assertThat(this.engine.search(query("napoly", null, Fulfilment.DELIVERY)).hits())
            .extracting((h) -> h.store().storeId()).containsExactly("napoli-dha");
        assertThat(this.engine.search(query("piz", null, Fulfilment.DELIVERY)).hits())
            .extracting((h) -> h.store().storeId()).containsExactly("napoli-dha");
    }

    @Test
    @DisplayName("filters by cuisine after counting, so the chips still show every cuisine")
    void cuisine() {
        StoreSearchEngine.Result result = this.engine.search(query(null, "italian", Fulfilment.DELIVERY));

        assertThat(result.hits()).extracting((h) -> h.store().storeId()).containsExactly("napoli-dha");
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.cuisines()).extracting(CuisineCount::cuisine).contains("pakistani", "italian");
    }

    @Test
    @DisplayName("pickup lists nearby branches that offer pickup, wherever they deliver")
    void pickup() {
        StoreSearchEngine.Result result = this.engine.search(query(null, null, Fulfilment.PICKUP));

        assertThat(result.hits()).extracting((h) -> h.store().storeId()).containsExactly("napoli-dha", "far-zone");
    }

    @Test
    @DisplayName("finds the indexed branches of a brand and of a delivery mode, and removes a branch")
    void idsAndDelete() throws Exception {
        assertThat(this.engine.storeIdsOfBrand("spice")).containsExactlyInAnyOrder("spice-gulberg", "spice-dha");
        assertThat(this.engine.storeIdsWithDeliveryMode("OPERATOR")).containsExactlyInAnyOrder("napoli-dha", "far-zone");

        this.engine.index(store("temp", "temp", "Temp", null, List.of(), new GeoPoint(31.5, 74.3), List.of(), Map.of(), false));
        this.engine.delete("temp");
        this.client.indices().refresh((r) -> r.index(this.engine.alias()));
        assertThat(this.engine.storeIdsOfBrand("temp")).isEmpty();
    }

    @Test
    @DisplayName("a draft branch removed several times before it goes live is still indexed when it does")
    void deletesBeforeFirstIndex() throws Exception {
        // given: each change while the branch is a draft removes it from the index
        for (int i = 0; i < 5; i++) {
            this.engine.delete("draft-then-live", 1);
        }

        // when: it goes live at version 2
        this.engine.index(withVersion(store("draft-then-live", "dtl", "Draft Kitchen", null, List.of("bbq"),
                new GeoPoint(31.5, 74.3), List.of(), Map.of(), false), "Draft Kitchen", 2));
        this.client.indices().refresh((r) -> r.index(this.engine.alias()));

        // and a late read of the draft does not remove it again
        this.engine.delete("draft-then-live", 1);
        this.client.indices().refresh((r) -> r.index(this.engine.alias()));

        // then
        assertThat(this.engine.storeIdsOfBrand("dtl")).containsExactly("draft-then-live");
    }

    @Test
    @DisplayName("delivery search stays within the discovery radius even when a far branch's zone covers the customer")
    void discoveryRadius() throws Exception {
        // given: a branch 12 km away (straight line) whose big zone reaches the customer
        ElasticsearchStoreSearchEngine engine = new ElasticsearchStoreSearchEngine(this.client, this.jsonMapper,
                new SearchProperties("radius_", 0, 15_000, null));
        engine.index(store("far-big-zone", "far", "Far Kitchen", null, List.of("bbq"), new GeoPoint(LAT + 0.108, LNG),
                ring(LAT + 0.108, LNG, 5), Map.of(), false));
        engine.index(store("near", "near", "Near Kitchen", null, List.of("bbq"), new GeoPoint(LAT + 0.01, LNG),
                ring(LAT + 0.01, LNG, 2), Map.of(), false));
        this.client.indices().refresh((r) -> r.index(engine.alias()));

        // when: 8 km by road (6.2 km straight), then 25 km by road
        List<String> first = engine.search(new StoreSearchQuery(LAT, LNG, null, null, Fulfilment.DELIVERY, 0, 20, 8000))
            .hits().stream().map((h) -> h.store().storeId()).toList();
        List<String> wider = engine.search(new StoreSearchQuery(LAT, LNG, null, null, Fulfilment.DELIVERY, 0, 20, 25_000))
            .hits().stream().map((h) -> h.store().storeId()).toList();

        // then
        assertThat(first).containsExactly("near");
        assertThat(wider).containsExactly("near", "far-big-zone");
    }

    @Test
    @DisplayName("writes carry the branch version: a late, older read never overwrites a newer one")
    void versionedWrites() throws Exception {
        // given
        ElasticsearchStoreSearchEngine engine = new ElasticsearchStoreSearchEngine(this.client, this.jsonMapper,
                new SearchProperties("versions_", 0, 15_000, null));
        IndexedStore base = store("v-1", "ver", "Version Kitchen", null, List.of("bbq"), new GeoPoint(LAT, LNG),
                ring(LAT, LNG, 2), Map.of(), false);

        // when: version 5 lands, then a late version 4, then version 5 again (a rebuild)
        engine.index(withVersion(base, "Fresh", 5));
        engine.index(withVersion(base, "Stale", 4));
        engine.index(withVersion(base, "Rebuilt", 5));
        this.client.indices().refresh((r) -> r.index(engine.alias()));

        // then
        assertThat(engine.search(new StoreSearchQuery(LAT, LNG, null, null, Fulfilment.DELIVERY, 0, 20, 8000)).hits())
            .extracting((h) -> h.store().brandName()).containsExactly("Rebuilt");
    }

    @Test
    @DisplayName("lists every branch of a brand, past one page of ids")
    void allIdsOfABrand() throws Exception {
        ElasticsearchStoreSearchEngine engine = new ElasticsearchStoreSearchEngine(this.client, this.jsonMapper,
                new SearchProperties("ids_", 0, 15_000, null));
        engine.ensureIndex();
        co.elastic.clients.elasticsearch.core.BulkRequest.Builder bulk = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();
        int count = ElasticsearchStoreSearchEngine.ID_PAGE + 5;
        for (int i = 0; i < count; i++) {
            String id = "chain-" + i;
            bulk.operations((o) -> o.index((x) -> x.index(engine.alias()).id(id).document(Map.of("brandId", "chain"))));
        }
        this.client.bulk(bulk.refresh(co.elastic.clients.elasticsearch._types.Refresh.True).build());

        assertThat(engine.storeIdsOfBrand("chain")).hasSize(count).doesNotHaveDuplicates();
    }

    private static IndexedStore withVersion(IndexedStore s, String brandName, long version) {
        return new IndexedStore(s.storeId(), s.slug(), s.brandId(), brandName, s.branchName(), s.branchCount(), s.description(),
                s.cuisines(), s.logoUrls(), s.coverUrls(), s.location(), s.area(), s.city(), s.deliveryMode(), s.pickupEnabled(),
                s.temporarilyClosed(), s.prepMinutes(), s.timezone(), s.openingHours(), s.closedDates(), s.dishNames(),
                s.dietaryTags(), s.deliveryArea(), version);
    }

    private static StoreSearchQuery query(String text, String cuisine, Fulfilment fulfilment) {
        return new StoreSearchQuery(LAT, LNG, text, cuisine, fulfilment, 0, 20);
    }

    /** A ring of scale times 3 km around a point, as geo sends delivery areas (open, without the closing point). */
    private static List<List<GeoPoint>> ring(double lat, double lng, double scale) {
        List<GeoPoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < 24; i++) {
            double angle = 2 * Math.PI * i / 24;
            points.add(new GeoPoint(lat + 0.027 * scale * Math.cos(angle), lng + 0.0316 * scale * Math.sin(angle)));
        }
        return List.of(points);
    }

    private static IndexedStore store(String id, String brandId, String brandName, String branchName, List<String> cuisines,
            GeoPoint location, List<List<GeoPoint>> area, Map<String, List<String>> dishes, boolean operatorDelivery) {
        Map<String, List<TimeRange>> hours = new java.util.LinkedHashMap<>();
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            hours.put(day.name(), List.of(new TimeRange("00:00", "23:59")));
        }
        return new IndexedStore(id, id + "-slug", brandId, brandName, branchName, 2, Map.of("en", brandName + " in Lahore"), cuisines,
                Map.of("thumb", "https://cdn.example/" + id + ".webp"), Map.of(), location, branchName, "Lahore",
                operatorDelivery ? "OPERATOR" : "SELF", operatorDelivery, false, 25, "Asia/Karachi", hours, List.of(), dishes,
                List.of("halal"), area, 3);
    }
}

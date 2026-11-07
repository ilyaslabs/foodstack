package io.github.ilyaslabs.foodstack.search.core.engine;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.search.api.CuisineCount;
import io.github.ilyaslabs.foodstack.search.api.Fulfilment;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchQuery;
import io.github.ilyaslabs.foodstack.search.core.SearchProperties;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Store discovery on Elasticsearch 9 (TDD 11). Writes go to the alias {@code <prefix>search_stores},
 * which points at the versioned index {@code <prefix>search_stores_v1}; a new mapping gets a new
 * version and a rebuild. Delivery areas are a {@code geo_shape} (a collection of polygons, which
 * may overlap); the customer's point must fall inside one. Results are collapsed on the brand so a
 * brand shows its nearest branch only.
 */
public class ElasticsearchStoreSearchEngine implements StoreSearchEngine {

    /**
     * 2: open windows ({@code openFrom}/{@code openUntil}) for open-now-first ranking across pages.
     * 3: {@code ratingScore} and {@code rankFactor} for ranking by rating and operator boost.
     */
    static final int MAPPING_VERSION = 3;

    /** Reviews a branch needs before its own average outweighs the prior (Bayesian average). */
    static final double RATING_WEIGHT = 5;

    /** What a branch without reviews is assumed to score, out of 5. */
    static final double RATING_PRIOR = 4.0;

    /** Rank multiplier per operator boost level 0 to 3. */
    static final double[] BOOST_FACTORS = { 1.0, 1.25, 1.5, 2.0 };

    /**
     * Days of opening windows stored per branch. The scheduled rebuild re-reads every branch well
     * within this period (see {@code foodstack.search.rebuild-interval}).
     */
    static final int OPEN_WINDOW_DAYS = 8;

    /** 0 when the branch is open at {@code params.now}, else 1; windows are disjoint and sorted, so the arrays line up. */
    private static final String OPEN_FIRST_SCRIPT = """
            def s = doc['openFrom']; def e = doc['openUntil'];
            if (s.size() != e.size()) { return 1; }
            for (int i = 0; i < s.size(); i++) { if (s[i] <= params.now && params.now < e[i]) { return 0; } }
            return 1;""";

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchStoreSearchEngine.class);

    private static final TypeReference<Map<String, List<TimeRange>>> HOURS = new TypeReference<>() {
    };

    private static final String[] TEXT_FIELDS = { "brandName^4", "branchName^2", "cuisines.text^3", "area", "dishes.*",
            "description.*" };

    /** Ids read per page when listing a brand's or a delivery mode's branches. */
    static final int ID_PAGE = 1000;

    private final ElasticsearchClient client;

    private final JsonMapper jsonMapper;

    private final SearchProperties properties;

    private final java.time.Clock clock;

    public ElasticsearchStoreSearchEngine(ElasticsearchClient client, JsonMapper jsonMapper, SearchProperties properties) {
        this(client, jsonMapper, properties, java.time.Clock.systemUTC());
    }

    public ElasticsearchStoreSearchEngine(ElasticsearchClient client, JsonMapper jsonMapper, SearchProperties properties,
            java.time.Clock clock) {
        this.client = client;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public String alias() {
        return this.properties.indexPrefix() + "search_stores";
    }

    String indexName() {
        return alias() + "_v" + MAPPING_VERSION;
    }

    /** Set once the index exists; writes check it so an early event never auto-creates an unmapped index. */
    private volatile boolean ready;

    /** Whether this process created the index and the bootstrap has not been told yet. */
    private boolean createdHere;

    @Override
    public synchronized boolean ensureIndex() {
        prepare();
        boolean created = this.createdHere;
        this.createdHere = false;
        return created;
    }

    private synchronized void prepare() {
        if (this.ready) {
            return;
        }
        String index = indexName();
        try {
            if (!this.client.indices().exists((e) -> e.index(index)).value()) {
                dropStrayIndexNamedLikeAlias();
                String body = indexDefinition();
                this.client.indices().create((c) -> c.index(index).withJson(new StringReader(body)));
                moveAliasToCurrentVersion();
                log.info("Created search index {} with alias {}", index, alias());
                this.createdHere = true;
            }
            else {
                applyReplicas(index);
            }
            this.ready = true;
        }
        catch (ElasticsearchException ex) {
            if ("resource_already_exists_exception".equals(ex.error().type())) {
                this.ready = true;
                return;
            }
            throw unavailable(ex);
        }
        catch (IOException ex) {
            throw unavailable(ex);
        }
    }

    /** Replicas are a live setting: a changed configuration reaches the existing index at startup. */
    private void applyReplicas(String index) {
        String settings = this.properties.autoExpandReplicas().isEmpty()
                ? "{\"index\":{\"auto_expand_replicas\":false,\"number_of_replicas\":" + this.properties.replicas() + "}}"
                : "{\"index\":{\"auto_expand_replicas\":\"" + this.properties.autoExpandReplicas() + "\"}}";
        try {
            this.client.indices().putSettings((p) -> p.index(index).withJson(new StringReader(settings)));
        }
        catch (IOException | ElasticsearchException ex) {
            log.warn("Could not apply the replica settings to {}: {}", index, ex.getMessage());
        }
    }

    /**
     * Points the alias at the new mapping version only, in one atomic step, and drops the older
     * versions: the index is a read model, so the rebuild that follows refills it.
     */
    private void moveAliasToCurrentVersion() throws IOException {
        String alias = alias();
        String index = indexName();
        List<String> older = new ArrayList<>();
        for (int version = 1; version < MAPPING_VERSION; version++) {
            String name = alias + "_v" + version;
            if (this.client.indices().exists((e) -> e.index(name)).value()) {
                older.add(name);
            }
        }
        this.client.indices().updateAliases((u) -> {
            for (String name : older) {
                u.actions((a) -> a.remove((r) -> r.index(name).alias(alias)));
            }
            return u.actions((a) -> a.add((add) -> add.index(index).alias(alias).isWriteIndex(true)));
        });
        for (String name : older) {
            this.client.indices().delete((d) -> d.index(name));
            log.info("Dropped search index {} of an older mapping", name);
        }
    }

    /**
     * A write that reached Elasticsearch before the index existed (older versions) auto-created a plain
     * index under the alias name, which blocks the alias. The index is rebuildable, so it is dropped.
     */
    private void dropStrayIndexNamedLikeAlias() throws IOException {
        String alias = alias();
        boolean exists = this.client.indices().exists((e) -> e.index(alias)).value();
        boolean isAlias = this.client.indices().existsAlias((e) -> e.name(alias)).value();
        if (exists && !isAlias) {
            log.warn("Dropping index {} that was created without the search mapping; it is rebuilt", alias);
            this.client.indices().delete((d) -> d.index(alias));
        }
    }

    @Override
    public void index(IndexedStore store) {
        prepare();
        Map<String, Object> source = toSource(store);
        try {
            // requireAlias: never let a write create an unmapped index under the alias name. The
            // branch's version orders writes: an older read that arrives late is refused (TDD 7.5).
            this.client.index((i) -> i.index(alias())
                .id(store.storeId())
                .requireAlias(true)
                .version(store.version())
                .versionType(VersionType.ExternalGte)
                .document(source));
        }
        catch (ElasticsearchException ex) {
            if (ex.status() == 409) {
                log.debug("Kept the newer indexed version of branch {}", store.storeId());
                return;
            }
            throw unavailable(ex);
        }
        catch (IOException ex) {
            throw unavailable(ex);
        }
    }

    @Override
    public void delete(String storeId) {
        try {
            this.client.delete((d) -> d.index(alias()).id(storeId));
        }
        catch (ElasticsearchException ex) {
            if (ex.status() != 404) {
                throw unavailable(ex);
            }
        }
        catch (IOException ex) {
            throw unavailable(ex);
        }
    }

    @Override
    public void delete(String storeId, long version) {
        try {
            // external version, like index(): an unversioned delete of a missing document leaves a
            // tombstone one version higher each time, which refused the branch's first listing
            this.client.delete((d) -> d.index(alias()).id(storeId).version(version).versionType(VersionType.ExternalGte));
        }
        catch (ElasticsearchException ex) {
            if (ex.status() == 409) {
                log.debug("Kept the newer indexed version of branch {}", storeId);
                return;
            }
            if (ex.status() != 404) {
                throw unavailable(ex);
            }
        }
        catch (IOException ex) {
            throw unavailable(ex);
        }
    }

    @Override
    public List<String> storeIdsOfBrand(String brandId) {
        return idsWhere("brandId", brandId);
    }

    @Override
    public List<String> storeIdsWithDeliveryMode(String deliveryMode) {
        return idsWhere("deliveryMode", deliveryMode);
    }

    /**
     * Every id matching a term, page by page over a point-in-time, so a brand or delivery mode with
     * any number of branches is read completely and consistently (no 10,000 hit window).
     */
    private List<String> idsWhere(String field, String value) {
        prepare();
        String pit;
        try {
            pit = this.client.openPointInTime((p) -> p.index(alias()).keepAlive((k) -> k.time("1m"))).id();
        }
        catch (IOException | ElasticsearchException ex) {
            throw unavailable(ex);
        }
        List<String> ids = new ArrayList<>();
        try {
            List<FieldValue> after = null;
            while (true) {
                ObjectNode body = this.jsonMapper.createObjectNode();
                body.put("size", ID_PAGE);
                body.put("_source", false);
                body.put("track_total_hits", false);
                body.putObject("query").putObject("term").put(field, value);
                ObjectNode pitNode = body.putObject("pit");
                pitNode.put("id", pit);
                pitNode.put("keep_alive", "1m");
                body.putArray("sort").add("_shard_doc");
                if (after != null) {
                    ArrayNode searchAfter = body.putArray("search_after");
                    for (FieldValue v : after) {
                        searchAfter.add(v.longValue());
                    }
                }
                String json = this.jsonMapper.writeValueAsString(body);
                SearchResponse<Map> page;
                try {
                    page = this.client.search(SearchRequest.of((r) -> r.withJson(new StringReader(json))), Map.class);
                }
                catch (IOException | ElasticsearchException ex) {
                    throw unavailable(ex);
                }
                List<co.elastic.clients.elasticsearch.core.search.Hit<Map>> hits = page.hits().hits();
                hits.forEach((hit) -> ids.add(hit.id()));
                if (hits.size() < ID_PAGE) {
                    return ids;
                }
                after = hits.getLast().sort();
                if (page.pitId() != null) {
                    pit = page.pitId();
                }
            }
        }
        finally {
            String id = pit;
            try {
                this.client.closePointInTime((c) -> c.id(id));
            }
            catch (IOException | ElasticsearchException ex) {
                // it expires on its own
            }
        }
    }

    @Override
    public Result search(StoreSearchQuery query, int from) {
        ObjectNode body = this.jsonMapper.createObjectNode();
        body.put("from", from);
        body.put("size", query.size());
        body.put("track_total_hits", false);
        body.putObject("_source").putArray("excludes").add("deliveryArea").add("dishes");

        // Relevance x nearness x rating and boost (TDD 11); the open-now sort still comes first
        ObjectNode scored = body.putObject("query").putObject("function_score");
        ObjectNode bool = scored.putObject("query").putObject("bool");
        ArrayNode functions = scored.putArray("functions");
        ObjectNode nearness = functions.addObject().putObject("gauss").putObject("location");
        location(nearness.putObject("origin"), query);
        nearness.put("offset", this.properties.rankingOffsetMeters() + "m");
        nearness.put("scale", this.properties.rankingScaleMeters() + "m");
        nearness.put("decay", 0.5);
        ObjectNode factor = functions.addObject().putObject("field_value_factor");
        factor.put("field", "rankFactor");
        factor.put("missing", rankFactor(RATING_PRIOR, 0));
        scored.put("score_mode", "multiply");
        scored.put("boost_mode", "multiply");
        ArrayNode filter = bool.putArray("filter");
        if (query.fulfilment() == Fulfilment.PICKUP) {
            filter.addObject().putObject("term").put("pickupEnabled", true);
            ObjectNode distance = filter.addObject().putObject("geo_distance");
            distance.put("distance", this.properties.pickupRadiusMeters() + "m");
            location(distance.putObject("location"), query);
        }
        else {
            ObjectNode shape = filter.addObject().putObject("geo_shape").putObject("deliveryArea");
            ObjectNode point = shape.putObject("shape");
            point.put("type", "point");
            point.putArray("coordinates").add(query.lng()).add(query.lat());
            shape.put("relation", "intersects");
            if (query.radiusMeters() != null) {
                // discovery radius: road distance turned into the straight line Elasticsearch measures
                ObjectNode near = filter.addObject().putObject("geo_distance");
                near.put("distance", Math.round(query.radiusMeters() / this.properties.roadFactor()) + "m");
                location(near.putObject("location"), query);
            }
        }
        boolean hasText = query.text() != null && !query.text().isBlank();
        if (hasText) {
            // As-you-type prefix match, or a whole-word match that forgives a typo
            ObjectNode textBool = bool.putArray("must").addObject().putObject("bool");
            ArrayNode should = textBool.putArray("should");
            ObjectNode prefix = should.addObject().putObject("multi_match");
            prefix.put("query", query.text().trim());
            prefix.put("type", "bool_prefix");
            fields(prefix);
            ObjectNode fuzzy = should.addObject().putObject("multi_match");
            fuzzy.put("query", query.text().trim());
            fuzzy.put("fuzziness", "AUTO");
            fields(fuzzy);
            textBool.put("minimum_should_match", 1);
        }
        else {
            // every match scores 1, so the functions alone rank
            bool.putArray("must").addObject().putObject("match_all");
        }

        ObjectNode cuisineFilter = this.jsonMapper.createObjectNode();
        if (query.cuisine() != null && !query.cuisine().isBlank()) {
            cuisineFilter.putObject("term").put("cuisines", query.cuisine().trim().toLowerCase(java.util.Locale.ROOT));
            body.set("post_filter", cuisineFilter);
        }
        else {
            cuisineFilter.putObject("match_all");
        }
        body.putObject("collapse").put("field", "brandId");
        ArrayNode sort = body.putArray("sort");
        // Open branches first across all pages (PRD SE-3), then the score, then distance
        ObjectNode openFirst = sort.addObject().putObject("_script");
        openFirst.put("type", "number");
        openFirst.put("order", "asc");
        ObjectNode script = openFirst.putObject("script");
        script.put("lang", "painless");
        script.put("source", OPEN_FIRST_SCRIPT);
        script.putObject("params").put("now", this.clock.instant().getEpochSecond());
        sort.add("_score");
        ObjectNode geoSort = sort.addObject().putObject("_geo_distance");
        location(geoSort.putObject("location"), query);
        geoSort.put("order", "asc");
        geoSort.put("unit", "m");
        geoSort.put("distance_type", "arc");

        ObjectNode aggs = body.putObject("aggs");
        ObjectNode cuisines = aggs.putObject("cuisines").putObject("terms");
        cuisines.put("field", "cuisines");
        cuisines.put("size", 30);
        ObjectNode matching = aggs.putObject("matching");
        matching.set("filter", cuisineFilter);
        matching.putObject("aggs").putObject("brands").putObject("cardinality").put("field", "brandId");

        SearchResponse<Map> response = search(body);
        List<StoreSearchEngine.Hit> hits = new ArrayList<>();
        for (co.elastic.clients.elasticsearch.core.search.Hit<Map> hit : response.hits().hits()) {
            List<FieldValue> sortValues = hit.sort();
            long distance = sortValues.isEmpty() ? 0 : Math.round(sortValues.getLast().doubleValue());
            hits.add(new StoreSearchEngine.Hit(fromSource(hit.id(), hit.source()), distance));
        }
        List<CuisineCount> counts = new ArrayList<>();
        Aggregate cuisineAggregate = response.aggregations().get("cuisines");
        if (cuisineAggregate != null && cuisineAggregate.isSterms()) {
            cuisineAggregate.sterms()
                .buckets()
                .array()
                .forEach((bucket) -> counts.add(new CuisineCount(bucket.key().stringValue(), bucket.docCount())));
        }
        long total = 0;
        Aggregate matchingAggregate = response.aggregations().get("matching");
        if (matchingAggregate != null && matchingAggregate.isFilter()) {
            total = matchingAggregate.filter().aggregations().get("brands").cardinality().value();
        }
        return new Result(hits, counts, total);
    }

    @SuppressWarnings("rawtypes")
    private SearchResponse<Map> search(ObjectNode body) {
        prepare();
        String json = this.jsonMapper.writeValueAsString(body);
        try {
            return this.client.search(SearchRequest.of((r) -> r.withJson(new StringReader(json)).index(alias())), Map.class);
        }
        catch (IOException | ElasticsearchException ex) {
            throw unavailable(ex);
        }
    }

    private static void fields(ObjectNode match) {
        ArrayNode fields = match.putArray("fields");
        for (String field : TEXT_FIELDS) {
            fields.add(field);
        }
    }

    private static void location(ObjectNode node, StoreSearchQuery query) {
        node.put("lat", query.lat());
        node.put("lon", query.lng());
    }

    // ---- Documents ------------------------------------------------------------------------

    Map<String, Object> toSource(IndexedStore store) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("storeId", store.storeId());
        source.put("slug", store.slug());
        source.put("brandId", store.brandId());
        source.put("brandName", store.brandName());
        source.put("branchName", store.branchName());
        source.put("branchCount", store.branchCount());
        source.put("description", store.description());
        source.put("cuisines", store.cuisines());
        source.put("logoUrls", store.logoUrls());
        source.put("coverUrls", store.coverUrls());
        if (store.location() != null) {
            source.put("location", Map.of("lat", store.location().lat(), "lon", store.location().lng()));
        }
        source.put("area", store.area());
        source.put("city", store.city());
        source.put("deliveryMode", store.deliveryMode());
        source.put("pickupEnabled", store.pickupEnabled());
        source.put("temporarilyClosed", store.temporarilyClosed());
        source.put("prepMinutes", store.prepMinutes());
        source.put("timezone", store.timezone());
        source.put("openingHours", store.openingHours());
        source.put("closedDates", store.closedDates());
        List<long[]> windows = io.github.ilyaslabs.foodstack.search.core.domain.OpenWindows.of(store.openingHours(),
                store.closedDates(), store.temporarilyClosed(), store.timezone(), this.clock.instant(), OPEN_WINDOW_DAYS);
        source.put("openFrom", windows.stream().map((w) -> w[0]).toList());
        source.put("openUntil", windows.stream().map((w) -> w[1]).toList());
        source.put("dishes", store.dishNames());
        source.put("dietaryTags", store.dietaryTags());
        List<Map<String, Object>> polygons = new ArrayList<>();
        if (store.deliveryArea() != null) {
            for (List<GeoPoint> ring : store.deliveryArea()) {
                if (ring.size() < 3) {
                    continue;
                }
                List<List<Double>> coordinates = new ArrayList<>();
                ring.forEach((p) -> coordinates.add(List.of(p.lng(), p.lat())));
                if (!ring.getFirst().equals(ring.getLast())) {
                    coordinates.add(List.of(ring.getFirst().lng(), ring.getFirst().lat()));
                }
                polygons.add(Map.of("type", "polygon", "coordinates", List.of(coordinates)));
            }
        }
        if (!polygons.isEmpty()) {
            source.put("deliveryArea", Map.of("type", "geometrycollection", "geometries", polygons));
        }
        source.put("version", store.version());
        // shown on cards; not mapped (dynamic false)
        source.put("ratingCount", store.ratingCount());
        source.put("ratingAverage", store.ratingAverage());
        source.put("searchBoost", store.searchBoost());
        // ranking: a well-rated branch with few reviews does not jump ahead of one with many
        double ratingScore = ratingScore(store.ratingCount(), store.ratingAverage());
        source.put("ratingScore", ratingScore);
        source.put("rankFactor", rankFactor(ratingScore, store.searchBoost()));
        return source;
    }

    @SuppressWarnings("unchecked")
    IndexedStore fromSource(String id, Map<String, Object> source) {
        Map<String, Object> s = (source != null) ? source : Map.of();
        Map<String, Object> location = (Map<String, Object>) s.get("location");
        GeoPoint point = (location != null)
                ? new GeoPoint(((Number) location.get("lat")).doubleValue(), ((Number) location.get("lon")).doubleValue())
                : null;
        Map<String, List<TimeRange>> hours = (s.get("openingHours") != null)
                ? this.jsonMapper.convertValue(s.get("openingHours"), HOURS) : Map.of();
        return new IndexedStore(id, (String) s.get("slug"), (String) s.get("brandId"), (String) s.get("brandName"), (String) s.get("branchName"),
                number(s.get("branchCount")), map(s.get("description")), list(s.get("cuisines")), map(s.get("logoUrls")),
                map(s.get("coverUrls")), point, (String) s.get("area"), (String) s.get("city"), (String) s.get("deliveryMode"),
                Boolean.TRUE.equals(s.get("pickupEnabled")), Boolean.TRUE.equals(s.get("temporarilyClosed")),
                number(s.get("prepMinutes")), (String) s.get("timezone"), hours, list(s.get("closedDates")), Map.of(),
                list(s.get("dietaryTags")), List.of(), number(s.get("version")), number(s.get("ratingCount")),
                (s.get("ratingAverage") instanceof Number n) ? n.doubleValue() : 0, number(s.get("searchBoost")));
    }

    /** Bayesian average out of 5: few reviews stay near the prior, many count fully. */
    static double ratingScore(long count, double average) {
        return (RATING_WEIGHT * RATING_PRIOR + count * average) / (RATING_WEIGHT + count);
    }

    /** 0.5 to 1 from the rating (a 1-star branch ranks at half), times the operator's boost. */
    static double rankFactor(double ratingScore, int boost) {
        double rating = 0.5 + 0.1 * Math.min(Math.max(ratingScore, 0), 5);
        return rating * BOOST_FACTORS[Math.min(Math.max(boost, 0), BOOST_FACTORS.length - 1)];
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> map(Object value) {
        return (value instanceof Map<?, ?> m) ? (Map<String, String>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object value) {
        return (value instanceof List<?> l) ? (List<String>) l : List.of();
    }

    private static int number(Object value) {
        return (value instanceof Number n) ? n.intValue() : 0;
    }

    private String indexDefinition() {
        ObjectNode root = this.jsonMapper.createObjectNode();
        ObjectNode settings = root.putObject("settings");
        settings.put("number_of_shards", this.properties.shards());
        if (this.properties.autoExpandReplicas().isEmpty()) {
            settings.put("number_of_replicas", this.properties.replicas());
        }
        else {
            settings.put("auto_expand_replicas", this.properties.autoExpandReplicas());
        }
        ObjectNode analysis = settings.putObject("analysis");
        ObjectNode analyzer = analysis.putObject("analyzer").putObject("foodstack_text");
        analyzer.put("type", "custom");
        analyzer.put("tokenizer", "standard");
        analyzer.putArray("filter").add("lowercase").add("asciifolding").add("persian_normalization").add("arabic_normalization");

        ObjectNode mappings = root.putObject("mappings");
        mappings.put("dynamic", "false");
        ArrayNode templates = mappings.putArray("dynamic_templates");
        for (String path : List.of("description", "dishes")) {
            ObjectNode template = templates.addObject().putObject(path + "_text");
            template.put("path_match", path + ".*");
            ObjectNode mapping = template.putObject("mapping");
            mapping.put("type", "text");
            mapping.put("analyzer", "foodstack_text");
        }
        ObjectNode props = mappings.putObject("properties");
        for (String keyword : List.of("storeId", "slug", "brandId", "city", "deliveryMode", "closedDates", "dietaryTags")) {
            props.putObject(keyword).put("type", "keyword");
        }
        text(props, "brandName").putObject("fields").putObject("keyword").put("type", "keyword");
        text(props, "branchName");
        text(props, "area");
        ObjectNode cuisines = props.putObject("cuisines");
        cuisines.put("type", "keyword");
        ObjectNode cuisineText = cuisines.putObject("fields").putObject("text");
        cuisineText.put("type", "text");
        cuisineText.put("analyzer", "foodstack_text");
        props.putObject("branchCount").put("type", "integer");
        props.putObject("prepMinutes").put("type", "integer");
        props.putObject("version").put("type", "long");
        props.putObject("openFrom").put("type", "long");
        props.putObject("openUntil").put("type", "long");
        props.putObject("pickupEnabled").put("type", "boolean");
        props.putObject("temporarilyClosed").put("type", "boolean");
        props.putObject("ratingScore").put("type", "float");
        props.putObject("rankFactor").put("type", "float");
        props.putObject("location").put("type", "geo_point");
        props.putObject("deliveryArea").put("type", "geo_shape");
        ObjectNode timezone = props.putObject("timezone");
        timezone.put("type", "keyword");
        timezone.put("index", false);
        for (String stored : List.of("logoUrls", "coverUrls", "openingHours")) {
            ObjectNode object = props.putObject(stored);
            object.put("type", "object");
            object.put("enabled", false);
        }
        // Translated text: one sub-field per language, mapped by the dynamic templates above
        props.putObject("description").put("type", "object").put("dynamic", "true");
        props.putObject("dishes").put("type", "object").put("dynamic", "true");
        return this.jsonMapper.writeValueAsString(root);
    }

    private static ObjectNode text(ObjectNode props, String name) {
        ObjectNode field = props.putObject(name);
        field.put("type", "text");
        field.put("analyzer", "foodstack_text");
        return field;
    }

    private static ModuleException unavailable(Exception ex) {
        log.warn("Search engine call failed: {}", ex.getMessage());
        return new ModuleException.Unavailable("Search is temporarily unavailable", "SEARCH_UNAVAILABLE");
    }
}

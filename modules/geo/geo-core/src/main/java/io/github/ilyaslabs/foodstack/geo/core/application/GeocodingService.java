package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import io.github.ilyaslabs.foodstack.geo.core.geocoding.GeocodingProvider;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Address search and reverse lookup through the configured provider (PRD SE-6), cached in Redis
 * under {@code <ns>:geo:cache:geocode:*} so repeated searches never reach the provider.
 */
public class GeocodingService {

    private static final TypeReference<List<AddressSuggestion>> LIST = new TypeReference<>() {
    };

    private final GeocodingProvider provider;

    private final StringRedisTemplate redis;

    private final JsonMapper jsonMapper;

    private final String prefix;

    private final Duration ttl;

    public GeocodingService(GeocodingProvider provider, StringRedisTemplate redis, JsonMapper jsonMapper, String namespace,
            Duration ttl) {
        this.provider = provider;
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.prefix = namespace + ":geo:cache:geocode:";
        this.ttl = ttl;
    }

    public List<AddressSuggestion> search(String query, String language) {
        String text = (query != null) ? query.trim() : "";
        if (text.length() < 3 || text.length() > 200) {
            throw new ModuleException.BadRequest("Type at least 3 characters", "ADDRESS_QUERY_INVALID");
        }
        String lang = language(language);
        return cached("q:" + lang + ":" + text.toLowerCase(Locale.ROOT), () -> this.provider.search(text, lang));
    }

    public List<AddressSuggestion> reverse(double lat, double lng, String language) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
            throw new ModuleException.BadRequest("The location is not a valid point", "LOCATION_INVALID");
        }
        String lang = language(language);
        String key = "r:" + lang + ":" + Math.round(lat * 10_000) + ":" + Math.round(lng * 10_000);
        return cached(key, () -> this.provider.reverse(lat, lng, lang));
    }

    public boolean isAvailable() {
        return this.provider.isAvailable();
    }

    private List<AddressSuggestion> cached(String key, Supplier<List<AddressSuggestion>> loader) {
        if (!this.provider.isAvailable()) {
            throw new ModuleException.Unavailable("Address search is switched off on this platform", "GEOCODING_DISABLED");
        }
        String cached = this.redis.opsForValue().get(this.prefix + key);
        if (cached != null) {
            return this.jsonMapper.readValue(cached, LIST);
        }
        List<AddressSuggestion> result = loader.get();
        this.redis.opsForValue().set(this.prefix + key, this.jsonMapper.writeValueAsString(result), this.ttl);
        return result;
    }

    private static String language(String language) {
        if (language == null || language.isBlank()) {
            return "en";
        }
        String tag = Locale.forLanguageTag(language.split(",")[0].trim()).getLanguage();
        return tag.isEmpty() ? "en" : tag;
    }
}

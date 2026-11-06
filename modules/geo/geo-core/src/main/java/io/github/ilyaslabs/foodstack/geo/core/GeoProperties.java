package io.github.ilyaslabs.foodstack.geo.core;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code foodstack.geo.*}: distance estimate and the address search provider (TDD 12).
 */
@ConfigurationProperties("foodstack.geo")
public class GeoProperties {

    /**
     * Road distance is estimated as the straight-line distance times this factor until a routing
     * provider is configured.
     */
    private double roadFactor = 1.3;

    /** How far from a city's centre a new branch or rider is still suggested that city. */
    private int cityRadiusMeters = 60_000;

    /** How long zones and the operator's pricing stay in Redis; changes evict them at once. 0 turns the cache off. */
    private Duration quoteCacheTtl = Duration.ofMinutes(10);

    private final Geocoding geocoding = new Geocoding();

    private final Routing routing = new Routing();

    public Duration getQuoteCacheTtl() {
        return this.quoteCacheTtl;
    }

    public void setQuoteCacheTtl(Duration quoteCacheTtl) {
        this.quoteCacheTtl = quoteCacheTtl;
    }

    public Routing getRouting() {
        return this.routing;
    }

    /** Road distance and driving time (TDD 14.1): quotes, rider dispatch and live ETAs. */
    public static class Routing {

        /** {@code osrm}, or {@code none} for the straight-line estimate (the default). */
        private String provider = "none";

        /** OSRM base URL, for example {@code http://osrm:5000}. */
        private String url = "";

        /** OSRM profile the server was built with. */
        private String profile = "driving";

        /** Average urban speed for the straight-line estimate. */
        private double estimateSpeedKmh = 25;

        /** How long answers are cached in Redis. */
        private Duration cacheTtl = Duration.ofHours(6);

        private Duration timeout = Duration.ofSeconds(3);

        public String getProvider() {
            return this.provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getUrl() {
            return this.url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getProfile() {
            return this.profile;
        }

        public void setProfile(String profile) {
            this.profile = profile;
        }

        public double getEstimateSpeedKmh() {
            return this.estimateSpeedKmh;
        }

        public void setEstimateSpeedKmh(double estimateSpeedKmh) {
            this.estimateSpeedKmh = estimateSpeedKmh;
        }

        public Duration getCacheTtl() {
            return this.cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }

        public Duration getTimeout() {
            return this.timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public int getCityRadiusMeters() {
        return this.cityRadiusMeters;
    }

    public void setCityRadiusMeters(int cityRadiusMeters) {
        this.cityRadiusMeters = cityRadiusMeters;
    }

    public double getRoadFactor() {
        return this.roadFactor;
    }

    public void setRoadFactor(double roadFactor) {
        this.roadFactor = roadFactor;
    }

    public Geocoding getGeocoding() {
        return this.geocoding;
    }

    /** Address search and reverse lookup of a map pin (PRD SE-6). */
    public static class Geocoding {

        /** {@code nominatim} (OpenStreetMap, the default) or {@code none}. */
        private String provider = "nominatim";

        /**
         * Nominatim base URL. The public server is for demos only (one request per second); run your
         * own or use a hosted provider in production (deployment guide).
         */
        private String url = "https://nominatim.openstreetmap.org";

        /** Sent as User-Agent, as the Nominatim usage policy requires. */
        private String userAgent = "FoodStack";

        /** Optional comma-separated ISO 3166-1 codes that limit results, for example {@code pk,ae}. */
        private String countryCodes = "";

        /** How long answers are cached in Redis. */
        private Duration cacheTtl = Duration.ofDays(7);

        private Duration timeout = Duration.ofSeconds(5);

        public String getProvider() {
            return this.provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getUrl() {
            return this.url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUserAgent() {
            return this.userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public String getCountryCodes() {
            return this.countryCodes;
        }

        public void setCountryCodes(String countryCodes) {
            this.countryCodes = countryCodes;
        }

        public Duration getCacheTtl() {
            return this.cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }

        public Duration getTimeout() {
            return this.timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}

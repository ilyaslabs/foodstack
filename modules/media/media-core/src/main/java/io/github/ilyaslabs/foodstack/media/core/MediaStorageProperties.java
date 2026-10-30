package io.github.ilyaslabs.foodstack.media.core;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** S3-compatible object storage, under {@code foodstack.storage} (TDD 13). */
@ConfigurationProperties("foodstack.storage")
public class MediaStorageProperties {

    /** S3 API endpoint the server uses; empty for AWS S3. */
    private String endpoint;

    /**
     * Endpoint browsers use for signed upload and download URLs. On one server Caddy routes the
     * bucket paths on the public domain to the storage service, so this is the public URL.
     */
    private String publicEndpoint;

    /** Base URL of public objects; defaults to {@code <public-endpoint>/<public-bucket>}. */
    private String publicBaseUrl;

    private String region = "us-east-1";

    private String accessKey;

    private String secretKey;

    private String publicBucket = "foodstack-public";

    private String privateBucket = "foodstack-private";

    /** Path-style URLs ({@code host/bucket/key}); needed by most S3-compatible servers. */
    private boolean pathStyle = true;

    /** Create the buckets (and the public read policy) at startup. */
    private boolean createBuckets;

    /** Keep the original upload after processing. */
    private boolean keepOriginals;

    /** Lifetime of signed upload and private download URLs. */
    private Duration signedUrlTtl = Duration.ofMinutes(5);

    /** Largest accepted upload. */
    private long maxUploadBytes = 10L * 1024 * 1024;

    public String getEndpoint() {
        return this.endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getPublicEndpoint() {
        return (this.publicEndpoint != null && !this.publicEndpoint.isBlank()) ? this.publicEndpoint : this.endpoint;
    }

    public void setPublicEndpoint(String publicEndpoint) {
        this.publicEndpoint = publicEndpoint;
    }

    public String getPublicBaseUrl() {
        if (this.publicBaseUrl != null && !this.publicBaseUrl.isBlank()) {
            return stripSlash(this.publicBaseUrl);
        }
        return stripSlash(getPublicEndpoint()) + "/" + this.publicBucket;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getRegion() {
        return this.region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccessKey() {
        return this.accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return this.secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getPublicBucket() {
        return this.publicBucket;
    }

    public void setPublicBucket(String publicBucket) {
        this.publicBucket = publicBucket;
    }

    public String getPrivateBucket() {
        return this.privateBucket;
    }

    public void setPrivateBucket(String privateBucket) {
        this.privateBucket = privateBucket;
    }

    public boolean isPathStyle() {
        return this.pathStyle;
    }

    public void setPathStyle(boolean pathStyle) {
        this.pathStyle = pathStyle;
    }

    public boolean isCreateBuckets() {
        return this.createBuckets;
    }

    public void setCreateBuckets(boolean createBuckets) {
        this.createBuckets = createBuckets;
    }

    public boolean isKeepOriginals() {
        return this.keepOriginals;
    }

    public void setKeepOriginals(boolean keepOriginals) {
        this.keepOriginals = keepOriginals;
    }

    public Duration getSignedUrlTtl() {
        return this.signedUrlTtl;
    }

    public void setSignedUrlTtl(Duration signedUrlTtl) {
        this.signedUrlTtl = signedUrlTtl;
    }

    public long getMaxUploadBytes() {
        return this.maxUploadBytes;
    }

    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }

    private static String stripSlash(String value) {
        return (value != null && value.endsWith("/")) ? value.substring(0, value.length() - 1) : value;
    }
}

package io.github.ilyaslabs.foodstack.media.core;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** S3-compatible object storage, under {@code foodstack.storage} (TDD 13). */
@EVL.Configuration(id = "media.storage-config", name = "Media Storage", prefix = "foodstack.storage",
        component = "media.service")
@ConfigurationProperties("foodstack.storage")
public class MediaStorageProperties {

    /** S3 API endpoint the server uses; empty for AWS S3. */
    @EVL.ConfigProperty(id = "media.storage.endpoint", key = "foodstack.storage.endpoint",
            description = "S3 API endpoint (env S3_ENDPOINT); empty for AWS S3", affects = "media.s3-object-storage")
    private String endpoint;

    /**
     * Endpoint browsers use for signed upload and download URLs. On one server Caddy routes the
     * bucket paths on the public domain to the storage service, so this is the public URL.
     */
    @EVL.ConfigProperty(id = "media.storage.public-endpoint", key = "foodstack.storage.public-endpoint",
            description = "Endpoint in the signed URLs browsers use (env S3_PUBLIC_ENDPOINT); defaults to the endpoint")
    private String publicEndpoint;

    /** Base URL of public objects; defaults to {@code <public-endpoint>/<public-bucket>}. */
    @EVL.ConfigProperty(id = "media.storage.public-base-url", key = "foodstack.storage.public-base-url",
            description = "Where browsers load public images, e.g. a CDN (env S3_PUBLIC_BASE_URL); "
                    + "defaults to <public-endpoint>/<public-bucket>",
            affects = { "media.service", "media.storage-check" })
    private String publicBaseUrl;

    @EVL.ConfigProperty(id = "media.storage.region", key = "foodstack.storage.region", defaultValue = "us-east-1",
            description = "Bucket region (env S3_REGION)")
    private String region = "us-east-1";

    @EVL.ConfigProperty(id = "media.storage.access-key", key = "foodstack.storage.access-key", sensitive = true,
            description = "Access key id (env S3_ACCESS_KEY); empty uses the AWS default credential chain")
    private String accessKey;

    @EVL.ConfigProperty(id = "media.storage.secret-key", key = "foodstack.storage.secret-key", sensitive = true,
            description = "Secret of the access key (env S3_SECRET_KEY)")
    private String secretKey;

    @EVL.ConfigProperty(id = "media.storage.public-bucket", key = "foodstack.storage.public-bucket",
            defaultValue = "foodstack-public", description = "Bucket of images anyone may read (env S3_PUBLIC_BUCKET)")
    private String publicBucket = "foodstack-public";

    @EVL.ConfigProperty(id = "media.storage.private-bucket", key = "foodstack.storage.private-bucket",
            defaultValue = "foodstack-private",
            description = "Bucket of raw uploads and private files, opened only by signed URLs (env S3_PRIVATE_BUCKET)")
    private String privateBucket = "foodstack-private";

    /** Path-style URLs ({@code host/bucket/key}); needed by most S3-compatible servers. */
    @EVL.ConfigProperty(id = "media.storage.path-style", key = "foodstack.storage.path-style", defaultValue = "true",
            description = "host/bucket/key URLs, needed by most S3-compatible servers (env S3_PATH_STYLE)")
    private boolean pathStyle = true;

    /** Create the buckets (and the public read policy) at startup. */
    @EVL.ConfigProperty(id = "media.storage.create-buckets", key = "foodstack.storage.create-buckets",
            defaultValue = "false",
            description = "Create the buckets and the public read policy at start (env S3_CREATE_BUCKETS)")
    private boolean createBuckets;

    /** Check the buckets before the server takes traffic, and refuse to start when they don't work. */
    @EVL.ConfigProperty(id = "media.storage.check-on-start", key = "foodstack.storage.check-on-start",
            defaultValue = "true", affects = "media.storage-check",
            description = "Run the storage check before taking traffic (env S3_CHECK_ON_START)")
    private boolean checkOnStart = true;

    /**
     * Sites whose pages upload straight to the buckets (the customer app and the back office); the
     * check makes sure the buckets' CORS rules let them. Empty when uploads go through the same site.
     */
    @EVL.ConfigProperty(id = "media.storage.check-origins", key = "foodstack.storage.check-origins",
            affects = "media.storage-check",
            description = "Sites that upload from the browser; the check tests CORS for each (env S3_CHECK_ORIGINS)")
    private List<String> checkOrigins = new ArrayList<>();

    /** Keep the original upload after processing. */
    @EVL.ConfigProperty(id = "media.storage.keep-originals", key = "foodstack.storage.keep-originals",
            defaultValue = "false", affects = "media.service",
            description = "Keep the raw upload after processing")
    private boolean keepOriginals;

    /** Lifetime of signed upload and private download URLs. */
    @EVL.ConfigProperty(id = "media.storage.signed-url-ttl", key = "foodstack.storage.signed-url-ttl",
            defaultValue = "5m", affects = "media.service",
            description = "Lifetime of signed upload and private download URLs")
    private Duration signedUrlTtl = Duration.ofMinutes(5);

    /** Largest accepted upload. */
    @EVL.ConfigProperty(id = "media.storage.max-upload-bytes", key = "foodstack.storage.max-upload-bytes",
            defaultValue = "10485760", affects = { "media.service", "media.image-processor" },
            description = "Largest accepted upload, in bytes")
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

    public boolean isCheckOnStart() {
        return this.checkOnStart;
    }

    public void setCheckOnStart(boolean checkOnStart) {
        this.checkOnStart = checkOnStart;
    }

    /** Origins, without paths or blanks, each once. */
    public List<String> getCheckOrigins() {
        return this.checkOrigins.stream()
            .map(String::strip)
            .filter((o) -> !o.isEmpty())
            .map((o) -> {
                URI uri = URI.create(o);
                return uri.getScheme() + "://" + uri.getRawAuthority();
            })
            .distinct()
            .toList();
    }

    public void setCheckOrigins(List<String> checkOrigins) {
        this.checkOrigins = (checkOrigins != null) ? new ArrayList<>(checkOrigins) : new ArrayList<>();
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

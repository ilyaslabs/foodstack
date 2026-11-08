package io.github.ilyaslabs.foodstack.media.core.storage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Checks the operator's buckets before the server takes traffic, and stops the start with what to
 * fix: both buckets exist and this key can write to them, the public bucket answers anyone at the
 * public address, and browsers on the FoodStack sites may upload to both (CORS). Without it, a
 * mistake shows up only later, as broken photos or failed uploads.
 */
public class StorageCheck {

    private static final Logger log = LoggerFactory.getLogger(StorageCheck.class);

    private static final String PREFIX = "_foodstack-check/";

    private final S3Client client;

    private final S3Presigner presigner;

    private final HttpClient http;

    public StorageCheck(S3Client client, S3Presigner presigner) {
        this(client, presigner, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    StorageCheck(S3Client client, S3Presigner presigner, HttpClient http) {
        this.client = client;
        this.presigner = presigner;
        this.http = http;
    }

    /**
     * @param publicBucket bucket of images anyone may read
     * @param privateBucket bucket of files only signed links open
     * @param publicBaseUrl where browsers load the public bucket's files
     * @param origins sites that upload from the browser; empty skips the CORS step (uploads through
     * the same site, as on a trial install)
     * @throws IllegalStateException naming the first thing to fix
     */
    public void check(String publicBucket, String privateBucket, String publicBaseUrl, List<String> origins) {
        String key = PREFIX + UUID.randomUUID() + ".txt";
        List<String> written = new ArrayList<>();
        try {
            for (String bucket : List.of(publicBucket, privateBucket)) {
                write(bucket, key);
                written.add(bucket);
            }
            publicRead(publicBaseUrl, key);
            for (String origin : origins) {
                for (String bucket : List.of(publicBucket, privateBucket)) {
                    browserUpload(bucket, origin);
                }
            }
            log.info("Storage check passed: buckets {} and {}, images at {}", publicBucket, privateBucket, publicBaseUrl);
        }
        finally {
            for (String bucket : written) {
                try {
                    this.client.deleteObject((request) -> request.bucket(bucket).key(key));
                }
                catch (RuntimeException ex) {
                    log.debug("Could not remove the check file from {}", bucket, ex);
                }
            }
        }
    }

    private void write(String bucket, String key) {
        try {
            this.client.putObject((request) -> request.bucket(bucket).key(key).contentType("text/plain"),
                    RequestBody.fromString("ok"));
        }
        catch (NoSuchBucketException ex) {
            throw fail("the bucket '" + bucket + "' does not exist. Create it in your storage provider's console "
                    + "(handbook guide 12) or fix S3_PUBLIC_BUCKET / S3_PRIVATE_BUCKET");
        }
        catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                throw fail("the bucket '" + bucket + "' does not exist at S3_ENDPOINT. Create it, or check S3_ENDPOINT "
                        + "and S3_REGION");
            }
            if (ex.statusCode() == 403 || ex.statusCode() == 401) {
                throw fail("the access key may not write to the bucket '" + bucket + "' (" + code(ex) + "). Check "
                        + "S3_ACCESS_KEY and S3_SECRET_KEY, and give the key read and write access to both buckets");
            }
            throw fail("the bucket '" + bucket + "' refused a test upload: " + code(ex));
        }
        catch (SdkClientException ex) {
            throw fail("the storage provider is not reachable at S3_ENDPOINT (" + ex.getMessage() + ")");
        }
    }

    private void publicRead(String publicBaseUrl, String key) {
        String url = publicBaseUrl.replaceAll("/+$", "") + "/" + key;
        HttpResponse<String> response;
        try {
            response = this.http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (IOException ex) {
            throw fail("images cannot be loaded from S3_PUBLIC_BASE_URL (" + url + "): " + ex.getMessage());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw fail("interrupted");
        }
        if (response.statusCode() != 200 || !"ok".equals(response.body().strip())) {
            throw fail("a test file in the public bucket is not readable by everyone at " + url + " (HTTP "
                    + response.statusCode() + "). Allow public reading on the public bucket, and check "
                    + "S3_PUBLIC_BASE_URL is where its files are served");
        }
    }

    private void browserUpload(String bucket, String origin) {
        PutObjectRequest put = PutObjectRequest.builder().bucket(bucket).key(PREFIX + "cors").contentType("image/jpeg")
            .build();
        URI url = URI.create(this.presigner
            .presignPutObject((request) -> request.signatureDuration(Duration.ofMinutes(1)).putObjectRequest(put))
            .url()
            .toString());
        HttpResponse<Void> response;
        try {
            response = this.http.send(HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(15))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type")
                .build(), HttpResponse.BodyHandlers.discarding());
        }
        catch (IOException ex) {
            throw fail("browsers cannot reach the bucket '" + bucket + "' for uploads at " + url.getScheme() + "://"
                    + url.getAuthority() + ": " + ex.getMessage() + ". Check S3_PUBLIC_ENDPOINT");
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw fail("interrupted");
        }
        String allowed = response.headers().firstValue("Access-Control-Allow-Origin").orElse("");
        if (response.statusCode() / 100 != 2 || !(allowed.equals("*") || allowed.equalsIgnoreCase(origin))) {
            throw fail("the bucket '" + bucket + "' does not allow uploads from " + origin + " (CORS, HTTP "
                    + response.statusCode() + "). Add a CORS rule allowing PUT and GET with any header from your "
                    + "customer and back office addresses (handbook guide 12)");
        }
    }

    private static String code(S3Exception ex) {
        return (ex.awsErrorDetails() != null && ex.awsErrorDetails().errorCode() != null)
                ? ex.awsErrorDetails().errorCode() : "HTTP " + ex.statusCode();
    }

    private static IllegalStateException fail(String problem) {
        IllegalStateException ex = new IllegalStateException("Storage check failed: " + problem
                + ". FoodStack does not start until media storage works.");
        log.error(ex.getMessage());
        return ex;
    }
}

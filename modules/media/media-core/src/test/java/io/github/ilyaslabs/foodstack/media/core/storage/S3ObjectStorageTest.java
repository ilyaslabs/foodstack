package io.github.ilyaslabs.foodstack.media.core.storage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;

class S3ObjectStorageTest {

    private static S3Client client;

    private static S3Presigner presigner;

    private static S3ObjectStorage storage;

    private static String publicBucket;

    private static String privateBucket;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeAll
    static void connect() {
        URI endpoint = URI.create(FoodstackContainers.s3Endpoint());
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(FoodstackContainers.S3_ACCESS_KEY, FoodstackContainers.S3_SECRET_KEY));
        var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        client = S3Client.builder().region(Region.US_EAST_1).endpointOverride(endpoint).credentialsProvider(credentials)
            .serviceConfiguration(pathStyle).build();
        presigner = S3Presigner.builder().region(Region.US_EAST_1).endpointOverride(endpoint).credentialsProvider(credentials)
            .serviceConfiguration(pathStyle).build();
        storage = new S3ObjectStorage(client, presigner);
        String prefix = FoodstackContainers.uniqueName();
        publicBucket = prefix + "-public";
        privateBucket = prefix + "-private";
        storage.ensureBuckets(publicBucket, privateBucket);
    }

    @AfterAll
    static void close() {
        client.close();
        presigner.close();
    }

    @Test
    @DisplayName("accepts an upload through a presigned PUT URL with the signed content type")
    void presignedUpload() throws Exception {
        // given
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        URI url = storage.presignUpload(privateBucket, "uploads/a", "image/png", content.length, Duration.ofMinutes(5));

        // when
        HttpResponse<String> response = this.http.send(HttpRequest.newBuilder(url)
            .header("Content-Type", "image/png")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
            .build(), HttpResponse.BodyHandlers.ofString());

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(storage.read(privateBucket, "uploads/a")).isEqualTo(content);
    }

    @Test
    @DisplayName("lets anyone read the public bucket but only signed URLs read the private one")
    void bucketVisibility() throws Exception {
        // given
        storage.write(publicBucket, "branding/x/thumb.webp", new byte[] { 1, 2, 3 }, "image/webp", "public, max-age=60");
        storage.write(privateBucket, "rider_document/y/thumb.webp", new byte[] { 4, 5 }, "image/webp", "private");
        String base = FoodstackContainers.s3Endpoint();

        // when
        int publicStatus = get(URI.create(base + "/" + publicBucket + "/branding/x/thumb.webp"));
        int privateStatus = get(URI.create(base + "/" + privateBucket + "/rider_document/y/thumb.webp"));
        int signedStatus = get(storage.presignDownload(privateBucket, "rider_document/y/thumb.webp", Duration.ofMinutes(1)));

        // then
        assertThat(publicStatus).isEqualTo(200);
        assertThat(privateStatus).isEqualTo(403);
        assertThat(signedStatus).isEqualTo(200);
    }

    @Test
    @DisplayName("returns null for missing objects and deletes existing ones")
    void readAndDelete() {
        storage.write(privateBucket, "tmp/z", new byte[] { 9 }, "application/octet-stream", "private");

        storage.delete(privateBucket, "tmp/z");

        assertThat(storage.read(privateBucket, "tmp/z")).isNull();
        assertThat(storage.read(privateBucket, "never/there")).isNull();
    }

    @Test
    @DisplayName("creating buckets again is harmless")
    void ensureBucketsIdempotent() {
        storage.ensureBuckets(publicBucket, privateBucket);
    }

    private int get(URI uri) throws Exception {
        return this.http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}

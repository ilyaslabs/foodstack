package io.github.ilyaslabs.foodstack.media.core.storage;

import java.net.URI;
import java.util.List;

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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The start-up check against real S3-compatible storage. The test storage allows browser uploads
 * from the two local dev origins only, like a bucket with a CORS rule for the FoodStack sites.
 */
class StorageCheckTest {

    private static S3Client client;

    private static S3Presigner presigner;

    private static String publicBucket;

    private static String privateBucket;

    private static String endpoint;

    @BeforeAll
    static void connect() {
        endpoint = FoodstackContainers.s3Endpoint();
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(FoodstackContainers.S3_ACCESS_KEY, FoodstackContainers.S3_SECRET_KEY));
        var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        client = S3Client.builder().region(Region.US_EAST_1).endpointOverride(URI.create(endpoint))
            .credentialsProvider(credentials).serviceConfiguration(pathStyle).build();
        presigner = S3Presigner.builder().region(Region.US_EAST_1).endpointOverride(URI.create(endpoint))
            .credentialsProvider(credentials).serviceConfiguration(pathStyle).build();
        String prefix = FoodstackContainers.uniqueName();
        publicBucket = prefix + "-public";
        privateBucket = prefix + "-private";
        new S3ObjectStorage(client, presigner).ensureBuckets(publicBucket, privateBucket);
    }

    @AfterAll
    static void close() {
        client.close();
        presigner.close();
    }

    private final StorageCheck check = new StorageCheck(client, presigner);

    @Test
    @DisplayName("passes for working buckets, readable by everyone, that allow uploads from the sites, and leaves nothing behind")
    void passes() {
        assertThatCode(() -> this.check.check(publicBucket, privateBucket, endpoint + "/" + publicBucket,
                List.of("http://localhost:4300", "http://localhost:4200"))).doesNotThrowAnyException();
        assertThat(client.listObjectsV2((r) -> r.bucket(publicBucket).prefix("_foodstack-check/")).contents()).isEmpty();
        assertThat(client.listObjectsV2((r) -> r.bucket(privateBucket).prefix("_foodstack-check/")).contents()).isEmpty();
    }

    @Test
    @DisplayName("names a missing bucket")
    void missingBucket() {
        assertThatThrownBy(() -> this.check.check(publicBucket, "no-such-bucket-" + System.nanoTime(),
                endpoint + "/" + publicBucket, List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("refuses a public bucket that the public cannot read")
    void notPublic() {
        assertThatThrownBy(() -> this.check.check(privateBucket, publicBucket, endpoint + "/" + privateBucket, List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not readable by everyone");
    }

    @Test
    @DisplayName("refuses buckets that do not allow uploads from a FoodStack site")
    void noCors() {
        assertThatThrownBy(() -> this.check.check(publicBucket, privateBucket, endpoint + "/" + publicBucket,
                List.of("https://shop.example.com")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not allow uploads from https://shop.example.com");
    }

    @Test
    @DisplayName("refuses a wrong secret key")
    void wrongKey() {
        try (S3Client wrong = S3Client.builder().region(Region.US_EAST_1).endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(FoodstackContainers.S3_ACCESS_KEY,
                    "not-the-secret")))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()) {
            assertThatThrownBy(() -> new StorageCheck(wrong, presigner).check(publicBucket, privateBucket,
                    endpoint + "/" + publicBucket, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access-key and secret-key");
        }
    }
}

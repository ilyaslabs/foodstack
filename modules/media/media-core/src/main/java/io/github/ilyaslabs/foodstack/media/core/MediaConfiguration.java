package io.github.ilyaslabs.foodstack.media.core;

import java.net.URI;
import java.time.Clock;

import io.github.ilyaslabs.foodstack.media.core.application.MediaService;
import io.github.ilyaslabs.foodstack.media.core.persistence.MediaAssetRepository;
import io.github.ilyaslabs.foodstack.media.core.processing.ImageProcessor;
import io.github.ilyaslabs.foodstack.media.core.storage.ObjectStorage;
import io.github.ilyaslabs.foodstack.media.core.storage.S3ObjectStorage;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Object storage, image processing and the media service. With
 * {@link MediaPersistenceConfiguration} and {@link MediaEventsConfiguration} this is everything the
 * module runs on; all three live in the module's package, so they disappear when media is not local.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaStorageProperties.class)
public class MediaConfiguration {

    @Bean(destroyMethod = "close")
    S3Client mediaS3Client(MediaStorageProperties properties) {
        var builder = S3Client.builder()
            .region(Region.of(properties.getRegion()))
            .credentialsProvider(credentials(properties))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.isPathStyle()).build());
        if (StringUtils.hasText(properties.getEndpoint())) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner mediaS3Presigner(MediaStorageProperties properties) {
        var builder = S3Presigner.builder()
            .region(Region.of(properties.getRegion()))
            .credentialsProvider(credentials(properties))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.isPathStyle()).build());
        if (StringUtils.hasText(properties.getPublicEndpoint())) {
            builder.endpointOverride(URI.create(properties.getPublicEndpoint()));
        }
        return builder.build();
    }

    @Bean
    ObjectStorage mediaObjectStorage(S3Client mediaS3Client, S3Presigner mediaS3Presigner,
            MediaStorageProperties properties) {
        S3ObjectStorage storage = new S3ObjectStorage(mediaS3Client, mediaS3Presigner);
        if (properties.isCreateBuckets()) {
            storage.ensureBuckets(properties.getPublicBucket(), properties.getPrivateBucket());
        }
        return storage;
    }

    @Bean
    ImageProcessor mediaImageProcessor(MediaStorageProperties properties) {
        return new ImageProcessor(properties.getMaxUploadBytes());
    }

    @Bean
    MediaService mediaService(MediaAssetRepository repository, ObjectStorage mediaObjectStorage,
            ImageProcessor mediaImageProcessor, OutboxWriter mediaOutbox, ModuleMongo mediaMongo,
            MediaStorageProperties properties, Clock clock) {
        return new MediaService(repository, mediaObjectStorage, mediaImageProcessor, mediaOutbox,
                mediaMongo.transactionTemplate(), properties, clock);
    }

    private static AwsCredentialsProvider credentials(MediaStorageProperties properties) {
        if (StringUtils.hasText(properties.getAccessKey())) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.getAccessKey(),
                    properties.getSecretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}

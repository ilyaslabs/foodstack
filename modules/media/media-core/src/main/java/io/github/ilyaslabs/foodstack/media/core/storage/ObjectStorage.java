package io.github.ilyaslabs.foodstack.media.core.storage;

import java.net.URI;
import java.time.Duration;

/**
 * Object storage SPI (TDD 13). The default adapter speaks the S3 API, so it works with AWS S3 and
 * S3-compatible servers.
 */
public interface ObjectStorage {

    /** A URL the client can {@code PUT} the object to directly, valid for {@code ttl}. */
    URI presignUpload(String bucket, String key, String contentType, long contentLength, Duration ttl);

    /** A URL to {@code GET} a private object, valid for {@code ttl}. */
    URI presignDownload(String bucket, String key, Duration ttl);

    /** The object's bytes; {@code null} when it does not exist. */
    byte[] read(String bucket, String key);

    void write(String bucket, String key, byte[] content, String contentType, String cacheControl);

    void delete(String bucket, String key);

    /** Creates the buckets when missing and lets anyone read the public one. */
    void ensureBuckets(String publicBucket, String privateBucket);
}

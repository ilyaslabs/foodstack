package io.github.ilyaslabs.foodstack.media.core.storage;

import java.net.URI;
import java.time.Duration;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** {@link ObjectStorage} on the AWS SDK v2 S3 client. */
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client client;

    private final S3Presigner presigner;

    public S3ObjectStorage(S3Client client, S3Presigner presigner) {
        this.client = client;
        this.presigner = presigner;
    }

    @Override
    public URI presignUpload(String bucket, String key, String contentType, long contentLength, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(contentLength)
            .build();
        return toUri(this.presigner.presignPutObject((request) -> request.signatureDuration(ttl).putObjectRequest(put))
            .url()
            .toString());
    }

    @Override
    public URI presignDownload(String bucket, String key, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return toUri(this.presigner.presignGetObject((request) -> request.signatureDuration(ttl).getObjectRequest(get))
            .url()
            .toString());
    }

    @Override
    public byte[] read(String bucket, String key) {
        try {
            return this.client.getObjectAsBytes((request) -> request.bucket(bucket).key(key)).asByteArray();
        }
        catch (NoSuchKeyException ex) {
            return null;
        }
    }

    @Override
    public void write(String bucket, String key, byte[] content, String contentType, String cacheControl) {
        this.client.putObject((request) -> request.bucket(bucket).key(key).contentType(contentType).cacheControl(cacheControl),
                RequestBody.fromBytes(content));
    }

    @Override
    public void delete(String bucket, String key) {
        this.client.deleteObject((request) -> request.bucket(bucket).key(key));
    }

    @Override
    public void ensureBuckets(String publicBucket, String privateBucket) {
        createIfMissing(publicBucket);
        createIfMissing(privateBucket);
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},\
                "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}""".formatted(publicBucket);
        this.client.putBucketPolicy((request) -> request.bucket(publicBucket).policy(policy));
    }

    private void createIfMissing(String bucket) {
        try {
            this.client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        }
        catch (NoSuchBucketException ex) {
            this.client.createBucket((request) -> request.bucket(bucket));
        }
        catch (S3Exception ex) {
            if (ex.statusCode() != 404) {
                throw ex;
            }
            this.client.createBucket((request) -> request.bucket(bucket));
        }
    }

    private static URI toUri(String url) {
        return URI.create(url);
    }
}

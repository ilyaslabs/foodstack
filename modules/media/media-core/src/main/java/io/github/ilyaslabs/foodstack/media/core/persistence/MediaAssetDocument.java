package io.github.ilyaslabs.foodstack.media.core.persistence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaStatus;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An uploaded asset in {@code media_assets}. Shard key {@code { _id: hashed }}: always read by id.
 * Variants are a fixed small map and the owner is a single reference, so the document never grows.
 */
@Document("media_assets")
public class MediaAssetDocument {

    @Id
    private String id;

    private MediaPurpose purpose;

    private MediaStatus status;

    private String contentType;

    private long declaredBytes;

    /** Key of the raw upload in the private bucket; removed after processing. */
    private String uploadKey;

    private Map<String, Variant> variants = new LinkedHashMap<>();

    private Owner owner;

    private String failureCode;

    private String createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private Long version;

    public record Variant(String bucket, String key, int width, int height, long bytes) {
    }

    public record Owner(String module, String entityId) {
    }

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public MediaPurpose getPurpose() {
        return this.purpose;
    }

    public void setPurpose(MediaPurpose purpose) {
        this.purpose = purpose;
    }

    public MediaStatus getStatus() {
        return this.status;
    }

    public void setStatus(MediaStatus status) {
        this.status = status;
    }

    public String getContentType() {
        return this.contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getDeclaredBytes() {
        return this.declaredBytes;
    }

    public void setDeclaredBytes(long declaredBytes) {
        this.declaredBytes = declaredBytes;
    }

    public String getUploadKey() {
        return this.uploadKey;
    }

    public void setUploadKey(String uploadKey) {
        this.uploadKey = uploadKey;
    }

    public Map<String, Variant> getVariants() {
        return this.variants;
    }

    public void setVariants(Map<String, Variant> variants) {
        this.variants = variants;
    }

    public Owner getOwner() {
        return this.owner;
    }

    public void setOwner(Owner owner) {
        this.owner = owner;
    }

    public String getFailureCode() {
        return this.failureCode;
    }

    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }

    public String getCreatedBy() {
        return this.createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return this.updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return this.version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}

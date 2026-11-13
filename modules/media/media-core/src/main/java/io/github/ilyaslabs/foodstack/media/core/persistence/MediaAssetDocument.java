package io.github.ilyaslabs.foodstack.media.core.persistence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaStatus;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An uploaded asset in {@code media_assets}. Shard key {@code { _id: hashed }}: always read by id.
 * Variants are a fixed small map and the owner is a single reference, so the document never grows.
 */
@EVL.DataModel(id = "media.asset", name = "Media Asset", module = "media", owner = "media",
        storage = "MongoDB foodstack_media.media_assets (shard key { _id: hashed })")
@EVL.Index(fields = { "status", "createdAt" }, reason = "Index status_created (changeset media-1)")
@Document("media_assets")
public class MediaAssetDocument {

    @Id
    private String id;

    @EVL.Field(meaning = "Why it was uploaded; decides the bucket (public or private) and who may claim it",
            required = true)
    private MediaPurpose purpose;

    @EVL.Field(meaning = "PENDING_UPLOAD, READY or FAILED", required = true)
    private MediaStatus status;

    private String contentType;

    @EVL.Field(meaning = "Size the client said it would upload; the presigned URL only accepts this size",
            unit = "bytes")
    private long declaredBytes;

    /** Key of the raw upload in the private bucket; removed after processing. */
    @EVL.Field(meaning = "Key of the raw upload in the private bucket", example = "uploads/<media id>")
    private String uploadKey;

    @EVL.Field(meaning = "Processed WebP sizes by name (thumb, card, detail): bucket, key, width, height, bytes")
    private Map<String, Variant> variants = new LinkedHashMap<>();

    @EVL.Field(meaning = "The one entity of another module that uses the asset (module + entity id), or null")
    private Owner owner;

    @EVL.Field(meaning = "Why processing rejected the upload", example = "MEDIA_CORRUPT")
    private String failureCode;

    @EVL.Field(meaning = "User id of the uploader; only this user may complete the upload")
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

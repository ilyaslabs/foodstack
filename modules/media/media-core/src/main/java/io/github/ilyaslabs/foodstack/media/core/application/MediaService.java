package io.github.ilyaslabs.foodstack.media.core.application;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaStatus;
import io.github.ilyaslabs.foodstack.media.api.events.MediaReady;
import io.github.ilyaslabs.foodstack.media.core.MediaStorageProperties;
import io.github.ilyaslabs.foodstack.media.core.persistence.MediaAssetDocument;
import io.github.ilyaslabs.foodstack.media.core.persistence.MediaAssetRepository;
import io.github.ilyaslabs.foodstack.media.core.processing.ImageProcessor;
import io.github.ilyaslabs.foodstack.media.core.processing.ImageRejectedException;
import io.github.ilyaslabs.foodstack.media.core.processing.ProcessedImage;
import io.github.ilyaslabs.foodstack.media.core.storage.ObjectStorage;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * Uploads (TDD 13): the client gets a presigned {@code PUT} URL, uploads directly to storage (the
 * server never streams large files), then calls {@link #completeUpload}, which validates and
 * processes the image into WebP variants.
 */
public class MediaService {

    static final Set<String> ACCEPTED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final MediaAssetRepository repository;

    private final ObjectStorage storage;

    private final ImageProcessor processor;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final MediaStorageProperties properties;

    private final Clock clock;

    public MediaService(MediaAssetRepository repository, ObjectStorage storage, ImageProcessor processor,
            OutboxWriter outbox, TransactionTemplate transaction, MediaStorageProperties properties, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.processor = processor;
        this.outbox = outbox;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
    }

    @Flow(value = "media-upload-start>ticket", label = "Create upload ticket")
    public UploadTicket startUpload(StartUpload request, String userId) {
        // @flow media-upload-start>ticket>validate: Check purpose, type (JPEG/PNG/WebP) and size
        if (request.purpose() == null) {
            throw new ModuleException.BadRequest("purpose is required", "MEDIA_PURPOSE_REQUIRED");
        }
        if (!ACCEPTED_TYPES.contains(request.contentType())) {
            throw new ModuleException.BadRequest("Only JPEG, PNG and WebP images are accepted", "MEDIA_UNSUPPORTED_TYPE");
        }
        if (request.sizeBytes() <= 0 || request.sizeBytes() > this.properties.getMaxUploadBytes()) {
            throw new ModuleException.BadRequest("Images must be between 1 byte and "
                    + this.properties.getMaxUploadBytes() + " bytes", "MEDIA_TOO_LARGE");
        }
        // @flow media-upload-start>ticket>save: Save pending asset
        Instant now = this.clock.instant();
        MediaAssetDocument asset = new MediaAssetDocument();
        asset.setId(Ids.newId());
        asset.setPurpose(request.purpose());
        asset.setStatus(MediaStatus.PENDING_UPLOAD);
        asset.setContentType(request.contentType());
        asset.setDeclaredBytes(request.sizeBytes());
        asset.setUploadKey("uploads/" + asset.getId());
        asset.setCreatedBy(userId);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        MediaAssetDocument saved = this.repository.save(asset);
        // @flow media-upload-start>ticket>presign: Presign PUT to the private bucket
        URI url = this.storage.presignUpload(this.properties.getPrivateBucket(), saved.getUploadKey(),
                saved.getContentType(), saved.getDeclaredBytes(), this.properties.getSignedUrlTtl());
        return new UploadTicket(details(saved), url, Map.of("Content-Type", saved.getContentType()),
                now.plus(this.properties.getSignedUrlTtl()));
    }

    /** Validates and processes the uploaded bytes. Only the uploader may complete. */
    @Flow(value = "media-upload-complete>process", label = "Validate and process the upload", description = "Only the uploader may complete")
    public MediaAssetDetails completeUpload(String mediaId, String userId) {
        // @flow media-upload-complete>process>load: Load asset, check uploader
        MediaAssetDocument asset = find(mediaId);
        if (!Objects.equals(asset.getCreatedBy(), userId)) {
            throw new ModuleException.Forbidden("Only the uploader can complete an upload", "MEDIA_NOT_UPLOADER");
        }
        // @flow media-upload-complete>process>status?: Already done?
        // @flow media-upload-complete>process>status?>ready: Return it [when READY]
        if (asset.getStatus() == MediaStatus.READY) {
            return details(asset);
        }
        // @flow media-upload-complete>process>status?>failed: Reject with failure code [when FAILED]
        if (asset.getStatus() == MediaStatus.FAILED) {
            throw new ModuleException.Conflict("The upload was rejected: " + asset.getFailureCode(), asset.getFailureCode());
        }
        // @flow media-upload-complete>process>read: Read uploaded bytes
        byte[] raw = this.storage.read(this.properties.getPrivateBucket(), asset.getUploadKey());
        if (raw == null) {
            throw new ModuleException.Conflict("Nothing was uploaded yet", "MEDIA_NOT_UPLOADED");
        }
        // @flow media-upload-complete>process>convert: Make WebP variants
        List<ProcessedImage> images;
        try {
            images = this.processor.process(raw);
        }
        // @flow media-upload-complete>process>reject: Mark FAILED, delete upload [on error ImageRejectedException]
        catch (ImageRejectedException ex) {
            asset.setStatus(MediaStatus.FAILED);
            asset.setFailureCode(ex.code());
            asset.setUpdatedAt(this.clock.instant());
            this.repository.save(asset);
            this.storage.delete(this.properties.getPrivateBucket(), asset.getUploadKey());
            throw new ModuleException.BadRequest(ex.getMessage(), ex.code());
        }
        // @flow media-upload-complete>process>write: Write variants to the public or private bucket [each variant]
        String bucket = asset.getPurpose().isPublic() ? this.properties.getPublicBucket() : this.properties.getPrivateBucket();
        Map<String, MediaAssetDocument.Variant> variants = new LinkedHashMap<>();
        for (ProcessedImage image : images) {
            String key = asset.getPurpose().name().toLowerCase(java.util.Locale.ROOT) + "/" + asset.getId() + "/"
                    + image.variant().key() + ".webp";
            this.storage.write(bucket, key, image.webp(), "image/webp", asset.getPurpose().isPublic() ? IMMUTABLE : "private");
            variants.put(image.variant().key(),
                    new MediaAssetDocument.Variant(bucket, key, image.width(), image.height(), image.webp().length));
        }
        asset.setVariants(variants);
        asset.setStatus(MediaStatus.READY);
        asset.setUpdatedAt(this.clock.instant());
        // @flow media-upload-complete>process>ready: Save READY and MediaReady event
        MediaAssetDocument saved = this.transaction.execute((status) -> {
            MediaAssetDocument result = this.repository.save(asset);
            this.outbox.append(new MediaReady(result.getId(), result.getPurpose(), List.copyOf(variants.keySet()),
                    result.getCreatedBy()));
            return result;
        });
        // @flow media-upload-complete>process>cleanup: Delete the original [when keep-originals off]
        if (!this.properties.isKeepOriginals()) {
            this.storage.delete(this.properties.getPrivateBucket(), asset.getUploadKey());
        }
        return details(saved);
    }

    @Flow(value = "media-view>load", label = "Load asset, build URLs", description = "public base URL, or a presigned GET for private images")
    public MediaAssetDetails get(String mediaId) {
        return details(find(mediaId));
    }

    /** Records that {@code module}/{@code entityId} uses the asset. */
    @Flow(value = "media-api-claim", label = "Claim image", description = "Records which module entity uses a READY asset")
    @EVL.Endpoint(method = "API", path = "MediaApi.claim", caller = "Settings and store modules")
    public MediaAssetDetails claim(String mediaId, MediaPurpose purpose, String module, String entityId) {
        MediaAssetDocument asset = find(mediaId);
        // @flow media-api-claim>check: Check READY and purpose
        if (asset.getStatus() != MediaStatus.READY) {
            throw MediaErrors.notReady(mediaId);
        }
        if (purpose != null && asset.getPurpose() != purpose) {
            throw new ModuleException.BadRequest("Media " + mediaId + " was uploaded for " + asset.getPurpose(),
                    "MEDIA_PURPOSE_MISMATCH");
        }
        // @flow media-api-claim>owner?: Who owns it now?
        // @flow media-api-claim>owner?>same: Nothing to do [when same owner]
        MediaAssetDocument.Owner owner = new MediaAssetDocument.Owner(module, entityId);
        if (owner.equals(asset.getOwner())) {
            return details(asset);
        }
        // @flow media-api-claim>owner?>taken: Reject MEDIA_ALREADY_CLAIMED [when other owner]
        if (asset.getOwner() != null) {
            throw new ModuleException.Conflict("Media " + mediaId + " is already in use", "MEDIA_ALREADY_CLAIMED");
        }
        // @flow media-api-claim>owner?>save: Save the owner [when free]
        asset.setOwner(owner);
        asset.setUpdatedAt(this.clock.instant());
        return details(this.repository.save(asset));
    }

    @Flow(value = "media-api-release", label = "Release image", description = "Clears the owner when it still matches")
    @EVL.Endpoint(method = "API", path = "MediaApi.release", caller = "Settings and store modules")
    public void release(String mediaId, String module, String entityId) {
        this.repository.findById(mediaId).ifPresent((asset) -> {
            if (asset.getOwner() != null && asset.getOwner().equals(new MediaAssetDocument.Owner(module, entityId))) {
                asset.setOwner(null);
                asset.setUpdatedAt(this.clock.instant());
                this.repository.save(asset);
            }
        });
    }

    private MediaAssetDocument find(String mediaId) {
        if (!Ids.isValid(mediaId)) {
            throw MediaErrors.notFound(mediaId);
        }
        return this.repository.findById(mediaId).orElseThrow(() -> MediaErrors.notFound(mediaId));
    }

    private MediaAssetDetails details(MediaAssetDocument asset) {
        Map<String, MediaAssetDetails.Variant> variants = new LinkedHashMap<>();
        asset.getVariants().forEach((name, variant) -> variants.put(name,
                new MediaAssetDetails.Variant(url(asset.getPurpose(), variant), variant.width(), variant.height(),
                        variant.bytes())));
        MediaAssetDocument.Owner owner = asset.getOwner();
        return new MediaAssetDetails(asset.getId(), asset.getPurpose(), asset.getStatus(), variants,
                (owner != null) ? owner.module() : null, (owner != null) ? owner.entityId() : null, asset.getCreatedAt());
    }

    private String url(MediaPurpose purpose, MediaAssetDocument.Variant variant) {
        if (purpose.isPublic()) {
            return this.properties.getPublicBaseUrl() + "/" + variant.key();
        }
        return this.storage.presignDownload(variant.bucket(), variant.key(), this.properties.getSignedUrlTtl()).toString();
    }
}

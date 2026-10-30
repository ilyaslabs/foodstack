package io.github.ilyaslabs.foodstack.media.core.application;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaStatus;
import io.github.ilyaslabs.foodstack.media.api.events.MediaReady;
import io.github.ilyaslabs.foodstack.media.core.MediaStorageProperties;
import io.github.ilyaslabs.foodstack.media.core.persistence.MediaAssetDocument;
import io.github.ilyaslabs.foodstack.media.core.persistence.MediaAssetRepository;
import io.github.ilyaslabs.foodstack.media.core.processing.ImageProcessor;
import io.github.ilyaslabs.foodstack.media.core.processing.ImageRejectedException;
import io.github.ilyaslabs.foodstack.media.core.processing.ImageVariant;
import io.github.ilyaslabs.foodstack.media.core.processing.ProcessedImage;
import io.github.ilyaslabs.foodstack.media.core.storage.ObjectStorage;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MediaServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    private final MediaAssetRepository repository = mock(MediaAssetRepository.class);

    private final ObjectStorage storage = mock(ObjectStorage.class);

    private final ImageProcessor processor = mock(ImageProcessor.class);

    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    private final MediaStorageProperties properties = new MediaStorageProperties();

    private MediaService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        this.properties.setPublicEndpoint("https://food.example.com");
        given(this.repository.save(any())).willAnswer((invocation) -> invocation.getArgument(0));
        given(this.transaction.execute(any())).willAnswer(
                (invocation) -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        this.service = new MediaService(this.repository, this.storage, this.processor, this.outbox, this.transaction,
                this.properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("issues a presigned upload URL into the private bucket for a pending asset")
    void startUpload() {
        // given
        given(this.storage.presignUpload(eq("foodstack-private"), any(), eq("image/png"), eq(2048L), any()))
            .willReturn(URI.create("https://food.example.com/foodstack-private/uploads/x?sig"));

        // when
        UploadTicket ticket = this.service.startUpload(new StartUpload(MediaPurpose.BRANDING, "image/png", 2048), "user-1");

        // then
        assertThat(ticket.asset().status()).isEqualTo(MediaStatus.PENDING_UPLOAD);
        assertThat(ticket.uploadUrl().toString()).startsWith("https://food.example.com/foodstack-private/");
        assertThat(ticket.headers()).containsEntry("Content-Type", "image/png");
        assertThat(ticket.expiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    @DisplayName("refuses unsupported types and oversized uploads before issuing a URL")
    void rejectsBadUploads() {
        assertThatThrownBy(() -> this.service.startUpload(new StartUpload(MediaPurpose.BRANDING, "image/svg+xml", 10), "u"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("MEDIA_UNSUPPORTED_TYPE"));
        assertThatThrownBy(() -> this.service.startUpload(new StartUpload(MediaPurpose.BRANDING, "image/png", 11L * 1024 * 1024), "u"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("MEDIA_TOO_LARGE"));
        verify(this.storage, never()).presignUpload(any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("processes the upload into public WebP variants, deletes the raw upload and publishes MediaReady")
    void completeUpload() {
        // given
        MediaAssetDocument asset = pending(MediaPurpose.BRANDING);
        given(this.repository.findById(asset.getId())).willReturn(Optional.of(asset));
        given(this.storage.read("foodstack-private", asset.getUploadKey())).willReturn(new byte[] { 1 });
        given(this.processor.process(any())).willReturn(List.of(new ProcessedImage(ImageVariant.THUMB, 160, 80, new byte[10]),
                new ProcessedImage(ImageVariant.CARD, 480, 240, new byte[20])));

        // when
        MediaAssetDetails details = this.service.completeUpload(asset.getId(), "user-1");

        // then
        assertThat(details.status()).isEqualTo(MediaStatus.READY);
        assertThat(details.variants().get("thumb").url())
            .isEqualTo("https://food.example.com/foodstack-public/branding/" + asset.getId() + "/thumb.webp");
        verify(this.storage).write(eq("foodstack-public"), eq("branding/" + asset.getId() + "/card.webp"), any(),
                eq("image/webp"), eq("public, max-age=31536000, immutable"));
        verify(this.storage).delete("foodstack-private", asset.getUploadKey());
        ArgumentCaptor<MediaReady> event = ArgumentCaptor.forClass(MediaReady.class);
        verify(this.outbox).append(event.capture());
        assertThat(event.getValue()).isEqualTo(new MediaReady(asset.getId(), MediaPurpose.BRANDING, List.of("thumb", "card"), "user-1"));
    }

    @Test
    @DisplayName("marks a rejected upload as failed and removes it")
    void rejectedUpload() {
        // given
        MediaAssetDocument asset = pending(MediaPurpose.BRANDING);
        given(this.repository.findById(asset.getId())).willReturn(Optional.of(asset));
        given(this.storage.read(any(), any())).willReturn(new byte[] { 1 });
        given(this.processor.process(any())).willThrow(new ImageRejectedException("MEDIA_CORRUPT", "bad"));

        // when / then
        assertThatThrownBy(() -> this.service.completeUpload(asset.getId(), "user-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("MEDIA_CORRUPT"));
        assertThat(asset.getStatus()).isEqualTo(MediaStatus.FAILED);
        verify(this.storage).delete("foodstack-private", asset.getUploadKey());
        verify(this.outbox, never()).append(any());
    }

    @Test
    @DisplayName("lets only the uploader complete an upload")
    void onlyUploader() {
        MediaAssetDocument asset = pending(MediaPurpose.BRANDING);
        given(this.repository.findById(asset.getId())).willReturn(Optional.of(asset));

        assertThatThrownBy(() -> this.service.completeUpload(asset.getId(), "someone-else"))
            .isInstanceOf(ModuleException.Forbidden.class);
    }

    @Test
    @DisplayName("serves private variants through short-lived signed URLs")
    void privateUrls() {
        // given
        MediaAssetDocument asset = pending(MediaPurpose.RIDER_DOCUMENT);
        asset.setStatus(MediaStatus.READY);
        asset.setVariants(Map.of("thumb", new MediaAssetDocument.Variant("foodstack-private", "k", 160, 80, 10)));
        given(this.repository.findById(asset.getId())).willReturn(Optional.of(asset));
        given(this.storage.presignDownload(eq("foodstack-private"), eq("k"), any())).willReturn(URI.create("https://signed"));

        // when / then
        assertThat(this.service.get(asset.getId()).variants().get("thumb").url()).isEqualTo("https://signed");
    }

    @Test
    @DisplayName("claims a ready asset for one owner and refuses a second owner")
    void claim() {
        // given
        MediaAssetDocument asset = pending(MediaPurpose.BRANDING);
        asset.setStatus(MediaStatus.READY);
        given(this.repository.findById(asset.getId())).willReturn(Optional.of(asset));

        // when
        MediaAssetDetails claimed = this.service.claim(asset.getId(), MediaPurpose.BRANDING, "settings", "branding");

        // then
        assertThat(claimed.ownerModule()).isEqualTo("settings");
        assertThat(this.service.claim(asset.getId(), MediaPurpose.BRANDING, "settings", "branding").ownerEntityId())
            .isEqualTo("branding");
        assertThatThrownBy(() -> this.service.claim(asset.getId(), MediaPurpose.BRANDING, "store", "s1"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("MEDIA_ALREADY_CLAIMED"));
    }

    @Test
    @DisplayName("refuses to claim assets that are not ready or were uploaded for another purpose")
    void claimRules() {
        MediaAssetDocument pending = pending(MediaPurpose.BRANDING);
        MediaAssetDocument menuPhoto = pending(MediaPurpose.MENU_ITEM);
        menuPhoto.setStatus(MediaStatus.READY);
        given(this.repository.findById(pending.getId())).willReturn(Optional.of(pending));
        given(this.repository.findById(menuPhoto.getId())).willReturn(Optional.of(menuPhoto));

        assertThatThrownBy(() -> this.service.claim(pending.getId(), MediaPurpose.BRANDING, "settings", "branding"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("MEDIA_NOT_READY"));
        assertThatThrownBy(() -> this.service.claim(menuPhoto.getId(), MediaPurpose.BRANDING, "settings", "branding"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("MEDIA_PURPOSE_MISMATCH"));
    }

    @Test
    @DisplayName("answers 404 for unknown and malformed ids")
    void notFound() {
        String id = Ids.newId();
        given(this.repository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.get(id)).isInstanceOf(ModuleException.NotFound.class);
        assertThatThrownBy(() -> this.service.get("../etc")).isInstanceOf(ModuleException.NotFound.class);
    }

    private static MediaAssetDocument pending(MediaPurpose purpose) {
        MediaAssetDocument asset = new MediaAssetDocument();
        asset.setId(Ids.newId());
        asset.setPurpose(purpose);
        asset.setStatus(MediaStatus.PENDING_UPLOAD);
        asset.setContentType("image/png");
        asset.setDeclaredBytes(2048);
        asset.setUploadKey("uploads/" + asset.getId());
        asset.setCreatedBy("user-1");
        asset.setCreatedAt(NOW);
        return asset;
    }
}

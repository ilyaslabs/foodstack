package io.github.ilyaslabs.foodstack.media.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.core.application.MediaService;
import io.github.ilyaslabs.foodstack.media.core.application.StartUpload;
import io.github.ilyaslabs.foodstack.media.web.dto.MediaAssetResponse;
import io.github.ilyaslabs.foodstack.media.web.dto.StartUploadRequest;
import io.github.ilyaslabs.foodstack.media.web.dto.UploadTicketResponse;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Uploads straight to object storage, then processing (TDD 13). */
@EVL.Component(id = "media.controller", name = "Media Controller", module = "media",
        layer = EVL.Layer.PRESENTATION, responsibilities = "REST API under /api/v1/media")
@RestController
@RequestMapping("/api/v1/media")
class MediaController {

    private final MediaService mediaService;

    MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @EVL.Endpoint(id = "media.start-upload", name = "Start Upload", protocol = EVL.Protocol.HTTP, method = "POST",
            path = "/api/v1/media/uploads", requestRef = "media.start-upload-request",
            responseRef = "media.upload-ticket-response", flow = "media.image-upload",
            auth = "media:upload, or media:upload:review for REVIEW photos only",
            errors = { "media.error.purpose-denied", "media.error.purpose-required", "media.error.unsupported-type",
                    "media.error.too-large" })
    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('media:upload', 'media:upload:review')")
    UploadTicketResponse startUpload(@Valid @RequestBody StartUploadRequest request) {
        if (!CurrentUser.hasAuthority("media:upload") && request.purpose() != MediaPurpose.REVIEW) {
            // customers upload review photos only
            throw new ModuleException.Forbidden("Only review photos can be uploaded", "MEDIA_PURPOSE_DENIED");
        }
        return MediaWebMapper.toResponse(this.mediaService.startUpload(
                new StartUpload(request.purpose(), request.contentType(), request.sizeBytes()), CurrentUser.id()));
    }

    @EVL.Endpoint(id = "media.complete-upload", name = "Complete Upload", protocol = EVL.Protocol.HTTP,
            method = "POST", path = "/api/v1/media/uploads/{mediaId}/complete", responseRef = "media.asset-response",
            flow = "media.image-upload", auth = "media:upload or media:upload:review; only the uploader",
            errors = { "media.error.not-found", "media.error.not-uploader", "media.error.not-uploaded",
                    "media.error.unsupported-type", "media.error.too-large", "media.error.corrupt",
                    "media.error.rejected-before" })
    @PostMapping("/uploads/{mediaId}/complete")
    @PreAuthorize("hasAnyAuthority('media:upload', 'media:upload:review')")
    MediaAssetResponse complete(@PathVariable String mediaId) {
        return MediaWebMapper.toResponse(this.mediaService.completeUpload(mediaId, CurrentUser.id()));
    }

    @EVL.Endpoint(id = "media.get-asset", name = "Get Asset", protocol = EVL.Protocol.HTTP, method = "GET",
            path = "/api/v1/media/{mediaId}", responseRef = "media.asset-response", auth = "Signed in",
            errors = "media.error.not-found")
    @GetMapping("/{mediaId}")
    MediaAssetResponse get(@PathVariable String mediaId) {
        return MediaWebMapper.toResponse(this.mediaService.get(mediaId));
    }
}

package io.github.ilyaslabs.foodstack.media.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
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
@RestController
@RequestMapping("/api/v1/media")
class MediaController {

    private final MediaService mediaService;

    MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('media:upload', 'media:upload:review')")
    @Flow(value = "media-start-upload", label = "Start image upload", description = "Checks purpose, saves a pending asset and returns a signed upload URL")
    @EVL.Endpoint(method = "POST", path = "/api/v1/media/uploads", caller = "Back office and customer web")
    UploadTicketResponse startUpload(@Valid @RequestBody StartUploadRequest request) {
        if (!CurrentUser.hasAuthority("media:upload") && request.purpose() != MediaPurpose.REVIEW) {
            // customers upload review photos only
            // @flow media-start-upload>purpose: Refuse customers anything but review photos [on error MEDIA_PURPOSE_DENIED]
            throw new ModuleException.Forbidden("Only review photos can be uploaded", "MEDIA_PURPOSE_DENIED");
        }
        return MediaWebMapper.toResponse(this.mediaService.startUpload(
                new StartUpload(request.purpose(), request.contentType(), request.sizeBytes()), CurrentUser.id()));
    }

    @PostMapping("/uploads/{mediaId}/complete")
    @PreAuthorize("hasAnyAuthority('media:upload', 'media:upload:review')")
    @Flow(value = "media-complete-upload", label = "Finish image upload", description = "Reads the uploaded bytes, makes WebP variants and marks the asset ready")
    @EVL.Endpoint(method = "POST", path = "/api/v1/media/uploads/{mediaId}/complete", caller = "Back office and customer web")
    MediaAssetResponse complete(@PathVariable String mediaId) {
        return MediaWebMapper.toResponse(this.mediaService.completeUpload(mediaId, CurrentUser.id()));
    }

    @GetMapping("/{mediaId}")
    @Flow(value = "media-get", label = "Show image asset")
    @EVL.Endpoint(method = "GET", path = "/api/v1/media/{mediaId}", caller = "Customer web")
    MediaAssetResponse get(@PathVariable String mediaId) {
        return MediaWebMapper.toResponse(this.mediaService.get(mediaId));
    }
}

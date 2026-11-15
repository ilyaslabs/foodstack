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
@EVL.Endpoint(path = "/api/v1/media")
@RequestMapping("/api/v1/media")
class MediaController {

    private final MediaService mediaService;

    MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @Flow(value = "media-upload-start", label = "Start image upload", description = "Creates a pending asset and a presigned PUT URL; the client uploads straight to storage")
    @EVL.Endpoint(method = "POST", path = "/uploads", caller = "Back office / customer app")
    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('media:upload', 'media:upload:review')")
    UploadTicketResponse startUpload(@Valid @RequestBody StartUploadRequest request) {
        // @flow media-upload-start>purpose: Customers may upload review photos only [when no media:upload]
        if (!CurrentUser.hasAuthority("media:upload") && request.purpose() != MediaPurpose.REVIEW) {
            // customers upload review photos only
            throw new ModuleException.Forbidden("Only review photos can be uploaded", "MEDIA_PURPOSE_DENIED");
        }
        return MediaWebMapper.toResponse(this.mediaService.startUpload(
                new StartUpload(request.purpose(), request.contentType(), request.sizeBytes()), CurrentUser.id()));
    }

    @Flow(value = "media-upload-complete", label = "Finish image upload", description = "Validates the uploaded bytes and stores WebP variants")
    @EVL.Endpoint(method = "POST", path = "/uploads/{mediaId}/complete", caller = "Back office / customer app")
    @PostMapping("/uploads/{mediaId}/complete")
    @PreAuthorize("hasAnyAuthority('media:upload', 'media:upload:review')")
    MediaAssetResponse complete(@PathVariable String mediaId) {
        return MediaWebMapper.toResponse(this.mediaService.completeUpload(mediaId, CurrentUser.id()));
    }

    @Flow(value = "media-view", label = "Get image", description = "An asset with its variant URLs")
    @EVL.Endpoint(method = "GET", path = "/{mediaId}", caller = "Any app")
    @GetMapping("/{mediaId}")
    MediaAssetResponse get(@PathVariable String mediaId) {
        return MediaWebMapper.toResponse(this.mediaService.get(mediaId));
    }
}

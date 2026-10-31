package io.github.ilyaslabs.foodstack.media.web;

import io.github.ilyaslabs.foodstack.media.core.application.MediaService;
import io.github.ilyaslabs.foodstack.media.core.application.StartUpload;
import io.github.ilyaslabs.foodstack.media.web.dto.MediaAssetResponse;
import io.github.ilyaslabs.foodstack.media.web.dto.StartUploadRequest;
import io.github.ilyaslabs.foodstack.media.web.dto.UploadTicketResponse;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
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
    @PreAuthorize("hasAuthority('media:upload')")
    UploadTicketResponse startUpload(@Valid @RequestBody StartUploadRequest request) {
        return MediaWebMapper.toResponse(this.mediaService.startUpload(
                new StartUpload(request.purpose(), request.contentType(), request.sizeBytes()), CurrentUser.id()));
    }

    @PostMapping("/uploads/{mediaId}/complete")
    @PreAuthorize("hasAuthority('media:upload')")
    MediaAssetResponse complete(@PathVariable String mediaId) {
        return MediaWebMapper.toResponse(this.mediaService.completeUpload(mediaId, CurrentUser.id()));
    }

    @GetMapping("/{mediaId}")
    MediaAssetResponse get(@PathVariable String mediaId) {
        return MediaWebMapper.toResponse(this.mediaService.get(mediaId));
    }
}

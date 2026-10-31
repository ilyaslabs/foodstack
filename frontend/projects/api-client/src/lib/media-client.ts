import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, switchMap } from 'rxjs';

import { MediaAsset, MediaPurpose, UploadTicket } from './models';
import { RUNTIME_CONFIG } from './runtime-config';

/** Uploads go straight to object storage with a short-lived ticket; the server then processes them. */
@Injectable({ providedIn: 'root' })
export class MediaClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RUNTIME_CONFIG);

  upload(file: File, purpose: MediaPurpose): Observable<MediaAsset> {
    return this.http
      .post<UploadTicket>(`${this.config.apiUrl}/api/v1/media/uploads`, {
        purpose,
        contentType: file.type,
        sizeBytes: file.size,
      })
      .pipe(
        switchMap((ticket) =>
          this.http
            .request(ticket.method, ticket.uploadUrl, { body: file, headers: ticket.headers })
            .pipe(
              switchMap(() =>
                this.http.post<MediaAsset>(
                  `${this.config.apiUrl}/api/v1/media/uploads/${ticket.media.id}/complete`,
                  null,
                ),
              ),
            ),
        ),
      );
  }
}

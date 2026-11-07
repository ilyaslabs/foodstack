import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { TwoStepClient } from '@foodstack/api-client';

import { describeError } from '../shared/errors';

/** One client id per line, blank lines and spaces dropped. */
function lines(text: string): string[] {
  return text
    .split(/[\n,]/)
    .map((line) => line.trim())
    .filter(Boolean);
}

/**
 * Google and Apple sign-in for customers (PRD ID-2): each is on when it has client ids. The
 * customer app signs in with the provider itself and hands its ID token over; FoodStack only
 * accepts tokens issued for these ids.
 */
@Component({
  selector: 'app-sign-in-settings-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatSnackBarModule,
  ],
  template: `
    <header class="fs-page-header">
      <h1>Sign-in options</h1>
      <p>
        Let customers continue with Google or Apple. New customers get an account with the email the
        provider confirmed; someone who already has an account with that email signs in to it.
      </p>
    </header>

    @if (loaded()) {
      <mat-card appearance="outlined">
        <form (ngSubmit)="save()">
          <div class="card-section">
            <div class="toggle-row">
              <h2>Google</h2>
              <mat-slide-toggle
                name="googleOn"
                [(ngModel)]="googleOn"
                aria-label="Google sign-in"
              />
            </div>
            <p class="section-hint">
              Create an OAuth client of type “Web application” in Google Cloud, with this site as an
              authorised JavaScript origin. Add Android or iOS client ids below it for the mobile
              apps.
            </p>
            @if (googleOn) {
              <mat-form-field appearance="outline">
                <mat-label>Client ids, the web one first</mat-label>
                <textarea
                  matInput
                  name="google"
                  [(ngModel)]="google"
                  rows="3"
                  placeholder="1234-abc.apps.googleusercontent.com"
                ></textarea>
                <mat-hint>One per line</mat-hint>
              </mat-form-field>
            }
          </div>
          <div class="card-section">
            <div class="toggle-row">
              <h2>Apple</h2>
              <mat-slide-toggle name="appleOn" [(ngModel)]="appleOn" aria-label="Apple sign-in" />
            </div>
            <p class="section-hint">
              Create a Services ID with Sign in with Apple in your Apple developer account, with
              this site as a domain and <code>{{ origin }}/sign-in</code> as the return URL. Add the
              iOS app's bundle id below it.
            </p>
            @if (appleOn) {
              <mat-form-field appearance="outline">
                <mat-label>Services ID first, then bundle ids</mat-label>
                <textarea
                  matInput
                  name="apple"
                  [(ngModel)]="apple"
                  rows="3"
                  placeholder="com.example.web"
                ></textarea>
                <mat-hint>One per line</mat-hint>
              </mat-form-field>
            }
          </div>
          @if (error()) {
            <div class="banner error" role="alert">{{ error() }}</div>
          }
          <div class="actions">
            <button mat-flat-button type="submit" [disabled]="saving()">Save</button>
          </div>
        </form>
      </mat-card>
    } @else if (loadError()) {
      <p>The sign-in options could not be loaded. Reload the page to try again.</p>
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }
  `,
  styleUrls: ['../shared/page.scss'],
  styles: `
    mat-card {
      max-inline-size: 720px;
    }
    .toggle-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
    }
    code {
      font-family: 'Roboto Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      overflow-wrap: anywhere;
    }
  `,
})
export class SignInSettingsPage implements OnInit {
  private readonly client = inject(TwoStepClient);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly loaded = signal(false);
  protected readonly loadError = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly origin = globalThis.location?.origin ?? '';

  protected googleOn = false;
  protected appleOn = false;
  protected google = '';
  protected apple = '';

  async ngOnInit(): Promise<void> {
    try {
      this.apply(await firstValueFrom(this.client.providers()));
      this.loaded.set(true);
    } catch {
      this.loadError.set(true);
    }
  }

  protected async save(): Promise<void> {
    const googleClientIds = this.googleOn ? lines(this.google) : [];
    const appleClientIds = this.appleOn ? lines(this.apple) : [];
    if (this.googleOn && googleClientIds.length === 0) {
      this.error.set('Enter the Google client id, or turn Google off.');
      return;
    }
    if (this.appleOn && appleClientIds.length === 0) {
      this.error.set('Enter the Apple Services ID, or turn Apple off.');
      return;
    }
    this.error.set('');
    this.saving.set(true);
    try {
      this.apply(
        await firstValueFrom(this.client.updateProviders({ googleClientIds, appleClientIds })),
      );
      this.snackBar.open(
        'Saved. The customer app shows the change within five minutes.',
        undefined,
        {
          duration: 4000,
        },
      );
    } catch (error) {
      this.error.set(describeError(error, 'The sign-in options could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }

  private apply(providers: { googleClientIds: string[]; appleClientIds: string[] }): void {
    this.google = providers.googleClientIds.join('\n');
    this.apple = providers.appleClientIds.join('\n');
    this.googleOn = providers.googleClientIds.length > 0;
    this.appleOn = providers.appleClientIds.length > 0;
  }
}

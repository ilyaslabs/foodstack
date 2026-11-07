import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';

/** Google Identity Services: signs in on the page and hands back an ID token (PRD ID-2). */
const GOOGLE_SCRIPT = 'https://accounts.google.com/gsi/client';

/** Sign in with Apple JS, in a popup, so the page never leaves the app. */
const APPLE_SCRIPT =
  'https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js';

interface GoogleAccounts {
  id: {
    initialize(options: {
      client_id: string;
      callback: (response: { credential?: string }) => void;
      ux_mode?: 'popup';
      use_fedcm_for_prompt?: boolean;
      context?: 'signin' | 'signup' | 'use';
    }): void;
    renderButton(
      element: HTMLElement,
      options: {
        type?: 'standard';
        theme?: 'outline' | 'filled_black';
        size?: 'large';
        text?: 'continue_with';
        shape?: 'pill' | 'rectangular';
        width?: number;
        locale?: string;
      },
    ): void;
  };
}

interface AppleAuth {
  auth: {
    init(options: {
      clientId: string;
      scope: string;
      redirectURI: string;
      usePopup: boolean;
    }): void;
    signIn(): Promise<{
      authorization: { id_token: string };
      user?: { name?: { firstName?: string; lastName?: string } };
    }>;
  };
}

/** What Apple's popup gave back: the ID token, and the name (sent only on the first sign-in). */
export interface AppleSignIn {
  idToken: string;
  name?: string;
}

/**
 * Loads each provider's own script once, only when the operator switched the provider on, and
 * only in the browser. FoodStack never sees the provider password; the script hands the page an
 * ID token that the server checks against the provider's published keys.
 */
@Injectable({ providedIn: 'root' })
export class SocialScripts {
  private readonly document = inject(DOCUMENT);
  private readonly loading = new Map<string, Promise<void>>();

  /** Draws Google's own button into `element`; `onToken` gets the ID token after a sign-in. */
  async renderGoogle(
    clientId: string,
    element: HTMLElement,
    onToken: (idToken: string) => void,
    options: { width: number; dark: boolean; locale?: string },
  ): Promise<void> {
    await this.load(GOOGLE_SCRIPT);
    const google = (this.window() as { google?: { accounts: GoogleAccounts } }).google;
    if (!google) throw new Error('Google sign-in did not load');
    google.accounts.id.initialize({
      client_id: clientId,
      ux_mode: 'popup',
      context: 'signin',
      callback: (response) => {
        if (response.credential) onToken(response.credential);
      },
    });
    google.accounts.id.renderButton(element, {
      type: 'standard',
      theme: options.dark ? 'filled_black' : 'outline',
      size: 'large',
      text: 'continue_with',
      shape: 'pill',
      width: Math.min(Math.max(Math.round(options.width), 200), 400),
      locale: options.locale,
    });
  }

  /** Opens Apple's sign-in popup; rejects when the person closes it. */
  async signInWithApple(clientId: string): Promise<AppleSignIn> {
    await this.load(APPLE_SCRIPT);
    const apple = (this.window() as { AppleID?: AppleAuth }).AppleID;
    if (!apple) throw new Error('Sign in with Apple did not load');
    const origin = this.window().location.origin;
    apple.auth.init({
      clientId,
      scope: 'name email',
      redirectURI: `${origin}/sign-in`,
      usePopup: true,
    });
    const result = await apple.auth.signIn();
    const name = [result.user?.name?.firstName, result.user?.name?.lastName]
      .filter(Boolean)
      .join(' ');
    return { idToken: result.authorization.id_token, name: name || undefined };
  }

  private load(src: string): Promise<void> {
    let pending = this.loading.get(src);
    if (!pending) {
      pending = new Promise<void>((resolve, reject) => {
        const script = this.document.createElement('script');
        script.src = src;
        script.async = true;
        script.onload = () => resolve();
        script.onerror = () => {
          this.loading.delete(src);
          script.remove();
          reject(new Error(`Could not load ${src}`));
        };
        this.document.head.appendChild(script);
      });
      this.loading.set(src, pending);
    }
    return pending;
  }

  private window(): Window {
    const view = this.document.defaultView;
    if (!view) throw new Error('No window');
    return view;
  }
}

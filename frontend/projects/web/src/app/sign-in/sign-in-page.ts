import { Component, computed, inject } from '@angular/core';

import { SignIn } from '@foodstack/auth';
import { LocaleStore } from '@foodstack/ui';

import { SIGN_IN_TRANSLATIONS } from './sign-in-texts';

/** Sign in or create an account inside the storefront, in the customer's language. */
@Component({
  selector: 'app-sign-in-page',
  imports: [SignIn],
  template: `
    <div class="page">
      <fs-sign-in [allowSignUp]="true" [texts]="texts()" />
    </div>
  `,
  styles: `
    .page {
      padding: 40px 16px 64px;
    }
    @media (max-width: 480px) {
      .page {
        padding-block-start: 20px;
      }
    }
  `,
})
export class SignInPage {
  private readonly locale = inject(LocaleStore);

  protected readonly texts = computed(() => SIGN_IN_TRANSLATIONS[this.locale.language()] ?? {});
}

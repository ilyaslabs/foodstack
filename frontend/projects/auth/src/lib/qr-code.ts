import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import qrcode from 'qrcode-generator';

/** The module grid; the typings leave these two out. */
interface QrMatrix {
  getModuleCount(): number;
  isDark(row: number, column: number): boolean;
}

/**
 * A QR code drawn as one SVG path, black on white whatever the theme, since phone cameras read
 * dark-on-light best. Built in the page: the secret never goes to a QR service.
 */
@Component({
  selector: 'fs-qr-code',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      role="img"
      [attr.aria-label]="label()"
      [attr.viewBox]="'0 0 ' + code().size + ' ' + code().size"
      shape-rendering="crispEdges"
    >
      <rect [attr.width]="code().size" [attr.height]="code().size" fill="#fff" />
      <path [attr.d]="code().path" fill="#000" />
    </svg>
  `,
  styles: `
    :host {
      display: inline-block;
      inline-size: 192px;
      block-size: 192px;
      padding: 8px;
      border-radius: var(--mat-sys-corner-medium);
      background: #fff;
    }
    svg {
      display: block;
      inline-size: 100%;
      block-size: 100%;
    }
  `,
})
export class QrCode {
  readonly value = input.required<string>();
  readonly label = input('QR code');

  protected readonly code = computed(() => {
    const qr = qrcode(0, 'M');
    qr.addData(this.value());
    qr.make();
    const matrix = qr as unknown as QrMatrix;
    const count = matrix.getModuleCount();
    const quiet = 2;
    let path = '';
    for (let row = 0; row < count; row++) {
      for (let column = 0; column < count; column++) {
        if (matrix.isDark(row, column)) path += `M${column + quiet} ${row + quiet}h1v1h-1z`;
      }
    }
    return { size: count + quiet * 2, path };
  });
}

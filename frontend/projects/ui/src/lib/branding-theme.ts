import {
  DynamicScheme,
  Hct,
  MaterialDynamicColors,
  SchemeTonalSpot,
  TonalPalette,
  argbFromHex,
  hexFromArgb,
} from '@material/material-color-utilities';

/** The Material 3 system colour roles the components read, as `--mat-sys-<role>`. */
const ROLES = [
  'primary',
  'onPrimary',
  'primaryContainer',
  'onPrimaryContainer',
  'secondary',
  'onSecondary',
  'secondaryContainer',
  'onSecondaryContainer',
  'tertiary',
  'onTertiary',
  'tertiaryContainer',
  'onTertiaryContainer',
  'error',
  'onError',
  'errorContainer',
  'onErrorContainer',
  'background',
  'onBackground',
  'surface',
  'onSurface',
  'surfaceVariant',
  'onSurfaceVariant',
  'surfaceDim',
  'surfaceBright',
  'surfaceContainerLowest',
  'surfaceContainerLow',
  'surfaceContainer',
  'surfaceContainerHigh',
  'surfaceContainerHighest',
  'inverseSurface',
  'inverseOnSurface',
  'inversePrimary',
  'outline',
  'outlineVariant',
  'shadow',
  'scrim',
  'surfaceTint',
] as const;

type Role = (typeof ROLES)[number];

function kebab(role: string): string {
  return role.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`);
}

/** Every colour role of the scheme built from the operator's two brand colours. */
export function brandColors(
  primaryHex: string,
  secondaryHex: string,
  dark: boolean,
): Record<string, string> {
  const primary = argbFromHex(primaryHex);
  const secondary = argbFromHex(secondaryHex);
  const source = Hct.fromInt(primary);
  // Tonal spot, Material's default, with the operator's own secondary colour instead of a derived one.
  const base = new SchemeTonalSpot(source, dark, 0);
  const scheme = new DynamicScheme({
    sourceColorArgb: primary,
    variant: base.variant,
    contrastLevel: 0,
    isDark: dark,
    primaryPalette: TonalPalette.fromInt(primary),
    secondaryPalette: TonalPalette.fromInt(secondary),
    // A grey or black brand colour has no real hue (HCT reports one anyway), so its tints fade to
    // pure grey instead of turning every background faintly pink.
    tertiaryPalette: TonalPalette.fromHueAndChroma(
      (source.hue + 60) % 360,
      Math.min(24, source.chroma),
    ),
    neutralPalette: TonalPalette.fromHueAndChroma(source.hue, Math.min(6, source.chroma / 8)),
    neutralVariantPalette: TonalPalette.fromHueAndChroma(
      source.hue,
      Math.min(8, source.chroma / 6),
    ),
  });
  const colors: Record<string, string> = {};
  for (const role of ROLES) {
    const color = (
      MaterialDynamicColors as unknown as Record<Role, { getArgb(s: DynamicScheme): number }>
    )[role];
    colors[`--mat-sys-${kebab(role)}`] = hexFromArgb(color.getArgb(scheme));
  }
  return colors;
}

/** A stylesheet that sets the brand tokens for light and dark mode. */
export function brandStylesheet(
  primaryHex: string,
  secondaryHex: string,
  fontFamily: string,
): string {
  const block = (colors: Record<string, string>) =>
    Object.entries(colors)
      .map(([name, value]) => `${name}: ${value};`)
      .join(' ');
  const font = fontFamily.replace(/[^A-Za-z0-9 \-]/g, '');
  return (
    `:root { ${block(brandColors(primaryHex, secondaryHex, false))} --fs-font-family: '${font}', '${font} Variable', system-ui, sans-serif; }\n` +
    `@media (prefers-color-scheme: dark) { :root { ${block(brandColors(primaryHex, secondaryHex, true))} } }`
  );
}

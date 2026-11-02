/**
 * Warm, food-like colours for places without a photo: tomato, saffron, chilli, olive, aubergine,
 * cinnamon, mint and paprika. The same name always gets the same colour.
 */
const COLOURS = [
  ['#e4572e', '#f3a712'],
  ['#d1495b', '#edae49'],
  ['#c0392b', '#e67e22'],
  ['#6a994e', '#a7c957'],
  ['#7b2d63', '#c94277'],
  ['#9c6644', '#ddb892'],
  ['#2a9d8f', '#8ab17d'],
  ['#bc4b51', '#f4a259'],
] as const;

function pick(name: string): readonly [string, string] {
  let hash = 0;
  for (const char of name) {
    hash = (hash * 31 + char.codePointAt(0)!) | 0;
  }
  return COLOURS[Math.abs(hash) % COLOURS.length];
}

/** One solid colour for a small mark. */
export function foodColour(name: string): string {
  return pick(name)[0];
}

/** A soft diagonal gradient for a cover. */
export function foodGradient(name: string): string {
  const [from, to] = pick(name);
  return `linear-gradient(135deg, ${from} 0%, ${to} 100%)`;
}

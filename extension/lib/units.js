/**
 * The unit boundary.
 *
 * docs/sync-protocol.md §1: everything that crosses the network is an integer count of
 * micrometres. Pixels stay local to whichever client measured them, because a pixel is not
 * a length -- a 560dpi phone logs 1.75x the pixels of a 320dpi phone for the same physical
 * thumb travel, and a browser's CSS pixel is related to neither.
 *
 * A CSS pixel *is* a length, though, and an exact one: CSS Values §5.2 defines it as 1/96
 * of an inch. That is the whole reason the browser conversion is a constant and not a
 * device lookup. It is also why we ignore `devicePixelRatio` (a rendering detail, not a
 * length) and browser zoom (which rescales the CSS pixel away from 1/96in, but is not
 * observable from script with any accuracy -- and a user who zooms to 150% genuinely is
 * moving their thumb further per line of feed, so counting it at 1/96in undercounts them
 * rather than inventing distance).
 */

/** 1 inch = 25400 µm, exactly. */
export const MICROMETRES_PER_INCH = 25400;

/** CSS Values §5.2. Not a guess, a definition. */
export const CSS_PIXELS_PER_INCH = 96;

/**
 * The contract's formula, written in the contract's order: `round(cssPixels / 96 * 25400)`.
 * The order matters -- `px / 96 * 25400` and `px * 25400 / 96` differ in the last bit for
 * some inputs, and the Android side is specified the same way, so both clients round the
 * same double.
 */
export function cssPixelsToMicrometres(cssPixels) {
  return Math.round((cssPixels / CSS_PIXELS_PER_INCH) * MICROMETRES_PER_INCH);
}

/** µm -> m, for display only. Never send this across the network. */
export function micrometresToMetres(um) {
  return um / 1_000_000;
}

/**
 * Java's `String.format("%.Nf", d)` rounds the *shortest round-trip decimal* of the double
 * (HALF_UP), where JS `toFixed` rounds the exact binary value. They disagree on every
 * decimal tie: 1.005 is really 1.00499999999999989342, so Java prints "1.01" and toFixed
 * prints "1.00". At one decimal -- what `comparison` uses -- 1.95 prints "2.0" and "1.9".
 * Neither is more correct; they are two defensible readings of the same double, and the
 * only thing that matters is that both clients pick the same one.
 *
 * `formatDistance` and `comparison` have to read identically on the phone and in the
 * browser -- a user comparing the two screens must not see 1.23 km here and 1.24 km there
 * -- so this reproduces Java's rule instead: take `String(value)`, which is by
 * specification the shortest decimal that round-trips (the same string Java's formatter
 * starts from), then round that decimal string half-up.
 */
export function formatFixed(value, digits) {
  if (!Number.isFinite(value)) return String(value);
  const negative = value < 0;
  let text = String(Math.abs(value));
  // Only reachable past 1e21 (or below 1e-7), i.e. never for a distance. Fall back to
  // toFixed rather than mis-parse an exponent.
  if (text.includes('e') || text.includes('E')) return value.toFixed(digits);

  const [whole, fraction = ''] = text.split('.');
  if (fraction.length <= digits) {
    return (negative ? '-' : '') + whole + '.' + fraction.padEnd(digits, '0');
  }
  // HALF_UP on a decimal string is exactly "is the first dropped digit >= 5".
  const kept = fraction.slice(0, digits);
  const roundUp = fraction.charCodeAt(digits) >= 53; // '5'
  let out = whole + kept;
  if (roundUp) {
    // String increment, so a carry across 9.99 -> 10.00 cannot re-introduce float error.
    const digitsOut = out.split('');
    let i = digitsOut.length - 1;
    for (; i >= 0; i--) {
      if (digitsOut[i] === '9') {
        digitsOut[i] = '0';
      } else {
        digitsOut[i] = String.fromCharCode(digitsOut[i].charCodeAt(0) + 1);
        break;
      }
    }
    if (i < 0) digitsOut.unshift('1');
    out = digitsOut.join('');
  }
  const cut = out.length - digits;
  const head = out.slice(0, cut) || '0';
  return (negative ? '-' : '') + (digits === 0 ? head : head + '.' + out.slice(cut));
}

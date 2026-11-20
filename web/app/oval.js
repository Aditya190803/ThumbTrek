// The lap oval, as numbers (DESIGN.md, "The lap oval").
//
// Pure functions only: metres and an optional limit go in, lane percentages, caption text
// and SVG geometry come out. Nothing here touches the DOM, so the rules that every client
// has to agree on (the scale, the 80% and over-limit states, the "Other" lane) are the
// part that is unit-tested. charts.js turns the result into SVG.

/** One lap of the track. Laps are always counted against this, limit or no limit. */
export const LAP_METRES = 400;

/** Lanes 2 to 5. Lane 1 is the total. */
export const MAX_APP_LANES = 4;

/** From this share of the limit the total lane turns to the warn colour. */
export const WARN_SHARE = 0.8;

function metres(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : 0;
}

/** A limit counts only when it is a positive number. Anything else means "no limit". */
export function hasLimit(limitMetres) {
  return Number.isFinite(limitMetres) && limitMetres > 0;
}

/**
 * What one full lap of a lane stands for, in metres.
 *
 * With a limit a lap is the limit. Without one it is the next whole 400 m above today's
 * total, never less than 400 m: `max(400, ceil(total / 400) * 400)`.
 */
export function lapScale(totalMetres, limitMetres = null) {
  if (hasLimit(limitMetres)) return limitMetres;
  return Math.max(LAP_METRES, Math.ceil(metres(totalMetres) / LAP_METRES) * LAP_METRES);
}

/**
 * Distances as the oval writes them: whole metres under a kilometre, two fixed decimals
 * above it ("2.00 km"), so a caption keeps its width as the number moves.
 */
export function ovalDistance(value) {
  const rounded = Math.round(metres(value));
  return rounded < 1000 ? `${rounded} m` : `${(rounded / 1000).toFixed(2)} km`;
}

/** "4.6 laps". One decimal, always. */
export function lapsText(totalMetres) {
  return `${(metres(totalMetres) / LAP_METRES).toFixed(1)} laps`;
}

function percentOf(value, scale) {
  if (scale <= 0) return 0;
  return Math.min(100, Math.max(0, (value / scale) * 100));
}

/**
 * The app lanes: the busiest first, at most four. With more than four apps the first three
 * keep their own lanes and everything else is summed into the last lane as "Other", which
 * stays last even when the sum is larger than a named lane.
 *
 * [apps] are `{ name, metres }`. Entries with no distance are dropped, and ties break on
 * the name so the order does not change between renders.
 */
export function appLanes(apps = []) {
  const ranked = apps
    .map((app) => ({ name: String(app?.name ?? ''), metres: metres(app?.metres) }))
    .filter((app) => app.name && app.metres > 0)
    .sort((a, b) => b.metres - a.metres || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  if (ranked.length <= MAX_APP_LANES) return ranked;
  const kept = ranked.slice(0, MAX_APP_LANES - 1);
  const rest = ranked.slice(MAX_APP_LANES - 1);
  return [
    ...kept,
    { name: 'Other', metres: rest.reduce((sum, app) => sum + app.metres, 0), other: true },
  ];
}

/**
 * Everything the oval and its text rows need for one day.
 *
 * Returns:
 *   scaleMetres  what a full lap stands for
 *   limited      whether a limit set the scale
 *   state        'open' (no limit), 'under', 'near' (80% or more) or 'over'
 *   stateText    the state in words, or null when there is no limit to judge against
 *   caption      the infield's caption line
 *   lanes        lane 1 (the total) followed by the app lanes, each
 *                { lane, name, metres, percent, tone }
 */
export function ovalModel({ totalMetres = 0, apps = [], limitMetres = null } = {}) {
  const total = metres(totalMetres);
  const limited = hasLimit(limitMetres);
  const scaleMetres = lapScale(total, limitMetres);

  let state = 'open';
  let stateText = null;
  let caption = `${lapsText(total)}, no limit`;
  let tone = 'volt';
  let totalPercent = percentOf(total, scaleMetres);

  if (limited) {
    // Compared in whole metres, the same figures the caption prints, so a day that reads
    // "0 m left" is never also called over the limit.
    const shown = Math.round(total);
    const cap = Math.round(limitMetres);
    if (shown > cap) {
      state = 'over';
      stateText = 'Over the limit';
      tone = 'danger';
      totalPercent = 100;
      caption = `${ovalDistance(shown - cap)} over ${ovalDistance(cap)}`;
    } else {
      const near = total >= limitMetres * WARN_SHARE;
      state = near ? 'near' : 'under';
      stateText = near ? 'Close to the limit' : 'Under the limit';
      tone = near ? 'warn' : 'volt';
      caption = `${ovalDistance(cap - shown)} left of ${ovalDistance(cap)}`;
    }
  }

  const lanes = [
    { lane: 1, name: 'Total', metres: total, percent: totalPercent, tone },
    ...appLanes(apps).map((app, index) => ({
      lane: index + 2,
      name: app.name,
      metres: app.metres,
      percent: percentOf(app.metres, scaleMetres),
      tone: `lane-${index + 2}`,
      other: Boolean(app.other),
    })),
  ];

  return { scaleMetres, limited, state, stateText, caption, lanes };
}

/**
 * The oval's shapes for a given lane count, in a 1000-wide viewBox.
 *
 * The outer shape is a stadium 1.75 times as wide as it is tall, or 1.35 times on a phone.
 * Lanes are equal bands, each 6% of the height. Every shape is a rounded rect with fully
 * round ends, so a stroked lane starts at the left end of the top straight and runs
 * clockwise, and the finish line crosses every lane at that one x.
 */
export function ovalGeometry(laneCount = 1, { phone = false } = {}) {
  const count = Math.min(Math.max(Math.round(laneCount) || 1, 1), MAX_APP_LANES + 1);
  const width = 1000;
  const height = Math.round(width / (phone ? 1.35 : 1.75));
  const band = Math.round(height * 0.06);
  const stadium = (inset) => ({
    x: inset,
    y: inset,
    width: width - inset * 2,
    height: height - inset * 2,
    rx: (height - inset * 2) / 2,
  });

  const lines = [];
  for (let i = 0; i <= count; i++) lines.push(stadium(i === 0 ? 0.75 : i * band));
  const lanes = [];
  for (let i = 0; i < count; i++) lanes.push(stadium(i * band + band / 2));

  return {
    width,
    height,
    band,
    strokeWidth: Math.round(band * 0.56),
    ground: stadium(0),
    infield: stadium(count * band),
    lines,
    lanes,
    finish: { x: height / 2, y1: 0, y2: count * band },
  };
}

/**
 * The lap oval's maths (DESIGN.md, "The lap oval"), kept pure so it runs under `node --test`.
 *
 * The oval is a stadium drawn as concentric lanes. Lane 1, the outermost, is today's total.
 * Lanes 2 to 5 are the day's biggest sites. This module turns metres and an optional daily
 * limit into lane percentages, the infield figure, the caption and the geometry of the SVG.
 * It touches no DOM and no storage; the popup does the drawing.
 */

import { formatFixed } from './units.js';

/** One lap of a running track. */
export const LAP_M = 400;

/** Lanes 2 to 5. */
export const MAX_SITE_LANES = 4;

/** From this share of the limit the total lane turns to the warning colour. */
export const WARN_SHARE = 0.8;

/**
 * Distances as the oval writes them: whole metres under a kilometre, then kilometres with
 * two fixed decimals ("2.00 km"), so the figure and its caption keep the same width as the
 * day moves. `formatDistance` in stats.js trims trailing zeros for parity with the phone's
 * history screens; the oval's format is the one DESIGN.md specifies for every client.
 */
export function trackDistance(meters) {
  const value = Math.max(0, Number(meters) || 0);
  if (Math.round(value) < 1000) return `${Math.round(value)} m`;
  return `${formatFixed(value / 1000, 2)} km`;
}

/** Laps of 400 m, one decimal. */
export function lapsText(meters) {
  const laps = Math.max(0, Number(meters) || 0) / LAP_M;
  return `${formatFixed(laps, 1)} ${formatFixed(laps, 1) === '1.0' ? 'lap' : 'laps'}`;
}

/**
 * The distance one full lap of any lane stands for. With a limit it is the limit. With no
 * limit it is the next whole 400 m above today's total, never less than one lap.
 */
export function lapScale(totalM, limitM = null) {
  if (limitM !== null && limitM > 0) return limitM;
  return Math.max(LAP_M, Math.ceil(Math.max(0, totalM) / LAP_M) * LAP_M);
}

const share = (meters, scaleM) =>
  Math.round(Math.min(100, Math.max(0, (meters / scaleM) * 100)) * 100) / 100;

/**
 * The site lanes: the four biggest sites, biggest first. With more than four, the first
 * three keep their lanes and everything else is summed into the last lane as "Other".
 * Returns `{ lanes, others }`, where `others` lists what "Other" is made of.
 */
export function siteLanes(sites) {
  const ranked = sites
    .filter((site) => site.meters > 0)
    .sort((a, b) => b.meters - a.meters || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  if (ranked.length <= MAX_SITE_LANES) return { lanes: ranked, others: [] };
  const kept = ranked.slice(0, MAX_SITE_LANES - 1);
  const others = ranked.slice(MAX_SITE_LANES - 1);
  const otherMeters = others.reduce((sum, site) => sum + site.meters, 0);
  return { lanes: [...kept, { name: 'Other', meters: otherMeters, other: true }], others };
}

/**
 * Everything the popup needs to draw the oval and its text rows.
 *
 * @param {object} day
 * @param {number} day.totalM  today's distance in metres
 * @param {{name: string, meters: number}[]} [day.sites]  today's sites, any order
 * @param {number|null} [day.limitM]  the daily limit in metres, or null for none
 *
 * `state` is "open" (no limit), "ok", "warn" (80% of the limit or more) or "over".
 * Every lane carries `pct`, its share of one lap from 0 to 100.
 */
export function ovalModel({ totalM, sites = [], limitM = null }) {
  const total = Math.max(0, Number(totalM) || 0);
  const limit = typeof limitM === 'number' && limitM > 0 ? limitM : null;
  const scaleM = lapScale(total, limit);

  let state = 'open';
  if (limit !== null) {
    state = total > limit ? 'over' : total >= limit * WARN_SHARE ? 'warn' : 'ok';
  }

  let caption;
  if (limit === null) caption = `${lapsText(total)}, no limit`;
  else if (state === 'over') caption = `${trackDistance(total - limit)} over ${trackDistance(limit)}`;
  else caption = `${trackDistance(limit - total)} left of ${trackDistance(limit)}`;

  const status = { open: null, ok: 'Within limit', warn: 'Close to limit', over: 'Over limit' }[state];

  const { lanes: siteRows, others } = siteLanes(sites);
  const lanes = [
    {
      lane: 1,
      name: 'Total',
      meters: total,
      distance: trackDistance(total),
      pct: state === 'over' ? 100 : share(total, scaleM),
      tone: state === 'over' ? 'danger' : state === 'warn' ? 'warn' : 'volt',
      total: true,
    },
    ...siteRows.map((site, index) => ({
      lane: index + 2,
      name: site.name,
      meters: site.meters,
      distance: trackDistance(site.meters),
      pct: share(site.meters, scaleM),
      tone: `lane-${index + 2}`,
      other: Boolean(site.other),
    })),
  ];

  const figure = trackDistance(total);
  const cut = figure.lastIndexOf(' ');
  const spoken = lanes
    .map((lane) => `Lane ${lane.lane}, ${lane.name}, ${lane.distance}`)
    .join('. ');

  return {
    scaleM,
    limitM: limit,
    state,
    status,
    caption,
    figure: [figure.slice(0, cut), figure.slice(cut + 1)],
    lanes,
    others: others.map((site) => ({ ...site, distance: trackDistance(site.meters) })),
    label: `Today drawn as a running track. ${figure}, ${caption}. ${spoken}.`,
  };
}

/**
 * The SVG geometry for an oval with `laneCount` lanes (1 to 5). Every lane is a rounded
 * rectangle whose corner radius is half its height, which is what makes it a stadium. A
 * stroked `<rect rx>` starts at the left end of its top straight and runs clockwise, so
 * with `pathLength="100"` a dash of N is N% of a lap from the finish line.
 */
export function ovalGeometry(laneCount, { width = 1000, height = 740, band = 40 } = {}) {
  const count = Math.min(MAX_SITE_LANES + 1, Math.max(1, Math.round(laneCount)));
  const stadium = (inset) => ({
    x: inset,
    y: inset,
    width: width - inset * 2,
    height: height - inset * 2,
    rx: (height - inset * 2) / 2,
  });
  const lines = [];
  // The outermost line sits half its own width inside the edge so the stroke is not clipped.
  for (let i = 0; i <= count; i++) lines.push(stadium(i === 0 ? 0.75 : i * band));
  const lanes = [];
  for (let i = 0; i < count; i++) lanes.push(stadium((i + 0.5) * band));
  return {
    width,
    height,
    band,
    ground: stadium(0),
    infield: stadium(count * band),
    lines,
    lanes,
    laneStroke: Math.round(band * 0.56),
    finish: { x: height / 2, y1: 0, y2: count * band },
  };
}

// Charts, drawn as inline SVG with a real data table behind them.
//
// Two rules hold everywhere in here:
//
//  1. A chart is a picture of numbers, so the numbers ship with it. Every chart renders a
//     `<table>` of its own data, visually hidden but in the accessibility tree, and the SVG
//     is aria-hidden. That beats an aria-label summary, which forces a screen reader user
//     to accept somebody else's reading of the shape, and it is what makes the charts
//     legible with images or CSS off.
//  2. The survey language from the landing page continues: a hairline baseline, moss for
//     the bars, amber only on the bucket you are standing in. No gridlines, no legend
//     chrome, no card around the plot — the section marker already says what this is.

import { el, svg } from './dom.js';

const BAR_GAP = 6;
const BAR_SLOT = 44;
const PLOT_HEIGHT = 150;
const LABEL_BAND = 22;

/**
 * A zero-filled bucket series as columns.
 *
 * [buckets] are `{ key, label, um }` from stats.js. The value axis is deliberately unlabelled
 * — the totals above the chart carry the magnitude, and a y-axis of micrometre-derived
 * metres would be five characters of noise per gridline.
 */
export function barChart(buckets, { format, caption, valueName = 'Distance' }) {
  const width = Math.max(buckets.length, 1) * BAR_SLOT;
  const height = PLOT_HEIGHT + LABEL_BAND;
  const peak = Math.max(...buckets.map((b) => b.um), 0);
  const lastIndex = buckets.length - 1;

  const bars = buckets.map((bucket, index) => {
    // A zero day still draws a 2px stub. An empty column that renders as literally nothing
    // reads as a missing bar rather than a day with no scrolling.
    const full = peak > 0 ? (bucket.um / peak) * (PLOT_HEIGHT - 14) : 0;
    const barHeight = Math.max(full, 2);
    const x = index * BAR_SLOT + BAR_GAP;
    const y = PLOT_HEIGHT - barHeight;
    const current = index === lastIndex;
    return svg('g', {}, [
      svg('rect', {
        x,
        y,
        width: BAR_SLOT - BAR_GAP * 2,
        height: barHeight,
        rx: 3,
        class: current ? 'bar bar-current' : 'bar',
      }),
      svg('text', {
        x: x + (BAR_SLOT - BAR_GAP * 2) / 2,
        y: height - 7,
        class: current ? 'bar-label bar-label-current' : 'bar-label',
        'text-anchor': 'middle',
        text: bucket.label,
      }),
    ]);
  });

  const plot = svg(
    'svg',
    {
      viewBox: `0 0 ${width} ${height}`,
      class: 'chart',
      preserveAspectRatio: 'xMidYMid meet',
      'aria-hidden': 'true',
      focusable: 'false',
    },
    svg('line', {
      x1: 0, y1: PLOT_HEIGHT + 0.5, x2: width, y2: PLOT_HEIGHT + 0.5, class: 'chart-base',
    }),
    bars,
  );

  return el(
    'figure',
    { class: 'chart-figure' },
    el('div', { class: 'chart-scroll' }, plot),
    dataTable(buckets, { format, caption, valueName }),
    caption ? el('figcaption', { class: 'chart-caption', text: caption }) : null,
  );
}

/** The chart's numbers, for screen readers and for anyone who wants the actual figures. */
function dataTable(buckets, { format, caption, valueName }) {
  return el(
    'table',
    { class: 'visually-hidden' },
    el('caption', { text: caption ?? 'Chart data' }),
    el(
      'thead',
      {},
      el('tr', {}, el('th', { scope: 'col', text: 'Period' }), el('th', { scope: 'col', text: valueName })),
    ),
    el(
      'tbody',
      {},
      buckets.map((bucket) =>
        el(
          'tr',
          {},
          el('th', { scope: 'row', text: bucket.label }),
          el('td', { text: format(bucket.um) }),
        ),
      ),
    ),
  );
}

/**
 * The workhorse row of the whole product: a label, a figure, and a proportional rail.
 *
 * Per-app splits, per-source splits and leaderboard rows all use it on the phone, so
 * relative size is encoded the same way everywhere and a reader learns it once. `fraction`
 * is against the largest row, not against the total — comparing bars to each other is the
 * question people actually ask of a split.
 */
export function measureRow({ label, value, fraction, tone = 'moss', note, leading }) {
  const width = `${Math.min(Math.max(fraction, 0), 1) * 100}%`;
  return el(
    'div',
    { class: 'measure' },
    el(
      'div',
      { class: 'measure-head' },
      leading ? el('span', { class: `dot dot-${tone}`, 'aria-hidden': 'true' }) : null,
      el('span', { class: 'measure-label', text: label }),
      note ? el('span', { class: 'measure-note', text: note }) : null,
      el('span', { class: 'measure-value num', text: value }),
    ),
    el(
      'div',
      { class: 'rail', 'aria-hidden': 'true' },
      el('div', { class: `rail-fill rail-${tone}`, style: `width:${width}` }),
    ),
  );
}

/**
 * Progress toward a landmark or a badge target. Same rail, smaller, and always paired with
 * words — a bare bar tells you a proportion of an unnamed whole.
 */
export function progressRail(fraction, tone = 'moss') {
  return el(
    'div',
    { class: 'rail rail-thin', 'aria-hidden': 'true' },
    el('div', {
      class: `rail-fill rail-${tone}`,
      style: `width:${Math.min(Math.max(fraction, 0), 1) * 100}%`,
    }),
  );
}

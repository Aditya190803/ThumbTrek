// Everything drawn as inline SVG: the lap oval and the history chart.
//
// Two rules hold everywhere in here:
//
//  1. A drawing of numbers ships with the numbers. The history chart renders a `<table>` of
//     its own data, visually hidden but in the accessibility tree, and the oval has a text
//     row for every lane (DESIGN.md), so neither is ever the only place a figure appears.
//  2. Colour comes from the design tokens and nothing else. The chart uses the series
//     tokens, which styles.css swaps between the dark and light sets. The oval sits on the
//     track, which carries its own ground, so it uses the fixed lane colours in both themes.

import { el, svg } from './dom.js';
import { ovalGeometry } from './oval.js';

// ---------------------------------------------------------------------------------------
// The lap oval
// ---------------------------------------------------------------------------------------

function rectProps(shape) {
  return { x: shape.x, y: shape.y, width: shape.width, height: shape.height, rx: shape.rx };
}

/**
 * The oval for one day, from an `ovalModel` (oval.js).
 *
 * Lane 1 is the total; its colour follows the model's tone (volt, warn or danger). A lane
 * with no distance draws no progress stroke at all: a zero-length dash with round caps
 * would still paint a dot at the finish line.
 */
export function lapOval(model, { phone = false, label = '' } = {}) {
  const shape = ovalGeometry(model.lanes.length, { phone });

  const progress = model.lanes.map((lane, index) => {
    if (lane.percent <= 0) return null;
    const tone = index === 0 ? `lane-1 lane-tone-${lane.tone}` : `lane-${lane.lane}`;
    return svg('rect', {
      class: `lane ${tone}`,
      style: `--lap: ${Math.round(lane.percent * 10) / 10}`,
      pathLength: 100,
      'stroke-width': shape.strokeWidth,
      ...rectProps(shape.lanes[index]),
    });
  });

  return svg(
    'svg',
    {
      viewBox: `0 0 ${shape.width} ${shape.height}`,
      role: 'img',
      'aria-label': label,
      focusable: 'false',
    },
    svg('rect', { class: 'oval-ground', ...rectProps(shape.ground) }),
    svg('rect', { class: 'oval-infield', ...rectProps(shape.infield) }),
    svg(
      'g',
      { 'aria-hidden': 'true' },
      shape.lines.map((line) => svg('rect', { class: 'oval-line', ...rectProps(line) })),
      progress,
      svg('line', {
        class: 'oval-finish',
        x1: shape.finish.x,
        y1: shape.finish.y1,
        x2: shape.finish.x,
        y2: shape.finish.y2,
      }),
    ),
  );
}

// ---------------------------------------------------------------------------------------
// History chart
// ---------------------------------------------------------------------------------------

const BAR_SLOT = 48;
const BAR_WIDTH = 24;
const PLOT_HEIGHT = 150;
const LABEL_BAND = 24;
const SEGMENT_GAP = 2;

/**
 * Distance per period as stacked columns, one segment per source.
 *
 * [buckets] are `{ key, label, um, parts }`, where `parts[i]` is the share of `um` that
 * came from `series[i]`. [series] are `{ label, slot }`; the slot (1 to 6) picks the series
 * token and belongs to the source, not to its rank, so a source keeps its colour when the
 * range changes. The value axis is left unlabelled: the caption names the busiest period,
 * each column carries its figures as a tooltip, and the table has every number.
 */
export function stackedBarChart(buckets, series, { format, caption }) {
  const width = Math.max(buckets.length, 1) * BAR_SLOT;
  const height = PLOT_HEIGHT + LABEL_BAND;
  const peak = Math.max(...buckets.map((b) => b.um), 0);
  const lastIndex = buckets.length - 1;
  const usable = PLOT_HEIGHT - 14;

  const columns = buckets.map((bucket, index) => {
    const x = index * BAR_SLOT + (BAR_SLOT - BAR_WIDTH) / 2;
    const current = index === lastIndex;
    const segments = [];
    let top = PLOT_HEIGHT;

    if (bucket.um <= 0 || peak <= 0) {
      // A period with no scrolling still draws a stub, so it reads as zero and not as a
      // missing column.
      segments.push(svg('rect', { x, y: PLOT_HEIGHT - 2, width: BAR_WIDTH, height: 2, class: 'bar bar-empty' }));
    } else {
      const drawn = bucket.parts
        .map((um, part) => ({ um, part }))
        .filter((segment) => segment.um > 0);
      drawn.forEach((segment, order) => {
        const last = order === drawn.length - 1;
        const full = (segment.um / peak) * usable;
        // The gap between two fills comes off the top of the lower segment, so the
        // column's total height still matches its value.
        const gap = last ? 0 : Math.min(SEGMENT_GAP, full / 2);
        const barHeight = Math.max(full - gap, 1);
        top -= full;
        segments.push(
          svg('rect', {
            x,
            y: top + gap,
            width: BAR_WIDTH,
            height: barHeight,
            rx: last ? Math.min(4, barHeight / 2) : 0,
            class: `bar bar-series-${series[segment.part].slot}`,
          }),
        );
      });
    }

    const detail =
      series.length > 1 && bucket.um > 0
        ? ` (${series
            .map((s, part) => `${s.label} ${format(bucket.parts[part] ?? 0)}`)
            .join(', ')})`
        : '';

    return svg(
      'g',
      {},
      svg('title', { text: `${bucket.label}: ${format(bucket.um)}${detail}` }),
      // A hit area the full height of the slot, so the tooltip does not need a 2px target.
      svg('rect', { x: index * BAR_SLOT, y: 0, width: BAR_SLOT, height, class: 'bar-hit' }),
      segments,
      svg('text', {
        x: index * BAR_SLOT + BAR_SLOT / 2,
        y: height - 7,
        class: current ? 'bar-label bar-label-current' : 'bar-label',
        'text-anchor': 'middle',
        text: bucket.label,
      }),
    );
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
    columns,
    svg('line', {
      x1: 0, y1: PLOT_HEIGHT + 0.5, x2: width, y2: PLOT_HEIGHT + 0.5, class: 'chart-base',
    }),
  );

  return el(
    'figure',
    { class: 'chart-figure' },
    series.length > 1 ? legend(series) : null,
    el('div', { class: 'chart-scroll' }, plot),
    dataTable(buckets, series, { format, caption }),
    caption ? el('figcaption', { class: 'chart-caption', text: caption }) : null,
  );
}

/** Which colour is which source, in words. Only drawn when there is more than one. */
function legend(series) {
  return el(
    'ul',
    { class: 'legend', 'aria-hidden': 'true' },
    series.map((s) =>
      el(
        'li',
        {},
        el('span', { class: `swatch swatch-series-${s.slot}` }),
        s.label,
      ),
    ),
  );
}

/** The chart's numbers, for screen readers and for anyone who wants the actual figures. */
function dataTable(buckets, series, { format, caption }) {
  const split = series.length > 1;
  return el(
    'table',
    { class: 'visually-hidden' },
    el('caption', { text: caption ?? 'Chart data' }),
    el(
      'thead',
      {},
      el(
        'tr',
        {},
        el('th', { scope: 'col', text: 'Period' }),
        split ? series.map((s) => el('th', { scope: 'col', text: s.label })) : null,
        el('th', { scope: 'col', text: split ? 'Total' : 'Distance' }),
      ),
    ),
    el(
      'tbody',
      {},
      buckets.map((bucket) =>
        el(
          'tr',
          {},
          el('th', { scope: 'row', text: bucket.label }),
          split ? series.map((s, part) => el('td', { text: format(bucket.parts[part] ?? 0) })) : null,
          el('td', { text: format(bucket.um) }),
        ),
      ),
    ),
  );
}

/**
 * A proportion as a thin stadium on the sunken track. Always paired with words by the
 * caller: a bare bar is a share of an unnamed whole.
 */
export function progressRail(fraction, slot = null) {
  return el(
    'div',
    { class: 'rail', 'aria-hidden': 'true' },
    el('div', {
      class: slot ? `rail-fill swatch-series-${slot}` : 'rail-fill',
      style: `width:${Math.min(Math.max(fraction, 0), 1) * 100}%`,
    }),
  );
}

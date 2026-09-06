/**
 * Renders the ThumbTrek mark to the PNGs the manifest needs. `node tools/make-icons.mjs`.
 *
 * ## Why this script exists at all
 *
 * Chrome will not accept an SVG for `icons` or `action.default_icon` -- it takes bitmaps
 * only -- so the extension needs PNGs even though the mark is a vector everywhere else in
 * this repo. Committing four hand-made binaries would mean the icon has a *second* source of
 * truth that silently drifts from `web/assets/mark.svg`, which is the exact failure the
 * launcher-icon PNG pipeline already ran into on the Android side: two definitions, one of
 * them dead, and an afternoon lost to editing the wrong one.
 *
 * So the geometry below is the mark's geometry, transcribed once, and the PNGs are output.
 * Change the mark, re-run this, and the two cannot disagree.
 *
 * ## Why it is written by hand
 *
 * No canvas, no sharp, no resvg: the extension has no dependencies and this repo has no
 * node_modules. What is actually needed is small -- fill a rounded rectangle, stroke a few
 * curves and a polyline, fill a disc -- so it is done with a supersampled distance field
 * (every shape reduces to "how far is this sample from a polyline") and node's built-in
 * `zlib` for the PNG's one compressed chunk.
 */

import { deflateSync } from 'node:zlib';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, '..', 'icons');

// ---------------------------------------------------------------------------------------
// The mark, on the same 32-unit grid as web/assets/mark.svg
// ---------------------------------------------------------------------------------------

const GROUND = [0x0c, 0x0f, 0x0b];
const MOSS = [0x8b, 0xe6, 0xa0];
const INK = [0xed, 0xf1, 0xe6];
const AMBER = [0xf2, 0xb4, 0x55];

/** Cubic Bezier, flattened to a polyline. 24 segments is invisible at any icon size. */
function bezier(p0, p1, p2, p3, steps = 24) {
  const points = [];
  for (let i = 0; i <= steps; i++) {
    const t = i / steps;
    const u = 1 - t;
    points.push([
      u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
      u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1],
    ]);
  }
  return points;
}

/** The three thumbprint contours: `M13 21 C13 17.5 19 17.5 19 21`, and its two siblings. */
const CONTOURS = [
  bezier([13, 21], [13, 17.5], [19, 17.5], [19, 21]),
  bezier([10, 21], [10, 14.5], [22, 14.5], [22, 21]),
  bezier([7, 21], [7, 11.5], [25, 11.5], [25, 21]),
];

/** The route: `M5 25.5 L11 23 L17 25 L25.5 20`. */
const ROUTE = [[5, 25.5], [11, 23], [17, 25], [25.5, 20]];

/** The amber waypoint at the route's end. */
const WAYPOINT = { cx: 25.5, cy: 20, r: 2.2 };

const CORNER = 7; // the mark's rounded-rect radius, on the 32 grid

// ---------------------------------------------------------------------------------------
// Rasterising
// ---------------------------------------------------------------------------------------

/** Squared distance from a point to a segment. Squared, so the inner loop has no sqrt. */
function distanceToSegment(px, py, [ax, ay], [bx, by]) {
  const dx = bx - ax;
  const dy = by - ay;
  const lengthSquared = dx * dx + dy * dy;
  const t = lengthSquared === 0 ? 0
    : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lengthSquared));
  const cx = ax + t * dx;
  const cy = ay + t * dy;
  return Math.hypot(px - cx, py - cy);
}

function distanceToPolyline(px, py, points) {
  let best = Infinity;
  for (let i = 1; i < points.length; i++) {
    const d = distanceToSegment(px, py, points[i - 1], points[i]);
    if (d < best) best = d;
  }
  return best;
}

/** Signed distance to the rounded rect, negative inside. */
function roundedRectDistance(px, py, size, radius) {
  const halfSize = size / 2;
  const qx = Math.abs(px - halfSize) - (halfSize - radius);
  const qy = Math.abs(py - halfSize) - (halfSize - radius);
  return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - radius;
}

const over = (dst, src, alpha) => dst.map((c, i) => Math.round(c * (1 - alpha) + src[i] * alpha));

/**
 * One pixel, supersampled. 4x4 samples per pixel is enough antialiasing that a 16px mark
 * reads cleanly; the whole set is ~18k pixels, so brute force is instant.
 */
function shade(px, py, unit) {
  const SS = 4;
  let r = 0;
  let g = 0;
  let b = 0;
  let a = 0;
  for (let sy = 0; sy < SS; sy++) {
    for (let sx = 0; sx < SS; sx++) {
      // Sample at the centre of each sub-pixel, in mark units.
      const x = (px + (sx + 0.5) / SS) * unit;
      const y = (py + (sy + 0.5) / SS) * unit;

      if (roundedRectDistance(x, y, 32, CORNER) > 0) continue; // outside the tile
      let colour = GROUND;
      // Painted in the SVG's own order, so overlaps resolve the same way.
      if (CONTOURS.some((c) => distanceToPolyline(x, y, c) <= 1)) colour = MOSS;
      if (distanceToPolyline(x, y, ROUTE) <= 1) colour = INK;
      if (Math.hypot(x - WAYPOINT.cx, y - WAYPOINT.cy) <= WAYPOINT.r) colour = AMBER;

      r += colour[0];
      g += colour[1];
      b += colour[2];
      a += 255;
    }
  }
  const samples = SS * SS;
  if (a === 0) return [0, 0, 0, 0];
  // Premultiplied average, un-premultiplied back out, so an edge pixel blends its own
  // colour rather than fading toward black.
  const coverage = a / (samples * 255);
  return [
    Math.round(r / (samples * coverage)),
    Math.round(g / (samples * coverage)),
    Math.round(b / (samples * coverage)),
    Math.round(coverage * 255),
  ];
}

function render(size) {
  const unit = 32 / size;
  const rgba = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const [r, g, b, a] = shade(x, y, unit);
      const at = (y * size + x) * 4;
      rgba[at] = r;
      rgba[at + 1] = g;
      rgba[at + 2] = b;
      rgba[at + 3] = a;
    }
  }
  return rgba;
}

// ---------------------------------------------------------------------------------------
// PNG container
// ---------------------------------------------------------------------------------------

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buffer) {
  let c = 0xffffffff;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

function png(size, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // colour type: RGBA
  // Filter byte 0 (None) on every scanline: these images are tiny and deflate handles them
  // fine, so the adaptive-filter machinery would be code for no measurable gain.
  const raw = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    rgba.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

mkdirSync(OUT, { recursive: true });
for (const size of [16, 32, 48, 128]) {
  const file = join(OUT, `icon-${size}.png`);
  writeFileSync(file, png(size, render(size)));
  console.log(`wrote ${file}`);
}

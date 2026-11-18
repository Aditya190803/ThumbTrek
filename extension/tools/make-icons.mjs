/**
 * Renders the ThumbTrek mark to the PNGs the manifest needs. `node tools/make-icons.mjs`.
 *
 * ## Why this script exists at all
 *
 * Chrome will not accept an SVG for `icons` or `action.default_icon` -- it takes bitmaps
 * only -- so the extension needs PNGs even though the mark is a vector everywhere else in
 * this repo. Committing four hand-made binaries would leave nobody able to change them, so
 * the geometry below is the mark's lap, adapted to a square tile, and the PNGs are output.
 * Change the mark, edit the constants, re-run this.
 *
 * ## Why it is written by hand
 *
 * No canvas, no sharp, no resvg: the extension has no dependencies and this repo has no
 * node_modules. What is actually needed is small -- fill a rounded rectangle, fill a small
 * stadium, stroke one path -- so it is done with a supersampled distance field
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
// The mark, on a 32-unit grid
// ---------------------------------------------------------------------------------------

// web/assets/track-mark.svg is a 40x24 stadium. A toolbar icon is square, so here the same lap
// sits on a track-blue tile: the darker infield, and the volt lap running 72% of the way
// round from the left end of the top straight. The chalk lane line is left out; at 16px it
// would be a smear between the lap and the edge.
const TRACK = [0x23, 0x40, 0xe0];
const TRACK_DEEP = [0x1a, 0x2f, 0xb0];
const VOLT = [0xd7, 0xff, 0x3a];

const CORNER = 7; // the tile's rounded-rect radius

/** The lap: a stadium 22 wide and 13 tall, centred on the tile. */
const LAP = { left: 5, top: 9.5, width: 22, height: 13 };
const LAP_HALF_STROKE = 1.7;
const LAP_SHARE = 0.72;

/** The infield: a short fat segment, which a distance field turns into a small stadium. */
const INFIELD = { from: [13, 16], to: [19, 16], radius: 2 };

/**
 * The stadium's outline as a polyline, starting at the left end of the top straight and
 * running clockwise, cut off after `share` of its length. That is the same start point and
 * direction a stroked SVG `<rect rx>` uses, so the icon agrees with every oval in the UI.
 */
function lapPath(share) {
  const r = LAP.height / 2;
  const x0 = LAP.left + r;
  const x1 = LAP.left + LAP.width - r;
  const cy = LAP.top + r;
  const points = [[x0, LAP.top], [x1, LAP.top]];
  const ARC_STEPS = 32;
  for (let i = 1; i <= ARC_STEPS; i++) {
    const angle = -Math.PI / 2 + (Math.PI * i) / ARC_STEPS;
    points.push([x1 + r * Math.cos(angle), cy + r * Math.sin(angle)]);
  }
  points.push([x0, LAP.top + LAP.height]);
  for (let i = 1; i <= ARC_STEPS; i++) {
    const angle = Math.PI / 2 + (Math.PI * i) / ARC_STEPS;
    points.push([x0 + r * Math.cos(angle), cy + r * Math.sin(angle)]);
  }

  let total = 0;
  for (let i = 1; i < points.length; i++) {
    total += Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]);
  }
  const budget = total * share;
  const cut = [points[0]];
  let walked = 0;
  for (let i = 1; i < points.length; i++) {
    const [ax, ay] = points[i - 1];
    const [bx, by] = points[i];
    const step = Math.hypot(bx - ax, by - ay);
    if (walked + step >= budget) {
      const t = (budget - walked) / step;
      cut.push([ax + (bx - ax) * t, ay + (by - ay) * t]);
      break;
    }
    cut.push(points[i]);
    walked += step;
  }
  return cut;
}

const LAP_PATH = lapPath(LAP_SHARE);

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
      let colour = TRACK;
      if (distanceToSegment(x, y, INFIELD.from, INFIELD.to) <= INFIELD.radius) colour = TRACK_DEEP;
      // A stroke with round caps is every point within half the stroke of the path.
      if (distanceToPolyline(x, y, LAP_PATH) <= LAP_HALF_STROKE) colour = VOLT;

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

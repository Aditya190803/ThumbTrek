import test from 'node:test';
import assert from 'node:assert/strict';

import { cssPixelsToMicrometres, formatFixed, micrometresToMetres } from '../lib/units.js';

test('a CSS pixel is exactly 1/96 inch', () => {
  // 96 CSS px is one inch by definition, and an inch is 25400 µm.
  assert.equal(cssPixelsToMicrometres(96), 25_400);
  assert.equal(cssPixelsToMicrometres(0), 0);
  // 9600 px = 100 in = 2.54 m.
  assert.equal(cssPixelsToMicrometres(9600), 2_540_000);
  assert.equal(micrometresToMetres(cssPixelsToMicrometres(9600)), 2.54);
});

test('the conversion always yields an integer', () => {
  for (const px of [1, 1.5, 3.14159, 0.0001, 12345.678]) {
    assert.ok(Number.isInteger(cssPixelsToMicrometres(px)), `${px} produced a float`);
  }
});

test('sub-micrometre movement rounds to nothing rather than to something', () => {
  // 1/300 px is well under a µm; banking it would let a jittery page manufacture distance.
  assert.equal(cssPixelsToMicrometres(0.001), 0);
  // The smallest movement that is worth a µm.
  assert.equal(cssPixelsToMicrometres(0.002), 1);
});

test('formatFixed rounds the shortest round-trip decimal, like Java %.Nf', () => {
  // The cases that motivate the whole function. 1.005 is really 1.00499999999999989342,
  // so toFixed -- which rounds the binary value -- says "1.00", while Java's formatter
  // rounds the shortest decimal ("1.005") HALF_UP and says "1.01". We must agree with Java:
  // a user reading 1.00 km here and 1.01 km on their phone is reading a bug.
  assert.equal((1.005).toFixed(2), '1.00');
  assert.equal(formatFixed(1.005, 2), '1.01');

  // The same at one decimal, which is what `comparison` prints: "1.9×" vs "2.0×".
  assert.equal((1.95).toFixed(1), '1.9');
  assert.equal(formatFixed(1.95, 1), '2.0');

  assert.equal(formatFixed(1, 2), '1.00');
  assert.equal(formatFixed(1.2, 2), '1.20');
  assert.equal(formatFixed(1.234, 2), '1.23');
  assert.equal(formatFixed(0.5, 0), '1');
  assert.equal(formatFixed(1.5, 1), '1.5');
});

test('formatFixed carries across a nine without touching a float', () => {
  assert.equal(formatFixed(9.999, 2), '10.00');
  assert.equal(formatFixed(0.999, 2), '1.00');
  assert.equal(formatFixed(99.995, 2), '100.00');
});

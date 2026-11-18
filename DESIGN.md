# ThumbTrek visual system

ThumbTrek is drawn as an athletics track. Scrolling is distance, a running track is where
people already count distance in laps, and every screen borrows from it: track blue, white
lane lines, painted lane numbers, a volt-yellow runner. It replaces the green survey-map
identity everywhere. The reference implementation is `web/styles.css`; the other clients
carry the same tokens.

## Color

Track blue and volt are the brand. Volt is only ever a fill on blue or on the dark ground,
never text on white.

| Token | Dark (night meet) | Light (day meet) | Use |
|---|---|---|---|
| `bg` | `#0A0F2E` | `#F3F5FB` | page ground |
| `surface` | `#121A4A` | `#FFFFFF` | panels, sheets, rows |
| `surface-2` | `#1A2460` | `#E9EDF8` | hover, pressed, nested chips |
| `sunken` | `#070B22` | `#E3E8F5` | progress tracks, inputs |
| `line` | `#26317A` | `#D3DAEE` | borders, lane dividers |
| `line-soft` | `#1B2459` | `#E4E9F6` | row separators inside a panel |
| `text` | `#F5F7FF` | `#0B1030` | primary text |
| `text-dim` | `#AEB6E0` | `#454E7A` | secondary text |
| `text-faint` | `#8891C4` | `#626B96` | captions, axis labels |
| `accent` | `#D7FF3A` | `#2340E0` | primary action, selection |
| `accent-hover` | `#E6FF7A` | `#1A30B0` | hover and pressed |
| `on-accent` | `#0A0F2E` | `#FFFFFF` | text on the accent |
| `accent-wash` | `#323F47` | `#E3E8FD` | selected chip, own leaderboard row |
| `good` | `#5BE3A0` | `#12754A` | under the limit, clean day |
| `good-wash` | `#12303F` | `#E0F3EA` | |
| `warn` | `#FFC83D` | `#8A5800` | streaks, 80% of the limit |
| `warn-wash` | `#2E2A33` | `#FBEFD6` | |
| `danger` | `#FF5C7A` | `#B4233A` | over the limit, destructive |
| `danger-wash` | `#33173F` | `#FBE6E9` | |
| `info` | `#7FD4FF` | `#0E6E94` | browser source, neutral highlights |
| `info-wash` | `#14294F` | `#E0F1F8` | |
| `gold` `silver` `bronze` | `#FFD34D` `#C9D1EE` `#E0A070` | `#8A6200` `#5B648C` `#8A5230` | podium |

Fixed in both themes, because the track carries its own ground:

| Token | Value | Use |
|---|---|---|
| `track` | `#2340E0` | the lap oval's lanes, the site hero |
| `track-deep` | `#1A2FB0` | the oval's infield, pressed track surfaces |
| `chalk` | `#FFFFFF` | lane lines, lane numbers, text on track |
| `volt` | `#D7FF3A` | the total lane, the runner |
| `on-volt` | `#0A0F2E` | text on volt |

Series colors. On the track and in dark charts: `#7FD4FF` `#FF8FB3` `#5BE3A0` `#C4A8FF`
`#FFC83D` `#FFFFFF`. In light charts: `#2340E0` `#0E8FB8` `#C4326B` `#12805A` `#6B3FC2`
`#8A5C00`. Volt is reserved for the total and is never an app's color.

## Typography

Archivo, one variable family, two widths. Wide (width 125) for numbers and headings, the
way a scoreboard and a painted lane number are wide. Normal (width 100) for everything
read as a sentence. Sentence case everywhere, no uppercase labels. Numbers use tabular
figures.

| Role | Size | Weight | Width | Tracking |
|---|---|---|---|---|
| Display (site hero, the oval's number) | 44–72 | 800 | 125 | -0.03em |
| Title (screen and section headings) | 24–30 | 700 | 125 | -0.02em |
| Heading (panel titles) | 16–17 | 650 | 100 | -0.005em |
| Body | 15–16 | 400 | 100 | 0 |
| Caption | 12–13 | 500 | 100 | 0 |
| Lane number | matches lane height | 800 | 125 | 0 |

## Shape and layout

The stadium (a rectangle with fully rounded ends) is the house shape.

- Buttons and chips are stadiums: fully rounded, 44 minimum height. Primary is `accent`
  with `on-accent` text. Secondary is transparent with a 1.5px `line` border. Destructive
  uses `danger` text.
- Panels: `surface` fill, 20 radius, 20 padding, no border in dark, a 1px `line` border in
  light, no shadow. Use a panel only when the content is one object. A list of things is
  rows separated by `line-soft` lane lines, not a grid of panels.
- Inputs sit on `sunken`, 12 radius, 1.5px `line` border, `accent` border on focus.
- Page gutter 20 on phones, 28 and up on desktop. Blocks stack with a 12 gap.
- Lists that rank or sequence things (leaderboard, how-it-works steps, per-app rows) carry
  a wide lane number at the left edge. Nothing else is numbered.

## The lap oval

The signature element, on every client. A stadium drawn as concentric lanes.

- Geometry: outer shape is a stadium, width about 1.75 times its height on wide layouts and
  1.35 times on a phone. Lanes are equal bands, each about 6% of the oval's height,
  separated by 1.5px `chalk` lines at 45% opacity. Lane fill is `track`, the infield is
  `track-deep`.
- Lanes: lane 1 (outermost) is the total, in `volt`. Lanes 2 to 5 are the top four apps or
  sites by distance today, in series colors, in descending order. Anything beyond four is
  summed into the last lane as "Other". With fewer apps, draw fewer lanes; lane 1 is always
  there.
- Direction: progress starts at the left end of the top straight and runs clockwise, which
  is what a stroked SVG `<rect rx>` does by default. A `chalk` finish line crosses every
  lane at the start point. Progress strokes have round caps.
- Scale with a limit: one full lap of any lane is the daily limit. The total lane shows
  today's share of the limit; each app lane shows that app's share of the limit.
- Scale with no limit (baseline learning or tracking only): one full lap is the next whole
  400 m above today's total, with a 400 m minimum. Formula:
  `max(400, ceil(totalMetres / 400) * 400)`.
- Infield: today's distance in Display on `chalk`, with one caption line beneath. With a
  limit the caption is "160 m left of 2.00 km". With no limit it is "4.6 laps, no limit".
  A lap is 400 m; laps show one decimal.
- States: from 80% of the limit the total lane turns `warn` and the caption stays as is.
  Over the limit the total lane is a full lap in `danger` and the caption reads
  "240 m over 2.00 km".
- Empty day: lanes are drawn with no progress and the infield reads "0 m".
- Small ovals (widget, popup header, tab icon) draw lane 1 only.

Every lane has a matching text row elsewhere on the screen (lane number, app name,
distance), so the oval is never the only place a figure appears.

## Navigation

Android: a bottom bar with four destinations (Today, History, Friends, Settings). The
selected one sits in an `accent` stadium with its label. Web dashboard: a top bar with the
same sections as stadium tabs. Extension popup: the oval first, then per-site lane rows,
then a footer link to options.

## Premium

Premium surfaces use the same parts. A Premium feature is an ordinary row, toggle or
section; nothing is blurred or hidden. The Premium page and sheet list the five features
in one order on every client (`PREMIUM_FEATURES` in `limits.js`, `Premium.kt`), the
planned price, and one primary button. While `BILLING_ENFORCED` is false every feature is
open, the copy says so ("free until Premium goes on sale"), and the button reads "Not on
sale yet" and is disabled. When billing ships, a feature the account does not have shows
its normal row with a small "Premium" chip in `accent-wash` that opens the sheet.

## Motion

On load the oval's lanes run once from the finish line to their values, 700ms ease-out,
total lane first. Other state changes take 160–240ms. Reduced motion jumps to the end
state.

## Accessibility

Both themes follow the system setting. Body text clears 4.5:1 on its surface. State is
always written in words as well as color. Touch targets are at least 44. Focus rings are
2px `accent` with a 3px offset; on track surfaces the ring is `chalk`.

## Onboarding

Four resumable steps: privacy, app selection and tracking permission, a daily limit, optional account and notifications. Learning a baseline is the recommended default: seven tracked days with no cap or clean-day judgments, after which the limit is set about 10% under the median day and announced with a one-tap undo. Setting a limit by hand is offered second and always goes through a caution step that compares it with the user's usual day. Days before a limit existed are never scored against it. Settings allows setup to be revisited.

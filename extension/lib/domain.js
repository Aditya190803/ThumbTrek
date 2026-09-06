/**
 * Hostname -> the key a trek is filed under.
 *
 * Contract §3: `apps` keys are "the natural identifier for the source" -- an Android package
 * name, or *a registrable domain*. Registrable, not hostname: `www.youtube.com`,
 * `m.youtube.com` and `music.youtube.com` are one site as far as a person is concerned, and
 * filing them as three rows would both split the breakdown and burn three of the 64 `apps`
 * slots the rules allow per day document.
 *
 * ## Why there is no Public Suffix List here
 *
 * The correct way to find a registrable domain is to look the suffix up in Mozilla's PSL.
 * That list is ~250KB, changes monthly, and would have to be vendored into the extension and
 * kept fresh -- for a feature whose entire job is choosing a label for a row. The rejected
 * alternative was fetching it at runtime, which is worse: a tracker that phones a third
 * party to decide what to call your browsing is exactly the thing this extension promises
 * not to be.
 *
 * So: last two labels, with a short table of the two-level suffixes that actually show up in
 * feed traffic. The failure mode is benign and visible -- a site under an unlisted two-level
 * suffix is filed one label too short (`bbc.co.uk` would become `co.uk` if `co.uk` were
 * missing from the table) -- and the user can always see the key in the popup breakdown.
 * Nothing about correctness of *measurement* depends on this; only the label does.
 */

/**
 * Second-level suffixes under which registrations actually happen. Deliberately short: each
 * line here is a claim that people register directly beneath it.
 */
const TWO_LEVEL_SUFFIXES = new Set([
  'co.uk', 'org.uk', 'me.uk', 'ac.uk', 'gov.uk', 'net.uk', 'sch.uk',
  'com.au', 'net.au', 'org.au', 'edu.au', 'gov.au', 'id.au',
  'co.nz', 'net.nz', 'org.nz', 'govt.nz', 'ac.nz',
  'co.jp', 'or.jp', 'ne.jp', 'ac.jp', 'go.jp',
  'co.kr', 'or.kr', 're.kr', 'go.kr',
  'com.br', 'net.br', 'org.br', 'gov.br',
  'com.cn', 'net.cn', 'org.cn', 'gov.cn', 'edu.cn',
  'com.tw', 'org.tw', 'net.tw',
  'com.hk', 'org.hk', 'net.hk',
  'com.sg', 'com.my', 'com.ph', 'com.vn', 'co.th', 'co.id',
  'com.mx', 'com.ar', 'com.co', 'com.pe', 'com.tr',
  'co.za', 'org.za', 'co.il', 'co.in', 'net.in', 'org.in', 'gov.in', 'ac.in',
  'com.ua', 'com.pk', 'com.ng', 'com.eg', 'com.sa',
  'co.ke', 'com.pl', 'com.es', 'com.pt', 'com.gr',
]);

/**
 * Sites that are one site wearing two names. The extension tracks the *live* domain and
 * folds the redirecting one into it, so a user who still has `twitter.com` bookmarked does
 * not end up with two rows for the same feed.
 *
 * Same reasoning as `Apps.kt`'s note that the X package is still `com.twitter.android`:
 * a rebrand does not create a second product, and pretending otherwise splits the numbers.
 * Direction is the opposite of Android's, though -- on the web the *new* name is the live
 * one, because `twitter.com` now 301s to `x.com`.
 */
export const DOMAIN_ALIASES = new Map([
  ['twitter.com', 'x.com'],
]);

/**
 * The registrable domain for a hostname, or '' when there isn't one (an IP literal,
 * `localhost`, a `file://` page). An empty key means "don't file this anywhere".
 */
export function registrableDomain(hostname) {
  if (!hostname) return '';
  let host = hostname.toLowerCase();
  if (host.endsWith('.')) host = host.slice(0, -1); // fully-qualified trailing dot
  // An IPv6 literal arrives bracketed; an IPv4 literal is all-digits-and-dots. Neither has
  // a registrable domain, and neither is a feed.
  if (host.startsWith('[') || /^[\d.]+$/.test(host)) return '';
  const labels = host.split('.');
  if (labels.length < 2) return ''; // localhost, or a single-label intranet name
  const lastTwo = labels.slice(-2).join('.');
  const base = labels.length >= 3 && TWO_LEVEL_SUFFIXES.has(lastTwo)
    ? labels.slice(-3).join('.')
    : lastTwo;
  return DOMAIN_ALIASES.get(base) ?? base;
}

/**
 * The four feeds ThumbTrek measures out of the box, mirroring `Apps.kt`. Order is the
 * display order in Options. `twitter.com` is not listed because `registrableDomain` folds
 * it into `x.com` before anything sees it.
 */
export const DEFAULT_SITES = new Map([
  ['instagram.com', 'Instagram'],
  ['youtube.com', 'YouTube'],
  ['x.com', 'X'],
  ['reddit.com', 'Reddit'],
]);

/** Display name for any domain: built-in first, then user-supplied labels, then the key. */
export function siteName(domain, customLabels = new Map()) {
  return DEFAULT_SITES.get(domain) ?? customLabels.get?.(domain) ?? customLabels[domain] ?? domain;
}

/**
 * Normalises whatever a user types into the "add a site" field: a bare domain, a hostname
 * with `www.`, or a pasted URL. Returns '' when nothing usable is in there, which is what
 * the options page treats as "reject this input".
 */
export function parseSiteInput(input) {
  const trimmed = String(input || '').trim();
  if (!trimmed) return '';
  // Give the URL parser a scheme so `youtube.com/feed` parses as a host and not a path.
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
  try {
    return registrableDomain(new URL(withScheme).hostname);
  } catch {
    return '';
  }
}

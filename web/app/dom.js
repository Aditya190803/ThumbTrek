// Tiny DOM helpers. Deliberately tiny: this page has no framework and does not want one,
// but building elements with `document.createElement` seventeen times in a row buries the
// structure. Nothing here does anything clever — the point is that a reader can hold all of
// it in their head and then trust every other file.

export function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key === 'html') node.innerHTML = value;
    else if (key === 'dataset') Object.assign(node.dataset, value);
    else if (key.startsWith('on')) node.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key in node && key !== 'list' && typeof value !== 'string') node[key] = value;
    else node.setAttribute(key, value === true ? '' : value);
  }
  append(node, children);
  return node;
}

/** Same as [el] but in the SVG namespace, which createElement cannot reach. */
export function svg(tag, props = {}, ...children) {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value === true ? '' : value);
  }
  append(node, children);
  return node;
}

function append(node, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function clear(node) {
  node.replaceChildren();
  return node;
}

export function fill(node, ...children) {
  clear(node);
  append(node, children);
  return node;
}

/** `hidden` rather than display:none so nothing is left focusable while it is off-screen. */
export function show(node, visible) {
  if (node) node.hidden = !visible;
}

export function byId(id) {
  return document.getElementById(id);
}

/**
 * Announces a message in the page's polite live region. Used for state that changes without
 * the focus moving — a refreshed board, a sent request, going offline — because a sighted
 * user sees those and a screen reader user otherwise would not.
 */
export function announce(message) {
  const region = byId('live');
  if (!region) return;
  // Re-setting identical text does not re-announce, so clear first.
  region.textContent = '';
  region.textContent = message;
}

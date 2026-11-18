// The download buttons point at the Releases page as a working href; when the API is
// reachable we upgrade that to the APK asset itself so the click is a single download.
// Modified clicks (new tab, new window) keep the plain link: hijacking them would
// turn "open in a new tab" into a download in this one.
//
// A file of its own, not an inline script, so the Content-Security-Policy in vercel.json
// can refuse inline scripts outright.
const FALLBACK = 'https://github.com/Aditya190803/ThumbTrek/releases/latest';

async function handleDirectDownload(e) {
  if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
  e.preventDefault();
  const btn = e.currentTarget;
  if (btn.getAttribute('aria-busy') === 'true') return;
  btn.setAttribute('aria-busy', 'true');
  try {
    const res = await fetch('https://api.github.com/repos/Aditya190803/ThumbTrek/releases/latest');
    if (!res.ok) throw new Error(`GitHub answered ${res.status}`);
    const data = await res.json();
    const apk = data.assets?.find((a) => a.name.endsWith('.apk'));
    window.location.href = apk ? apk.browser_download_url : FALLBACK;
  } catch {
    window.location.href = FALLBACK;
  } finally {
    btn.removeAttribute('aria-busy');
  }
}

document.querySelectorAll('[data-direct-download]').forEach((btn) => {
  btn.addEventListener('click', handleDirectDownload);
});

// Click only the public listing matching the saved accessible label and photo.
// No auth endpoints, cookies, binary action decoding or private APIs are used.
(() => {
  const target = window.__shopRadarFacebookCard;
  if (!target) return 'invalid';
  const normalize = text => String(text || '').replace(/\s+/g,' ').trim();
  const key = src => { try { return new URL(src, location.href).pathname; } catch (_) { return ''; } };
  const hosts = new Set(['www.facebook.com','m.facebook.com','web.facebook.com']);
  const itemUrl = raw => { try { const u=new URL(raw,location.href); return u.protocol==='https:' && hosts.has(u.hostname) && /^\/marketplace\/item\/\d{6,}\/?$/.test(u.pathname) ? `https://www.facebook.com${u.pathname.replace(/\/?$/,'/')}` : ''; } catch (_) { return ''; } };
  const current = itemUrl(location.href);
  if (current) return current;
  if (/\/(?:login|checkpoint|two_step_verification)(?:\/|$)/i.test(location.pathname)) return 'login';
  if (window.__shopRadarFacebookCardClicked) {
    const canonical = itemUrl(document.querySelector('link[rel="canonical"]')?.href ||
      document.querySelector('meta[property="og:url"]')?.content);
    if (canonical) return canonical;
    if ((window.__shopRadarFacebookCardAttempts || 0) >= 3 || Date.now() - (window.__shopRadarFacebookCardClickAt || 0) < 1500) return 'waiting';
  }
  for (const node of document.querySelectorAll('[aria-label]')) {
    if (normalize(node.getAttribute('aria-label')) !== normalize(target.label)) continue;
    const card = node.closest('[data-action-id][data-type="container"]') || node.closest('a[href]');
    if (!card) continue;
    const img = card.querySelector('img');
    if (!img || key(img.currentSrc || img.src) !== target.image) continue;
    const url = itemUrl(card.getAttribute('href') || card.querySelector('a[href]')?.getAttribute('href')) ||
      itemUrl(card.getAttribute('data-href')) || itemUrl(card.getAttribute('data-url')) || itemUrl(node.getAttribute('data-href'));
    if (url) return url;
    window.__shopRadarFacebookCardClicked = true;
    window.__shopRadarFacebookCardAttempts = (window.__shopRadarFacebookCardAttempts || 0) + 1;
    window.__shopRadarFacebookCardClickAt = Date.now();
    // Server-rendered cards can precede their click-handler hydration. Retry only
    // this exact card, at most three times, instead of giving up after one early click.
    card.click();
    return 'clicked';
  }
  // Cards beyond the first batch are only loaded by ordinary scrolling.
  const scroller = [...document.querySelectorAll('[data-scrollable]')].find(node => node.scrollHeight > node.clientHeight);
  if (scroller) scroller.scrollTop += Math.max(300, innerHeight * 0.7);
  else window.scrollBy(0, Math.max(300, innerHeight * 0.7));
  return 'loading';
})()

(() => {
  const shopeeHosts = new Set(['shopee.tw','www.shopee.tw','m.shopee.tw']);
  const facebookHosts = new Set(['www.facebook.com','m.facebook.com','web.facebook.com']);
  const shopee = shopeeHosts.has(location.hostname);
  const allowed = location.protocol === 'https:' && !location.port &&
    !new URL(location.href).username && !new URL(location.href).password &&
    (shopee && /^\/search\/?$/.test(location.pathname) ||
      facebookHosts.has(location.hostname) && /^\/marketplace\/(?:\d+\/)?search\/?$/.test(location.pathname));
  if (!allowed) return JSON.stringify({error: 'not_public_search_page', html: ''});
  const MAX = 4 * 1024 * 1024;
  const root = document.documentElement;
  if (!root) return JSON.stringify({error: 'documentElement_missing', html: ''});
  const copy = root.cloneNode(true);
  // Work on a detached clone. Never change the live page or read cookie/storage APIs.
  for (const input of copy.querySelectorAll('input')) input.removeAttribute('value');
  for (const field of copy.querySelectorAll('textarea,[contenteditable="true"]')) field.textContent = '';
  const html = '<!doctype html>\n' + copy.outerHTML;
  const productLinks = new Set([...document.querySelectorAll('a[href]')].map(node => {
    try {
      const url = new URL(node.getAttribute('href'), location.href);
      return url.protocol === 'https:' && shopeeHosts.has(url.hostname) &&
        /^(?:\/product\/\d+\/\d+\/?|\/[^/]+-i\.\d+\.\d+\/?|\/i\.\d+\.\d+\/?)$/.test(url.pathname) ? url.pathname : '';
    } catch (_) { return ''; }
  }).filter(Boolean));
  const passwordFields = document.querySelectorAll('input[type="password"]').length;
  return JSON.stringify({
    html: html.slice(0, MAX), originalCharacters: html.length, truncated: html.length > MAX,
    snapshotAt: new Date().toISOString(), url: location.href,
    readyState: document.readyState, visibility: document.visibilityState,
    title: document.title, viewport: {width: innerWidth, height: innerHeight},
    scriptCount: document.scripts.length, imageCount: document.images.length,
    anchorCount: document.querySelectorAll('a[href]').length,
    marketplaceItemAnchors: document.querySelectorAll('a[href*="/marketplace/item/"]').length,
    shopeeProductLinks: productLinks.size,
    captureState: shopee ? productLinks.size ? 'product_links_present' : passwordFields ? 'password_form_present' : 'no_product_links' : 'unknown',
    languageConfirmAttempts: shopee ? Math.min(3, Math.max(0, Number(window.__shopRadarLanguageConfirmAttempts) || 0)) : 0,
    languageConfirmState: shopee && ['clicked','confirmed','unresponsive'].includes(window.__shopRadarLanguageState) ? window.__shopRadarLanguageState : 'not_attempted',
    iframeCount: document.querySelectorAll('iframe').length,
    passwordFields,
    dialogCount: document.querySelectorAll('[role="dialog"]').length,
    redacted: 'input value attributes, textarea and editable contents; scripts may still contain account data/tokens',
    scope: 'rendered top-level DOM only, not cross-origin frames or response headers'
  });
})()

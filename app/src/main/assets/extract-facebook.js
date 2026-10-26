// Facebook Marketplace public card reader. Uses only what the rendered page
// exposes: item link, accessible label, image, and visible listing time.
(() => {
  const hosts = new Set(['www.facebook.com', 'm.facebook.com', 'web.facebook.com']);
  if (!hosts.has(location.hostname)) return JSON.stringify({items:[], state:'unsupported', candidateCount:0});
  const closeOverlay = () => {
    const popup = document.querySelector('#login_popup_cta_form, form[id*="login_popup"]');
    if (!popup || window.__shopRadarFacebookOverlayDismissed) return false;
    const root = popup.closest('[role="dialog"]') || popup.parentElement || popup;
    const close = [...root.querySelectorAll('button,[role="button"]')].find(node =>
      /^(?:關閉|关闭|Close)$/i.test((node.getAttribute('aria-label') || node.textContent || '').trim()));
    if (!close) return false;
    window.__shopRadarFacebookOverlayDismissed = true;
    close.click();
    return true;
  };
  if (closeOverlay()) return JSON.stringify({items:[], state:'overlay_dismissed', candidateCount:0});
  const helpers = window.__shopRadarFacebookMetadata;
  if (!helpers) return JSON.stringify({items:[], state:'unsupported', candidateCount:0});
  const {itemUrl,cardTime,recordTime} = helpers;
  const amount = value => {
    const match = String(value || '').replace(/,/g, '').match(/(?:NT\$|TWD\s*|\$)\s*(\d+(?:\.\d+)?)/i);
    return match ? Number(match[1]) : NaN;
  };
  const result = new Map();
  window.__shopRadarFacebookScroll = () => {
    const scroller = [...document.querySelectorAll('[data-scrollable]')].find(node => node.scrollHeight > node.clientHeight);
    const distance = Math.max(300, innerHeight * 0.75);
    if (scroller) scroller.scrollTop += distance;
    else window.scrollBy(0, distance);
  };
  // WebLite renders public listings as actionable DIVs, not anchors. An action
  // ID is an ephemeral UI handle, NOT a Marketplace listing ID. Keep a local
  // reference for opening the real card later, without inventing a product URL.
  let actionCandidates = 0;
  const imageKey = raw => { try { return new URL(raw, location.href).pathname; } catch (_) { return ''; } };
  for (const area of document.querySelectorAll('[data-mcomponent="ServerImageArea"][aria-label],[data-mcomponent="ImageArea"][aria-label]')) {
    const label = (area.getAttribute('aria-label') || '').trim();
    const marker = label.match(/\s+for sale\s*-\s*/i);
    if (!marker) continue;
    const card = area.closest('[data-action-id][data-type="container"]');
    if (!card || card.querySelector('a[href*="/marketplace/item/"]')) continue;
    const title = label.slice(0, marker.index).replace(/\s+/g, ' ').trim();
    const sale = label.slice(marker.index + marker[0].length);
    const price = amount(sale);
    const freePrefix = !Number.isFinite(price) ? title.match(/^(?:免費|Free)\s+/i) : null;
    const free = !!freePrefix || /(?:^|\s-\s)(?:免費|Free)(?:\s+in\s|\s*$)/i.test(sale);
    const image = card.querySelector('img');
    const src = image?.currentSrc || image?.src || '';
    if (!title || (!Number.isFinite(price) && !free) || !src.startsWith('https://')) continue;
    actionCandidates++;
    const source = new URL(location.href); source.hash = '';
    if (!/^\/marketplace\/(?:\d+\/)?search\/?$/.test(source.pathname)) continue;
    source.hash = 'shopradar-fb-card=' + encodeURIComponent(JSON.stringify({label, image:imageKey(src)}));
    const direct = itemUrl(card.getAttribute('data-href')) || itemUrl(card.getAttribute('data-url')) || itemUrl(area.getAttribute('data-href'));
    const url = direct || source.href;
    if (!result.has(url)) result.set(url, {platform:'Facebook', title:(freePrefix ? title.slice(freePrefix[0].length) : title).slice(0,240),
      price:free ? 0 : Math.round(price), url, image:src, ...cardTime(card, title), shop:'', isAdvertisement:false});
    if (!window.__shopRadarInfiniteSearch && result.size >= 80) break;
  }
  const links = [...document.querySelectorAll('a[href]')].filter(link => itemUrl(link.href));
  for (const link of links) {
    const url = itemUrl(link.href);
    const label = (link.getAttribute('aria-label') || link.innerText || link.textContent || '').replace(/\s+/g, ' ').trim();
    let card = link;
    for (let i = 0; i < 4 && card.parentElement; i++) {
      const parent = card.parentElement;
      const itemLinks = new Set([...parent.querySelectorAll('a[href]')].map(a => itemUrl(a.href)).filter(Boolean));
      if (itemLinks.size > 1) break; // Never borrow a neighbouring listing's date/image.
      if (parent.querySelector('img') && itemLinks.size === 1) { card = parent; break; }
      if (parent === document.body || parent.innerText.length > 1800) break;
      card = parent;
    }
    // Some mobile layouts expose price first and split title across separate spans.
    const price = amount(label) || (amount(label) === 0 ? 0 : amount(card.innerText));
    if (!Number.isFinite(price) || price < 0) continue;
    let title = label.split(/[,，](?=(?:NT\$|TWD\s*|\$))/i)[0].trim();
    if (/^(?:NT\$|TWD\s*|\$)/i.test(title) || title === label) {
      const lines = (link.innerText || card.innerText || '').split(/\n+/).map(x => x.trim()).filter(Boolean);
      title = lines.find(line => !Number.isFinite(amount(line)) && !/^(?:免費|Free|商品\d+|已售出|Sold)$/i.test(line)) || '';
      if (!title) title = label.replace(/(?:NT\$|TWD\s*|\$)\s*[\d,.]+/ig,'').replace(/^[,，\s]+|[,，\s]+$/g,'').split(/[,，]/)[0].trim();
    }
    if (title.length < 2) continue;
    const image = link.querySelector('img') || card.querySelector('img');
    const item = {platform:'Facebook', title:title.slice(0,240), price:Math.round(price), url,
      image:image?.currentSrc || image?.src || '', ...cardTime(card, title), shop:'', isAdvertisement:false};
    if (!result.has(url) || (!result.get(url).image && item.image)) result.set(url, item);
    if (!window.__shopRadarInfiniteSearch && result.size >= 80) break;
  }
  // Read only identifiable public listing records supplied in this page, not private APIs.
  // React may include cards in inline JSON before it renders their DOM links.
  let structuredCandidates = 0;
  for (const node of document.querySelectorAll('script[type="application/json"],script[type="application/ld+json"]')) {
    const raw = node.textContent || '';
    if (!/marketplace_listing_title|listing_price|"@type"\s*:\s*"Product"/.test(raw) || raw.length > 3000000) continue;
    let payload;
    try { payload = JSON.parse(raw); } catch (_) { continue; }
    const stack = [payload]; let visited = 0;
    while (stack.length && visited++ < 30000) {
      const record = stack.pop();
      if (!record || typeof record !== 'object') continue;
      if (typeof record.marketplace_listing_title === 'string' && record.listing_price) {
        structuredCandidates++;
        const id = typeof record.id === 'number' && !Number.isSafeInteger(record.id) ? '' : String(record.id || '');
        const currency = record.listing_price.currency;
        const rawPrice = String(record.listing_price.amount ?? '').trim();
        const price = rawPrice ? Number(rawPrice.replace(/,/g,'')) : NaN;
        if (/^\d{6,}$/.test(id) && currency === 'TWD' && Number.isFinite(price) && price >= 0 && record.marketplace_listing_title.trim()) {
          const url = `https://www.facebook.com/marketplace/item/${id}/`;
          const rawImage = record.primary_listing_photo?.image?.uri;
          const image = typeof rawImage === 'string' ? rawImage : '';
          const title = record.marketplace_listing_title.trim().slice(0,240);
          const date = recordTime(record);
          const refs = [...result.entries()].filter(([key,item]) => key.includes('#shopradar-fb-card=') && item.title === title &&
            item.price === Math.round(price) && image.startsWith('https://') && imageKey(item.image) === imageKey(image));
          if (refs.length === 1) {
            const [key,old] = refs[0];
            result.set(key,{...old,...(date.listedAt ? date : {}),resolvedUrl:url});
          } else if (!result.has(url)) result.set(url, {platform:'Facebook', title,
            price:Math.round(price), url, image: image.startsWith('https://') ? image : '', ...date, shop:'', isAdvertisement:false});
          else if (date.listedAt) result.set(url,{...result.get(url),...date});
        }
      }
      if (record['@type'] === 'Product') {
        const url = itemUrl(record.url || record['@id'] || record.offers?.url);
        const date = recordTime(record);
        if (url && date.listedAt && result.has(url)) result.set(url,{...result.get(url),...date});
      }
      for (const value of Object.values(record)) if (value && typeof value === 'object') stack.push(value);
    }
  }
  const body = document.body?.innerText || '';
  const loginRoute = /\/(?:login|checkpoint|two_step_verification)(?:\/|$)/i.test(location.pathname);
  const loginWall = !!document.querySelector('#login_popup_cta_form, form[id*="login_popup"]') ||
    (!links.length && !!document.querySelector('[role="dialog"] input[type="password"]'));
  const empty = /沒有符合.*(?:商品|結果)|找不到符合|No results found|No listings found/i.test(body);
  return JSON.stringify({items:[...result.values()], state:result.size ? 'ready' : loginRoute || loginWall ? 'login' : empty ? 'empty' : 'loading',
    candidateCount:links.length + structuredCandidates + actionCandidates, structuredCandidates, actionCandidates,
    scriptCount:document.scripts.length, imageCount:document.images.length,
    visibility:document.visibilityState, viewportWidth:innerWidth, viewportHeight:innerHeight,
    readyState:document.readyState});
})()

// Only published, product-identified metadata. No guessed dates, private globals or extra requests.
(() => {
  const helpers = window.__shopRadarFacebookMetadata;
  if (!helpers) return;
  const identity = raw => {
    try {
      const u = new URL(raw, location.href);
      if (u.protocol !== 'https:' || u.username || u.password || u.port) return '';
      if (['shopee.tw','www.shopee.tw','m.shopee.tw'].includes(u.hostname)) {
        const m = u.pathname.match(/^\/product\/(\d+)\/(\d+)\/?$/) || u.pathname.match(/-i\.(\d+)\.(\d+)\/?$/);
        return m ? 'shopee:' + m[1] + ':' + m[2] : '';
      }
      if (u.hostname === 'mall.iopenmall.tw' && /^\/\d{6}\/index\.php$/.test(u.pathname) &&
          u.searchParams.get('action') === 'product_detail' && /^P\d+$/.test(u.searchParams.get('prod_no') || ''))
        return 'iopen:' + u.pathname + ':' + u.searchParams.get('prod_no');
      return helpers.itemUrl(u.href);
    } catch (_) { return ''; }
  };
  const dates = new Map();
  // Reuse only extracted public product timestamps. No parsed account/session state is retained.
  // Invalidate on script text changes and route changes; new lazy scripts are still read.
  const scriptCache = window.__shopRadarListingScriptCache ||= new WeakMap();
  let visited = 0;
  const walk = (value, depth = 0, output = dates) => {
    if (!value || typeof value !== 'object' || depth > 18 || ++visited > 20000) return;
    if (!Array.isArray(value)) {
      const declarations = [value.url,value['@id'],value.canonical_url,value.listing_url].filter(v => typeof v === 'string');
      const ids = declarations.map(identity).filter(Boolean);
      const shopeeItem = /^\d+$/.test(String(value.itemid)) && /^\d+$/.test(String(value.shopid));
      const facebookItem = value.__typename === 'MarketplaceListing' && /^\d{6,}$/.test(String(value.id));
      const productType = [value['@type']].flat().includes('Product');
      if (shopeeItem)
        ids.push('shopee:' + value.shopid + ':' + value.itemid);
      if (facebookItem)
        ids.push('https://www.facebook.com/marketplace/item/' + value.id + '/');
      // A bare SKU is accepted only on its own canonical product page, never on a result list.
      const current = identity(location.href);
      const canonical = document.querySelector('link[rel="canonical"]')?.href;
      if (current.startsWith('iopen:') && identity(canonical) === current &&
          [value.productID,value.sku].includes(new URL(location.href).searchParams.get('prod_no'))) ids.push(current);
      if ((productType || shopeeItem || facebookItem) && ids.length && new Set(ids).size === 1 &&
          declarations.every(raw => identity(raw) === ids[0])) {
        const id = ids[0];
        const keys = ['datePosted','datePublished','listing_creation_time'];
        if (id.startsWith('shopee:')) keys.push('ctime');
        for (const key of keys) {
          const date = helpers.absoluteTime(value[key]);
          if (date.listedAt) { output.set(id,date); break; }
        }
      }
    }
    for (const child of Object.values(value)) if (child && typeof child === 'object') walk(child,depth+1,output);
  };
  let bytes = 0;
  for (const script of document.querySelectorAll('script[type="application/ld+json"],script[type="application/json"]')) {
    let cached = scriptCache.get(script);
    const budget = 20000 - visited;
    if (cached && !cached.dirty && cached.route === location.href && cached.budget === budget) {
      bytes += cached.bytes;
      if (bytes > 2097152) break;
      visited += cached.visited;
      for (const [id, date] of cached.dates) dates.set(id, date);
      continue;
    }
    const raw = script.textContent || '';
    bytes += raw.length;
    if (bytes > 2097152) break;
    const before = visited, productDates = new Map();
    try { walk(JSON.parse(raw), 0, productDates); } catch (_) {}
    for (const [id, date] of productDates) dates.set(id, date);
    if (typeof MutationObserver !== 'undefined') {
      if (!cached) {
        cached = {};
        const observer = new MutationObserver(() => { cached.dirty = true; });
        observer.observe(script, {childList: true, characterData: true, subtree: true});
        scriptCache.set(script, cached);
      }
      Object.assign(cached, {dirty: false, route: location.href, budget, bytes: raw.length,
        visited: visited - before, dates: productDates});
    }
  }
  const structured = url => dates.get(identity(url)) || helpers.emptyTime();
  const forProduct = (url, card) => {
    const published = structured(url);
    if (published.listedAt) return published;
    if (!card) return helpers.emptyTime();
    // A card's explicit listing field is allowed; arbitrary numeric text is not.
    for (const node of card.querySelectorAll('time,[data-listed-at],[itemprop="datePublished"],[itemprop="datePosted"],[data-testid*="listing-time"],[data-testid*="posted"],[class*="listing-time"],[class*="posted-time"]')) {
      if (!helpers.shown(node)) continue;
      if (node.matches('[itemprop="dateModified"],[data-testid*="updated"]') || node.closest('aside,nav,footer,[itemprop="review"]')) continue;
      if (node.closest('h1,h2,h3,[itemprop="name"],[data-sqe="name"]')) continue;
      const context = (node.parentElement?.innerText || '').trim();
      if (context.length < 160 && /更新|降價|售出|送達|配送|活動|上線|活躍|updated|delivery|sold|last active/i.test(context)) continue;
      const date = helpers.absoluteTime(node.getAttribute('datetime') || node.getAttribute('data-listed-at') || node.getAttribute('content'));
      if (date.listedAt) return date;
      const visible = helpers.visibleTime(node.innerText, identity(url).startsWith('shopee:'));
      if (visible.listedAt) return visible;
    }
    for (const node of card.querySelectorAll('span,p,li,dt,dd,div')) {
      const text = (node.innerText || '').replace(/\s+/g,' ').trim();
      if (text.length > 140 || !/^(?:上架|刊登|發布|发布)(?:時間|日期|於|于)?\s*[:：]|^(?:listed|posted|published)\b/i.test(text) || !helpers.shown(node)) continue;
      if (node.closest('h1,h2,h3,[itemprop="name"],[data-sqe="name"]')) continue;
      const date = helpers.visibleTime(text.replace(/^(上架|刊登|發布|发布)日期/, '$1時間'), identity(url).startsWith('shopee:'));
      if (date.listedAt) return date;
    }
    return helpers.emptyTime();
  };
  window.__shopRadarPublicListingTime = {identity,structured,forProduct};
})()

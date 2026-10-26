// First-party rendered cards only. Never infer stock or listing time from an ID/image filename.
(() => {
  const host = location.hostname;
  const bobo = ['www.bbbobo.com.tw', 'bbbobo.com.tw'].includes(host);
  const iopen = host === 'mall.iopenmall.tw';
  if (!bobo && !iopen) return JSON.stringify([]);
  const platform = bobo ? '跳蚤本舖' : 'iOPEN Mall';
  const results = new Map();
  const absolute = value => { if (!value) return ''; try { const u = new URL(value, location.href); return u.protocol === 'https:' ? u.href : ''; } catch (_) { return ''; } };
  function add(card, title, priceText, url, shop) {
    const price = Number(String(priceText).replace(/,/g, '').match(/\d+(?:\.\d+)?/)?.[0]);
    if (!title || !Number.isFinite(price) || price < 0 || !url) return;
    const image = card.querySelector('img');
    const date = card.querySelector('time[datetime],[data-listed-at]');
    results.set(url, {title: title.trim(), price, platform, url,
      image: absolute(image?.getAttribute('data-src') || image?.getAttribute('src') || ''),
      shop: (shop || '').trim(), listedAt: date?.getAttribute('datetime') || date?.getAttribute('data-listed-at') || '',
      ...(iopen ? window.__shopRadarPublicListingTime?.forProduct(url, card) : {})});
  }
  if (bobo) {
    const mall = /^\/shop\/0_1\//i.test(location.pathname) ? '0_1' : '0';
    for (const card of document.querySelectorAll('.pr-info[GID][AID]')) {
      const aid = card.getAttribute('AID'), gid = card.getAttribute('GID');
      if (!/^\d+$/.test(aid || '') || !/^GTT\d{8,}$/i.test(gid || '')) continue;
      const title = card.querySelector('.pr-name');
      add(card, title?.getAttribute('title') || title?.textContent || '', card.querySelector('.pr-price-org')?.textContent || '',
        'https://www.bbbobo.com.tw/shop/' + mall + '/Goods.asp?AID=' + aid + '&GID=' + gid.toUpperCase(), card.querySelector('.shop-name')?.textContent);
    }
    for (const [url, item] of results) {
      const metadata = window.__shopRadarBoboMetadata?.(document, url);
      if (metadata) Object.assign(item, metadata);
    }
  } else {
    const productUrl = value => {
      try {
        const u = new URL(value, location.href), id = u.searchParams.get('prod_no');
        if (u.protocol !== 'https:' || u.hostname !== host || !/^\/\d{6}\/index\.php$/i.test(u.pathname) ||
            u.searchParams.get('action') !== 'product_detail' || !/^P\d+$/.test(id || '')) return '';
        return u.origin + u.pathname + '?action=product_detail&prod_no=' + id;
      } catch (_) { return ''; }
    };
    for (const link of document.querySelectorAll('a[href*="product_detail"]')) {
      const url = productUrl(link.getAttribute('href'));
      if (!url || results.has(url)) continue;
      let card = link.parentElement;
      for (let depth = 0; card && depth < 6 && card !== document.body; depth++, card = card.parentElement) {
        const identities = new Set([...card.querySelectorAll('a[href*="product_detail"]')]
          .map(node => productUrl(node.getAttribute('href'))).filter(Boolean));
        if (identities.size > 1) break; // Never take another card's title or price.
        const heading = card.querySelector('h2,h3,[itemprop="name"]');
        const title = heading?.textContent.trim() || link.getAttribute('title') || card.querySelector('img')?.getAttribute('alt') || '';
        const offer = card.querySelector('[itemprop="price"]');
        const priceOnly = card.cloneNode(true);
        // Original/struck-through prices and names cannot supply the current price.
        priceOnly.querySelectorAll('h2,h3,del,s,script,style,[itemprop="name"]').forEach(node => node.remove());
        const money = (priceOnly.innerText || priceOnly.textContent || '').match(/(?:NT\$|\$)\s*([\d,]+(?:\.\d+)?)/);
        const price = offer?.getAttribute('content') || offer?.textContent || money?.[1] || '';
        if (title && price) { add(card, title, price, url, ''); break; }
      }
    }
    // Detect only the existence of the site's verification controls; never read token values.
    window.__shopRadarVerificationRequired = results.size === 0 &&
      Boolean(document.querySelector('.h-captcha,iframe[src*="hcaptcha"],form input[name="cfi"]'));
  }
  return JSON.stringify([...results.values()]);
})()

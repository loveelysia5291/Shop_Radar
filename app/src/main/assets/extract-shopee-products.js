// Read only product cards already rendered in this public document.
// No requests, page routing, session access, or hidden application state.
(function readShopeePublicCards() {
  window.__shopRadarPublicCardCount = 0;
  const stats = {routeAllowed: false, candidates: 0, missingTitle: 0, missingPrice: 0, parsed: 0};
  window.__shopRadarPublicCardStats = stats;
  const state = {requestMatches: false, pageVerified: false, slots: 0, complete: false, previousPageOnly: false, totalPages: 0};
  let profileName = '';
  const response = items => {
    window.__shopRadarShopeePageState = state; // Counts/booleans only, for local Debug diagnostics.
    return JSON.stringify(window.__shopRadarShopeeEnvelope ? {items, state, shopName: profileName, shopUrl: location.href} : items);
  };
  const officialHosts = new Set(['shopee.tw', 'www.shopee.tw', 'm.shopee.tw']);
  const current = new URL(location.href);
  const publicListPath = /^\/(?:search|shop\/[1-9]\d*(?:\/search)?)\/?$/;
  const privatePath = /^\/(?:buyer|user|account|login|signup|register|verify|verification|captcha|cart|checkout|purchase|order|orders|wallet|bank|payment|settings|chat|conversation|notifications|wishlist|seller|seller-center|portal|api)(?:\/|$)/i;
  // Native subscription reads may select an official username store route. Ordinary
  // search reads remain restricted to the known list routes above.
  const requestedStore = window.__shopRadarShopeeStoreUrl ? new URL(window.__shopRadarShopeeStoreUrl) : null;
  const usernameStore = requestedStore && requestedStore.origin === current.origin &&
    requestedStore.pathname.replace(/\/$/, '') === current.pathname.replace(/\/$/, '') &&
    /^\/[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}\/?$/.test(current.pathname);
  if (window.top !== window || current.protocol !== 'https:' || !officialHosts.has(current.hostname) ||
      current.port || current.username || current.password || privatePath.test(current.pathname) ||
      (!publicListPath.test(current.pathname) && !usernameStore)) return response([]);
  stats.routeAllowed = true;
  // A SPA can show page 1's cards while page 2 is loading. A matching URL alone
  // does not prove that the rendered product list has changed pages.
  const expected = new URL(window.__shopRadarExpectedSearchUrl || current.href);
  const infinite = window.__shopRadarInfiniteSearch === true;
  const signature = url => [url.pathname.replace(/\/$/, ''),
    ...['keyword', ...(infinite ? [] : ['page']), 'sortBy', 'order', 'fe_filter_options'].map(key =>
      url.searchParams.get(key) || (key === 'page' ? '0' : key === 'sortBy' ? 'relevancy' : ''))].join('\n');
  state.requestMatches = signature(current) === signature(expected);
  const pageLabel = document.querySelector('.shopee-mini-page-controller__current, .shopee-page-controller .shopee-button-solid--primary');
  const pageNumber = Number(pageLabel?.textContent.trim());
  state.pageVerified = infinite || !pageLabel || pageNumber === Number(expected.searchParams.get('page') || '0') + 1;
  if (!state.requestMatches || !state.pageVerified) return response([]);
  const totalPages = Number(document.querySelector('.shopee-mini-page-controller__total')?.textContent.trim());
  if (Number.isInteger(totalPages) && totalPages > 0 && totalPages <= 10000) state.totalPages = totalPages;
  // Do not mix recommendations, shop previews or another list into search results.
  const grid = document.querySelector('.shopee-search-item-result__items');
  const listingRoot = grid || document;
  const slots = grid ? [...grid.querySelectorAll('.shopee-search-item-result__item')] : [];
  if (grid && !slots.length) slots.push(...grid.children);
  state.slots = slots.length;
  const scroller = document.scrollingElement || document.documentElement;
  state.atBottom = grid ? grid.getBoundingClientRect().bottom <= window.innerHeight + 32 :
    scroller.scrollTop + window.innerHeight >= scroller.scrollHeight - 32;
  window.__shopRadarShopeeScroll = () => {
    // Advance through lazy rows, not straight to the footer (which skips rows).
    window.scrollBy(0, Math.max(240, window.innerHeight * 0.7));
  };

  const normalizeText = value => String(value || '').replace(/[\u200b-\u200d\ufeff]/g, '').replace(/\s+/g, ' ').trim();
  // These caches live for one synchronous read only. A later poll rechecks DOM.
  const visibility = new WeakMap(), styles = new WeakMap(), texts = new WeakMap(), nodeNames = new WeakMap();
  const style = node => {
    if (!styles.has(node)) styles.set(node, getComputedStyle(node));
    return styles.get(node);
  };
  const rendered = node => {
    if (!node) return false;
    if (visibility.has(node)) return visibility.get(node);
    const hidden = () => { visibility.set(node, false); return false; };
    if (!node.isConnected || node.closest('[hidden],[aria-hidden="true"]')) return hidden();
    for (let ancestor = node; ancestor && ancestor !== document; ancestor = ancestor.parentElement) {
      const computed = style(ancestor);
      if (computed.display === 'none' || computed.visibility === 'hidden' || computed.visibility === 'collapse' || Number(computed.opacity) === 0) return hidden();
    }
    // The current PC card's anchor and picture wrappers use display: contents.
    // They intentionally have no box, while their children are fully rendered.
    // Only this explicit CSS mode may inherit visibility from a rendered child;
    // an ordinary empty/zero-size link must not expose an unrendered card.
    if (![...node.getClientRects()].some(rect => rect.width > 0 && rect.height > 0) &&
        !(style(node).display === 'contents' && [...node.children].some(rendered))) return hidden();
    visibility.set(node, true);
    return true;
  };
  const text = node => {
    if (!node) return '';
    if (!texts.has(node)) texts.set(node, normalizeText(node.innerText || ''));
    return texts.get(node);
  };
  const names = node => {
    if (!nodeNames.has(node)) nodeNames.set(node, `${node.getAttribute('class') || ''} ${node.id || ''} ${node.getAttribute('data-testid') || ''} ${node.getAttribute('data-sqe') || ''} ${node.getAttribute('itemprop') || ''}`);
    return nodeNames.get(node);
  };

  function canonicalProduct(value) {
    try {
      const url = new URL(value, current.href);
      if (url.protocol !== 'https:' || !officialHosts.has(url.hostname) || url.port || url.username || url.password) return '';
      const match = url.pathname.match(/^\/product\/([1-9]\d{0,19})\/([1-9]\d{0,19})\/?$/) ||
        url.pathname.match(/^\/[^/]+-i\.([1-9]\d{0,19})\.([1-9]\d{0,19})\/?$/);
      return match ? `https://shopee.tw/product/${match[1]}/${match[2]}` : '';
    } catch (_) { return ''; }
  }

  const identities = new WeakMap();
  function cardIdentities(node) {
    if (!identities.has(node)) {
      const links = node.matches('a[href]') ? [node, ...node.querySelectorAll('a[href]')] : [...node.querySelectorAll('a[href]')];
      identities.set(node, new Set(links.map(link => canonicalProduct(link.getAttribute('href'))).filter(Boolean)));
    }
    return identities.get(node);
  }

  // Metadata, former prices and seller profile content are not product names
  // or current prices, even when they appear earlier in a card's DOM.
  const metadataNames = /(?:^|[\s_-])(?:avatar|profile|seller-name|shop-name|seller-avatar|shop-logo|rating|reviews?|sold|sales|shipping|delivery|freight|voucher|coupon|cashback|installment|original-price|old-price|price-old|price-original|price-before-discount)(?:$|[\s_-])/i;
  function metadata(node, boundary, includePrices = false) {
    for (let ancestor = node; ancestor; ancestor = ancestor.parentElement) {
      if (metadataNames.test(names(ancestor)) || /(?:seller|shop|store|profile)[_-]?(?:name|title|avatar|image|logo)|(?:old|original)[_-]*price|price[_-]*(?:old|original|before[_-]*discount)/i.test(names(ancestor)) || /^(?:S|STRIKE|DEL)$/.test(ancestor.tagName) ||
          style(ancestor).textDecorationLine.includes('line-through')) return true;
      if (includePrices && /price/i.test(names(ancestor))) return true;
      if (ancestor === boundary) break;
    }
    return false;
  }

  function titleValue(value, general = false) {
    const valueText = normalizeText(value);
    if (valueText.length < 2 || /^[\d\s,.+%★☆\-–~～]+$/.test(valueText)) return '';
    if (/^(?:商品(?:圖片|照片)|產品(?:圖片|照片)|圖片|照片|product(?:\s+(?:image|photo))?|image|photo)$/i.test(valueText)) return '';
    if (/^(?:賣家|seller|查看賣家|view profile|頭像|avatar|profile|蝦皮優選|蝦皮商城|贊助|sponsored|廣告|免運費?|貨到付款|促銷|銷量|銷售|已售出?|售出|rating|reviews?)(?:$|[\s:：\d])/i.test(valueText)) return '';
    if (/^(?:NT\s*[$＄]|NTD|TWD|[$＄])\s*\d/i.test(valueText) ||
        /(?:已售出?|售出|銷量|sold)\s*[\d,.萬千k]/i.test(valueText) ||
        /^[\d,.萬千k]+\s*(?:已售出?|售出|sold|ratings?|reviews?)/i.test(valueText)) return '';
    if (/^(?:\d+\s*(?:分鐘|小時|天|週|個月)前|昨天|今天|剛剛|just now|\d+\s*(?:minutes?|hours?|days?) ago)$/i.test(valueText)) return '';
    if (general && /(?:[$＄]\s*\d|\b(?:NTD|TWD)\s*\d)/i.test(valueText)) return '';
    return valueText.slice(0, 240);
  }

  function readTitle(card, links, productImage) {
    const selector = '[itemprop="name"],[data-sqe="name"],[data-testid*="title"],[data-testid*="name"],[class*="product-name"],[class*="product-title"],[class*="item-name"],[class*="item_name"],[class*="line-clamp"],h2,h3';
    const preferred = [...new Set(links.flatMap(link => [...link.querySelectorAll(selector)]).concat([...card.querySelectorAll(selector)]))];
    preferred.sort((a, b) => Number(/(?:title|name)/i.test(names(b))) - Number(/(?:title|name)/i.test(names(a))));
    for (const node of preferred) {
      if (!rendered(node) || metadata(node, card, true)) continue;
      const title = titleValue(text(node));
      if (title) return title;
    }
    for (const link of links) {
      for (const value of [link.getAttribute('title'), link.getAttribute('aria-label')]) {
        const title = titleValue(normalizeText(value).replace(/^View product\s*:\s*/i, ''), true);
        if (title) return title;
      }
    }
    const simple = [...new Set(links.flatMap(link => [link, ...link.querySelectorAll('span,div,p')]).concat([...card.querySelectorAll('span,div,p')]))];
    for (const node of simple) {
      if (!rendered(node) || metadata(node, card, true)) continue;
      // A wrapper concatenating a title with sales/price labels is ambiguous.
      const title = titleValue(text(node), true);
      if (title && title.length <= 240 && text(node).length <= 240) return title;
    }
    return titleValue(productImage?.getAttribute('alt'), true);
  }

  const number = '(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?';
  const currency = '(?:NT\\s*[$＄]|NTD|TWD|[$＄])';
  const displayedMoney = new RegExp(`^(?:(?:售價|特價|優惠價|價格|Price)\\s*[:：]?\\s*)?${currency}\\s*(${number})(?:\\s*[-–~～至]\\s*(?:${currency}\\s*)?${number})?$`, 'i');
  const displayedNumber = new RegExp(`^(${number})(?:\\s*[-–~～至]\\s*${number})?$`);
  function priceText(node, card) {
    const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
    const parts = [];
    for (let part = walker.nextNode(); part; part = walker.nextNode()) {
      if (rendered(part.parentElement) && !metadata(part.parentElement, card)) parts.push(part.nodeValue);
    }
    return normalizeText(parts.join(''));
  }
  function readPrice(card) {
    const nodes = [...card.querySelectorAll('span,div,p,strong,b,em,[itemprop="price"]')];
    if (card.matches('span,div,p,strong,b,em,[itemprop="price"]')) nodes.unshift(card);
    const candidates = nodes.filter(node => rendered(node) && !metadata(node, card));
    const score = node => /(?:sale|current|final|display)[_-]?price|price[_-]?(?:sale|current|final)/i.test(names(node)) ? 2 : /price/i.test(names(node)) ? 1 : 0;
    candidates.sort((a, b) => score(b) - score(a));
    for (const node of candidates) {
      const value = priceText(node, card);
      if (value.length > 100) continue;
      const match = value.match(displayedMoney) || (score(node) > 0 ? value.match(displayedNumber) : null);
      if (!match) continue;
      const amount = Number(match[1].replace(/,/g, ''));
      if (Number.isFinite(amount) && amount >= 0 && Number.isSafeInteger(Math.round(amount))) return Math.round(amount);
    }
    return null;
  }

  function imageUrl(value) {
    try {
      const url = new URL(value, current.href);
      if (!value || url.protocol !== 'https:' || url.username || url.password || url.port ||
          /(?:placeholder|transparent|spacer|empty[-_]image|no[-_]image|default[-_]image|loading\.(?:gif|png|svg)|blank\.(?:gif|png|svg))(?:[/.?_-]|$)/i.test(url.pathname)) return '';
      return url.href;
    } catch (_) { return ''; }
  }
  function srcsetValues(value) {
    return String(value || '').split(',').map(part => {
      const match = part.trim().match(/^(\S+?)(?:\s+(\d+(?:\.\d+)?)(?:w|x))?$/);
      return match ? {url: match[1], size: Number(match[2] || 1)} : null;
    }).filter(Boolean).sort((a, b) => a.size - b.size).map(part => part.url);
  }
  function readImage(card, links) {
    const images = [...new Set(links.flatMap(link => [...link.querySelectorAll('img')]).concat([...card.querySelectorAll('img')]))];
    for (const image of images) {
      if (!rendered(image) || metadata(image, card) ||
          /avatar|profile|user[-_ ]?image|seller[-_ ]?(?:image|avatar)|shop[-_ ]?logo/i.test(`${names(image)} ${image.getAttribute('alt') || ''}`) ||
          /^(?:custom[-_ ]overlay|flag[-_ ]label|rating[-_ ]star(?:[-_ ](?:full|half|empty))?|location[-_ ]icon)$/i.test(image.getAttribute('alt') || '') ||
          image.closest('[aria-label^="image overlay"],[class*="custom-overlay"],[class*="image-overlay"]')) continue;
      const tiny = image.naturalWidth > 0 && image.naturalHeight > 0 && (image.naturalWidth < 4 || image.naturalHeight < 4);
      const sources = [
        ...srcsetValues(image.getAttribute('data-srcset')), ...srcsetValues(image.getAttribute('srcset')),
        ...[...image.closest('picture')?.querySelectorAll('source[srcset]') || []].flatMap(source => srcsetValues(source.getAttribute('srcset'))),
        image.getAttribute('data-src'), image.getAttribute('data-lazy-src'),
        ...(!tiny ? [image.getAttribute('src'), image.currentSrc] : []), image.getAttribute('data-original')
      ];
      for (const source of sources) {
        const url = imageUrl(source);
        if (url && !/avatar|profile/i.test(new URL(url).pathname)) return {url, node: image};
      }
    }
    return null;
  }

  function listingTime(card) {
    for (const node of card.querySelectorAll('time,[data-testid*="listing-time"],[data-testid*="posted"],[class*="listing-time"],[class*="posted-time"]')) {
      if (!rendered(node)) continue;
      const value = text(node);
      if (/^(?:20\d{2}[./-]\d{1,2}[./-]\d{1,2}(?:[ T]\d{1,2}:\d{2}(?::\d{2})?)?|\d+\s*(?:分鐘|小時|天|週|個月)前|昨天|今天|剛剛|just now|\d+\s*(?:minutes?|hours?|days?) ago)$/i.test(value)) return value;
    }
    return '';
  }
  function shopName() {
    if (usernameStore || /^\/shop\/[1-9]\d*\/?$/.test(current.pathname)) {
      const title = normalizeText(document.title).match(/^(.+?)[,，]\s*線上商店\s*[|｜]\s*(?:蝦皮購物|Shopee)$/i);
      if (title) return title[1].trim().slice(0, 120);
    }
    return '';
  }
  function advertisement(card) {
    return [...card.querySelectorAll('span,div,p')].some(node => rendered(node) && /^(?:廣告|贊助|Sponsored|Ad)$/i.test(text(node)));
  }
  function startingCard(link, url) {
    let fallback = null;
    for (let node = link, depth = 0; node && depth < 10; node = node.parentElement, depth++) {
      if (/^(?:BODY|HTML|MAIN|NAV|HEADER|FOOTER|FORM)$/.test(node.tagName)) break;
      const ids = cardIdentities(node);
      if (ids.size !== 1 || !ids.has(url) || text(node).length > 4000 ||
          node.getAttribute('role') === 'dialog' || node.querySelector('form,input')) break;
      if (node.matches('article,li,.shopee-search-item-result__item,[role="group"][aria-label="Product card"],[role="group"][aria-label^="Product card:"],[data-sqe="item"],[data-testid="listing-card"],[data-testid*="item-card"],[data-testid*="product-card"]')) return node;
      // Unclassified div cards may put the real title/time beside an image link.
      // Prefer the nearest single-product parent with its own public title;
      // an image alt on the child must not end the card search prematurely.
      if (!fallback && node !== link && node.querySelector('h2,h3,[itemprop="name"],[data-sqe="name"],[data-testid*="title"],[class*="product-name"],[class*="item-name"]')) {
        const links = [...node.querySelectorAll('a[href]')].filter(a => canonicalProduct(a.getAttribute('href')) === url);
        if (readTitle(node, links, null) && readImage(node, links) && readPrice(node) != null) fallback = node;
      }
    }
    return fallback || link;
  }

  profileName = shopName();
  const products = new Map();
  const candidates = new Set(), missing = new Map();
  for (const link of listingRoot.querySelectorAll('a[href]')) {
    const url = canonicalProduct(link.getAttribute('href'));
    if (!url || products.has(url) || !rendered(link) || link.closest('form,[role="dialog"],nav,header,footer')) continue;
    candidates.add(url);
    let missingReason = 'missingPrice';
    let card = startingCard(link, url);
    // A multi-product wrapper is never a card. Start at the listing link and
    // grow only while every listing link still points to this exact product.
    for (let depth = 0; card && depth < 10; depth++, card = card.parentElement) {
      if (/^(?:BODY|HTML|MAIN|NAV|HEADER|FOOTER|FORM)$/.test(card.tagName) || card.getAttribute('role') === 'dialog') break;
      const ids = cardIdentities(card);
      if (ids.size !== 1 || !ids.has(url) || text(card).length > 4000 || card.querySelector('input[type="password"]')) break;
      const cardLinks = [card.matches('a[href]') ? card : null, ...card.querySelectorAll('a[href]')]
        .filter(candidate => candidate && canonicalProduct(candidate.getAttribute('href')) === url && rendered(candidate));
      const image = readImage(card, cardLinks);
      const price = readPrice(card);
      if (price == null) continue;
      const title = readTitle(card, cardLinks, image?.node);
      if (!title) { missingReason = 'missingTitle'; continue; }
      // An unavailable image is optional; never substitute a seller avatar or
      // discard a named, priced public product just because its image is late.
      const date = window.__shopRadarPublicListingTime?.forProduct(url, card);
      products.set(url, {platform: '蝦皮', title, price, url, image: image?.url || '',
        ...date, listedAt: date?.listedAt || listingTime(card),
        shop: profileName, isAdvertisement: advertisement(card), isAdult: false});
      break;
    }
    if (!products.has(url)) missing.set(url, missingReason);
    else missing.delete(url);
  }
  stats.candidates = candidates.size;
  for (const reason of missing.values()) stats[reason]++;
  stats.parsed = products.size;
  window.__shopRadarPublicCardCount = products.size;
  const previousUrls = new Set(window.__shopRadarPreviousPageUrls || []);
  state.previousPageOnly = previousUrls.size > 0 && products.size > 0 && [...products.keys()].every(url => previousUrls.has(url));
  const loading = !!listingRoot.querySelector('[aria-busy="true"],.shopee-search-item-result__skeleton,[data-testid="loading-skeleton"]');
  // Variant cards can share one canonical product ID. They are complete slots,
  // not missing products that should force a 20-second wait.
  state.complete = state.slots > 0 && slots.every(slot =>
    [...slot.querySelectorAll('a[href]')].some(link => products.has(canonicalProduct(link.getAttribute('href'))))) && !loading;
  return response(state.previousPageOnly ? [] : [...products.values()]);
})()

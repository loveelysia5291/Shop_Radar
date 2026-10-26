// Public rendered product cards only; no cookies, credentials, or private APIs.
(() => {
  const canonicalHost = value => ({'m.ruten.com.tw':'www.ruten.com.tw','ruten.com.tw':'www.ruten.com.tw',
    'm.tw.carousell.com':'tw.carousell.com','m.tw.bid.yahoo.com':'tw.bid.yahoo.com'})[value] || value;
  const host = canonicalHost(location.hostname);
  const platform = ({'www.ruten.com.tw':'露天','tw.carousell.com':'旋轉拍賣','tw.bid.yahoo.com':'Y拍'})[host];
  if (!platform) return JSON.stringify([]);
  window.__shopRadarCaptureStarted ||= Date.now();
  const results = new Map();
  const pageUrl = new URL(location.href);
  const yahooStore = host === 'tw.bid.yahoo.com' && /^\/booth\//.test(location.pathname);
  // The booth's outer article contains every listing, plus the homepage has
  // separate promotional/top-ten sections. Only the official all-products area
  // can establish a newest-first subscription boundary.
  const yahooStoreCards = yahooStore ? document.querySelector('[class*="boothListings__"], [data-testid="booth-listings"]') : null;
  const cardRoot = yahooStore ? yahooStoreCards : document;
  const categoryId = pageUrl.searchParams.get('cateid') || pageUrl.searchParams.get('cid') || location.pathname.match(/\/category\/(\d+)/)?.[1] || location.pathname.match(/-(\d+)-category\.html/)?.[1] || '';
  const adultCategory = host === 'www.ruten.com.tw' ? /^0025/.test(categoryId) || categoryId === '00020052'
    : host === 'tw.bid.yahoo.com' && ['41179373','41179374','41179375','41179376','41179378','41179379','41179383','53686009'].includes(categoryId);
  function productUrl(value) {
    try {
      const u = new URL(value, location.href);
      if (u.protocol !== 'https:' || canonicalHost(u.hostname) !== host) return null;
      u.hostname = host;
      let valid = host === 'tw.carousell.com' ? /^\/p\/[^/]+\d+\/?$/.test(u.pathname)
        : host === 'tw.bid.yahoo.com' ? /^\/item\/\d+/.test(u.pathname)
        : /^\/item\/(?:show)?\/?/.test(u.pathname) && /\d{10,}/.test(u.href);
      if (!valid) return null;
      if (host !== 'www.ruten.com.tw' || !u.pathname.includes('show')) u.search = '';
      u.hash = ''; return u.href;
    } catch (_) { return null; }
  }
  function amount(s) {
    const m = String(s).replace(/,/g,'').match(/(?:NT\$|NTD|\$)?\s*(\d+(?:\.\d+)?)/);
    return m ? Number(m[1]) : NaN;
  }
  // Dates are deliberately taken only from marketplace-provided markup/text.
  // A missing date is not replaced with "now" because that would falsely mark
  // an old listing as new in both search and subscriptions.
  function listingTime(card, text) {
    const dated = card.querySelector('time[datetime], [data-listed-at], [data-listing-time], [data-post-time], [data-created-at]');
    const attr = dated && (dated.getAttribute('datetime') || dated.getAttribute('data-listed-at') ||
      dated.getAttribute('data-listing-time') || dated.getAttribute('data-post-time') || dated.getAttribute('data-created-at'));
    if (attr && String(attr).trim()) return String(attr).trim();
    const absolute = String(text).match(/(?:20\d{2}[./-]\d{1,2}[./-]\d{1,2})(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?/);
    if (absolute) return absolute[0];
    const relative = String(text).match(/(?:不到\s*1\s*分鐘前|未滿\s*1\s*分鐘前|less\s+than\s+(?:a|1)\s+minute\s+ago|\d+\s*(?:分鐘|小時|天|週|個月|分|時)前|昨天|今天|剛剛|just\s+now|\d+\s*(?:min(?:ute)?s?|h(?:ou)?rs?|d(?:ays?)?)\s*ago|\d+\s*[mhd]\s*ago)/i);
    return relative ? relative[0] : '';
  }
  function pageShopName(card) {
    if (host === 'tw.carousell.com' && /^\/(?:u\/)?[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}\/?$/.test(location.pathname) &&
        !/^\/(?:search|categories|login|signup|sell)\/?$/.test(location.pathname)) {
      // Profile CSS classes are generated; og:title contains the account handle,
      // not the displayed shop name. Mobile/desktop may render duplicate h1s.
      const names = [...document.querySelectorAll('h1')].map(node =>
        String(node.textContent || '').replace(/\s+/g, ' ').trim()).filter(Boolean);
      const unique = [...new Set(names)];
      return unique.length === 1 ? unique[0].slice(0, 120) : '';
    }
    const selector = '[data-testid*="profile-name"], [data-testid*="seller-name"], [class*="seller"] h1, [class*="profile"] h1';
    const isStore = /^\/(?:store|booth|u)\//.test(location.pathname);
    const node = card?.querySelector(selector) || (isStore ? document.querySelector(selector) : null);
    const storeId = location.pathname.match(/^\/(?:store|booth|u)\/([^/]+)/)?.[1] || '';
    const value = (node?.textContent || (yahooStore && storeId === 'search' ? pageUrl.searchParams.get('seller') : storeId) || '').trim();
    if (/^(?:優惠推薦|有\s*[\d,]+\+?\s*(?:件|個|的)|搜尋結果)/.test(value)) return '';
    return value.replace(/^全部商品\s*[-－]\s*/, '').replace(/\s*[|｜-]\s*(?:露天市集|旋轉拍賣|Carousell|蝦皮|Yahoo拍賣).*$/i, '')
      // Yahoo Auction may append this account badge to the public shop title;
      // it is not part of the seller's chosen shop name.
      .replace(/(?:\s*[-｜|]?\s*實名認證\s*)+$/g, '').trim().slice(0, 120);
  }
  function isAvatar(image) {
    const source = `${image?.currentSrc || image?.src || ''} ${image?.alt || ''} ${image?.className || ''}`.toLowerCase();
    return /avatar|profile|user[-_ ]?image|seller/.test(source);
  }
  function thumbnail(image) {
    if (!image) return '';
    const candidates = ['data-srcset', 'srcset'].flatMap(key => String(image.getAttribute(key) || '').split(',').map(part => {
      const match = part.trim().match(/^(https:\/\/\S+)(?:\s+(\d+(?:\.\d+)?)[wx])?$/);
      return match ? {url:match[1],size:Number(match[2] || 1)} : null;
    }).filter(Boolean)).sort((a,b) => a.size - b.size);
    return candidates[0]?.url || image.getAttribute('data-src') || image.getAttribute('data-lazy-src') || image.src || image.currentSrc || '';
  }
  function productLinks(card, url) {
    return [...card.querySelectorAll('a[href]')].filter(a => productUrl(a.href) === url);
  }
  function meaningfulTitle(value) {
    const title = String(value || '').replace(/\s+/g, ' ').trim();
    if (title.length < 2 || /^(?:NT\$|NTD|\$|已售|銷售|\d+\s*(?:分鐘|小時|天|週|個月)前|頭像|avatar|profile)/i.test(title)) return '';
    if (/(?:^|\s)(?:賣家|seller|查看賣家|view profile)(?:\s|$)/i.test(title)) return '';
    return title;
  }
  // On Carousell, the listing link's accessible text occasionally concatenates
  // the title, its displayed price, and the condition without separators.
  // Price has its own field in the app, so remove it from the title and keep the
  // remaining condition as a second line (for example: "iPhone 13\n幾乎全新").
  function carousellDisplayTitle(value) {
    const title = String(value || '').replace(/\s+/g, ' ').trim();
    const price = /(?:NT\$|NTD|\$)\s*[\d,]+(?:\.\d+)?/i.exec(title);
    if (!price) return title;
    const before = title.slice(0, price.index).trim();
    const after = title.slice(price.index + price[0].length).trim();
    if (!before) return after;
    return after ? `${before}\n${after}` : before;
  }
  function yahooPlaceholderTitle(value) {
    return /^(?:Y\d{5,}|全部商品|所有商品|商品列表|全部|拍賣商品|近全新|全新|二手|實名認證)$/i.test(String(value || '').replace(/\s+/g, ' ').trim());
  }
  // Yahoo's search page contains category/filter links which can look like a
  // listing card. The public product detail page supplies the authoritative
  // title, price and image, so use it to replace those ambiguous card fields.
  function yahooDetail(html, expectedUrl) {
    try {
      const raw = html.match(/<script\b[^>]*\bid=["']isoredux-data["'][^>]*>([\s\S]*?)<\/script>/i)?.[1];
      const root = raw && JSON.parse(raw);
      const item = root?.item;
      if (!item) return {};
      if (String(item.id) !== new URL(expectedUrl).pathname.match(/\/item\/(\d+)/)?.[1]) return {};
      const title = meaningfulTitle(item.title);
      const bid = item.bidInfo || {};
      const price = [bid.currentPrice, item.price, item.buyPrice, item.salePrice]
        .filter(value => value != null && value !== '').map(Number).find(Number.isFinite);
      const image = item.images?.[0]?.sm?.src || item.images?.[0]?.md?.src || item.images?.[0]?.src || '';
      const isAuction = Number(item.type) === 2;
      const end = Number(bid.endTime);
      const server = Number(root.page?.serverTs);
      const bidders = (Array.isArray(bid.highestBidders) ? bid.highestBidders : [])
        .map(value => typeof value.ecid === 'string' ? value.ecid.trim() : '').filter(Boolean).join('、');
      return { title, price, image, start: item.startTime || bid.startTime, isAuction,
        auctionEndsAt: isAuction && Number.isFinite(end) && end > 0 ? (end < 100000000000 ? end * 1000 : end) : 0,
        auctionServerAt: isAuction && Number.isFinite(server) && server > 0 ? (server < 100000000000 ? server * 1000 : server) : 0,
        auctionBidCount: Number.isInteger(bid.totalBids) && bid.totalBids >= 0 ? bid.totalBids : -1,
        highestBidder: bidders || (bid.totalBids === 0 ? '尚無人出價' : ''),
        auctionCancelled: bid.isCancelled === true,
        auctionPriceKnown: isAuction && bid.currentPrice != null && bid.currentPrice !== '' && Number.isFinite(Number(bid.currentPrice)) };
    } catch (_) { return {}; }
  }
  // Carousell's category/search pages append another batch behind this button.
  // Do not click it during the first read: the app exposes a deliberate
  // "顯示更多結果" control beneath the retrieved items, so a large feed is
  // never fetched all at once.  A fresh WebView marks a manual continuation
  // with __shopRadarLoadMore and performs exactly one click.
  if (host === 'tw.carousell.com' && window.__shopRadarLoadMore && !window.__shopRadarInfiniteSearch) {
    const more = [...document.querySelectorAll('button, [role="button"]')]
      .find(node => /顯示更多(?:結果|商品)?|載入更多|show more(?: results)?|load more/i.test(node.textContent || ''));
    const target = Math.max(1, Number(window.__shopRadarMoreTarget || 1));
    const clicks = Number(window.__shopRadarMoreClicks || 0);
    const currentIds = new Set([...document.querySelectorAll('a[href]')].map(a => productUrl(a.href)).filter(Boolean));
    const pending = window.__shopRadarMorePending;
    if (pending && [...currentIds].some(id => !pending.ids.includes(id))) {
      window.__shopRadarMoreClicks = clicks + 1;
      window.__shopRadarMorePending = null;
    } else if (pending && Date.now() - pending.at > 4500) {
      // A click without new cards is retried, never counted as a completed batch.
      window.__shopRadarMorePending = null;
    }
    window.__shopRadarMoreDone = Number(window.__shopRadarMoreClicks || 0) >= target;
    if (more && !window.__shopRadarMoreDone && !window.__shopRadarMorePending && !more.disabled && more.getAttribute('aria-disabled') !== 'true') {
      more.scrollIntoView({block:'center'});
      window.__shopRadarMorePending = {ids:[...currentIds],at:Date.now()};
      more.click();
    }
  }
  for (const link of cardRoot?.querySelectorAll('a[href]') || []) {
    const url = productUrl(link.href);
    if (!url || results.has(url)) continue;
    let card = link.closest('.rt-goods-list-item, [data-testid="listing-card"], article') || link;
    if (new Set([...card.querySelectorAll('a[href]')].map(a => productUrl(a.href)).filter(Boolean)).size > 1) card = link;
    // Expand only to a card with a single product, never an entire result grid.
    for (let n=0; n<(host === 'tw.bid.yahoo.com' ? 8 : 4) && card.parentElement; n++) {
      const cardText = card.innerText || '';
      const hasPrice = /(?:NT\$|NTD|\$)\s*[\d,]+/.test(cardText) || card.querySelector('[class*="price"], [class*="Price"], [itemprop="price"]');
      // Carousell frequently places "58 分鐘前" beside, rather than inside, the
      // clickable listing link.  Expand its single-listing wrapper before deciding
      // the card is complete, otherwise the timestamp is lost.
      const needsCarousellTime = host === 'tw.carousell.com' && !listingTime(card, cardText);
      if (card.querySelector('img') && hasPrice && !needsCarousellTime) break;
      const parent = card.parentElement;
      if (['BODY','HTML','MAIN'].includes(parent.tagName)) break;
      const urls = new Set([...parent.querySelectorAll('a[href]')].map(a=>productUrl(a.href)).filter(Boolean));
      if (urls.size !== 1 || (parent.innerText||'').length > 2500) break;
      card = parent;
    }
    if (!card.getClientRects().length) continue;
    const links = productLinks(card, url);
    // A Carousell card can contain the seller avatar before the product picture.
    // Prefer an image inside a link to this exact listing, never a profile image.
    const images = [...links.flatMap(a => [...a.querySelectorAll('img')]), ...card.querySelectorAll('img')];
    const image = images.find(img => !isAvatar(img)) || images.find(img => img.currentSrc || img.src);
    const text = (card.innerText||'').trim();
    const priceEl = card.querySelector('[data-testid*="price"], [class*="price"], [class*="Price"], [itemprop="price"]');
    const marked = text.match(/(?:NT\$|NTD|\$)\s*([\d,]+(?:\.\d+)?)/);
    const price = marked ? amount(marked[1]) : priceEl ? amount(priceEl.getAttribute('content')||priceEl.textContent) : NaN;
    if (!Number.isFinite(price) || price < 0) continue;
    const titleSelector = '[data-testid*="listing-title"], [data-testid*="listing_title"], [data-testid*="item-title"], .product-card-name-text, [class*="prod_name"], [class*="item-name"], h3, h2';
    const carousellTitle = host === 'tw.carousell.com' ? [
      ...links.flatMap(a => [...a.querySelectorAll(titleSelector)].map(n => n.textContent)),
      ...[...card.querySelectorAll(titleSelector)].map(n => n.textContent),
      ...links.map(a => a.getAttribute('data-testid')?.includes('listing') ? a.textContent : ''),
      ...links.map(a => a.getAttribute('title')),
      ...links.map(a => a.textContent)
    ].map(meaningfulTitle).find(Boolean) : '';
    const titleEl = links.flatMap(a => [...a.querySelectorAll(titleSelector)])[0] || card.querySelector(titleSelector);
    const yahooTitle = host === 'tw.bid.yahoo.com' ? [
      ...links.flatMap(a => [...a.querySelectorAll(titleSelector)].map(n => n.textContent)),
      ...links.map(a => a.getAttribute('title')),
      ...images.filter(img => !isAvatar(img)).map(img => img.alt),
      ...links.map(a => a.getAttribute('aria-label')),
      ...text.split('\n')
    ].map(meaningfulTitle).find(value => value && !yahooPlaceholderTitle(value)) : '';
    const rawTitle = (yahooTitle || carousellTitle || links.map(a => a.getAttribute('aria-label') || a.getAttribute('title')).map(meaningfulTitle).find(Boolean) ||
      meaningfulTitle(titleEl?.textContent) || meaningfulTitle(link.getAttribute('title')) ||
      text.split('\n').map(meaningfulTitle).find(Boolean) || meaningfulTitle(!isAvatar(image) ? image?.alt : '') || '').trim();
    const title = host === 'tw.carousell.com' ? carousellDisplayTitle(rawTitle)
      : host === 'tw.bid.yahoo.com' && (!rawTitle || yahooPlaceholderTitle(rawTitle)) ? '商品名稱待確認' : rawTitle;
    if (title.length < 2) continue;
    const listedAt = listingTime(card, text);
    results.set(url,{platform,title:title.slice(0,240),price:Math.round(price),url,
      image:thumbnail(image), listedAt,shop:pageShopName(card),isAdvertisement:false,isAdult:adultCategory,
      isAuction: /(?:競標|競拍|出價\s*\d+|\d+\s*次出價)/.test(text)});
    // The first Carousell read is intentionally small. A manual continuation
    // may retrieve one additional batch while still avoiding an entire feed.
    const moreTarget = Math.max(1, Number(window.__shopRadarMoreTarget || 1));
    const limit = window.__shopRadarInfiniteSearch || host === 'tw.carousell.com' ? Infinity : 240;
    if (results.size >= limit) break;
  }
  const items = [...results.values()];
  if (host === 'tw.carousell.com') {
    const minutes = value => {
      const s = String(value || '');
      if (/(?:不到|未滿)\s*1\s*分鐘前|less\s+than\s+(?:a|1)\s+minute\s+ago|剛剛|just\s+now/i.test(s)) return 0;
      const m = s.match(/(\d+)\s*(分鐘|分|小時|時|天|週|個月)前/);
      if (!m) return Number.POSITIVE_INFINITY;
      const n = Number(m[1]);
      return n * ({'分鐘':1,'分':1,'小時':60,'時':60,'天':1440,'週':10080,'個月':43200}[m[2]] || 1);
    };
    const dated = items.filter(item => Number.isFinite(minutes(item.listedAt))).sort((a,b) => minutes(a.listedAt) - minutes(b.listedAt));
    // A lone listing separated from its neighbours by more than a day is usually
    // a promoted/advertising insertion in a recent-first feed. Keep it visible,
    // flag it, and place it after the chronological listings.
    dated.forEach((item, index) => {
      const before = index ? minutes(item.listedAt) - minutes(dated[index - 1].listedAt) : 0;
      const after = index + 1 < dated.length ? minutes(dated[index + 1].listedAt) - minutes(item.listedAt) : 0;
      item.isAdvertisement = before > 1440 && after > 1440;
    });
    items.sort((a, b) => Number(a.isAdvertisement) - Number(b.isAdvertisement) || minutes(a.listedAt) - minutes(b.listedAt));
  }
  if (host === 'tw.bid.yahoo.com' && typeof window.ShopRadarYahoo !== 'undefined') {
    if (window.__shopRadarInfiniteSearch) {
      // Same mobile document across batches. Enrich only newly seen public cards,
      // never call the one-shot bridge that used to end/reload the search page.
      const cache = window.__shopRadarFeedDetails ||= new Map();
      const pending = window.__shopRadarFeedPending ||= new Set();
      const firstBatchReady = items.length >= 15;
      for (const item of items) {
        const saved = cache.get(item.url);
        if (saved) Object.assign(item, saved);
        // Do not compete with hydration/lazy-load requests for optional dates.
        // Ambiguous titles still get one repair slot before the first batch;
        // afterwards details are bounded to two concurrent public HTML reads.
        const repair = !meaningfulTitle(item.title) || item.title === '商品名稱待確認';
        // Optional listing dates are filled by the native visible-card reader;
        // never request a full detail page for every ordinary search result here.
        const needsDetail = repair || item.isAuction && !item.auctionPriceKnown;
        if (!saved && needsDetail && (firstBatchReady || repair) &&
            pending.size < (firstBatchReady ? 2 : 1) && !pending.has(item.url)) {
          pending.add(item.url);
          const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 4000);
          fetch(item.url, {credentials:'same-origin', signal:abort.signal})
            .then(r => r.ok ? r.text() : '').then(html => {
              const detail = yahooDetail(html, item.url), patch = {};
              for (const key of ['title','price','image','isAuction','auctionEndsAt','auctionServerAt','auctionBidCount','highestBidder','auctionCancelled','auctionPriceKnown'])
                if (detail[key] != null && detail[key] !== '') patch[key] = detail[key];
              if (detail.start) { const n = Number(detail.start); if (Number.isFinite(n) && n > 0) patch.listedAtEpochMillis = n < 100000000000 ? n * 1000 : n; }
              cache.set(item.url, patch);
            }).catch(() => cache.set(item.url, {})).finally(() => { clearTimeout(timer); pending.delete(item.url); });
        }
      }
      return JSON.stringify(items);
    }
    // Accumulate lazily rendered cards across polls before starting detail work.
    // Fifteen is a minimum target, never a cap on the products already found.
    const collection = window.__shopRadarYahooCollection ||= {items: new Map(), stable: 0, polls: 0};
    const previous = collection.items.size;
    const auctionOnly = new URL(location.href).searchParams.get('refine') === 'bidding';
    items.forEach(item => {
      if (auctionOnly) item.isAuction = true;
      if (!meaningfulTitle(item.title) || yahooPlaceholderTitle(item.title)) item.title = '商品名稱待確認';
      if (!collection.items.has(item.url)) {
        collection.items.set(item.url, item);
        if (window.__shopRadarYahooReading) {
          window.__shopRadarYahooActive?.push(item);
          window.__shopRadarYahooPending?.push(item);
        }
      }
    });
    window.ShopRadarYahoo.preview?.(JSON.stringify([...collection.items.values()]));
    collection.stable = previous === collection.items.size ? collection.stable + 1 : 0;
    collection.polls++;
    if (!collection.items.size) {
      // An unmounted/lazy GraphQL list is loading, not an empty shop. Do not
      // seal the native bridge after two polls or replace the saved baseline.
      // Only the list's explicit website empty-state can complete an empty read;
      // network/auth failures are handled by the bounded native reader.
      const empty = yahooStoreCards && /(?:此)?店家還沒有上架商品[唷喔]/.test(yahooStoreCards.innerText || '');
      if (empty && !window.__shopRadarYahooReading) {
        window.__shopRadarYahooReading = true;
        window.ShopRadarYahoo.deliver('[]');
      } else if (yahooStoreCards) yahooStoreCards.scrollIntoView?.({block:'center'});
      else window.scrollBy(0, Math.max(240, window.innerHeight * 0.7));
      return JSON.stringify([]);
    }
    // Visit the rows in order; jumping to the footer can skip lazy card mounts.
    window.scrollBy(0, Math.max(240, window.innerHeight * 0.7));
    if (window.__shopRadarYahooReading) return JSON.stringify([]);
    if (!window.__shopRadarQuickSearch && !window.__shopRadarScanSubscription && collection.polls < 20 && (collection.polls < 4 || collection.stable < (collection.items.size < 15 ? 8 : 3))) return JSON.stringify([]);
    if (window.__shopRadarScanSubscription && collection.polls < 2) return JSON.stringify([]);
    if (window.__shopRadarQuickSearch && collection.polls < 2) return JSON.stringify([]);
    items.splice(0, items.length, ...collection.items.values());
    window.__shopRadarYahooReading = true;
    window.__shopRadarYahooActive = items;
    (async () => {
      const deadline = window.__shopRadarQuickSearch ?
        Math.max(window.__shopRadarCaptureStarted + (items.length < 15 ? 18500 : 4500), Date.now() + 2500) : Date.now() + 35000;
      const pending = window.__shopRadarYahooPending = [...items];
      const enrich = async item => {
        const abort = new AbortController();
        const timeout = setTimeout(() => abort.abort(), Math.max(1, Math.min(5000, deadline - Date.now())));
        try {
          const page = await fetch(item.url, { credentials: 'same-origin', signal: abort.signal }).then(r => r.ok ? r.text() : '');
          const detail = yahooDetail(page, item.url);
          if (detail.title) item.title = detail.title;
          if (Number.isFinite(detail.price)) item.price = Math.round(detail.price);
          if (detail.image) item.image = detail.image;
          if (typeof detail.isAuction === 'boolean') {
            item.isAuction = detail.isAuction;
            item.auctionEndsAt = detail.auctionEndsAt;
            item.auctionServerAt = detail.auctionServerAt;
            item.auctionBidCount = detail.auctionBidCount;
            item.highestBidder = detail.highestBidder;
            item.auctionPriceKnown = detail.auctionPriceKnown;
            item.auctionCancelled = detail.auctionCancelled;
          }
          const start = String(detail.start || '');
          if (/^\d{10,13}$/.test(start)) {
             const millis = Number(start) * (start.length === 10 ? 1000 : 1);
             item.listedAtEpochMillis = millis;
            const d = new Date(millis);
            const pad = n => String(n).padStart(2, '0');
            item.listedAt = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
          }
          return item;
        } catch (_) { return item; }
        finally { clearTimeout(timeout); window.ShopRadarYahoo.preview?.(JSON.stringify(items)); }
      };
      // Bound both concurrency and total enrichment time. The card itself is
      // retained if a detail page is slow; missing dates remain explicitly blank.
      const boundary = item => window.__shopRadarScanSubscription && (
        (window.__shopRadarSubscriptionSeen || []).includes(item.url) ||
        window.__shopRadarSubscriptionCheckpoint > 0 && item.listedAtEpochMillis > 0 && item.listedAtEpochMillis <= window.__shopRadarSubscriptionCheckpoint);
      // Check the first real listing before scheduling any other detail fetch.
      if (window.__shopRadarScanSubscription && pending.length) {
        const first = pending.shift();
        await enrich(first);
        if (boundary(first)) { window.ShopRadarYahoo.deliver(JSON.stringify([first])); return; }
      }
      let reachedBoundary = false;
      await Promise.all(Array.from({length: Math.min(4, pending.length)}, async () => {
        while (Date.now() < deadline && !reachedBoundary) {
          if (pending.length) { const item = pending.shift(); await enrich(item); if (boundary(item)) reachedBoundary = true; }
          else if (!window.__shopRadarQuickSearch || collection.stable >= (collection.items.size < 15 ? 8 : 2)) break;
          else await new Promise(resolve => setTimeout(resolve, 100));
        }
      }));
      const dated = items.map(item => {
        if (!meaningfulTitle(item.title) || yahooPlaceholderTitle(item.title)) item.title = '商品名稱待確認';
        return item;
      });
      // Respect the submitted ordering after enrichment; unknown dates stay last.
      // Never read timestamps from recommendations elsewhere in the detail HTML.
      const sort = new URL(location.href).searchParams.get('sort');
      const stamp = item => Date.parse(String(item.listedAt || '').replace(' ', 'T') + '+08:00');
      if (!window.__shopRadarScanSubscription && (sort === 'curp' || sort === '-curp')) dated.sort((a,b) => (a.price-b.price) * (sort === 'curp' ? 1 : -1));
      if (!window.__shopRadarScanSubscription && (sort === 'ptime' || sort === '-ptime')) dated.sort((a,b) => {
        const x = stamp(a), y = stamp(b);
        if (!Number.isFinite(x)) return Number.isFinite(y) ? 1 : 0;
        if (!Number.isFinite(y)) return -1;
        return (x-y) * (sort === '-ptime' ? -1 : 1);
      });
      if (!window.__shopRadarScanSubscription && sort === 'etime') dated.sort((a,b) => (a.auctionEndsAt || Infinity) - (b.auctionEndsAt || Infinity));
      window.ShopRadarYahoo.deliver(JSON.stringify(dated));
    })();
    return JSON.stringify([]);
  }
  return JSON.stringify(items);
})()

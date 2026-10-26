// Read the requested listing header, never dates from seller activity or recommendations.
(() => {
  const helpers = window.__shopRadarFacebookMetadata;
  const expected = window.__shopRadarFacebookListing;
  if (!helpers || !expected) return JSON.stringify({state:'invalid'});
  const {itemUrl,emptyTime,cardTime,shown} = helpers;
  const url = itemUrl(location.href);
  const target = itemUrl(expected.url);
  if (!url || url !== target) return JSON.stringify({state:'unmatched'});
  const canonical = document.querySelector('link[rel="canonical"]')?.href;
  if (canonical && itemUrl(canonical) !== target) return JSON.stringify({state:'unmatched'});
  const published = window.__shopRadarPublicListingTime?.structured(target);
  if (published?.listedAt) return JSON.stringify({state:'ready',url:target,...published});
  const normalize = value => String(value || '').replace(/\s+/g,' ').trim();
  const title = normalize(expected.title);
  const headings = [...document.querySelectorAll('h1,[role="heading"][aria-level="1"]')]
    .filter(node => {
      const actual = normalize(node.textContent);
      const prefix = title.replace(/(?:\.\.\.|…)$/, '').trim();
      // Subscription refresh has only a verified item URL, not necessarily a title.
      // In that case require one nonempty primary heading on this same item page.
      return actual && (!title || actual === title || prefix.length >= 20 && actual.startsWith(prefix)) &&
        node.getAttribute('aria-hidden') !== 'true' && shown(node);
    });
  if (headings.length !== 1) return JSON.stringify({state:'loading'});
  let scope = headings[0].parentElement;
  for (let i=0; i<4 && scope && scope !== document.body; i++,scope=scope.parentElement) {
    if (scope.matches('main') || scope.querySelector('h2,section,article,aside') ||
        (scope.textContent || '').length > 4000 || scope.querySelectorAll('h1').length > 1) break;
    if ([...scope.querySelectorAll('a[href]')].some(a => itemUrl(a.href) && itemUrl(a.href) !== target)) break;
    // Facebook exposes "<abbr aria-label='6天前'>…</abbr>於地區上架"
    // next to the primary title/price. Requiring the listing label excludes
    // description text such as "昨天買的" and seller "last active" dates.
    for (const node of scope.querySelectorAll('span,div,p,time,abbr,[data-utime]')) {
      const text = normalize(node.innerText);
      if (node.contains(headings[0]) || headings[0].contains(node)) continue;
      if (text.length > 160 || !/(?:上架|刊登|發布|发布)$|^(?:上架|刊登|發布|发布)|^(?:listed|posted|published)\b/i.test(text)) continue;
      if (node.closest('a[href*="/marketplace/item/"]')) continue;
      if (!shown(node)) continue;
      const date = cardTime(node.matches('time,abbr,[data-utime]') ? node.parentElement : node);
      if (date.listedAt) return JSON.stringify({state:'ready',url:target,...date});
      const direct = helpers.visibleTime(text);
      if (direct.listedAt) return JSON.stringify({state:'ready',url:target,...direct});
    }
    // A title-only wrapper may need one more parent. Once header content is
    // present, never widen to the surrounding seller/description/page panels.
    // Price-only and nested title wrappers are common. Keep climbing within
    // the small single-listing header, but never enter seller/recommendation panels.
    if (scope.querySelector('h2,[role="heading"][aria-level="2"],section,article')) break;
  }
  return JSON.stringify({state:'loading',url:target,...emptyTime()});
})()

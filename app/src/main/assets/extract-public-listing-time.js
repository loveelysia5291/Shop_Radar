(() => {
  const metadata = window.__shopRadarPublicListingTime;
  const expected = window.__shopRadarFacebookListing;
  if (!metadata || !expected) return JSON.stringify({state:'invalid'});
  const target = metadata.identity(expected.url);
  if (!target || metadata.identity(location.href) !== target) return JSON.stringify({state:'unmatched'});
  const declarations = [...document.querySelectorAll('link[rel="canonical"],meta[property="og:url"]')]
    .map(n => n.getAttribute('href') || n.getAttribute('content')).filter(Boolean);
  if (declarations.some(url => metadata.identity(url) !== target)) return JSON.stringify({state:'unmatched'});
  const result = date => JSON.stringify({state:date.listedAt ? 'ready':'loading',url:expected.url,...date});
  const structured = metadata.structured(expected.url);
  if (structured.listedAt) return result(structured);
  const headings = [...document.querySelectorAll('h1,[role="heading"][aria-level="1"],[itemprop="name"]')]
    .filter(n => window.__shopRadarFacebookMetadata.shown(n) && !n.closest('aside,footer,nav'));
  if (headings.length !== 1) return result(window.__shopRadarFacebookMetadata.emptyTime());
  let scope = headings[0].parentElement;
  for (let depth=0; scope && depth<5 && !scope.matches('main,body,html'); depth++,scope=scope.parentElement) {
    if ((scope.textContent || '').length > 5000 || scope.querySelector('h2,section,article,aside')) break;
    const other = [...scope.querySelectorAll('a[href]')].some(a => {
      const id = metadata.identity(a.href); return id && id !== target;
    });
    if (other) break;
    const date = metadata.forProduct(expected.url,scope);
    if (date.listedAt) return result(date);
  }
  return result(window.__shopRadarFacebookMetadata.emptyTime());
})()

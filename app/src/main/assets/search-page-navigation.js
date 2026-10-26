// Read the site's real numbered-page controls. Never invent an iOPEN query key.
(() => {
  const wanted = Number(window.__shopRadarWantedPage || 1);
  const current = new URL(location.href);
  const controls = [...document.querySelectorAll('.pagination,.pager,.pages,[class*="pagination"],[aria-label*="分頁"],[aria-label*="Pagination"],select[name*="page" i],select[id*="page" i]')];
  const links = [...document.querySelectorAll('a[href]')].filter(node => {
    if (!/^\d+$/.test((node.textContent || '').trim())) return false;
    try {
      const next = new URL(node.href, current);
      return next.protocol === 'https:' && next.origin === current.origin && next.pathname === current.pathname &&
        next.searchParams.get('action') === current.searchParams.get('action') &&
        next.searchParams.get('prod_keyword') === current.searchParams.get('prod_keyword') && next.search !== current.search;
    } catch (_) { return false; }
  });
  const nodes = [...new Set([...links, ...controls.flatMap(root => [...root.querySelectorAll('a,button,[role="button"],option')])])];
  const number = node => Number((node.textContent || '').trim().match(/^(?:第\s*)?(\d+)(?:\s*頁)?$/)?.[1]);
  const pages = nodes.map(number).filter(n => Number.isInteger(n) && n > 0 && n <= 500);
  for (const root of controls) {
    const total = (root.textContent || '').match(/(?:共|總共|of)\s*(\d+)\s*(?:頁|pages?)?/i);
    if (total && Number(total[1]) <= 500) pages.push(Number(total[1]));
    for (const node of root.querySelectorAll('a[href]')) {
      if (!/末頁|最後|last/i.test((node.textContent || '') + ' ' + (node.getAttribute('aria-label') || ''))) continue;
      try {
        const last = new URL(node.href, current);
        if (last.origin !== current.origin || last.pathname.toLowerCase() !== current.pathname.toLowerCase()) continue;
        for (const [key, value] of last.searchParams) {
          if (/^(page|pageno|pageindex|pg|p)$/i.test(key) && /^\d+$/.test(value) && Number(value) <= 500)
            pages.push(Number(value));
        }
      } catch (_) { }
    }
  }
  const totalPages = pages.length ? Math.max(...pages) : document.readyState === 'complete' ? 1 : 0;
  const active = controls.flatMap(root => [...root.querySelectorAll('[aria-current="page"],.active,.current,.selected')])
    .some(node => number(node) === wanted);
  if (wanted === 1 || active) return JSON.stringify({ready:true,pages:totalPages});
  const candidate = nodes.find(node => number(node) === wanted);
  if (!candidate) return JSON.stringify({ready:false,pages:totalPages});
  if (links.includes(candidate)) return JSON.stringify({ready:false,pages:totalPages,next:candidate.href});
  // Some mobile pagers expose a real button instead of a link. Read its selected
  // state on later polls before allowing any product from this document through.
  if (window.__shopRadarClickedPage !== wanted && !candidate.disabled && candidate.getAttribute('aria-disabled') !== 'true') {
    window.__shopRadarClickedPage = wanted;
    if (candidate.tagName === 'OPTION' && candidate.parentElement?.tagName === 'SELECT') {
      candidate.parentElement.value = candidate.value;
      candidate.parentElement.dispatchEvent(new Event('change', {bubbles:true}));
    } else candidate.click();
  }
  return JSON.stringify({ready:false,pages:totalPages});
})()

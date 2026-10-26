// Observe public, rendered scroll containers. Never navigate or click a load-more control.
(() => {
  const root = document.scrollingElement || document.documentElement;
  const visible = node => !!node && [...node.getClientRects()].some(r => r.width > 0 && r.height > 0) &&
    !node.closest('[hidden],[aria-hidden="true"]') && getComputedStyle(node).visibility !== 'hidden';
  const links = [...document.querySelectorAll('a[href]')].filter(node =>
    /(?:\/p\/|\/item\/|\/product\/\d+\/\d+|-i\.\d+\.\d+)/.test(node.getAttribute('href') || '') && visible(node));
  let scroller = root;
  // Mobile SPAs can scroll an inner list instead of document.body.
  for (const link of links.slice(-8)) {
    for (let node = link.parentElement; node && node !== root; node = node.parentElement) {
      if (node.clientHeight > 160 && node.scrollHeight > node.clientHeight + 32 &&
          /auto|scroll/.test(getComputedStyle(node).overflowY)) { scroller = node; break; }
    }
    if (scroller !== root) break;
  }
  const height = scroller === root ? window.innerHeight : scroller.clientHeight;
  const atBottom = scroller.scrollTop + height >= scroller.scrollHeight - 32;
  const busy = [...document.querySelectorAll('[aria-busy="true"],[role="progressbar"],.shopee-search-item-result__skeleton,[data-testid="loading-skeleton"]')].some(visible);
  window.__shopRadarFeedScroll = () => {
    // First Shopee/Yahoo batch visits overlapping viewports faster, never skips a whole viewport.
    const delta = Math.max(240, height * (window.__shopRadarFeedFast ? 0.9 : 0.7));
    if (scroller === root) window.scrollBy(0, delta);
    else scroller.scrollTop += delta;
  };
  return JSON.stringify({ready: document.readyState === 'complete', atBottom, busy,
    cursor: `${Math.round(scroller.scrollTop)}:${scroller.scrollHeight}:${links.length}`});
})()

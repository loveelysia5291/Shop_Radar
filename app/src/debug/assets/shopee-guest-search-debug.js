// Debug-only presentation experiment for products already supplied publicly.
// No cookies, credentials, API interception, history/location overrides or verification bypass.
(() => {
  if (location.protocol !== 'https:' || !/^(?:www\.|m\.)?shopee\.tw$/.test(location.hostname) ||
      location.pathname.replace(/\/$/, '') !== '/search' || location.port) return;
  const productLink = a => {
    try {
      const u = new URL(a.getAttribute('href'), location.href);
      return u.protocol === 'https:' && /^(?:www\.|m\.)?shopee\.tw$/.test(u.hostname) &&
        !u.username && !u.password && !u.port &&
        (/-i\.\d+\.\d+$/.test(u.pathname) || /^\/product\/\d+\/\d+\/?$/.test(u.pathname)) &&
        a.querySelector('img') && (a.textContent || '').trim().length > 3;
    } catch (_) { return false; }
  };
  // Do not dismiss a verification challenge, language chooser or an actual sign-in form.
  const dismiss = () => {
    if (![...document.querySelectorAll('a[href]')].some(productLink)) return;
    for (const dialog of document.querySelectorAll('[role="dialog"], [aria-modal="true"]')) {
      if (dialog.hidden || dialog.querySelector('input, form, iframe, [role="checkbox"]')) continue;
      const text = (dialog.textContent || '').trim();
      if (text.length > 500 || !/登入|登錄|log\s*in|sign\s*in/i.test(text) ||
          /驗證|验证|captcha|verify|verification|語言|语言|language/i.test(text)) continue;
      dialog.hidden = true;
      dialog.style.setProperty('display', 'none', 'important');
      if (![...document.querySelectorAll('[role="dialog"], [aria-modal="true"]')].some(d =>
        !d.hidden && getComputedStyle(d).display !== 'none')) {
        for (const node of [document.documentElement, document.body]) {
          if (node && node.style.overflow === 'hidden') node.style.removeProperty('overflow');
        }
      }
    }
  };
  // Called alongside the existing bounded extraction polling; no extra timers or observers.
  dismiss();
})();

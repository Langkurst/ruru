// Runs only on the English home page "/", from <head>, before anything is drawn.
// Sends a visitor to their language's page when their browser asks for a language the site has,
// or to the language they chose in the menu before. The English page is the only page that loads
// this file, so a language page can never redirect and nothing can loop. The built languages are
// the <link rel="alternate" hreflang> tags above this script.
(function () {
  "use strict";
  var KEY = "ruru-lang";

  // Chinese: only the Simplified page exists, so Traditional (Hant, TW, HK, MO) stays on English.
  function traditional(tag) { return /(^|-)(hant|tw|hk|mo)(-|$)/.test(tag); }

  function find(pages, tag) {
    tag = tag.toLowerCase();
    if (pages[tag]) return pages[tag];
    var primary = tag.split("-")[0];
    if (primary === "zh" && traditional(tag)) return "";
    for (var code in pages) if (code.split("-")[0] === primary) return pages[code];
    return "";
  }

  try {
    if (navigator.webdriver || window.location.pathname !== "/") return;
    var pages = {};
    var links = document.querySelectorAll('link[rel="alternate"][hreflang]');
    for (var i = 0; i < links.length; i++) {
      var code = links[i].getAttribute("hreflang").toLowerCase();
      if (code === "en" || code === "x-default") continue;
      pages[code] = new URL(links[i].href, window.location.href).pathname;
    }

    var saved = "";
    try { saved = window.localStorage.getItem(KEY) || ""; } catch (e) { saved = ""; }

    var target = "";
    if (saved) {
      target = pages[saved.toLowerCase()] || "";
    } else {
      var wanted = navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || ""];
      for (var j = 0; j < wanted.length && !target; j++) {
        if (!wanted[j]) continue;
        if (wanted[j].toLowerCase().split("-")[0] === "en") break; // English comes first: stay
        target = find(pages, wanted[j]);
      }
    }
    if (target && target !== window.location.pathname) {
      window.location.replace(target + window.location.search + window.location.hash);
    }
  } catch (e) { /* stay on this page */ }
})();

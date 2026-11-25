(function () {
  "use strict";

  var API_URL = "https://api.github.com/repos/Langkurst/ruru/releases/latest";
  var REPO_URL = "https://api.github.com/repos/Langkurst/ruru";
  var CACHE_KEY = "ruru-latest-release";
  var CACHE_MS = 10 * 60 * 1000;

  var SUFFIXES = {
    msi: ".msi",
    exe: ".exe",
    dmg: "-macos-arm64.dmg",
    deb: "_amd64.deb",
    rpm: ".x86_64.rpm",
    apk: ".apk",
    ipa: ".ipa"
  };

  // Which file the big button gets on each system. Its words are in the page's strings.
  var HERO = {
    windows: { asset: "msi" },
    mac: { asset: "dmg" },
    android: { asset: "apk" },
    ios: {},
    linux: {}
  };

  // The words come from the page: build.mjs writes them as JSON in <script id="strings">.
  var S = {};
  try { S = JSON.parse(document.getElementById("strings").textContent) || {}; } catch (e) { S = {}; }
  var LOCALE = S.locale || "en-NZ";
  var LANG_KEY = "ruru-lang";

  function fill(text, values) {
    return String(text).replace(/\{(\w+)\}/g, function (m, name) { return name in values ? values[name] : m; });
  }

  function detectSystem() {
    var nav = window.navigator;
    var platform = "";
    try { platform = (nav.userAgentData && nav.userAgentData.platform) || ""; } catch (e) { platform = ""; }
    var text = (platform + " " + (nav.userAgent || "")).toLowerCase();
    if (/iphone|ipad|ipod/.test(text)) return "ios";
    if (/macintosh|macos/.test(text) && nav.maxTouchPoints > 1) return "ios";
    if (/android/.test(text)) return "android";
    if (/windows/.test(text)) return "windows";
    if (/macintosh|macos|mac os/.test(text)) return "mac";
    if (/linux|cros|x11/.test(text)) return "linux";
    return "";
  }

  function readCache(key) {
    try {
      var item = JSON.parse(window.sessionStorage.getItem(key));
      if (item && typeof item.t === "number" && Date.now() - item.t < CACHE_MS) return item.d;
    } catch (e) { /* storage unavailable */ }
    return null;
  }

  function writeCache(key, data) {
    try { window.sessionStorage.setItem(key, JSON.stringify({ t: Date.now(), d: data })); } catch (e) { /* storage unavailable */ }
  }

  function getJson(key, url) {
    var cached = readCache(key);
    if (cached) return Promise.resolve(cached);
    return window.fetch(url, { headers: { Accept: "application/vnd.github+json" } })
      .then(function (res) {
        if (!res.ok) throw new Error("status " + res.status);
        return res.json();
      })
      .then(function (data) { writeCache(key, data); return data; });
  }

  function loadRelease() { return getJson(CACHE_KEY, API_URL); }

  // The star count stays hidden until GitHub has answered.
  function showStars() {
    getJson("ruru-repo", REPO_URL).then(function (repo) {
      var el = document.getElementById("stars");
      // No count until there is at least one star: a 0 next to the button puts people off.
      if (!el || typeof repo.stargazers_count !== "number" || repo.stargazers_count < 1) return;
      el.textContent = new Intl.NumberFormat(LOCALE).format(repo.stargazers_count);
      el.hidden = false;
    }).catch(function () { /* the button works without the count */ });
  }

  var VERSION_PATTERN = /^\d+\.\d+\.\d+$/;

  // Returns 1 if a is newer than b, -1 if older, 0 if equal. Both are "1.2.3" strings.
  function compareVersions(a, b) {
    var x = a.split(".");
    var y = b.split(".");
    for (var i = 0; i < 3; i++) {
      var d = parseInt(x[i], 10) - parseInt(y[i], 10);
      if (d !== 0) return d > 0 ? 1 : -1;
    }
    return 0;
  }

  // The app's "Check for updates" button opens this page with ?have=<its version>.
  function readHaveVersion() {
    try {
      var have = new URLSearchParams(window.location.search).get("have");
      return have && VERSION_PATTERN.test(have) ? have : "";
    } catch (e) { return ""; }
  }

  function showUpdateNotice(have, newest) {
    var panel = document.querySelector("#download .panel");
    if (!panel || !have || !VERSION_PATTERN.test(newest) || !S["update.newer"] || !S["update.same"]) return;
    var note = document.createElement("p");
    note.className = "notice";
    note.setAttribute("role", "status");
    panel.parentNode.insertBefore(note, panel);
    // Fill it after it is in the page, so screen readers announce the text.
    window.setTimeout(function () {
      note.textContent = compareVersions(newest, have) > 0
        ? fill(S["update.newer"], { have: have, newest: newest })
        : S["update.same"];
    }, 100);
  }

  function formatDate(iso) {
    var d = new Date(iso);
    if (isNaN(d.getTime())) return "";
    return new Intl.DateTimeFormat(LOCALE, { day: "numeric", month: "long", year: "numeric", timeZone: "Pacific/Auckland" }).format(d);
  }

  function findAsset(assets, suffix) {
    for (var i = 0; i < assets.length; i++) {
      var name = assets[i] && assets[i].name;
      if (typeof name === "string" && name.toLowerCase().slice(-suffix.length) === suffix) return assets[i];
    }
    return null;
  }

  function hideSizes() {
    var sizes = document.querySelectorAll(".size");
    for (var i = 0; i < sizes.length; i++) sizes[i].hidden = true;
  }

  function markSystem(system) {
    if (!system) return;
    var row = document.querySelector('.row[data-os="' + system + '"]');
    var label = row && row.querySelector(".you");
    if (label) label.hidden = false;
  }

  function apply(release, system) {
    var assets = Array.isArray(release.assets) ? release.assets : [];
    var urls = {};

    var links = document.querySelectorAll(".file[data-asset]");
    for (var i = 0; i < links.length; i++) {
      var link = links[i];
      var key = link.getAttribute("data-asset");
      var asset = SUFFIXES[key] ? findAsset(assets, SUFFIXES[key]) : null;
      var size = link.querySelector(".size");
      if (asset && asset.browser_download_url) {
        link.href = asset.browser_download_url;
        urls[key] = asset.browser_download_url;
        if (size && typeof asset.size === "number") {
          size.textContent = fill(S.size || "{n} MB", { n: new Intl.NumberFormat(LOCALE, { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(asset.size / 1e6) });
          size.classList.add("ready");
          size.removeAttribute("aria-hidden");
        } else if (size) {
          size.hidden = true;
        }
      } else if (size) {
        size.hidden = true;
      }
    }

    var version = document.getElementById("version");
    var tag = typeof release.tag_name === "string" ? release.tag_name.replace(/^v/i, "") : "";
    var date = release.published_at ? formatDate(release.published_at) : "";
    if (version && tag && S.version && S.versionNoDate) {
      version.textContent = date ? fill(S.version, { version: tag, date: date }) : fill(S.versionNoDate, { version: tag });
    }

    showUpdateNotice(readHaveVersion(), tag);

    var hero = document.getElementById("hero-button");
    var choice = HERO[system];
    if (hero && choice && S["hero." + system]) {
      hero.textContent = S["hero." + system];
      if (choice.asset && urls[choice.asset]) hero.href = urls[choice.asset];
      else hero.setAttribute("href", "#download");
    }
  }

  // The language menu: go to that language's page and remember the choice (build.mjs writes the options).
  function initLanguageMenu() {
    var menu = document.getElementById("lang");
    if (!menu) return;
    menu.addEventListener("change", function () {
      var option = menu.options[menu.selectedIndex];
      if (!option) return;
      try { window.localStorage.setItem(LANG_KEY, option.lang); } catch (e) { /* storage unavailable */ }
      window.location.href = option.value + window.location.search + window.location.hash;
    });
  }

  function init() {
    initLanguageMenu();
    var system = detectSystem();
    markSystem(system);
    showStars();
    loadRelease().then(function (release) {
      apply(release, system);
    }).catch(function () {
      hideSizes();
    });
  }

  if (typeof document !== "undefined") {
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
    else init();
  }
  if (typeof module !== "undefined" && module.exports) module.exports = { compareVersions: compareVersions };
})();

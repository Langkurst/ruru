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

  var HERO = {
    windows: { text: "Download for Windows", asset: "msi" },
    mac: { text: "Download for Mac", asset: "dmg" },
    android: { text: "Download for Android", asset: "apk" },
    ios: { text: "iPhone download" },
    linux: { text: "Download for Linux" }
  };

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
      if (!el || typeof repo.stargazers_count !== "number") return;
      el.textContent = new Intl.NumberFormat("en-NZ").format(repo.stargazers_count);
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
    if (!panel || !have || !VERSION_PATTERN.test(newest)) return;
    var note = document.createElement("p");
    note.className = "notice";
    note.setAttribute("role", "status");
    panel.parentNode.insertBefore(note, panel);
    // Fill it after it is in the page, so screen readers announce the text.
    window.setTimeout(function () {
      note.textContent = compareVersions(newest, have) > 0
        ? "You have version " + have + ". The newest is " + newest + "."
        : "You have the newest version.";
    }, 100);
  }

  function formatDate(iso) {
    var d = new Date(iso);
    if (isNaN(d.getTime())) return "";
    return new Intl.DateTimeFormat("en-NZ", { day: "numeric", month: "long", year: "numeric", timeZone: "Pacific/Auckland" }).format(d);
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
          size.textContent = (asset.size / 1e6).toFixed(1) + " MB";
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
    if (version && tag) {
      version.textContent = "Version " + tag + (date ? ", released " + date : "");
    }

    showUpdateNotice(readHaveVersion(), tag);

    var hero = document.getElementById("hero-button");
    var choice = HERO[system];
    if (hero && choice) {
      hero.textContent = choice.text;
      if (choice.asset && urls[choice.asset]) hero.href = urls[choice.asset];
      else hero.setAttribute("href", "#download");
    }
  }

  function init() {
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

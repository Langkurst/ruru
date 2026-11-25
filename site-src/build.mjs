// Builds the website pages from site-src/template.html and the language files in site-src/i18n/.
// Plain Node 20+, no packages.
//
//   node site-src/build.mjs                  writes site/index.html (English) and site/<lang>/index.html
//   node site-src/build.mjs --check          writes nothing; exits 1 if site/ is not what the build gives
//   node site-src/build.mjs --all            also builds languages marked "_status": "pending" (for tests)
//   node site-src/build.mjs --out <dir>      writes into <dir> instead of site/
//   node site-src/build.mjs --i18n <dir>     reads the language files from <dir>
//
// A language file is flat JSON: "key": "text". Keys starting with "_" are settings:
//   _name       the language's own name, shown in the menu (required)
//   _htmlLang   the lang attribute of its page (default: the file name)
//   _status     "pending" keeps the language out of the build until it is removed
// Keys starting with "js." are also written into the page as <script type="application/json" id="strings">
// for app.js and demo.js (without the "js." prefix). A text may hold {name} values and one <a>...</a> link.
//
// Template slots: {{key}}, {{key|name=value|a=https://...}} and the page-wide {{@base}}, {{@htmlLang}},
// {{@head}}, {{@strings}}, {{@langmenu}}. Parameter values are trusted HTML written in the template.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const flag = (n) => args.includes(n);
const opt = (n, d) => { const i = args.indexOf(n); return i >= 0 ? path.resolve(args[i + 1]) : d; };
const OUT = opt('--out', path.join(here, '..', 'site'));
const I18N = opt('--i18n', path.join(here, 'i18n'));
const SITE = 'https://ruru-app.pages.dev';
const ORDER = ['en', 'de', 'fr', 'es', 'it', 'nl', 'pt-BR', 'ja', 'zh-CN', 'ko', 'pl'];

const fail = (m) => { console.error('build: ' + m); process.exit(1); };
const esc = (t) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const lf = (t) => t.replace(/\r\n/g, '\n');
const read = (p) => lf(fs.readFileSync(p, 'utf8'));

// ---- languages
const files = fs.readdirSync(I18N).filter((f) => f.endsWith('.json')).map((f) => f.slice(0, -5));
if (!files.includes('en')) fail('i18n/en.json is missing');
const known = (c) => (ORDER.includes(c) ? ORDER.indexOf(c) : 100);
files.sort((a, b) => known(a) - known(b) || a.localeCompare(b));
const data = {};
for (const c of files) {
  try { data[c] = JSON.parse(read(path.join(I18N, c + '.json'))); } catch (e) { fail(`${c}.json: ${e.message}`); }
}
const pending = files.filter((c) => data[c]._status === 'pending' && !flag('--all'));
const built = files.filter((c) => !pending.includes(c));
const en = data.en;
const textKeys = (o) => Object.keys(o).filter((k) => !k.startsWith('_'));
const holes = (t) => (t.match(/\{[A-Za-z]+\}/g) || []).sort().join(',');
const anchors = (t) => (t.match(/<\/?a>/g) || []).join('');

// Every built language must have exactly the English keys, the same {values} and the same links.
for (const c of built) {
  const d = data[c];
  if (typeof d._name !== 'string' || !d._name) fail(`${c}.json has no "_name"`);
  if (c === 'en') continue;
  const missing = textKeys(en).filter((k) => typeof d[k] !== 'string');
  const extra = textKeys(d).filter((k) => typeof en[k] !== 'string');
  if (missing.length || extra.length) fail(`${c}.json: missing ${missing.join(', ') || 'none'}; unknown ${extra.join(', ') || 'none'}`);
  for (const k of textKeys(en)) {
    if (!d[k].trim()) fail(`${c}.json: "${k}" is empty`);
    if (holes(d[k]) !== holes(en[k])) fail(`${c}.json: "${k}" has values ${holes(d[k]) || 'none'}, English has ${holes(en[k]) || 'none'}`);
    if (anchors(d[k]) !== anchors(en[k])) fail(`${c}.json: "${k}" must keep the <a>...</a> link of the English text`);
  }
}

// ---- text slots
function renderText(S, key, params) {
  const value = S[key];
  if (typeof value !== 'string') fail(`template uses "${key}", which the language file does not have`);
  const hasLink = anchors(value) === '<a></a>';
  if (hasLink !== ('a' in params)) fail(`"${key}": the template and the text disagree about a link`);
  let out = value.split(/(<\/?a>)/).map((part) => {
    if (part === '<a>') return `<a href="${esc(params.a)}">`;
    if (part === '</a>') return '</a>';
    return esc(part);
  }).join('');
  out = out.replace(/\{([A-Za-z]+)\}/g, (m, n) => { if (!(n in params)) fail(`"${key}": no value for {${n}}`); return params[n]; });
  return out;
}

function renderPage(code, template, used) {
  const S = data[code];
  const root = code === 'en';
  const base = root ? '' : '/';
  const urlOf = (c) => (c === 'en' ? '/' : `/${c}/`);
  const self = urlOf(code);
  const others = built.filter((c) => c !== 'en');

  const head = [
    `<link rel="canonical" href="${SITE}${self}">`,
    ...built.map((c) => `<link rel="alternate" hreflang="${c}" href="${SITE}${urlOf(c)}">`),
    `<link rel="alternate" hreflang="x-default" href="${SITE}/">`,
    ...(root && others.length ? ['<script src="lang-redirect.js"></script>'] : []),
  ].join('\n');

  const strings = {};
  for (const k of textKeys(S).filter((k) => k.startsWith('js.'))) { strings[k.slice(3)] = S[k]; used.add(k); }
  const stringsJson = JSON.stringify(strings, null, 1).replace(/</g, String.raw`\u003c`);

  const langmenu = [
    '    <div class="lang">',
    '      <svg class="globe" width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><g fill="none" stroke="currentColor" stroke-width="1.25"><circle cx="8" cy="8" r="6.5"/><ellipse cx="8" cy="8" rx="2.8" ry="6.5"/><path d="M1.5 8h13"/></g></svg>',
    `      <select id="lang" aria-label="${renderText(S, 'lang.label', {})}">`,
    ...built.map((c) => `        <option value="${urlOf(c)}" lang="${c}"${c === code ? ' selected' : ''}>${esc(data[c]._name)}</option>`),
    '      </select>',
    '    </div>',
  ].join('\n');
  used.add('lang.label');

  const special = {
    base,
    htmlLang: esc(S._htmlLang || code),
    head,
    strings: stringsJson,
    langmenu,
  };
  return template.replace(/\{\{([^{}]*)\}\}/g, (m, inner) => {
    const parts = inner.trim().split('|');
    const name = parts[0].trim();
    if (name.startsWith('@')) {
      const v = special[name.slice(1)];
      if (v === undefined) fail(`unknown slot ${m}`);
      return v;
    }
    const params = {};
    for (const p of parts.slice(1)) { const i = p.indexOf('='); params[p.slice(0, i)] = p.slice(i + 1); }
    used.add(name);
    return renderText(S, name, params);
  });
}

// ---- build
const template = read(path.join(here, 'template.html'));
const pages = new Map();
let used = new Set();
for (const c of built) {
  used = new Set();
  pages.set(c === 'en' ? 'index.html' : `${c}/index.html`, renderPage(c, template, used));
  const unused = textKeys(data[c]).filter((k) => !used.has(k));
  if (unused.length) fail(`${c}.json has keys the template never uses: ${unused.join(', ')}`);
}

// Pages of languages that are pending (or gone) must not stay in the site.
const stale = [];
for (const c of files.filter((f) => f !== 'en')) {
  const rel = `${c}/index.html`;
  if (!pages.has(rel) && fs.existsSync(path.join(OUT, rel))) stale.push(rel);
}

if (flag('--check')) {
  const bad = [];
  for (const [rel, html] of pages) {
    const p = path.join(OUT, rel);
    if (!fs.existsSync(p)) bad.push(`${rel} is missing`);
    else if (read(p) !== html) bad.push(`${rel} is out of date`);
  }
  for (const rel of stale) bad.push(`${rel} should not exist`);
  if (bad.length) { console.error('site/ is not what the build gives:\n  ' + bad.join('\n  ') + '\nRun: node site-src/build.mjs'); process.exit(1); }
  console.log(`site/ is up to date (${built.join(', ')})`);
} else {
  for (const [rel, html] of pages) {
    const p = path.join(OUT, rel);
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, html);
  }
  for (const rel of stale) {
    fs.rmSync(path.join(OUT, rel));
    try { fs.rmdirSync(path.dirname(path.join(OUT, rel))); } catch { /* not empty: leave it */ }
  }
  console.log(`built ${[...pages].map(([r, h]) => `${r} (${Buffer.byteLength(h)} bytes)`).join(', ')}`);
  if (pending.length) console.log(`skipped (pending): ${pending.join(', ')}`);
}

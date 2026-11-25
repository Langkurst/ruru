// Checks that need no browser: node site-src/test.mjs
//  - site/ is what the build gives
//  - site/lang-redirect.js sends the right visitors to the right page, and nobody else
//  - compareVersions in site/app.js (the app's "Check for updates" link depends on it)
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const site = path.join(here, '..', 'site');
let failures = 0;
const check = (name, ok, detail = '') => { if (!ok) failures++; console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${ok ? '' : ' ' + detail}`); };

try {
  execFileSync(process.execPath, [path.join(here, 'build.mjs'), '--check'], { stdio: 'pipe' });
  check('site/ is what the build gives', true);
} catch (e) { check('site/ is what the build gives', false, String(e.stderr || e)); }

const code = fs.readFileSync(path.join(site, 'lang-redirect.js'), 'utf8');
const BUILT = ['de', 'fr', 'es', 'it', 'nl', 'pt-BR', 'ja', 'zh-CN', 'ko', 'pl'];

// Runs the script as the browser would; returns where it sent the visitor (or '' if it stayed).
function run({ languages, saved, path: p = '/', webdriver = false, built = BUILT, search = '', hash = '' }) {
  let to = '';
  const links = built.map((c) => ({ getAttribute: (a) => (a === 'hreflang' ? c : 'rel'), href: `https://ruru-app.pages.dev/${c}/` }));
  links.unshift({ getAttribute: () => 'en', href: 'https://ruru-app.pages.dev/' }, { getAttribute: () => 'x-default', href: 'https://ruru-app.pages.dev/' });
  const window = {
    location: { pathname: p, search, hash, href: 'https://ruru-app.pages.dev' + p, replace: (u) => { to = u; } },
    localStorage: { getItem: () => (saved === undefined ? null : saved) },
  };
  const context = {
    window, URL,
    navigator: { languages, language: languages && languages[0], webdriver },
    document: { querySelectorAll: () => links },
  };
  vm.runInNewContext(code, context);
  return to;
}

const cases = [
  ['German browser goes to /de/', { languages: ['de-DE', 'en'] }, '/de/'],
  ['Brazilian Portuguese goes to /pt-BR/', { languages: ['pt-BR'] }, '/pt-BR/'],
  ['European Portuguese goes to /pt-BR/', { languages: ['pt-PT'] }, '/pt-BR/'],
  ['Simplified Chinese goes to /zh-CN/', { languages: ['zh-Hans-CN'] }, '/zh-CN/'],
  ['plain zh goes to /zh-CN/', { languages: ['zh'] }, '/zh-CN/'],
  ['Traditional Chinese stays', { languages: ['zh-TW', 'en'] }, ''],
  ['Hong Kong Chinese stays', { languages: ['zh-HK'] }, ''],
  ['English first stays, even if German is second', { languages: ['en-NZ', 'de'] }, ''],
  ['an unknown language then German goes to /de/', { languages: ['sv', 'de'] }, '/de/'],
  ['an unknown language stays', { languages: ['sv'] }, ''],
  ['Japanese keeps the search and the link target', { languages: ['ja'], search: '?have=0.1.1', hash: '#download' }, '/ja/?have=0.1.1#download'],
  ['a saved language wins over the browser language', { languages: ['de'], saved: 'pl' }, '/pl/'],
  ['a saved English choice stays', { languages: ['de'], saved: 'en' }, ''],
  ['a saved language that is not built stays', { languages: ['de'], saved: 'xx' }, ''],
  ['a robot (webdriver) stays', { languages: ['de'], webdriver: true }, ''],
  ['a language page never redirects', { languages: ['de'], path: '/de/' }, ''],
  ['another page never redirects', { languages: ['de'], path: '/code-signing.html' }, ''],
  ['a language that is not built stays', { languages: ['de'], built: ['fr'] }, ''],
];
for (const [name, input, want] of cases) {
  let got;
  try { got = run(input); } catch (e) { got = 'threw ' + e.message; }
  check(name, got === want, `wanted "${want}", got "${got}"`);
}

const require = createRequire(import.meta.url);
const { compareVersions } = require(path.join(site, 'app.js'));
check('compareVersions orders versions', compareVersions('0.1.10', '0.1.9') === 1 && compareVersions('0.1.2', '0.1.2') === 0 && compareVersions('0.0.9', '0.1.0') === -1);

// ---- features list, desktop screenshot and the scroll reveal
const pages = ['index.html', ...BUILT.map((c) => `${c}/index.html`)];
for (const rel of pages) {
  const html = fs.readFileSync(path.join(site, rel), 'utf8');
  const ticks = (html.match(/<svg class="ck"[^>]*>(?:(?!<\/svg>).)*<path [^>]*pathLength="1"\/><\/svg>/g) || []).length;
  check(`${rel}: 2 feature panels with 14 ticks`, (html.match(/class="feat sr"/g) || []).length === 2 && ticks === 14, `ticks ${ticks}`);
  check(`${rel}: screenshot has width, height, alt and a light source`, /<img [^>]*width="1100" height="720"[^>]*loading="lazy"[^>]*alt="[^"]+"/.test(html) && /media="\(prefers-color-scheme: light\)"/.test(html));
  check(`${rel}: nothing the CSP would block`, !/<script(?![^>]*\ssrc=)[^>]*>(?!\s*\{)/.test(html.replace(/<script id="strings"[\s\S]*?<\/script>/, '')) && !/\sstyle="/.test(html) && !/\son[a-z]+="/.test(html));
}
const css = fs.readFileSync(path.join(site, 'styles.css'), 'utf8');
const hides = css.match(/[^{}]*\.sr[^{}]*\{[^}]*opacity:\s*0[^}]*\}/g) || [];
check('the reveal hides things only under html.js and no-preference motion', hides.length > 0 && hides.every((r) => /html\.js /.test(r)) && /@media \(prefers-reduced-motion: no-preference\) \{[^@]*html\.js \.sr \{ opacity: 0/.test(css));

const revealCode = fs.readFileSync(path.join(site, 'reveal.js'), 'utf8');
function reveal({ reduced = false, observer = true, search = '' }) {
  const classes = new Set();
  const window = {
    location: { search },
    matchMedia: () => ({ matches: reduced }),
    innerHeight: 800,
    addEventListener() {},
    IntersectionObserver: observer ? function () { this.observe = () => {}; this.unobserve = () => {}; } : undefined,
  };
  const document = { readyState: 'complete', documentElement: { classList: { add: (c) => classes.add(c) } }, querySelectorAll: () => [], addEventListener() {} };
  vm.runInNewContext(revealCode, { window, document });
  return classes.has('js');
}
check('reveal.js hides content when motion is allowed', reveal({}) === true);
check('reveal.js leaves everything visible with reduced motion', reveal({ reduced: true }) === false);
check('reveal.js leaves everything visible without IntersectionObserver', reveal({ observer: false }) === false);
check('reveal.js leaves everything visible with ?static', reveal({ search: '?static' }) === false);

// Copy rules that need no judgement: no em or en dashes, no exclamation marks, in any language file.
for (const f of fs.readdirSync(path.join(here, 'i18n')).filter((n) => n.endsWith('.json'))) {
  const data = JSON.parse(fs.readFileSync(path.join(here, 'i18n', f), 'utf8'));
  const bad = Object.entries(data).filter(([k, v]) => !k.startsWith('_') && /[–—!！]/.test(v)).map(([k]) => k);
  check(`${f}: no dashes or exclamation marks in the text`, bad.length === 0, bad.join(', '));
}

if (failures) { console.error(`${failures} failed`); process.exit(1); }
console.log('all passed');

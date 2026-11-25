(function () {
  "use strict";

  // Pointer x to a slider value. left and width describe the rail the thumb's centre travels.
  function valueAt(x, left, width, min, max) {
    var f = width > 0 ? (x - left) / width : 0;
    return min + Math.round(Math.min(1, Math.max(0, f)) * (max - min));
  }
  if (typeof document === "undefined") { module.exports = { valueAt: valueAt }; return; }

  var d = document, win = window;
  var reduce = win.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var $ = function (s, r) { return (r || d).querySelector(s); };
  var $$ = function (s, r) { return [].slice.call((r || d).querySelectorAll(s)); };

  // The words come from the page: build.mjs writes them as JSON in <script id="strings">.
  var S = {};
  try { S = JSON.parse(d.getElementById("strings").textContent) || {}; } catch (e) { S = {}; }

  // will-change only while an element moves: set now, cleared ms later.
  function hold(el, ms) {
    el.style.willChange = "transform";
    win.clearTimeout(el._w);
    el._w = win.setTimeout(function () { el.style.willChange = ""; }, ms);
  }

  var demo = $("#demo");
  if (!demo) return;
  var modesEl = $(".modes", demo), mb = $$(".mode", demo), nz = $("#nz"), lockName = $("#ln");
  var sw = $(".sw", demo), segEl = $(".seg", demo), segs = $$(".sg button", demo), tap = $(".tap", demo);
  var sls = $$(".sl", demo), noise = sls[0], eqs = sls.slice(1);
  var reset = $("#d-reset", demo);
  // p marks the built-in modes; the names are the ones written in the page, so they follow its language.
  var M = [
    { p: 1, v: [10, 0, 0] }, { p: 1, v: [3, 0, 0] }, { p: 1, v: [6, 0, 0] },
    { v: [7, 0, 0] }, { v: [10, 0, 0] }
  ];
  M.forEach(function (m, i) { m.n = $(".lb .a", mb[i]).textContent; });
  var cur = 0;

  // Each label gets a second copy in ink-on-yellow; selecting a mode cross-fades them (opacity only).
  $$(".lb", demo).forEach(function (l) {
    var b = d.createElement("span");
    b.className = "b"; b.setAttribute("aria-hidden", "true"); b.textContent = l.textContent;
    l.appendChild(b);
  });

  function fmt(el, v) { return v < 0 ? "−" + -v : v > 0 && el._min < 0 ? "+" + v : "" + v; }

  function put(el, v, noText) {
    v = Math.max(el._min, Math.min(el._max, v));
    el._v = v; el._g = (el._g || 0) + 1;
    el.style.setProperty("--f", (v - el._min) / (el._max - el._min));
    el.setAttribute("aria-valuenow", v);
    el.setAttribute("aria-valuetext", fmt(el, v));
    if (!noText) el._o.textContent = fmt(el, v);
  }

  function move(el, ms) { $$(".th,.trk > *", el).forEach(function (n) { hold(n, ms); }); }

  // Slide to a value with the compositor; the number steps through the values the thumb passes.
  function glide(el, to) {
    var from = el._v;
    if (from === to) return;
    if (reduce) return put(el, to);
    el.style.setProperty("--t", "300ms");
    move(el, 450);
    put(el, to, 1);
    var n = Math.abs(to - from), g = el._g;
    for (var k = 1; k <= n; k++) (function (k) {
      win.setTimeout(function () { if (el._g === g) el._o.textContent = fmt(el, from + (to > from ? k : -k)); }, 300 * (1 - Math.pow(1 - (k - .5) / n, .2)));
    })(k);
  }

  function summary(i) {
    var v = M[i].v;
    if (M[i].p || !S.noiseN) return;
    var text = S.noiseN.replace("{n}", v[0]) + (v[1] ? S.join + S.windBlock : "") + (v[2] ? S.join + S.immersive : "");
    $$(".sm .a,.sm .b", mb[i]).forEach(function (n) { n.textContent = text; });
  }

  function paintSw(on) { sw.setAttribute("aria-checked", on ? "true" : "false"); }
  function paintSeg(i) {
    segEl.style.setProperty("--i", i);
    segs.forEach(function (b, j) { b.setAttribute("aria-checked", j === i ? "true" : "false"); b.tabIndex = j === i ? 0 : -1; });
  }
  function setSw(on) { paintSw(on); M[cur].v[1] = on ? 1 : 0; summary(cur); }
  function setSeg(i) { hold($(".sk", segEl), 450); paintSeg(i); M[cur].v[2] = i; summary(cur); }

  function pick(i) {
    var m = M[cur = i];
    modesEl.style.setProperty("--i", i);
    hold($(".mk", modesEl), 450);
    mb.forEach(function (b, j) { b.setAttribute("aria-checked", j === i ? "true" : "false"); b.tabIndex = j === i ? 0 : -1; });
    nz.classList.toggle("locked", !!m.p);
    lockName.textContent = m.n;
    noise.setAttribute("aria-disabled", m.p ? "true" : "false");
    noise.tabIndex = m.p ? -1 : 0;
    sw.disabled = !!m.p;
    segs.forEach(function (b) { b.disabled = !!m.p; });
    glide(noise, m.v[0]);
    paintSw(m.v[1]);
    hold($(".sk", segEl), 450);
    paintSeg(m.v[2]);
  }

  function flat() { eqs.forEach(function (s) { glide(s, 0); }); }

  sls.forEach(function (el) {
    el._min = +el.getAttribute("data-min"); el._max = +el.getAttribute("data-max");
    el._o = $("#" + el.getAttribute("data-o"));
    put(el, +el.getAttribute("aria-valuenow"));
    var rail = $(".rail", el), r, x, raf, drag;
    function edit() { if (el === noise) { M[cur].v[0] = el._v; summary(cur); } }
    function draw() {
      raf = 0;
      var v = valueAt(x, r.left, r.width, el._min, el._max);
      if (v !== el._v) { put(el, v); edit(); }
    }
    el.addEventListener("pointerdown", function (e) {
      if (e.button || el.getAttribute("aria-disabled") === "true") return;
      r = rail.getBoundingClientRect(); x = e.clientX; drag = 1;
      el.setPointerCapture(e.pointerId);
      el.style.setProperty("--t", "90ms");
      move(el, 6e5);
      draw();
    }, { passive: true });
    el.addEventListener("pointermove", function (e) {
      if (!drag) return;
      x = e.clientX;
      if (!raf) raf = win.requestAnimationFrame(draw);
    }, { passive: true });
    function up() { if (!drag) return; drag = 0; move(el, 250); el.style.setProperty("--t", "160ms"); }
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", up);
    el.addEventListener("keydown", function (e) {
      var k = e.key, v = el._v;
      if (el.getAttribute("aria-disabled") === "true") return;
      v = k === "ArrowRight" || k === "ArrowUp" ? v + 1 : k === "ArrowLeft" || k === "ArrowDown" ? v - 1 : k === "Home" ? el._min : k === "End" ? el._max : null;
      if (v === null) return;
      e.preventDefault();
      el.style.setProperty("--t", "160ms"); move(el, 300);
      put(el, v); edit();
    });
  });

  // Radio groups: arrow keys move the choice, as in the app.
  function arrows(group, items, choose) {
    group.addEventListener("keydown", function (e) {
      var k = e.key, i = items.indexOf(d.activeElement);
      var j = k === "ArrowDown" || k === "ArrowRight" ? i + 1 : k === "ArrowUp" || k === "ArrowLeft" ? i - 1 : -1;
      if (i < 0 || j < 0 || j >= items.length) return;
      e.preventDefault(); items[j].focus(); choose(j);
    });
  }
  mb.forEach(function (b, i) { b.addEventListener("click", function () { pick(i); }); });
  segs.forEach(function (b, i) { b.addEventListener("click", function () { setSeg(i); }); });
  sw.addEventListener("click", function () { setSw(sw.getAttribute("aria-checked") !== "true"); });
  reset.addEventListener("click", flat);
  arrows(modesEl, mb, pick);
  arrows(segEl, segs, setSeg);
  pick(0);

  // The tour: one soft fingertip travels between the controls. One rAF clock drives it.
  function bez(x1, y1, x2, y2) {
    return function (x) {
      var a = 0, b = 1, t = x, m = 1 - t;
      for (var i = 0; i < 18; i++) { t = (a + b) / 2; m = 1 - t; if (3 * m * m * t * x1 + 3 * m * t * t * x2 + t * t * t < x) a = t; else b = t; }
      return 3 * m * m * t * y1 + 3 * m * t * t * y2 + t * t * t;
    };
  }
  var ease = bez(.45, 0, .2, 1);
  var NUDGE = [0, 150, -100, 200, -150, 100, -200, 50]; // pause variation, fixed per step
  var noop = function () {};
  var steps = [
    { at: function () { return mb[1]; }, act: function () { pick(1); } },
    { at: function () { return mb[3]; }, act: function () { M[3].v = [7, 0, 0]; summary(3); pick(3); } },
    { el: noise, to: 4, ms: 1000, pause: 2500, done: function () { M[3].v[0] = 4; summary(3); } },
    { at: function () { return sw; }, act: function () { setSw(1); } },
    { at: function () { return segs[2]; }, act: function () { setSeg(2); } },
    { el: eqs[0], to: 3, ms: 1100, pause: 2500, done: noop },
    { at: function () { return reset; }, act: flat },
    { at: function () { return mb[0]; }, act: function () { pick(0); } }
  ];
  var si = 0, plan, fx, fy, hold0, shift = 0, pausedAt = 0, raf = 0, live = 0, vis, hov, foc, stopped = reduce;
  var rip = $(".r", tap);

  function centre(el) {
    var a = el.getBoundingClientRect(), b = demo.getBoundingClientRect();
    return [a.left + Math.min(a.width / 2, 56) - b.left - demo.clientLeft, a.top + a.height / 2 - b.top - demo.clientTop];
  }
  function begin(c) {
    var s = steps[si], p = plan = { s: s, t: c };
    var to = centre(s.el ? $(".th", s.el) : s.at());
    if (fx === undefined) { fx = to[0] + 70; fy = to[1] + 90; }
    var dx = to[0] - fx, dy = to[1] - fy, dist = Math.sqrt(dx * dx + dy * dy) || 1, bow = dist * .14 * (si % 2 ? 1 : -1);
    p.x0 = fx; p.y0 = fy; p.x1 = to[0]; p.y1 = to[1];
    p.cx = (fx + to[0]) / 2 - dy / dist * bow; p.cy = (fy + to[1]) / 2 + dx / dist * bow;
    p.d = 650 + Math.min(250, dist * .6);
    p.p = c + p.d + 120;
    if (s.el) {
      var rr = $(".rail", s.el).getBoundingClientRect(), b = demo.getBoundingClientRect(), span = s.el._max - s.el._min;
      p.rl = rr.left - b.left - demo.clientLeft; p.rw = rr.width;
      p.f0 = (s.el._v - s.el._min) / span; p.f1 = (s.to - s.el._min) / span;
      p.d0 = p.p + 200; p.d1 = p.d0 + s.ms; p.r = p.d1 + 120;
    } else p.r = p.p + 160;
    p.e = p.r + 600 + (s.pause || 3200) + NUDGE[si];
  }
  function dragTo(el, f) {
    el.style.setProperty("--f", f);
    var v = el._min + Math.round(f * (el._max - el._min));
    if (v !== el._v) { el._v = v; el.setAttribute("aria-valuenow", v); el.setAttribute("aria-valuetext", fmt(el, v)); el._o.textContent = fmt(el, v); }
  }
  function drop(p) { // the drag ends where it is; the value is kept
    var el = p.s.el;
    p.dropped = 1; put(el, el._v); el.style.setProperty("--t", "160ms"); move(el, 250);
    if (el === noise) { M[cur].v[0] = el._v; summary(cur); }
  }
  function frame(now) {
    raf = win.requestAnimationFrame(frame);
    var c = now - shift;
    if (!plan) {
      if (hold0 === undefined) hold0 = c + 450;
      if (c < hold0) return;
      begin(c);
    }
    var p = plan, s = p.s, x = p.x1, y = p.y1;
    if (c < p.p - 120) {
      var u = ease((c - p.t) / p.d), m = 1 - u;
      x = m * m * p.x0 + 2 * m * u * p.cx + u * u * p.x1;
      y = m * m * p.y0 + 2 * m * u * p.cy + u * u * p.y1;
    }
    if (!p.pressed && c >= p.p) {
      p.pressed = 1; tap.classList.add("pr");
      if (rip.animate) rip.animate([{ transform: "scale(1)", opacity: .35 }, { transform: "scale(1.8)", opacity: 0 }], { duration: 420, easing: "cubic-bezier(.23,1,.32,1)" });
      if (s.el) { s.el.style.setProperty("--t", "0ms"); move(s.el, 6e5); } else s.act();
    }
    if (s.el && p.pressed && !p.dropped && c >= p.d0) {
      var f = p.f0 + (p.f1 - p.f0) * ease(Math.min(1, (c - p.d0) / s.ms));
      dragTo(s.el, f); x = p.rl + f * p.rw;
      if (c >= p.d1) { drop(p); s.done(); }
    }
    if (p.pressed && !p.released && c >= p.r) { p.released = 1; tap.classList.remove("pr"); }
    fx = x; fy = y;
    tap.style.transform = "translate(" + (x - 15) + "px," + (y - 15) + "px)";
    if (c >= p.e) { plan = null; si = (si + 1) % steps.length; }
  }
  function sync() {
    var want = !stopped && vis && !hov && !foc && !d.hidden;
    if (want && !live) {
      live = 1;
      if (pausedAt) shift += win.performance.now() - pausedAt;
      pausedAt = 0; tap.style.willChange = "transform"; tap.classList.add("on");
      raf = win.requestAnimationFrame(frame);
    } else if (!want && live) {
      live = 0; pausedAt = win.performance.now();
      win.cancelAnimationFrame(raf); tap.classList.remove("on", "pr"); tap.style.willChange = "";
    }
  }
  function stop(e) {
    if (e.isTrusted === false || e.key === "Tab" || stopped) return;
    if (plan && plan.pressed && plan.s.el && !plan.dropped) drop(plan);
    stopped = 1; sync();
  }
  if (!reduce) {
    new win.IntersectionObserver(function (es) { vis = es[es.length - 1].isIntersecting; sync(); }, { threshold: 0.15 }).observe(demo);
    d.addEventListener("visibilitychange", sync);
    demo.addEventListener("pointerenter", function (e) { if (e.pointerType === "mouse") { hov = 1; sync(); } });
    demo.addEventListener("pointerleave", function (e) { if (e.pointerType === "mouse") { hov = 0; sync(); } });
    demo.addEventListener("focusin", function (e) { try { foc = e.target.matches(":focus-visible"); } catch (x) { foc = 0; } sync(); });
    demo.addEventListener("focusout", function () { foc = 0; sync(); });
    demo.addEventListener("pointerdown", stop, true);
    demo.addEventListener("keydown", stop, true);
  }
  if (typeof module !== "undefined" && module.exports) module.exports = { valueAt: valueAt };
})();

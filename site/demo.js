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
  d.documentElement.classList.add("js");

  // will-change only while an element moves: set now, cleared ms later.
  function hold(el, ms) {
    el.style.willChange = "transform";
    win.clearTimeout(el._w);
    el._w = win.setTimeout(function () { el.style.willChange = ""; }, ms);
  }

  // Sections rise and fade in once, as they first come into view.
  if (!reduce && win.IntersectionObserver) {
    var io = new win.IntersectionObserver(function (es) {
      es.forEach(function (e) {
        if (!e.isIntersecting) return;
        io.unobserve(e.target);
        e.target._rv.forEach(function (t, i) { t.classList.add("in"); hold(t, 700 + i * 40); });
      });
    }, { rootMargin: "0px 0px -8% 0px" });
    $$("main > section:not(.intro)").forEach(function (s) {
      var items = [];
      [].forEach.call(s.children, function (c) {
        var inner = c.classList.contains("panel") ? c.children : c.classList.contains("table-panel") ? c.querySelectorAll("tr") : [c];
        [].push.apply(items, inner);
      });
      items.forEach(function (t, i) { t.classList.add("rv"); t.style.setProperty("--i", i); });
      s._rv = items;
      io.observe(s);
    });
  }

  var demo = $("#demo");
  if (!demo) return;
  var modesEl = $(".modes", demo), mb = $$(".mode", demo), nz = $("#nz"), lockName = $("#ln");
  var sw = $(".sw", demo), segEl = $(".seg", demo), segs = $$(".sg button", demo), tap = $(".tap", demo);
  var sls = $$(".sl", demo), noise = sls[0], eqs = sls.slice(1);
  var reset = $("#d-reset", demo);
  var M = [
    { n: "Quiet", p: 1, v: [10, 0, 0] }, { n: "Aware", p: 1, v: [3, 0, 0] }, { n: "Immersion", p: 1, v: [6, 0, 0] },
    { n: "Commute", v: [7, 0, 0] }, { n: "Focus", v: [10, 0, 0] }
  ];
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
    if (M[i].p) return;
    $$(".sm .a,.sm .b", mb[i]).forEach(function (n) {
      n.textContent = "Noise " + v[0] + (v[1] ? ", Wind Block" : "") + (v[2] ? ", Immersive" : "");
    });
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

  // The tour: one step every 3.2 s, one clock. A tap marker appears at T, the control reacts at T + 120 ms.
  var steps = [
    [function () { return mb[1]; }, function () { pick(1); }],
    [function () { return mb[3]; }, function () { M[3].v = [7, 0, 0]; summary(3); pick(3); }],
    [function () { return $(".th", noise); }, function () { glide(noise, 4); M[3].v[0] = 4; summary(3); }],
    [function () { return sw; }, function () { setSw(1); }],
    [function () { return segs[2]; }, function () { setSeg(2); }],
    [function () { return $(".th", eqs[0]); }, function () { glide(eqs[0], 3); }],
    [function () { return reset; }, flat],
    [function () { return mb[0]; }, function () { pick(0); }]
  ];
  var si = 0, timer, stopped = reduce, waiting = 1, vis, hov, foc;

  function place(el) {
    var a = el.getBoundingClientRect(), b = demo.getBoundingClientRect();
    var x = a.left + Math.min(a.width / 2, 56) - b.left - demo.clientLeft - 14;
    var y = a.top + a.height / 2 - b.top - demo.clientTop - 14;
    tap.style.transform = "translate(" + x + "px," + y + "px)";
  }
  function run() {
    if (stopped) return;
    if (!vis || hov || foc || d.hidden) { waiting = 1; return; }
    var s = steps[si]; si = (si + 1) % steps.length;
    place(s[0]()); tap.classList.add("on");
    timer = win.setTimeout(function () {
      s[1]();
      timer = win.setTimeout(function () { tap.classList.remove("on"); timer = win.setTimeout(run, 2800); }, 280);
    }, 120);
  }
  function sync() {
    if (stopped || !waiting || !vis || hov || foc || d.hidden) return;
    waiting = 0; timer = win.setTimeout(run, 900);
  }
  function stop(e) {
    if (e.isTrusted === false || e.key === "Tab" || stopped) return;
    stopped = 1; win.clearTimeout(timer); tap.classList.remove("on");
  }
  if (!reduce) {
    new win.IntersectionObserver(function (es) { vis = es[es.length - 1].isIntersecting; sync(); }, { threshold: 0.15 }).observe(demo);
    d.addEventListener("visibilitychange", sync);
    demo.addEventListener("pointerenter", function (e) { if (e.pointerType === "mouse") { hov = 1; } });
    demo.addEventListener("pointerleave", function (e) { if (e.pointerType === "mouse") { hov = 0; sync(); } });
    demo.addEventListener("focusin", function (e) { try { foc = e.target.matches(":focus-visible"); } catch (x) { foc = 0; } });
    demo.addEventListener("focusout", function () { foc = 0; sync(); });
    demo.addEventListener("pointerdown", stop, true);
    demo.addEventListener("keydown", stop, true);
  }
  if (typeof module !== "undefined" && module.exports) module.exports = { valueAt: valueAt };
})();

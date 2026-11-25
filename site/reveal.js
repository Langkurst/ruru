// Scroll reveal. Sections and panels fade in and rise 12px once, when they first come into view.
// Nothing is hidden unless this script has run, motion is allowed and IntersectionObserver exists, so the
// page is complete without it. Add ?static to the address to switch it off.
(function () {
  "use strict";
  var win = window, d = document;
  if (!win.IntersectionObserver || !win.matchMedia || /[?&]static\b/.test(win.location.search)) return;
  if (win.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  d.documentElement.classList.add("js");

  function start() {
    var items = [].slice.call(d.querySelectorAll(".sr"));
    var show = function (el, instant) { el.classList.add("in"); if (instant) el.classList.add("now"); };
    var io = new win.IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (!e.isIntersecting) return;
        io.unobserve(e.target);
        show(e.target, false);
      });
    }, { threshold: 0.15 });
    var fold = win.innerHeight * 0.9;
    items.forEach(function (el) {
      // What is already in view when the page opens stays as it is: nothing moves on load.
      if (el.getBoundingClientRect().top < fold) show(el, true);
      else io.observe(el);
    });
    win.addEventListener("beforeprint", function () { items.forEach(function (el) { show(el, true); }); });
  }

  if (d.readyState === "loading") d.addEventListener("DOMContentLoaded", start);
  else start();
})();

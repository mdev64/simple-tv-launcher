(function () {
  "use strict";

  // Footer year
  var year = document.querySelector("[data-year]");
  if (year) year.textContent = new Date().getFullYear();

  // Screenshot gallery
  var gallery = document.querySelector("[data-gallery]");
  var lightbox = document.querySelector("[data-lightbox]");
  var lightboxImg = document.querySelector("[data-lightbox-img]");

  if (gallery) {
    var thumbs = Array.prototype.slice.call(gallery.querySelectorAll(".gallery-thumbs button"));
    var main = gallery.querySelector("[data-main]");
    var title = gallery.querySelector("[data-title]");
    var desc = gallery.querySelector("[data-desc]");
    var current = 0;

    // Preload full images so switching is instant
    thumbs.forEach(function (t) { new Image().src = t.dataset.src; });

    function show(i, focusThumb) {
      current = (i + thumbs.length) % thumbs.length;
      var t = thumbs[current];
      thumbs.forEach(function (b, n) {
        b.setAttribute("aria-selected", n === current ? "true" : "false");
        b.tabIndex = n === current ? 0 : -1;
      });
      title.textContent = t.dataset.t;
      desc.textContent = t.dataset.d;
      if (main.getAttribute("src") !== t.dataset.src) {
        main.classList.add("fading");
        setTimeout(function () {
          main.src = t.dataset.src;
          main.alt = t.dataset.t;
          main.classList.remove("fading");
        }, 180);
      }
      if (focusThumb) t.focus();
    }

    thumbs.forEach(function (t, i) {
      t.addEventListener("click", function () { show(i); });
    });
    gallery.querySelector("[data-prev]").addEventListener("click", function () { show(current - 1); });
    gallery.querySelector("[data-next]").addEventListener("click", function () { show(current + 1); });
    gallery.querySelector("[data-open]").addEventListener("click", function () {
      openLightbox(thumbs[current].dataset.src, thumbs[current].dataset.t);
    });

    // Arrow keys step the gallery while it's on screen, like the remote
    function galleryOnScreen() {
      var r = gallery.querySelector(".gallery-stage").getBoundingClientRect();
      var mid = r.top + r.height / 2;
      return mid > 0 && mid < window.innerHeight;
    }

    document.addEventListener("keydown", function (e) {
      if (lightbox && !lightbox.hidden) {
        if (e.key === "Escape") closeLightbox();
        else if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
          show(current + (e.key === "ArrowLeft" ? -1 : 1));
          lightboxImg.src = thumbs[current].dataset.src;
        }
        return;
      }
      var inThumbs = gallery.contains(document.activeElement);
      if ((inThumbs || galleryOnScreen()) && (e.key === "ArrowLeft" || e.key === "ArrowRight")) {
        var tag = document.activeElement && document.activeElement.tagName;
        if (tag === "INPUT" || tag === "TEXTAREA") return;
        e.preventDefault();
        show(current + (e.key === "ArrowLeft" ? -1 : 1), inThumbs && document.activeElement.getAttribute("role") === "tab");
      }
    });

    // Swipe on touch screens
    var startX = null;
    main.addEventListener("touchstart", function (e) { startX = e.touches[0].clientX; }, { passive: true });
    main.addEventListener("touchend", function (e) {
      if (startX === null) return;
      var dx = e.changedTouches[0].clientX - startX;
      if (Math.abs(dx) > 40) show(current + (dx < 0 ? 1 : -1));
      startX = null;
    });

    show(0);
  }

  function openLightbox(src, alt) {
    if (!lightbox) return;
    lightboxImg.src = src;
    lightboxImg.alt = alt || "";
    lightbox.hidden = false;
    document.body.style.overflow = "hidden";
    lightbox.querySelector("[data-close]").focus();
  }
  function closeLightbox() {
    lightbox.hidden = true;
    document.body.style.overflow = "";
    var open = document.querySelector("[data-open]");
    if (open) open.focus();
  }
  if (lightbox) lightbox.addEventListener("click", closeLightbox);

  // Remote: hovering a key row lights the matching button, and vice versa
  var rows = document.querySelectorAll("[data-keys] li");
  var pads = document.querySelectorAll(".remote [data-key]");
  function light(key, on) {
    rows.forEach(function (r) { r.classList.toggle("active", on && r.dataset.key === key); });
    pads.forEach(function (p) { p.classList.toggle("lit", on && p.dataset.key.split(" ").indexOf(key) !== -1); });
  }
  rows.forEach(function (r) {
    r.addEventListener("mouseenter", function () { light(r.dataset.key, true); });
    r.addEventListener("mouseleave", function () { light(r.dataset.key, false); });
  });

  // Reveal on scroll
  var reveals = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) { e.target.classList.add("visible"); io.unobserve(e.target); }
      });
    }, { threshold: 0.12 });
    reveals.forEach(function (el) { io.observe(el); });
  } else {
    reveals.forEach(function (el) { el.classList.add("visible"); });
  }
})();

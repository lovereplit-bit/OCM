/*
 * Injected by the Android shell into the OpenCode web UI. It adds the few things a phone needs that a desktop browser
 * gets for free: saving files to the Download folder, matching the system bars to the theme, telling the app when the
 * terminal has focus (so the extra keys row appears) and a clipboard fallback. It does nothing outside the app.
 */
(function () {
  "use strict";

  var native = window.OpenCodeAndroid;

  if (!native || window.__ocAndroidBridge) return;

  window.__ocAndroidBridge = true;

  // File downloads
  var blobs = new Map();
  var createObjectURL = URL.createObjectURL;
  var revokeObjectURL = URL.revokeObjectURL;

  URL.createObjectURL = function (object) {
    var url = createObjectURL.call(URL, object);

    try {
      if (object instanceof Blob) blobs.set(url, object);
    } catch (e) {}

    return url;
  };

  // The save may still be reading the blob when the page revokes its URL, so forget it a little later.
  URL.revokeObjectURL = function (url) {
    setTimeout(function () {
      blobs.delete(url);
    }, 60000);

    return revokeObjectURL.call(URL, url);
  };

  var CHUNK = 192 * 1024; // a multiple of 3, so every chunk encodes to base64 without padding

  function base64(bytes) {
    var out = "";

    for (var i = 0; i < bytes.length; i += 0x8000) {
      out += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }

    return btoa(out);
  }

  function fileName(anchor, url) {
    var given = anchor && anchor.getAttribute && anchor.getAttribute("download");

    if (given) return given;

    try {
      var last = new URL(url).pathname.split("/").filter(Boolean).pop();

      if (last) return decodeURIComponent(last);
    } catch (e) {}

    return "download";
  }

  async function saveBlob(blob, name) {
    var id = native.saveBegin(name, blob.type || "application/octet-stream");

    if (!id) return false;

    try {
      for (var offset = 0; offset < blob.size; offset += CHUNK) {
        var buffer = await blob.slice(offset, offset + CHUNK).arrayBuffer();

        if (!native.saveChunk(id, base64(new Uint8Array(buffer)))) {
          native.saveAbort(id);

          return false;
        }
      }

      native.saveEnd(id);

      return true;
    } catch (e) {
      native.saveAbort(id);

      return false;
    }
  }

  function saveUrl(url, name) {
    var known = blobs.get(url);
    var source = known ? Promise.resolve(known) : fetch(url).then(function (response) { return response.blob(); });

    return source.then(function (blob) {
      return saveBlob(blob, name);
    });
  }

  // Returns true when the anchor was a blob or data download that the app took over.
  function takeOver(anchor) {
    var href = anchor && anchor.href;

    if (typeof href !== "string") return false;
    if (href.indexOf("blob:") !== 0 && href.indexOf("data:") !== 0) return false;

    saveUrl(href, fileName(anchor, href));

    return true;
  }

  var anchorClick = HTMLAnchorElement.prototype.click;

  HTMLAnchorElement.prototype.click = function () {
    if (takeOver(this)) return;

    return anchorClick.apply(this, arguments);
  };

  var anchorDispatch = HTMLAnchorElement.prototype.dispatchEvent;

  HTMLAnchorElement.prototype.dispatchEvent = function (event) {
    if (event && event.type === "click" && takeOver(this)) return true;

    return anchorDispatch.call(this, event);
  };

  document.addEventListener(
    "click",
    function (event) {
      var target = event.target;
      var anchor = target && target.closest ? target.closest("a[href]") : null;

      if (anchor && takeOver(anchor)) {
        event.preventDefault();
        event.stopPropagation();
      }
    },
    true,
  );

  window.__ocSaveUrl = saveUrl;

  // System bars follow the theme
  function parseColor(value) {
    var match = /rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+))?/.exec(value || "");

    if (!match) return null;

    var alpha = match[4] === undefined ? 1 : parseFloat(match[4]);

    if (alpha < 0.5) return null;

    return [parseInt(match[1], 10), parseInt(match[2], 10), parseInt(match[3], 10)];
  }

  function pageColor() {
    var candidates = [document.querySelector("#root"), document.body, document.documentElement];

    for (var i = 0; i < candidates.length; i++) {
      if (!candidates[i]) continue;

      var color = parseColor(getComputedStyle(candidates[i]).backgroundColor);

      if (color) return color;
    }

    return null;
  }

  var lastBars = "";

  function syncBars() {
    var color = pageColor();

    if (!color) return;

    var hex = "#" + color.map(function (v) { return ("0" + v.toString(16)).slice(-2); }).join("");
    var luminance = (0.299 * color[0] + 0.587 * color[1] + 0.114 * color[2]) / 255;
    var key = hex + (luminance < 0.5);

    if (key === lastBars) return;

    lastBars = key;
    native.setBars(luminance < 0.5, hex);
  }

  var barTimer = 0;

  function scheduleBars() {
    clearTimeout(barTimer);
    barTimer = setTimeout(syncBars, 120);
  }

  new MutationObserver(scheduleBars).observe(document.documentElement, { attributes: true, attributeFilter: ["class", "style", "data-theme", "data-color-scheme"] });
  window.addEventListener("load", scheduleBars);
  document.addEventListener("DOMContentLoaded", scheduleBars);

  if (window.matchMedia) {
    var scheme = window.matchMedia("(prefers-color-scheme: dark)");

    if (scheme.addEventListener) scheme.addEventListener("change", scheduleBars);
  }

  setInterval(syncBars, 2000);
  scheduleBars();

  // Terminal focus
  var terminalFocused = false;

  function inTerminal(element) {
    if (!element || !element.closest) return false;

    var typing = element.tagName === "TEXTAREA" || element.tagName === "INPUT" || element.isContentEditable;

    return typing && !!element.closest('[data-component="terminal-panel"]');
  }

  function reportFocus() {
    var now = inTerminal(document.activeElement);

    if (now !== terminalFocused) {
      terminalFocused = now;
      native.terminalFocus(now);
    }
  }

  document.addEventListener("focusin", reportFocus, true);
  document.addEventListener("focusout", function () { setTimeout(reportFocus, 50); }, true);

  // Clipboard fallback
  if (navigator.clipboard && navigator.clipboard.writeText) {
    var writeText = navigator.clipboard.writeText.bind(navigator.clipboard);

    navigator.clipboard.writeText = function (text) {
      return writeText(text).catch(function () {
        native.copyText(String(text));
      });
    };
  }
})();

(function () {
  function native(name, impl) {
    return __nativeFn(name, impl, 0);
  }
  /* Listener lists live here, never as a property BotGuard can see on window/document/elements. */
  var listenerMaps = new WeakMap();
  function listenersOf(target, type, create) {
    if (target === null || (typeof target !== "object" && typeof target !== "function"))
      return create ? [] : null;
    var map = listenerMaps.get(target);
    if (!map) {
      if (!create) return null;
      map = Object.create(null);
      listenerMaps.set(target, map);
    }
    if (!map[type] && create) map[type] = [];
    return map[type] || null;
  }
  /* Event state lives here for the same reason: isTrusted is a Chrome event's only own property. */
  var eventData = new WeakMap();
  function eventOf(ev) {
    var d = ev !== null && typeof ev === "object" ? eventData.get(ev) : undefined;
    if (!d) throw new TypeError("Illegal invocation");
    return d;
  }
  var imageRequest = null;
  function domError(name, code, message) {
    var e = Object.create(globalThis.DOMException.prototype);
    hide(e, "name", name);
    hide(e, "message", message);
    hide(e, "code", code);
    return e;
  }
  function hrtime() {
    var p = globalThis.performance;
    if (p && typeof p.now === "function")
      return (typeof p.timeOrigin === "number" ? p.timeOrigin : 0) + p.now();
    return Date.now();
  }

  function unwrapScript(s) {
    if (s != null && typeof s === "object" && typeof s.toString === "function") {
      var t = s.toString();
      if (typeof t === "string" && t.indexOf("[object ") !== 0) return t;
    }
    return s;
  }

  function absScriptUrl(src) {
    src = String(unwrapScript(src) || "");
    if (src.indexOf("://") >= 0) return src;
    if (src.indexOf("//") === 0) return "https:" + src;
    if (src.charAt(0) === "/") return "https://www.youtube.com" + src;
    return src;
  }

  function fireScript(el, type) {
    var ev = { type: type, target: el, currentTarget: el, bubbles: false };
    var fn = el["on" + type];
    var list, i;
    if (typeof fn === "function") {
      try {
        fn.call(el, ev);
      } catch (e) {}
    }
    list = listenersOf(el, type, false) || [];
    for (i = 0; i < list.length; i++) {
      try {
        list[i].call(el, ev);
      } catch (e2) {}
    }
  }

  /* Chrome: insert <script src> fetches and evals globally, then onload.
   * appendChild alone is not enough. */
  var scriptResult = Object.create(null);

  function runScript(el) {
    var src, code, prev;
    if (!el || String(el.tagName || "").toUpperCase() !== "SCRIPT") return;
    src = el.src ? absScriptUrl(el.src) : "";
    if (src && src !== "about:blank") {
      prev = scriptResult[src];
      if (prev) {
        /* Chrome fires load/error on every inserted <script src>, even if
           the URL was already fetched. */
        el.readyState = prev.ok ? "complete" : el.readyState;
        fireScript(el, prev.ok ? "load" : "error");
        return;
      }
      if (typeof fetch !== "function") {
        fireScript(el, "error");
        return;
      }
      fetch(src)
        .then(function (r) {
          if (!r.ok) throw new Error("script " + r.status);
          return r.text();
        })
        .then(function (body) {
          try {
            (0, eval)(body);
          } catch (e) {
            console.warn("script eval failed " + src + " " + (e && e.message ? e.message : e));
            scriptResult[src] = { ok: false };
            fireScript(el, "error");
            return;
          }
          scriptResult[src] = { ok: true };
          el.readyState = "complete";
          fireScript(el, "load");
        })
        .catch(function (e) {
          console.warn("script fetch failed " + src + " " + (e && e.message ? e.message : e));
          scriptResult[src] = { ok: false };
          fireScript(el, "error");
        });
      return;
    }
    code = unwrapScript(el.textContent || el.innerText || el.text || "");
    if (!code) return;
    el._bbd_ran = true;
    try {
      (0, eval)(String(code));
      fireScript(el, "load");
    } catch (e) {
      console.warn("script eval failed inline " + (e && e.message ? e.message : e));
      fireScript(el, "error");
    }
  }

  function TrustedScript(s) {
    this._s = String(s);
  }
  TrustedScript.prototype.toString = native("toString", function () {
    return this._s;
  });
  TrustedScript.prototype.toJSON = native("toJSON", function () {
    return this._s;
  });
  globalThis.TrustedScript = native("TrustedScript", TrustedScript);
  globalThis.TrustedScript.prototype = TrustedScript.prototype;
  function makeTrustedScript(s) {
    var o = Object.create(TrustedScript.prototype);
    o._s = String(s);
    return o;
  }
  function TrustedHTML(s) {
    this._s = String(s);
  }
  TrustedHTML.prototype.toString = native("toString", function () {
    return this._s;
  });
  TrustedHTML.prototype.toJSON = native("toJSON", function () {
    return this._s;
  });
  globalThis.TrustedHTML = native("TrustedHTML", TrustedHTML);
  globalThis.TrustedHTML.prototype = TrustedHTML.prototype;
  function makeTrustedHTML(s) {
    var o = Object.create(TrustedHTML.prototype);
    o._s = String(s);
    return o;
  }
  function TrustedScriptURL(s) {
    this._s = String(s);
  }
  TrustedScriptURL.prototype.toString = native("toString", function () {
    return this._s;
  });
  TrustedScriptURL.prototype.toJSON = native("toJSON", function () {
    return this._s;
  });
  globalThis.TrustedScriptURL = native("TrustedScriptURL", TrustedScriptURL);
  globalThis.TrustedScriptURL.prototype = TrustedScriptURL.prototype;

  globalThis.window = globalThis;
  var cons = globalThis.console || {};
  function consFn(name, alt) {
    var fn = cons[name];
    if (typeof fn !== "function" && alt) fn = cons[alt];
    if (typeof fn !== "function") fn = function () {};
    return native(name, fn);
  }
  globalThis.console = {
    log: consFn("log"),
    info: consFn("info"),
    warn: consFn("warn"),
    error: consFn("error"),
    debug: consFn("debug", "log"),
    group: consFn("group"),
    groupCollapsed: consFn("groupCollapsed"),
    groupEnd: consFn("groupEnd"),
  };
  globalThis.self = globalThis;
  globalThis.top = globalThis;
  globalThis.parent = globalThis;
  globalThis.frames = globalThis;

  /* Do not replace global eval. Direct eval must stay the builtin.
   * TrustedScript unwrap is C. */

  globalThis.addEventListener = native("addEventListener", function (type, fn) {
    if (typeof fn === "function")
      listenersOf(this == null ? globalThis : this, type, true).push(fn);
  });
  globalThis.removeEventListener = native("removeEventListener", function (type, fn) {
    var list, i;
    list = listenersOf(this == null ? globalThis : this, type, false);
    if (!list) return;
    i = list.indexOf(fn);
    if (i >= 0) list.splice(i, 1);
  });
  var dispatchNative = (globalThis.dispatchEvent = __nativeFn(
    "dispatchEvent",
    function (ev) {
      var prefix = "Failed to execute 'dispatchEvent' on 'EventTarget': ";
      var target = this == null ? globalThis : this;
      var d, fn, list, i;
      if (!arguments.length)
        throw new TypeError(prefix + "1 argument required, but only 0 present.");
      d = ev !== null && typeof ev === "object" ? eventData.get(ev) : undefined;
      if (!d) throw new TypeError(prefix + "parameter 1 is not of type 'Event'.");
      if (!d.init)
        throw domError("InvalidStateError", 11, prefix + "The event provided is uninitialized.");
      if (d.dispatching)
        throw domError("InvalidStateError", 11, prefix + "The event is already being dispatched.");
      d.dispatching = true;
      d.target = d.currentTarget = target;
      d.eventPhase = 2;
      d.path = [target];
      fn = target["on" + d.type];
      if (typeof fn === "function") {
        try {
          fn.call(target, ev);
        } catch (e) {}
      }
      list = (listenersOf(target, d.type, false) || []).slice();
      for (i = 0; i < list.length && !d.stopNow; i++) {
        try {
          list[i].call(target, ev);
        } catch (e2) {}
      }
      d.dispatching = d.stopNow = d.cancelBubble = false;
      d.currentTarget = d.path = null;
      d.eventPhase = 0;
      return !d.defaultPrevented;
    },
    1,
  ));
  globalThis.getComputedStyle = native("getComputedStyle", function getComputedStyle(el) {
    if (el && el.style) return el.style;
    return cssStyle();
  });
  globalThis.requestIdleCallback = native("requestIdleCallback", function (fn, opts) {
    return setTimeout(fn, (opts && opts.timeout) || 0);
  });
  globalThis.cancelIdleCallback = native("cancelIdleCallback", function (id) {
    clearTimeout(id);
  });
  if (typeof globalThis.queueMicrotask !== "function") {
    globalThis.queueMicrotask = native("queueMicrotask", function (fn) {
      Promise.resolve().then(fn);
    });
  }
  if (typeof globalThis.requestAnimationFrame !== "function") {
    globalThis.requestAnimationFrame = native("requestAnimationFrame", function (fn) {
      return setTimeout(function () {
        fn(platformNow.call(globalThis.performance));
      }, 16);
    });
  }
  if (typeof globalThis.cancelAnimationFrame !== "function") {
    globalThis.cancelAnimationFrame = native("cancelAnimationFrame", function (id) {
      clearTimeout(id);
    });
  }

  function locHref() {
    return String(this && this.href != null ? this.href : "");
  }
  globalThis.location = {
    href: "https://www.youtube.com/",
    protocol: "https:",
    host: "www.youtube.com",
    hostname: "www.youtube.com",
    port: "",
    pathname: "/",
    search: "",
    hash: "",
    origin: "https://www.youtube.com",
    ancestorOrigins: { length: 0 },
    toString: native("toString", locHref),
    valueOf: native("valueOf", locHref),
  };

  /* Shape only — EVENT_ID / data_ come from live ytcfg (installYtcfg).
   * Closed-over data_: native() wrap can call set with this=undefined. */
  var eventIdHeld = "";
  var data_ = {};
  Object.defineProperty(data_, "EVENT_ID", {
    configurable: true,
    enumerable: true,
    get: function () {
      return eventIdHeld;
    },
    set: function (v) {
      eventIdHeld = v;
    },
  });
  globalThis.ytcfg = {
    data_: data_,
    set: native("set", function (d) {
      if (!d || typeof d !== "object") return;
      var k;
      for (k in d) if (Object.prototype.hasOwnProperty.call(d, k)) data_[k] = d[k];
    }),
    get: native("get", function (k, fb) {
      return Object.prototype.hasOwnProperty.call(data_, k) ? data_[k] : fb;
    }),
  };
  globalThis.yt = { config_: data_ };

  var origin;
  if (!globalThis.performance || typeof globalThis.performance.now !== "function") {
    origin = hrtime() - 1847.3;
    globalThis.performance = {
      timeOrigin: origin,
      now: native("now", function now() {
        return hrtime() - origin;
      }),
      memory: {
        jsHeapSizeLimit: 4294967296,
        totalJSHeapSize: 19100000,
        usedJSHeapSize: 10200000,
      },
    };
  } else {
    origin = globalThis.performance.timeOrigin || hrtime() - 1847.3;
  }
  if (!globalThis.performance.memory) {
    globalThis.performance.memory = {
      jsHeapSizeLimit: 4294967296,
      totalJSHeapSize: 19100000,
      usedJSHeapSize: 10200000,
    };
  }
  /* Stamps internal to the browser, which page script can't observe as a read. */
  var platformNow = globalThis.performance.now;

  /* Milliseconds after navigation start, rolled once per session at Chrome's 0.1 ms resolution. */
  var navTiming = (function () {
    var at = 0;
    function after(min, max) {
      at += min + Math.random() * (max - min);
      at = Math.round(at * 10) / 10;
      return at;
    }
    var t = { fetchStart: after(0.5, 3) };
    t.domainLookupStart = t.fetchStart;
    t.domainLookupEnd = after(1, 30);
    t.connectStart = t.domainLookupEnd;
    t.secureConnectionStart = after(4, 20);
    t.connectEnd = after(4, 20);
    t.requestStart = after(0.2, 2);
    t.responseStart = after(80, 400);
    t.responseEnd = after(20, 120);
    t.domInteractive = after(300, 900);
    t.domContentLoadedEventStart = after(0, 3);
    t.domContentLoadedEventEnd = after(0.1, 2);
    t.domComplete = after(100, 500);
    t.loadEventStart = t.domComplete;
    t.loadEventEnd = after(0.1, 1);
    return t;
  })();

  (function attachTiming(perf, navStart) {
    if (!perf.timing) {
      perf.timing = {
        navigationStart: navStart,
        unloadEventStart: 0,
        unloadEventEnd: 0,
        redirectStart: 0,
        redirectEnd: 0,
        fetchStart: navStart + Math.round(navTiming.fetchStart),
        domainLookupStart: navStart + Math.round(navTiming.domainLookupStart),
        domainLookupEnd: navStart + Math.round(navTiming.domainLookupEnd),
        connectStart: navStart + Math.round(navTiming.connectStart),
        connectEnd: navStart + Math.round(navTiming.connectEnd),
        secureConnectionStart: navStart + Math.round(navTiming.secureConnectionStart),
        requestStart: navStart + Math.round(navTiming.requestStart),
        responseStart: navStart + Math.round(navTiming.responseStart),
        responseEnd: navStart + Math.round(navTiming.responseEnd),
        domLoading: navStart + Math.round(navTiming.responseEnd) + 1,
        domInteractive: navStart + Math.round(navTiming.domInteractive),
        domContentLoadedEventStart: navStart + Math.round(navTiming.domContentLoadedEventStart),
        domContentLoadedEventEnd: navStart + Math.round(navTiming.domContentLoadedEventEnd),
        domComplete: navStart + Math.round(navTiming.domComplete),
        loadEventStart: navStart + Math.round(navTiming.loadEventStart),
        loadEventEnd: navStart + Math.round(navTiming.loadEventEnd),
      };
    }
    if (!perf.navigation) {
      perf.navigation = { type: 0, redirectCount: 0 };
    }
    if (typeof perf.getEntriesByType !== "function") {
      perf.getEntriesByType = native("getEntriesByType", function () {
        return [];
      });
      perf.getEntriesByName = native("getEntriesByName", function () {
        return [];
      });
      perf.getEntries = native("getEntries", function () {
        return [];
      });
      perf.mark = native("mark", function () {});
      perf.measure = native("measure", function () {});
      perf.clearMarks = native("clearMarks", function () {});
      perf.clearMeasures = native("clearMeasures", function () {});
      perf.clearResourceTimings = native("clearResourceTimings", function () {});
    }
  })(globalThis.performance, Math.floor(origin));

  if (typeof globalThis.Intl !== "object" || globalThis.Intl == null) {
    (function () {
      /* __nativeFn is a C function: `new C()` is the C return value, not
         an auto-created `this`. Must return the instance. */
      function hostTimeZone() {
        if (typeof __timeZone === "function") {
          try {
            var z = __timeZone();
            if (typeof z === "string" && z) return z;
          } catch (e) {}
        }
        var off = 0;
        try {
          off = new Date().getTimezoneOffset();
        } catch (e2) {}
        if (!off) return "UTC";
        var h = Math.round(off / 60);
        return "Etc/GMT" + (h > 0 ? "+" + h : h);
      }
      function resolvedOptions() {
        return {
          locale: "en-US",
          calendar: "gregory",
          numberingSystem: "latn",
          timeZone: hostTimeZone(),
        };
      }
      function ctor(name) {
        var C = native(name, function () {
          var self = {};
          self.format = native("format", function (v) {
            return String(v == null ? "" : v);
          });
          self.formatToParts = native("formatToParts", function () {
            return [];
          });
          self.resolvedOptions = native("resolvedOptions", resolvedOptions);
          self.compare = native("compare", function (a, b) {
            return a < b ? -1 : a > b ? 1 : 0;
          });
          self.select = native("select", function () {
            return "other";
          });
          return self;
        });
        if (!C.prototype) C.prototype = {};
        C.prototype.resolvedOptions = resolvedOptions;
        C.supportedLocalesOf = native("supportedLocalesOf", function () {
          return ["en-US"];
        });
        return C;
      }
      globalThis.Intl = {
        DateTimeFormat: ctor("DateTimeFormat"),
        NumberFormat: ctor("NumberFormat"),
        Collator: ctor("Collator"),
        PluralRules: ctor("PluralRules"),
        RelativeTimeFormat: ctor("RelativeTimeFormat"),
        ListFormat: ctor("ListFormat"),
        DisplayNames: ctor("DisplayNames"),
        Segmenter: ctor("Segmenter"),
      };
    })();
  }

  function asTrusted(kind, v) {
    if (v != null && typeof v === "object") return v;
    if (kind === "script") return makeTrustedScript(v);
    if (kind === "html") return makeTrustedHTML(v);
    return String(v);
  }
  globalThis.trustedTypes = {
    emptyHTML: makeTrustedHTML(""),
    emptyScript: makeTrustedScript(""),
    defaultPolicy: null,
    createPolicy: native("createPolicy", function (name, rules) {
      return {
        name: name,
        createHTML: native("createHTML", function (s) {
          return asTrusted("html", rules && rules.createHTML ? rules.createHTML(s) : s);
        }),
        createScript: native("createScript", function (s) {
          return asTrusted("script", rules && rules.createScript ? rules.createScript(s) : s);
        }),
        createScriptURL: native("createScriptURL", function (s) {
          return asTrusted("url", rules && rules.createScriptURL ? rules.createScriptURL(s) : s);
        }),
      };
    }),
    isHTML: native("isHTML", function (v) {
      return v != null && typeof v === "object" && v instanceof TrustedHTML;
    }),
    isScript: native("isScript", function (v) {
      return v != null && typeof v === "object" && v instanceof TrustedScript;
    }),
    isScriptURL: native("isScriptURL", function (v) {
      return v != null && typeof v === "object" && v instanceof TrustedScriptURL;
    }),
    getAttributeType: native("getAttributeType", function () {
      return null;
    }),
    getPropertyType: native("getPropertyType", function (tagName, prop) {
      if (prop === "innerHTML") return "TrustedHTML";
      if (prop === "src" && String(tagName).toLowerCase() === "script") return "TrustedScriptURL";
      return null;
    }),
  };

  /* Desktop Chrome on youtube.com. */
  globalThis.chrome = {
    loadTimes: native("loadTimes", function loadTimes() {
      return {};
    }),
    csi: native("csi", function csi() {
      return {
        startE: origin,
        onloadT: origin + 80,
        pageT: platformNow.call(globalThis.performance),
        tran: 15,
      };
    }),
    app: { isInstalled: false },
  };

  var nav = {
    /* Same string as DESKTOP_UA in yt/session.ts — HTTP User-Agent and this getter must match. */
    userAgent:
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
    appVersion:
      "5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
    appName: "Netscape",
    appCodeName: "Mozilla",
    product: "Gecko",
    productSub: "20030107",
    vendor: "Google Inc.",
    vendorSub: "",
    platform: "MacIntel",
    language: "en-US",
    languages: ["en-US", "en"],
    hardwareConcurrency: 8,
    deviceMemory: 8,
    maxTouchPoints: 0,
    webdriver: false,
    cookieEnabled: true,
    onLine: true,
    pdfViewerEnabled: true,
    doNotTrack: null,
    userAgentData: {
      brands: [
        { brand: "Chromium", version: "151" },
        { brand: "Google Chrome", version: "151" },
        { brand: "Not-A.Brand", version: "99" },
      ],
      mobile: false,
      platform: "macOS",
    },
  };
  nav.userAgentData.getHighEntropyValues = native("getHighEntropyValues", function () {
    var d = nav.userAgentData;
    return Promise.resolve({
      brands: d.brands,
      mobile: false,
      platform: "macOS",
      platformVersion: "15.7.1",
      architecture: "arm",
      bitness: "64",
      model: "",
      uaFullVersion: "151.0.0.0",
      fullVersionList: [
        { brand: "Chromium", version: "151.0.0.0" },
        { brand: "Google Chrome", version: "151.0.0.0" },
        { brand: "Not-A.Brand", version: "10.0.1.4" },
      ],
    });
  });
  nav.connection = {
    effectiveType: "4g",
    downlink: 10,
    rtt: 50,
    saveData: false,
    onchange: null,
    addEventListener: native("addEventListener", function () {}),
    removeEventListener: native("removeEventListener", function () {}),
  };
  nav.userActivation = { hasBeenActive: true, isActive: true };
  (function () {
    function plugin(name, filename, desc) {
      var m0 = { type: "application/pdf", suffixes: "pdf", description: desc, enabledPlugin: null };
      var m1 = { type: "text/pdf", suffixes: "pdf", description: desc, enabledPlugin: null };
      var p = { name: name, filename: filename, description: desc, length: 2, 0: m0, 1: m1 };
      m0.enabledPlugin = p;
      m1.enabledPlugin = p;
      p.item = native("item", function (i) {
        return p[i] || null;
      });
      p.namedItem = native("namedItem", function (n) {
        if (n === "application/pdf") return m0;
        if (n === "text/pdf") return m1;
        return null;
      });
      return p;
    }
    var list = [
      plugin("PDF Viewer", "internal-pdf-viewer", "Portable Document Format"),
      plugin("Chrome PDF Viewer", "internal-pdf-viewer", "Portable Document Format"),
      plugin("Chromium PDF Viewer", "internal-pdf-viewer", "Portable Document Format"),
      plugin("Microsoft Edge PDF Viewer", "internal-pdf-viewer", "Portable Document Format"),
      plugin("WebKit built-in PDF", "internal-pdf-viewer", "Portable Document Format"),
    ];
    var plugins = { length: list.length };
    var i;
    for (i = 0; i < list.length; i++) plugins[i] = list[i];
    plugins.item = native("item", function (i) {
      return plugins[i] || null;
    });
    plugins.namedItem = native("namedItem", function (n) {
      var j;
      for (j = 0; j < list.length; j++) if (list[j].name === n) return list[j];
      return null;
    });
    nav.plugins = plugins;
    var mt = { length: 2, 0: list[0][0], 1: list[0][1] };
    mt.item = native("item", function (i) {
      return mt[i] || null;
    });
    nav.mimeTypes = mt;
  })();
  nav.mediaDevices = {
    enumerateDevices: native("enumerateDevices", function () {
      return Promise.resolve(
        ["audioinput", "videoinput", "audiooutput"].map(function (kind) {
          return { deviceId: "", kind: kind, label: "", groupId: "" };
        }),
      );
    }),
    getUserMedia: native("getUserMedia", function () {
      var e = new Error("Requested device not found");
      e.name = "NotFoundError";
      return Promise.reject(e);
    }),
    getDisplayMedia: native("getDisplayMedia", function () {
      var e = new Error("Permission denied");
      e.name = "NotAllowedError";
      return Promise.reject(e);
    }),
  };
  var keyboardLayoutMap;
  nav.keyboard = {
    lock: native("lock", function () {
      return Promise.resolve();
    }),
    unlock: native("unlock", function () {}),
    getLayoutMap: native("getLayoutMap", function () {
      return Promise.resolve(keyboardLayoutMap);
    }),
  };
  nav.gpu = {
    wgslLanguageFeatures: {
      size: 0,
      has: function () {
        return false;
      },
    },
    requestAdapter: native("requestAdapter", function () {
      return Promise.resolve({
        info: { vendor: "apple", architecture: "metal-3", device: "", description: "" },
        features: {
          size: 0,
          has: function () {
            return false;
          },
        },
        limits: {},
        requestDevice: native("requestDevice", function () {
          return Promise.resolve({ queue: { submit: native("submit", function () {}) } });
        }),
      });
    }),
  };
  globalThis.navigator = nav;
  globalThis.length = 0;

  globalThis.screen = {
    width: 1512,
    height: 982,
    availWidth: 1512,
    availHeight: 944,
    colorDepth: 24,
    pixelDepth: 24,
    availLeft: 0,
    availTop: 0,
    orientation: { angle: 0, type: "landscape-primary" },
  };
  /* Chrome window geometry; live Create 9943 read screenY as undefined. */
  globalThis.innerWidth = 1280;
  globalThis.innerHeight = 800;
  globalThis.outerWidth = 1280;
  globalThis.outerHeight = 860;
  globalThis.devicePixelRatio = 2;
  globalThis.screenX = 0;
  globalThis.screenY = 0;
  globalThis.screenLeft = 0;
  globalThis.screenTop = 0;
  globalThis.pageXOffset = 0;
  globalThis.pageYOffset = 0;
  globalThis.scrollX = 0;
  globalThis.scrollY = 0;
  /* Chrome window identity; live Create 10023 read name as undefined. */
  globalThis.name = "";
  globalThis.status = "";
  globalThis.defaultStatus = "";
  (function installZoneName() {
    /* QuickJS Date strings stop at GMT+0700. Standard name, daylight name, by standard offset. */
    var ZONES = {
      "-480": ["Pacific Standard Time", "Pacific Daylight Time"],
      "-420": ["Mountain Standard Time", "Mountain Daylight Time"],
      "-360": ["Central Standard Time", "Central Daylight Time"],
      "-300": ["Eastern Standard Time", "Eastern Daylight Time"],
      "-180": ["Brasilia Standard Time"],
      0: ["Greenwich Mean Time", "British Summer Time"],
      60: ["Central European Standard Time", "Central European Summer Time"],
      120: ["Eastern European Standard Time", "Eastern European Summer Time"],
      180: ["Moscow Standard Time"],
      240: ["Gulf Standard Time"],
      300: ["Pakistan Standard Time"],
      330: ["India Standard Time"],
      420: ["Indochina Time"],
      480: ["China Standard Time"],
      540: ["Japan Standard Time"],
      600: ["Australian Eastern Standard Time", "Australian Eastern Daylight Time"],
    };
    var proto = Date.prototype;
    function zoneName(date) {
      var at = date.getTimezoneOffset();
      var year = date.getFullYear();
      var standard = -Math.max(
        new Date(year, 0, 1).getTimezoneOffset(),
        new Date(year, 6, 1).getTimezoneOffset(),
      );
      var names = ZONES[standard];
      if (names) return names[-at !== standard && names[1] ? 1 : 0];
      var abs = Math.abs(at);
      var pad = function (n) {
        return (n < 10 ? "0" : "") + n;
      };
      return "GMT" + (at > 0 ? "-" : "+") + pad(Math.floor(abs / 60)) + ":" + pad(abs % 60);
    }
    ["toString", "toTimeString"].forEach(function (key) {
      var plain = proto[key];
      hide(
        proto,
        key,
        native(key, function () {
          var text = plain.call(this);
          return isNaN(this.getTime()) ? text : text + " (" + zoneName(this) + ")";
        }),
      );
    });
  })();
  globalThis.origin = globalThis.location.origin;
  globalThis.closed = false;
  globalThis.isSecureContext = true;
  globalThis.crossOriginIsolated = false;
  /* Chrome: opener is present and null on a top-level tab (`'opener' in window`). */
  globalThis.opener = null;
  (function () {
    var bars = ["menubar", "toolbar", "locationbar", "personalbar", "scrollbars", "statusbar"];
    var bi;
    for (bi = 0; bi < bars.length; bi++) globalThis[bars[bi]] = { visible: true };
  })();
  /* Chrome window.history; live Create 9959 read it as undefined. */
  globalThis.history = {
    length: 2,
    state: null,
    scrollRestoration: "auto",
    pushState: native("pushState", function () {}),
    replaceState: native("replaceState", function () {}),
    go: native("go", function () {}),
    back: native("back", function () {}),
    forward: native("forward", function () {}),
  };

  /* Observers construct as in Chrome, before the PO snapshot probes them. State lives in a WeakMap,
   * never on the instance. IntersectionObserver reports each target once, measured against the
   * viewport; the player's SABR visibility check runs on it. */
  (function () {
    var state = new WeakMap();
    var entryData = new WeakMap();
    function hostFn(name, impl, length) {
      return __nativeFn(name, impl, length);
    }
    function ctorError(name, msg) {
      return new TypeError("Failed to construct '" + name + "': " + msg);
    }
    function callError(iface, op, msg) {
      return new TypeError("Failed to execute '" + op + "' on '" + iface + "': " + msg);
    }
    function missing(n, got) {
      return n + " argument" + (n > 1 ? "s" : "") + " required, but only " + got + " present.";
    }
    function stateOf(self) {
      var s = state.get(self);
      if (!s) throw new TypeError("Illegal invocation");
      return s;
    }
    function isNode(v) {
      return v != null && typeof v === "object" && typeof v.nodeType === "number";
    }
    function isElement(v) {
      return isNode(v) && v.nodeType === 1;
    }
    function defineProto(C, name, members) {
      var proto = {};
      var key;
      Object.defineProperty(proto, "constructor", { value: C, writable: true, configurable: true });
      Object.defineProperty(proto, Symbol.toStringTag, { value: name, configurable: true });
      for (key in members) {
        Object.defineProperty(proto, key, {
          value: hostFn(key, members[key][0], members[key][1]),
          writable: true,
          enumerable: true,
          configurable: true,
        });
      }
      Object.defineProperty(C, "prototype", { value: proto });
      return proto;
    }
    function defineGetters(proto, map, names) {
      names.forEach(function (key) {
        Object.defineProperty(proto, key, {
          get: hostFn(
            "get " + key,
            function () {
              var d = map.get(this);
              if (!d) throw new TypeError("Illegal invocation");
              return d[key];
            },
            0,
          ),
          enumerable: true,
          configurable: true,
        });
      });
    }
    function observerCtor(name, init) {
      var C = hostFn(
        name,
        function (callback, options) {
          if (typeof this !== "function")
            throw ctorError(
              name,
              "Please use the 'new' operator, this DOM object constructor cannot be called as a function.",
            );
          if (arguments.length < 1) throw ctorError(name, missing(1, 0));
          if (typeof callback !== "function")
            throw ctorError(name, "parameter 1 is not of type 'Function'.");
          var self = Object.create(this.prototype || C.prototype);
          var s = { callback: callback, targets: [], records: [], timer: null };
          if (init) init(s, options);
          state.set(self, s);
          return self;
        },
        1,
      );
      globalThis[name] = C;
      return C;
    }
    function entryCtor(name) {
      var C = hostFn(
        name,
        function () {
          throw new TypeError("Illegal constructor");
        },
        0,
      );
      globalThis[name] = C;
      return C;
    }
    function deliver(self, s) {
      s.timer = null;
      if (!s.records.length) return;
      var records = s.records;
      s.records = [];
      s.callback.call(self, records, self);
    }
    function schedule(self, s) {
      if (s.timer == null)
        s.timer = setTimeout(function () {
          deliver(self, s);
        }, 0);
    }
    function cancel(s) {
      if (s.timer != null) clearTimeout(s.timer);
      s.timer = null;
    }
    function removeTarget(s, target) {
      var i = s.targets.indexOf(target);
      if (i >= 0) s.targets.splice(i, 1);
      s.records = s.records.filter(function (r) {
        return r.target !== target;
      });
    }
    function rectOf(target) {
      var r = target.getBoundingClientRect ? target.getBoundingClientRect() : null;
      var x = (r && +r.left) || 0;
      var y = (r && +r.top) || 0;
      var w = (r && +r.width) || 0;
      var h = (r && +r.height) || 0;
      return rect(x, y, w, h);
    }
    function rect(x, y, w, h) {
      return { x: x, y: y, width: w, height: h, top: y, left: x, bottom: y + h, right: x + w };
    }

    var ioEntry = entryCtor("IntersectionObserverEntry");
    var ioEntryProto = defineProto(ioEntry, "IntersectionObserverEntry", {});
    defineGetters(ioEntryProto, entryData, [
      "time",
      "rootBounds",
      "boundingClientRect",
      "intersectionRect",
      "isIntersecting",
      "isVisible",
      "intersectionRatio",
      "target",
    ]);
    function ioRecord(s, target) {
      var box = rectOf(target);
      var vw = +globalThis.innerWidth || 0;
      var vh = +globalThis.innerHeight || 0;
      var left = Math.max(box.left, 0);
      var top = Math.max(box.top, 0);
      var right = Math.min(box.right, vw);
      var bottom = Math.min(box.bottom, vh);
      var hit = right >= left && bottom >= top;
      var area = box.width * box.height;
      var inter = hit ? rect(left, top, right - left, bottom - top) : rect(0, 0, 0, 0);
      var entry = Object.create(ioEntryProto);
      entryData.set(entry, {
        time: platformNow.call(globalThis.performance),
        rootBounds: s.root == null ? rect(0, 0, vw, vh) : rectOf(s.root),
        boundingClientRect: box,
        intersectionRect: inter,
        isIntersecting: hit,
        isVisible: false,
        intersectionRatio: !hit ? 0 : area > 0 ? (inter.width * inter.height) / area : 1,
        target: target,
      });
      return entry;
    }
    var io = observerCtor("IntersectionObserver", function (s, options) {
      var o = options == null ? {} : options;
      var t = o.threshold === undefined ? [0] : [].concat(o.threshold);
      t = t.map(Number);
      t.forEach(function (v) {
        if (!(v >= 0 && v <= 1))
          throw new RangeError(
            "Failed to construct 'IntersectionObserver': Threshold values must be numbers between 0 and 1",
          );
      });
      s.root = o.root == null ? null : o.root;
      s.thresholds = t.sort(function (a, b) {
        return a - b;
      });
      s.delay = +o.delay || 0;
      s.trackVisibility = !!o.trackVisibility;
    });
    var ioProto = defineProto(io, "IntersectionObserver", {
      observe: [
        function (target) {
          var s = stateOf(this);
          if (arguments.length < 1)
            throw callError("IntersectionObserver", "observe", missing(1, 0));
          if (!isElement(target))
            throw callError(
              "IntersectionObserver",
              "observe",
              "parameter 1 is not of type 'Element'.",
            );
          if (s.targets.indexOf(target) >= 0) return;
          s.targets.push(target);
          s.records.push(ioRecord(s, target));
          schedule(this, s);
        },
        1,
      ],
      unobserve: [
        function (target) {
          var s = stateOf(this);
          if (arguments.length < 1)
            throw callError("IntersectionObserver", "unobserve", missing(1, 0));
          if (!isElement(target))
            throw callError(
              "IntersectionObserver",
              "unobserve",
              "parameter 1 is not of type 'Element'.",
            );
          removeTarget(s, target);
        },
        1,
      ],
      disconnect: [
        function () {
          var s = stateOf(this);
          s.targets = [];
          s.records = [];
          cancel(s);
        },
        0,
      ],
      takeRecords: [
        function () {
          var s = stateOf(this);
          var r = s.records;
          s.records = [];
          return r;
        },
        0,
      ],
    });
    defineGetters(ioProto, state, ["root", "thresholds", "delay", "trackVisibility"]);
    ["rootMargin", "scrollMargin"].forEach(function (key) {
      Object.defineProperty(ioProto, key, {
        get: hostFn(
          "get " + key,
          function () {
            stateOf(this);
            return "0px 0px 0px 0px";
          },
          0,
        ),
        enumerable: true,
        configurable: true,
      });
    });

    var roEntry = entryCtor("ResizeObserverEntry");
    var roEntryProto = defineProto(roEntry, "ResizeObserverEntry", {});
    defineGetters(roEntryProto, entryData, [
      "target",
      "contentRect",
      "borderBoxSize",
      "contentBoxSize",
      "devicePixelContentBoxSize",
    ]);
    function roRecord(target) {
      var box = rectOf(target);
      var dpr = +globalThis.devicePixelRatio || 1;
      var size = [Object.freeze({ inlineSize: box.width, blockSize: box.height })];
      var entry = Object.create(roEntryProto);
      entryData.set(entry, {
        target: target,
        contentRect: rect(0, 0, box.width, box.height),
        borderBoxSize: Object.freeze(size),
        contentBoxSize: Object.freeze(size.slice()),
        devicePixelContentBoxSize: Object.freeze([
          Object.freeze({ inlineSize: box.width * dpr, blockSize: box.height * dpr }),
        ]),
      });
      return entry;
    }
    var ro = observerCtor("ResizeObserver");
    defineProto(ro, "ResizeObserver", {
      observe: [
        function (target) {
          var s = stateOf(this);
          if (arguments.length < 1) throw callError("ResizeObserver", "observe", missing(1, 0));
          if (!isElement(target))
            throw callError("ResizeObserver", "observe", "parameter 1 is not of type 'Element'.");
          if (s.targets.indexOf(target) >= 0) return;
          s.targets.push(target);
          s.records.push(roRecord(target));
          schedule(this, s);
        },
        1,
      ],
      unobserve: [
        function (target) {
          var s = stateOf(this);
          if (arguments.length < 1) throw callError("ResizeObserver", "unobserve", missing(1, 0));
          if (!isElement(target))
            throw callError("ResizeObserver", "unobserve", "parameter 1 is not of type 'Element'.");
          removeTarget(s, target);
        },
        1,
      ],
      disconnect: [
        function () {
          var s = stateOf(this);
          s.targets = [];
          s.records = [];
          cancel(s);
        },
        0,
      ],
    });

    /* No DOM mutation feed exists to report from: observe validates like Chrome, records stay empty. */
    var mo = observerCtor("MutationObserver");
    globalThis.WebKitMutationObserver = mo;
    defineProto(mo, "MutationObserver", {
      observe: [
        function (target, options) {
          var s = stateOf(this);
          var o = options == null ? {} : options;
          if (arguments.length < 1) throw callError("MutationObserver", "observe", missing(1, 0));
          if (!isNode(target))
            throw callError("MutationObserver", "observe", "parameter 1 is not of type 'Node'.");
          if (
            !o.childList &&
            !o.attributes &&
            !o.characterData &&
            o.attributeOldValue === undefined &&
            o.attributeFilter === undefined &&
            o.characterDataOldValue === undefined
          )
            throw callError(
              "MutationObserver",
              "observe",
              "The options object must set at least one of 'attributes', 'characterData', or 'childList' to true.",
            );
          if (s.targets.indexOf(target) < 0) s.targets.push(target);
        },
        1,
      ],
      disconnect: [
        function () {
          var s = stateOf(this);
          s.targets = [];
          s.records = [];
        },
        0,
      ],
      takeRecords: [
        function () {
          var s = stateOf(this);
          var r = s.records;
          s.records = [];
          return r;
        },
        0,
      ],
    });

    var po = observerCtor("PerformanceObserver");
    defineProto(po, "PerformanceObserver", {
      observe: [
        function (options) {
          var s = stateOf(this);
          var o = options == null ? {} : options;
          if (o.entryTypes === undefined && o.type === undefined)
            throw callError(
              "PerformanceObserver",
              "observe",
              "An observe() call must include either entryTypes or type arguments.",
            );
          s.observing = true;
        },
        0,
      ],
      disconnect: [
        function () {
          var s = stateOf(this);
          s.observing = false;
          s.records = [];
        },
        0,
      ],
      takeRecords: [
        function () {
          var s = stateOf(this);
          var r = s.records;
          s.records = [];
          return r;
        },
        0,
      ],
    });
    var supportedEntryTypes = Object.freeze([
      "element",
      "event",
      "first-input",
      "largest-contentful-paint",
      "layout-shift",
      "long-animation-frame",
      "longtask",
      "mark",
      "measure",
      "navigation",
      "paint",
      "resource",
      "visibility-state",
    ]);
    Object.defineProperty(po, "supportedEntryTypes", {
      get: hostFn(
        "get supportedEntryTypes",
        function () {
          return supportedEntryTypes;
        },
        0,
      ),
      enumerable: true,
      configurable: true,
    });

    var rpo = observerCtor("ReportingObserver");
    defineProto(rpo, "ReportingObserver", {
      observe: [
        function () {
          stateOf(this).observing = true;
        },
        0,
      ],
      disconnect: [
        function () {
          stateOf(this).observing = false;
        },
        0,
      ],
      takeRecords: [
        function () {
          var s = stateOf(this);
          var r = s.records;
          s.records = [];
          return r;
        },
        0,
      ],
    });
  })();

  /* Illegal constructors. Omit TextEncoder (C native). MSE ctors are
   * hidden before this runs, so the PO snapshot sees throwers instead of engine-js classes. */
  var WINDOW_CTORS = [
    "PressureObserver",
    "Worker",
    "SharedWorker",
    "BroadcastChannel",
    "MessageChannel",
    "MessagePort",
    "Image",
    "Audio",
    "Option",
    "WebSocket",
    "Blob",
    "File",
    "FileReader",
    "FormData",
    "URLSearchParams",
    "TextDecoder",
    "Event",
    "CustomEvent",
    "MouseEvent",
    "KeyboardEvent",
    "PointerEvent",
    "TouchEvent",
    "ErrorEvent",
    "PromiseRejectionEvent",
    "DOMParser",
    "XMLSerializer",
    "Notification",
    "OffscreenCanvas",
    "Path2D",
    "ImageData",
    "ImageBitmap",
    "FileList",
    "DataTransfer",
    "ClipboardEvent",
    "StorageEvent",
    "MediaStream",
    "RTCPeerConnection",
    "AudioContext",
    "webkitAudioContext",
    "OfflineAudioContext",
    "AnalyserNode",
    "WebGLRenderingContext",
    "WebGL2RenderingContext",
    "PluginArray",
    "MimeTypeArray",
    "Plugin",
    "MimeType",
    "Screen",
    "Navigator",
    "Location",
    "History",
    "Document",
    "HTMLDocument",
    "Element",
    "HTMLElement",
    "HTMLIFrameElement",
    "HTMLCanvasElement",
    "HTMLImageElement",
    "HTMLMediaElement",
    "HTMLVideoElement",
    "HTMLAudioElement",
    "MediaSource",
    "SourceBuffer",
    "XMLHttpRequest",
    "ReadableStream",
    "ReadableStreamDefaultReader",
    "CanvasRenderingContext2D",
    "ShadowRoot",
    "Node",
    "NodeList",
    "HTMLCollection",
    "NamedNodeMap",
    "CSSStyleDeclaration",
    "DOMTokenList",
    "Range",
    "TreeWalker",
    "XPathResult",
    "XPathEvaluator",
    "MutationRecord",
    "Storage",
    "Headers",
    "Request",
    "Response",
    "AbortController",
    "AbortSignal",
    "CSSMediaRule",
    "Selection",
    "PerformanceEntry",
    "PerformanceNavigation",
    "PerformanceTiming",
    "Crypto",
    "SubtleCrypto",
    "IDBFactory",
    "CacheStorage",
    "TouchList",
    "CompressionStream",
    "DecompressionStream",
    "HighlightRegistry",
    "SVGForeignObjectElement",
    "SVGElement",
    "SVGSVGElement",
    "DOMException",
    "OscillatorNode",
    "GainNode",
    "BiquadFilterNode",
    "AudioBuffer",
    "AudioNode",
    "AudioParam",
    "AudioBufferSourceNode",
    "AudioDestinationNode",
    "AudioListener",
    "PeriodicWave",
    "PannerNode",
    "StereoPannerNode",
    "ChannelMergerNode",
    "ChannelSplitterNode",
    "ConstantSourceNode",
    "ConvolverNode",
    "DelayNode",
    "DynamicsCompressorNode",
    "IIRFilterNode",
    "WaveShaperNode",
    "MediaElementAudioSourceNode",
    "MediaStreamAudioSourceNode",
    "MediaStreamAudioDestinationNode",
    "AudioWorkletNode",
    "MediaRecorder",
    "Permissions",
    "PermissionStatus",
    "SpeechSynthesis",
    "SpeechSynthesisUtterance",
    "SpeechSynthesisEvent",
    "SpeechSynthesisErrorEvent",
    "NetworkInformation",
    "HTMLFencedFrameElement",
    "GPU",
    "GPUAdapter",
    "GPUDevice",
    "GPUBindGroup",
    "GPUBindGroupLayout",
    "GPUBuffer",
    "GPUCanvasContext",
    "GPUCommandEncoder",
    "GPUComputePipeline",
    "GPUPipelineLayout",
    "GPUQuerySet",
    "GPUQueue",
    "GPURenderPipeline",
    "GPUSampler",
    "GPUShaderModule",
    "GPUTexture",
    "GPUTextureView",
    "DOMPoint",
    "DOMPointReadOnly",
    "DOMRect",
    "DOMRectReadOnly",
    "DOMMatrix",
    "DOMMatrixReadOnly",
    "DOMQuad",
    "AbstractRange",
    "StaticRange",
    "CSSStyleSheet",
    "FontFace",
    "FontFaceSet",
    "MediaError",
    "VTTCue",
    "TextTrack",
    "TextTrackCue",
    "webkitSpeechRecognition",
    "SpeechRecognition",
    "HTMLFormElement",
    "HTMLAnchorElement",
    "HTMLInputElement",
    "HTMLTextAreaElement",
    "HTMLButtonElement",
    "HTMLDivElement",
    "HTMLSpanElement",
    "HTMLScriptElement",
    "InputEvent",
    "UIEvent",
    "FocusEvent",
    "WheelEvent",
    "CompositionEvent",
    "CSSMatrixComponent",
    "CSSTransformComponent",
    "CSSTranslate",
    "CSSRotate",
    "CSSScale",
    "CSSPerspective",
    "CSSTransformValue",
    "CSSStyleValue",
    "CSSNumericValue",
    "CSSUnitValue",
    "CSSUnparsedValue",
    "CSSKeywordValue",
    "MediaStreamTrack",
    "MediaStreamTrackAudioStats",
    "BarProp",
    "UserActivation",
    "SVGFEColorMatrixElement",
    "SVGFEComponentTransferElement",
    "SVGAnimatedNumberList",
    "XRAnchorSet",
    "XRWebGLBinding",
    "SVGGraphicsElement",
    "SVGComponentTransferFunctionElement",
  ];
  var WINDOW_FUNCS = [
    "fetchLater",
    "alert",
    "confirm",
    "prompt",
    "open",
    "close",
    "focus",
    "blur",
    "print",
    "stop",
    "moveTo",
    "moveBy",
    "resizeTo",
    "resizeBy",
    "scroll",
    "scrollTo",
    "scrollBy",
    "postMessage",
    "getSelection",
    "matchMedia",
    "createImageBitmap",
    "structuredClone",
    "reportError",
    "captureEvents",
    "releaseEvents",
    "webkitRequestAnimationFrame",
    "webkitCancelAnimationFrame",
  ];

  function illegalCtor(name) {
    var fn = globalThis[name];
    var proto;
    if (typeof fn !== "function") {
      fn = native(name, function () {
        throw new TypeError("Illegal constructor");
      });
      globalThis[name] = fn;
    }
    if (
      name !== "HTMLElement" &&
      name !== "Element" &&
      name !== "Node" &&
      name !== "HTMLIFrameElement"
    ) {
      proto = fn.prototype;
      if (!proto || typeof proto !== "object") {
        proto = {};
        fn.prototype = proto;
      }
    }
    return fn;
  }
  /* QuickJS has no URL; mse.ts leaves a plain object carrying the blob URL statics. */
  (function installUrl() {
    if (typeof globalThis.URL === "function") return;
    var blob = globalThis.URL || {};
    var urls = new WeakMap();
    var params = new WeakMap();
    var PORTS = {
      "http:": "80",
      "https:": "443",
      "ws:": "80",
      "wss:": "443",
      "ftp:": "21",
      "file:": "",
    };
    var ABS =
      /^([a-zA-Z][a-zA-Z0-9+.-]*:)(?:\/\/(?:([^:@\/?#]*)(?::([^@\/?#]*))?@)?(\[[^\]]*\]|[^:\/?#]*)(?::(\d*))?)?([^?#]*)(\?[^#]*)?(#.*)?$/;
    var REL = /^([^?#]*)(\?[^#]*)?(#.*)?$/;
    function special(r) {
      return Object.prototype.hasOwnProperty.call(PORTS, r.protocol);
    }
    function removeDots(path) {
      var out = [];
      var segs = path.split("/");
      segs.forEach(function (seg, i) {
        var last = i === segs.length - 1;
        if (seg === ".." || seg === ".") {
          if (seg === ".." && out.length > 1) out.pop();
          if (last) out.push("");
        } else out.push(seg);
      });
      return out.join("/");
    }
    function finish(r) {
      r.protocol = r.protocol.toLowerCase();
      if (special(r)) {
        r.hostname = r.hostname.toLowerCase();
        if (r.port === PORTS[r.protocol]) r.port = "";
        r.pathname = removeDots(r.pathname || "/");
        if (r.pathname.charAt(0) !== "/") r.pathname = "/" + r.pathname;
      } else if (r.pathname.charAt(0) === "/") r.pathname = removeDots(r.pathname);
      r.pathname = r.pathname.replace(/ /g, "%20");
      r.search = r.search === "?" ? "" : r.search.replace(/ /g, "%20");
      r.hash = r.hash === "#" ? "" : r.hash.replace(/ /g, "%20");
      return r;
    }
    function parse(input, base) {
      var s = String(input)
        .replace(/^[\u0000- ]+|[\u0000- ]+$/g, "")
        .replace(/[\t\n\r]/g, "");
      var m = ABS.exec(s);
      var r, rel, b;
      if (m && (m[4] !== undefined || !PORTS.hasOwnProperty(m[1].toLowerCase()))) {
        r = {
          protocol: m[1],
          username: m[2] || "",
          password: m[3] || "",
          hostname: m[4] || "",
          port: m[5] || "",
          pathname: m[6] || "",
          search: m[7] || "",
          hash: m[8] || "",
          authority: m[4] !== undefined,
        };
        if (special(r) && !r.hostname && r.protocol !== "file:") return null;
        return finish(r);
      }
      if (base === undefined) return null;
      b = base instanceof Object && urls.get(base) ? urls.get(base) : parse(base);
      if (!b) return null;
      if (s.slice(0, 2) === "//") return parse(b.protocol + s);
      rel = REL.exec(s);
      r = Object.assign({}, b, { hash: rel[3] || "" });
      if (rel[1]) {
        r.pathname = rel[1].charAt(0) === "/" ? rel[1] : b.pathname.replace(/[^\/]*$/, "") + rel[1];
        r.search = rel[2] || "";
      } else if (rel[2] !== undefined) r.search = rel[2];
      return finish(r);
    }
    function host(r) {
      return r.hostname + (r.port ? ":" + r.port : "");
    }
    function href(r) {
      var auth = "";
      if (r.authority || special(r)) {
        auth = "//";
        if (r.username || r.password)
          auth += r.username + (r.password ? ":" + r.password : "") + "@";
        auth += host(r);
      }
      return r.protocol + auth + r.pathname + r.search + r.hash;
    }
    function own(self, what) {
      var r = (what === "url" ? urls : params).get(self);
      if (!r) throw new TypeError("Illegal invocation");
      return r;
    }
    function invalid(what, s) {
      return new TypeError("Failed to " + what + ": Invalid URL" + (s ? " '" + s + "'" : ""));
    }
    function encodeForm(v) {
      return encodeURIComponent(v)
        .replace(/[!'()~]/g, function (c) {
          return "%" + c.charCodeAt(0).toString(16).toUpperCase();
        })
        .replace(/%20/g, "+");
    }
    function decodeForm(v) {
      try {
        return decodeURIComponent(v.replace(/\+/g, " "));
      } catch (e) {
        return v;
      }
    }
    function parseQuery(q) {
      var list = [];
      String(q)
        .replace(/^\?/, "")
        .split("&")
        .forEach(function (pair) {
          if (!pair) return;
          var i = pair.indexOf("=");
          list.push(
            i < 0
              ? [decodeForm(pair), ""]
              : [decodeForm(pair.slice(0, i)), decodeForm(pair.slice(i + 1))],
          );
        });
      return list;
    }
    function serialize(list) {
      return list
        .map(function (kv) {
          return encodeForm(kv[0]) + "=" + encodeForm(kv[1]);
        })
        .join("&");
    }
    function method(proto, name, length, fn) {
      hide(proto, name, __nativeFn(name, fn, length));
      Object.defineProperty(proto, name, { enumerable: true });
    }
    function accessor(proto, name, get, set) {
      Object.defineProperty(proto, name, {
        configurable: true,
        enumerable: true,
        get: __nativeFn("get " + name, get, 0),
        set: set ? __nativeFn("set " + name, set, 1) : undefined,
      });
    }
    function ctor(name, length, make) {
      var C = __nativeFn(
        name,
        function () {
          if (typeof this !== "function")
            throw new TypeError(
              "Failed to construct '" +
                name +
                "': Please use the 'new' operator, this DOM object constructor cannot be called as a function.",
            );
          return make.apply(null, arguments);
        },
        length,
      );
      Object.defineProperty(C, "prototype", { value: {} });
      return C;
    }

    var Params = ctor("URLSearchParams", 0, function (init) {
      var self = Object.create(Params.prototype);
      var list = [];
      if (init instanceof Object && params.get(init)) list = params.get(init).list.slice();
      else if (Array.isArray(init))
        list = init.map(function (kv) {
          return [String(kv[0]), String(kv[1])];
        });
      else if (init !== null && typeof init === "object")
        list = Object.keys(init).map(function (k) {
          return [k, String(init[k])];
        });
      else if (init !== undefined) list = parseQuery(init);
      params.set(self, { list: list, url: null });
      return self;
    });
    var pp = Params.prototype;
    function update(p) {
      if (p.url) {
        var q = serialize(p.list);
        urls.get(p.url).search = q ? "?" + q : "";
      }
    }
    accessor(pp, "size", function () {
      return own(this).list.length;
    });
    method(pp, "append", 2, function (k, v) {
      var p = own(this);
      p.list.push([String(k), String(v)]);
      update(p);
    });
    method(pp, "delete", 1, function (k) {
      var p = own(this);
      p.list = p.list.filter(function (kv) {
        return kv[0] !== String(k);
      });
      update(p);
    });
    method(pp, "get", 1, function (k) {
      var hit = own(this).list.filter(function (kv) {
        return kv[0] === String(k);
      })[0];
      return hit ? hit[1] : null;
    });
    method(pp, "getAll", 1, function (k) {
      return own(this)
        .list.filter(function (kv) {
          return kv[0] === String(k);
        })
        .map(function (kv) {
          return kv[1];
        });
    });
    method(pp, "has", 1, function (k) {
      return own(this).list.some(function (kv) {
        return kv[0] === String(k);
      });
    });
    method(pp, "set", 2, function (k, v) {
      var p = own(this);
      var seen = false;
      p.list = p.list.filter(function (kv) {
        if (kv[0] !== String(k)) return true;
        if (seen) return false;
        kv[1] = String(v);
        return (seen = true);
      });
      if (!seen) p.list.push([String(k), String(v)]);
      update(p);
    });
    method(pp, "sort", 0, function () {
      var p = own(this);
      p.list = p.list
        .map(function (kv, i) {
          return [kv, i];
        })
        .sort(function (a, b) {
          return a[0][0] < b[0][0] ? -1 : a[0][0] > b[0][0] ? 1 : a[1] - b[1];
        })
        .map(function (e) {
          return e[0];
        });
      update(p);
    });
    method(pp, "toString", 0, function () {
      return serialize(own(this).list);
    });
    method(pp, "entries", 0, function () {
      return own(this)
        .list.map(function (kv) {
          return [kv[0], kv[1]];
        })
        [Symbol.iterator]();
    });
    method(pp, "forEach", 1, function (fn, that) {
      var self = this;
      own(this).list.forEach(function (kv) {
        fn.call(that, kv[1], kv[0], self);
      });
    });
    method(pp, "keys", 0, function () {
      return own(this)
        .list.map(function (kv) {
          return kv[0];
        })
        [Symbol.iterator]();
    });
    method(pp, "values", 0, function () {
      return own(this)
        .list.map(function (kv) {
          return kv[1];
        })
        [Symbol.iterator]();
    });
    hide(pp, "constructor", Params);
    hide(pp, Symbol.iterator, pp.entries);
    Object.defineProperty(pp, Symbol.toStringTag, { value: "URLSearchParams", configurable: true });

    var Url = ctor("URL", 1, function (input, base) {
      var r = parse(input, base);
      if (!r) throw invalid("construct 'URL'");
      var self = Object.create(Url.prototype);
      urls.set(self, r);
      return self;
    });
    var up = Url.prototype;
    function searchParamsOf(self) {
      var r = own(self, "url");
      if (!r.params) {
        r.params = new Params(r.search);
        params.get(r.params).url = self;
      }
      return r.params;
    }
    function setSearch(self, v) {
      var r = own(self, "url");
      v = String(v);
      r.search = !v || v === "?" ? "" : (v.charAt(0) === "?" ? "" : "?") + v;
      if (r.params) params.get(r.params).list = parseQuery(r.search);
    }
    accessor(up, "origin", function () {
      var r = own(this, "url");
      return special(r) && r.protocol !== "file:" ? r.protocol + "//" + host(r) : "null";
    });
    [
      [
        "protocol",
        function (r, v) {
          if (/^[a-zA-Z][a-zA-Z0-9+.-]*:?$/.test(v))
            r.protocol = v.replace(/:?$/, ":").toLowerCase();
        },
      ],
      [
        "username",
        function (r, v) {
          r.username = v;
        },
      ],
      [
        "password",
        function (r, v) {
          r.password = v;
        },
      ],
      [
        "host",
        function (r, v) {
          var m = /^([^:\/?#]*)(?::(\d*))?/.exec(v);
          r.hostname = m[1];
          if (m[2] !== undefined) r.port = m[2];
          finish(r);
        },
      ],
      [
        "hostname",
        function (r, v) {
          r.hostname = v;
          finish(r);
        },
      ],
      [
        "port",
        function (r, v) {
          r.port = String(parseInt(v, 10) >= 0 ? parseInt(v, 10) : "");
          finish(r);
        },
      ],
      [
        "pathname",
        function (r, v) {
          r.pathname = v;
          finish(r);
        },
      ],
    ].forEach(function (entry) {
      var key = entry[0];
      accessor(
        up,
        key,
        function () {
          var r = own(this, "url");
          return key === "host" ? host(r) : r[key];
        },
        function (v) {
          entry[1](own(this, "url"), String(v));
        },
      );
    });
    accessor(
      up,
      "search",
      function () {
        return own(this, "url").search;
      },
      function (v) {
        setSearch(this, v);
      },
    );
    accessor(up, "searchParams", function () {
      return searchParamsOf(this);
    });
    accessor(
      up,
      "hash",
      function () {
        return own(this, "url").hash;
      },
      function (v) {
        v = String(v);
        own(this, "url").hash = !v || v === "#" ? "" : (v.charAt(0) === "#" ? "" : "#") + v;
      },
    );
    accessor(
      up,
      "href",
      function () {
        return href(own(this, "url"));
      },
      function (v) {
        var old = own(this, "url");
        var r = parse(v);
        if (!r) throw invalid("set the 'href' property on 'URL'", String(v));
        urls.set(this, r);
        if (old.params) {
          r.params = old.params;
          params.get(r.params).list = parseQuery(r.search);
        }
      },
    );
    method(up, "toJSON", 0, function () {
      return href(own(this, "url"));
    });
    method(up, "toString", 0, function () {
      return href(own(this, "url"));
    });
    hide(up, "constructor", Url);
    Object.defineProperty(up, Symbol.toStringTag, { value: "URL", configurable: true });
    method(Url, "canParse", 1, function (input, base) {
      return !!parse(input, base);
    });
    method(Url, "parse", 1, function (input, base) {
      try {
        return Url.apply(Url, arguments);
      } catch (e) {
        return null;
      }
    });
    ["createObjectURL", "revokeObjectURL"].forEach(function (k) {
      if (typeof blob[k] === "function") method(Url, k, 1, blob[k]);
    });
    globalThis.URL = Url;
    globalThis.URLSearchParams = Params;
  })();
  var ci;
  for (ci = 0; ci < WINDOW_CTORS.length; ci++) illegalCtor(WINDOW_CTORS[ci]);
  if (globalThis.HTMLMediaElement && globalThis.HTMLMediaElement.prototype) {
    /* Chrome's answers for a type without codecs= (fixed MIME list, never the device); BotGuard
     * probes audio/x-m4a in the mill. */
    var BARE_TYPE_ANSWERS = {
      "audio/x-m4a": "maybe",
      "audio/mp4": "maybe",
      "audio/webm": "maybe",
      "audio/mpeg": "probably",
      "audio/ogg": "maybe",
      "video/mp4": "maybe",
      "video/webm": "maybe",
      "audio/aac": "probably",
      "audio/wav": "maybe",
      "audio/flac": "probably",
    };
    globalThis.HTMLMediaElement.prototype.canPlayType = native("canPlayType", function (type) {
      var tag = this && this.tagName;
      if (tag !== "VIDEO" && tag !== "AUDIO") throw new TypeError("Illegal invocation");
      type = String(type == null ? "" : type).toLowerCase();
      if (!type) return "";
      if (type.indexOf("codecs=") < 0) {
        var bare = BARE_TYPE_ANSWERS[type.split(";")[0].trim()];
        if (typeof bare === "string") return bare;
      }
      var avc = /avc1\.([0-9a-f]{6})/i.exec(type);
      if (avc && typeof __mseAvcMaxLevel === "function") {
        var lvl = parseInt(avc[1].slice(4, 6), 16);
        if (lvl > __mseAvcMaxLevel()) return "";
      }
      if (/avc1|mp4a\.40|vp09|vp8|vp9|opus|flac|av01/.test(type)) return "probably";
      if (
        type.indexOf("video/mp4") === 0 ||
        type.indexOf("audio/mp4") === 0 ||
        type.indexOf("video/webm") === 0 ||
        type.indexOf("audio/webm") === 0 ||
        type.indexOf("audio/mpeg") === 0 ||
        type.indexOf("video/ogg") === 0 ||
        type.indexOf("audio/ogg") === 0
      )
        return "maybe";
      return "";
    });
  }
  /* Chrome's event interfaces: parent, constructor length (0 is not constructable), and own
   * prototype members in Chrome's order. "x" is an attribute defaulting to null, "x:s" to "",
   * "x:0" to 0, "x:f" to false, "x:a" to []; "x=n" is a constant, "x()" an operation. */
  var EVENT_CLASSES = {
    Event: [
      "",
      1,
      "type:s target currentTarget eventPhase:0 bubbles:f cancelable:f defaultPrevented:f composed:f timeStamp:0 srcElement returnValue:f cancelBubble:f NONE=0 CAPTURING_PHASE=1 AT_TARGET=2 BUBBLING_PHASE=3 composedPath() initEvent() preventDefault() stopImmediatePropagation() stopPropagation()",
    ],
    UIEvent: ["Event", 1, "view detail:0 sourceCapabilities which:0 initUIEvent() pseudoTarget"],
    MouseEvent: [
      "UIEvent",
      1,
      "screenX:0 screenY:0 clientX:0 clientY:0 ctrlKey:f shiftKey:f altKey:f metaKey:f button:0 buttons:0 relatedTarget pageX:0 pageY:0 x:0 y:0 offsetX:0 offsetY:0 movementX:0 movementY:0 fromElement toElement layerX:0 layerY:0 getModifierState() initMouseEvent()",
    ],
    KeyboardEvent: [
      "UIEvent",
      1,
      "key:s code:s location:0 ctrlKey:f shiftKey:f altKey:f metaKey:f repeat:f isComposing:f charCode:0 keyCode:0 DOM_KEY_LOCATION_STANDARD=0 DOM_KEY_LOCATION_LEFT=1 DOM_KEY_LOCATION_RIGHT=2 DOM_KEY_LOCATION_NUMPAD=3 getModifierState() initKeyboardEvent()",
    ],
    CustomEvent: ["Event", 1, "detail initCustomEvent()"],
    CompositionEvent: ["UIEvent", 1, "data:s initCompositionEvent()"],
    TextEvent: ["UIEvent", 0, "data:s initTextEvent()"],
    FocusEvent: ["UIEvent", 1, "relatedTarget"],
    MessageEvent: [
      "Event",
      1,
      "data origin:s lastEventId:s source ports:a userActivation initMessageEvent()",
    ],
    StorageEvent: ["Event", 1, "key oldValue newValue url:s storageArea initStorageEvent()"],
    HashChangeEvent: ["Event", 1, "oldURL:s newURL:s"],
    BeforeUnloadEvent: ["Event", 0, "returnValue:s"],
    DeviceMotionEvent: [
      "Event",
      1,
      "acceleration accelerationIncludingGravity rotationRate interval:0",
    ],
    DeviceOrientationEvent: ["Event", 1, "alpha beta gamma absolute:f"],
    DragEvent: ["MouseEvent", 1, "dataTransfer"],
  };
  /* The arguments after (type, bubbles, cancelable); "-" is one Chrome ignores. */
  var EVENT_INIT_ARGS = {
    initEvent: "",
    initUIEvent: "view detail",
    initMouseEvent:
      "view detail screenX screenY clientX clientY ctrlKey altKey shiftKey metaKey button relatedTarget",
    initKeyboardEvent: "view - location ctrlKey altKey shiftKey metaKey",
    initCustomEvent: "detail",
    initCompositionEvent: "view data",
    initTextEvent: "view data",
    initMessageEvent: "data origin lastEventId source ports",
    initStorageEvent: "key oldValue newValue url storageArea",
  };
  var EVENT_ALIASES = {
    srcElement: "target",
    x: "clientX",
    y: "clientY",
    pageX: "clientX",
    pageY: "clientY",
    offsetX: "clientX",
    offsetY: "clientY",
    layerX: "clientX",
    layerY: "clientY",
  };
  var EVENT_MODIFIERS = { Control: "ctrlKey", Shift: "shiftKey", Alt: "altKey", Meta: "metaKey" };
  var EVENT_GETTERS = {
    "Event.returnValue": function (d) {
      return !d.defaultPrevented;
    },
    "BeforeUnloadEvent.returnValue": function (d) {
      return d.unloadMessage === undefined ? "" : d.unloadMessage;
    },
    "UIEvent.which": function (d, ev) {
      return ev instanceof globalThis.MouseEvent ? (d.button || 0) + 1 : 0;
    },
  };
  var EVENT_SETTERS = {
    "Event.returnValue": function (d, v) {
      if (!v && d.cancelable) d.defaultPrevented = true;
    },
    "Event.cancelBubble": function (d, v) {
      if (v) d.cancelBubble = true;
    },
    "BeforeUnloadEvent.returnValue": function (d, v) {
      d.unloadMessage = String(v);
    },
  };
  var EVENT_OPS = {
    composedPath: function (d) {
      return d.path ? d.path.slice() : [];
    },
    preventDefault: function (d) {
      if (d.cancelable) d.defaultPrevented = true;
    },
    stopImmediatePropagation: function (d) {
      d.stopNow = true;
    },
    stopPropagation: function (d) {
      d.cancelBubble = true;
    },
    getModifierState: function (d, key) {
      return !!d[EVENT_MODIFIERS[key]];
    },
  };
  var eventDefaults = {};
  /* createEvent's names, lowercased: every class but the aliases below takes its own name. */
  var eventProtoByName = Object.create(null);
  var isTrustedGetter = __nativeFn(
    "get isTrusted",
    function () {
      return eventOf(this).trusted === true;
    },
    0,
  );
  function newEvent(proto, data) {
    var ev = Object.create(proto);
    var perf = globalThis.performance;
    Object.defineProperty(ev, "isTrusted", { get: isTrustedGetter, enumerable: true });
    data.timeStamp = perf && typeof perf.now === "function" ? platformNow.call(perf) : 0;
    eventData.set(ev, data);
    return ev;
  }
  function eventCoerce(dflt, v) {
    if (dflt === 0) return +v || 0;
    if (dflt === false) return !!v;
    return dflt === "" ? String(v) : v;
  }
  function eventCtor(name, length) {
    var prefix = "Failed to construct '" + name + "': ";
    return __nativeFn(
      name,
      function (type, init) {
        var data, key;
        if (!length) throw new TypeError(prefix + "Illegal constructor");
        if (typeof this !== "function")
          throw new TypeError(
            prefix +
              "Please use the 'new' operator, this DOM object constructor cannot be called as a function.",
          );
        if (!arguments.length)
          throw new TypeError(prefix + "1 argument required, but only 0 present.");
        data = { type: String(type), init: true };
        if (init !== null && init !== undefined) {
          for (key in eventDefaults[name]) {
            if (key !== "type" && !EVENT_ALIASES[key] && init[key] !== undefined)
              data[key] = eventCoerce(eventDefaults[name][key], init[key]);
          }
        }
        return newEvent(this.prototype, data);
      },
      length,
    );
  }
  function eventInitOp(name, owner) {
    var fields = EVENT_INIT_ARGS[name] ? EVENT_INIT_ARGS[name].split(" ") : [];
    return function (d, type, bubbles, cancelable) {
      var i, v;
      if (d.dispatching) return;
      d.init = true;
      d.type = String(type);
      d.bubbles = !!bubbles;
      d.cancelable = !!cancelable;
      d.defaultPrevented = d.cancelBubble = d.stopNow = false;
      d.target = null;
      for (i = 0; i < fields.length; i++) {
        v = arguments[i + 4];
        if (fields[i] !== "-" && v !== undefined)
          d[fields[i]] = eventCoerce(eventDefaults[owner][fields[i]], v);
      }
    };
  }
  function defineEventClass(name) {
    var spec = EVENT_CLASSES[name];
    var parent = spec[0] ? globalThis[spec[0]] : null;
    var C = eventCtor(name, spec[1]);
    var proto = Object.create(parent ? parent.prototype : Object.prototype);
    var defaults = (eventDefaults[name] = Object.create(parent ? eventDefaults[spec[0]] : null));
    spec[2].split(" ").forEach(function (member) {
      var m = /^(\w+)(?::(\w)|=(\d)|(\(\)))?$/.exec(member);
      var key = m[1];
      var dflt, fn, takesArg;
      if (m[3] !== undefined) {
        var constant = { value: +m[3], enumerable: true };
        Object.defineProperty(proto, key, constant);
        Object.defineProperty(C, key, constant);
      } else if (m[4]) {
        fn = EVENT_OPS[key] || eventInitOp(key, name);
        takesArg = !EVENT_OPS[key] || key === "getModifierState";
        hide(
          proto,
          key,
          __nativeFn(
            key,
            function () {
              var args = [eventOf(this)];
              if (takesArg && !arguments.length)
                throw new TypeError(
                  "Failed to execute '" +
                    key +
                    "' on '" +
                    name +
                    "': 1 argument required, but only 0 present.",
                );
              args.push.apply(args, arguments);
              return fn.apply(this, args);
            },
            takesArg ? 1 : 0,
          ),
        );
      } else {
        dflt = { s: "", 0: 0, f: false, a: [] }[m[2]];
        if (dflt === undefined) dflt = null;
        defaults[key] = dflt;
        Object.defineProperty(proto, key, {
          configurable: true,
          enumerable: true,
          get: __nativeFn(
            "get " + key,
            function () {
              var d = eventOf(this);
              var getter = EVENT_GETTERS[name + "." + key];
              var field = EVENT_ALIASES[key] || key;
              if (getter) return getter(d, this);
              return field in d ? d[field] : Array.isArray(dflt) ? [] : dflt;
            },
            0,
          ),
          set: EVENT_SETTERS[name + "." + key]
            ? __nativeFn(
                "set " + key,
                function (v) {
                  EVENT_SETTERS[name + "." + key](eventOf(this), v);
                },
                1,
              )
            : undefined,
        });
      }
    });
    hide(proto, "constructor", C);
    Object.defineProperty(proto, Symbol.toStringTag, { value: name, configurable: true });
    Object.defineProperty(C, "prototype", { value: proto });
    if (parent) Object.setPrototypeOf(C, parent);
    hide(globalThis, name, C);
    eventProtoByName[name.toLowerCase()] = proto;
  }
  Object.keys(EVENT_CLASSES).forEach(defineEventClass);
  eventProtoByName.events =
    eventProtoByName.htmlevents =
    eventProtoByName.svgevents =
      eventProtoByName.event;
  eventProtoByName.uievents = eventProtoByName.uievent;
  eventProtoByName.mouseevents = eventProtoByName.mouseevent;
  eventProtoByName.keyboardevents = eventProtoByName.keyboardevent;
  for (ci = 0; ci < WINDOW_FUNCS.length; ci++) {
    if (typeof globalThis[WINDOW_FUNCS[ci]] !== "function") {
      globalThis[WINDOW_FUNCS[ci]] = native(WINDOW_FUNCS[ci], function () {
        return null;
      });
    }
  }
  globalThis.matchMedia = native("matchMedia", function matchMedia(q) {
    var media = String(q == null ? "" : q);
    return {
      matches: /^\((any-)?(pointer:fine|hover:hover)\)$/.test(
        media.replace(/\s/g, "").toLowerCase(),
      ),
      media: media,
      addListener: native("addListener", function () {}),
      removeListener: native("removeListener", function () {}),
      addEventListener: globalThis.addEventListener,
      removeEventListener: globalThis.removeEventListener,
    };
  });

  /* Members land on htmlElProto; idl_stubs.raw.js moves them to the interface that declares them. */
  var nodeProto = {};
  var elementProto = Object.create(nodeProto);
  var htmlElProto = Object.create(elementProto);
  function ensureCtor(name) {
    if (typeof globalThis[name] !== "function")
      globalThis[name] = native(name, function () {
        throw new TypeError("Illegal constructor");
      });
  }
  ensureCtor("HTMLElement");
  ensureCtor("Element");
  ensureCtor("Node");
  ensureCtor("HTMLIFrameElement");
  globalThis.HTMLElement.prototype = htmlElProto;
  globalThis.Element.prototype = elementProto;
  globalThis.Node.prototype = nodeProto;
  var iframeProto = Object.create(htmlElProto);
  globalThis.HTMLIFrameElement.prototype = iframeProto;

  if (!globalThis.crypto) globalThis.crypto = {};
  if (typeof globalThis.crypto.getRandomValues !== "function") {
    globalThis.crypto.getRandomValues = native("getRandomValues", function (a) {
      var i;
      for (i = 0; i < a.length; i++) a[i] = Math.floor(Math.random() * 256);
      return a;
    });
  }

  /* Chrome CSSStyleDeclaration is not an Array: join/push are undefined. */
  var NODE_CONST = {
    ELEMENT_NODE: 1,
    ATTRIBUTE_NODE: 2,
    TEXT_NODE: 3,
    CDATA_SECTION_NODE: 4,
    ENTITY_REFERENCE_NODE: 5,
    ENTITY_NODE: 6,
    PROCESSING_INSTRUCTION_NODE: 7,
    COMMENT_NODE: 8,
    DOCUMENT_NODE: 9,
    DOCUMENT_TYPE_NODE: 10,
    DOCUMENT_FRAGMENT_NODE: 11,
    NOTATION_NODE: 12,
    DOCUMENT_POSITION_DISCONNECTED: 1,
    DOCUMENT_POSITION_PRECEDING: 2,
    DOCUMENT_POSITION_FOLLOWING: 4,
    DOCUMENT_POSITION_CONTAINS: 8,
    DOCUMENT_POSITION_CONTAINED_BY: 16,
    DOCUMENT_POSITION_IMPLEMENTATION_SPECIFIC: 32,
  };

  function hide(obj, key, val) {
    Object.defineProperty(obj, key, {
      configurable: true,
      enumerable: false,
      writable: true,
      value: val,
    });
  }

  /* Fresh prototype: same behavior as src, but function identity and
   * toString are ours (native trampoline), not QuickJS builtins. */
  function ownNativeProto(src) {
    var p = {},
      names,
      i,
      n,
      orig;
    names = Object.getOwnPropertyNames(src);
    for (i = 0; i < names.length; i++) {
      n = names[i];
      if (n === "constructor" || n === "arguments" || n === "caller") continue;
      orig = src[n];
      if (typeof orig !== "function") continue;
      hide(
        p,
        n,
        native(
          n,
          (function (fn) {
            return function () {
              return fn.apply(this, arguments);
            };
          })(orig),
        ),
      );
    }
    return p;
  }

  /* Chrome's layout of a youtube.com page (body font 10px Roboto, 1280px wide), enough to answer
   * size/offset probes: detached or display:none is 0, a block fills its container (or its style
   * width) and wraps words, inlines flow left to right with their margins. Derived from the DOM and
   * styles alone, so a BotGuard replay recomputes them exactly. Checked against Chrome on macOS.
   * ROBOTO_10PX: Chrome's advances for U+0020..U+007E. */
  var ROBOTO_10PX = [
    2.4805, 2.5781, 3.2031, 6.1572, 5.6201, 7.3242, 6.2207, 1.748, 3.4229, 3.4814, 4.3066, 5.6738,
    1.9678, 2.7637, 2.6367, 4.126, 5.6201, 5.6201, 5.6201, 5.6201, 5.6201, 5.6201, 5.6201, 5.6201,
    5.6201, 5.6201, 2.4219, 2.1143, 5.083, 5.4883, 5.2295, 4.7266, 8.9795, 6.5234, 6.2305, 6.5088,
    6.5625, 5.6836, 5.5273, 6.8115, 7.1338, 2.7197, 5.5176, 6.2744, 5.3857, 8.7305, 7.1338, 6.8799,
    6.3086, 6.8799, 6.1621, 5.9375, 5.9668, 6.4844, 6.3672, 8.8721, 6.2695, 6.0059, 5.9912, 2.6514,
    4.1064, 2.6514, 4.1797, 4.5117, 3.0908, 5.4395, 5.6152, 5.2344, 5.6396, 5.3027, 3.4766, 5.6152,
    5.5078, 2.4316, 2.3926, 5.0684, 2.4316, 8.7695, 5.5225, 5.7031, 5.6152, 5.6836, 3.3887, 5.1611,
    3.2715, 5.5127, 4.8438, 7.5146, 4.9609, 4.7314, 4.9609, 3.3838, 2.4414, 3.3838, 6.8018,
  ];
  var MONO_ADVANCE = 0.60205078125; // em
  var ROBOTO_LINE = 1.171875; // em
  var INLINE_TAGS = {
    SPAN: 1,
    A: 1,
    B: 1,
    I: 1,
    EM: 1,
    STRONG: 1,
    SMALL: 1,
    FONT: 1,
    LABEL: 1,
    CODE: 1,
    ABBR: 1,
    SUB: 1,
    SUP: 1,
    U: 1,
    S: 1,
    Q: 1,
    CITE: 1,
    MARK: 1,
    TIME: 1,
    BDI: 1,
    BDO: 1,
    KBD: 1,
    VAR: 1,
    SAMP: 1,
    BR: 1,
    IMG: 1,
  };
  var HIDDEN_TAGS = {
    SCRIPT: 1,
    STYLE: 1,
    LINK: 1,
    META: 1,
    TITLE: 1,
    HEAD: 1,
    TEMPLATE: 1,
    NOSCRIPT: 1,
  };
  var REPLACED_SIZE = { IFRAME: [304, 154], CANVAS: [300, 150], VIDEO: [300, 150] };
  var LAYOUT_PROPS = [
    "offsetWidth",
    "offsetHeight",
    "clientWidth",
    "clientHeight",
    "scrollWidth",
    "scrollHeight",
    "offsetLeft",
  ];

  function tagOf(el) {
    return String(el.tagName || "").toUpperCase();
  }

  var DASHED = {};

  function dashed(camel) {
    var d = DASHED[camel];
    if (d === undefined)
      d = DASHED[camel] = camel.replace(/[A-Z]/g, function (c) {
        return "-" + c.toLowerCase();
      });
    return d;
  }

  function css(el, camel) {
    var key, memo, value;
    if (layoutMemo === null) return computeCss(el, camel);
    key = "css " + camel;
    memo = layoutMemo[key] || (layoutMemo[key] = new Map());
    if (memo.has(el)) return memo.get(el);
    value = computeCss(el, camel);
    memo.set(el, value);
    return value;
  }

  function computeCss(el, camel) {
    var st = el && el.nodeType === 1 ? el.style : null;
    var v;
    if (!st || typeof st !== "object") return "";
    v = st[camel];
    if (v == null || v === "") v = st[dashed(camel)];
    return v == null
      ? ""
      : String(v)
          .replace(/\s*!important\s*$/i, "")
          .trim();
  }

  function inheritedCss(el, camel) {
    var n, v;
    for (n = el; n && n.nodeType === 1; n = n.parentNode) {
      v = css(n, camel);
      if (v) return v;
    }
    return "";
  }

  function px(v) {
    var m = /^(-?[\d.]+)px$/.exec(v);
    return m ? +m[1] : null;
  }

  /* Per-element results, kept only for the outermost layout call: one read lays out each node once
     (uncached, every node re-walked its ancestors: ~18 s of BotGuard on the player DOM in QuickJS),
     and the next read sees DOM changes made since. */
  var layoutMemo = null;

  /* BotGuard reads layout only while its program loads, before the snapshot; the player UI reads
     hundreds of sizes afterwards, and those keep the cheap zero answers (see endLayoutProbes). */
  var layoutProbes = true;

  function cached(kind, el, compute) {
    var outer = layoutMemo === null,
      memo;
    if (outer) layoutMemo = {};
    try {
      memo = layoutMemo[kind] || (layoutMemo[kind] = new Map());
      if (memo.has(el)) return memo.get(el);
      var value = compute(el);
      memo.set(el, value);
      return value;
    } finally {
      if (outer) layoutMemo = null;
    }
  }

  function fontOf(el) {
    return cached("font", el, computeFont);
  }

  function rendered(el) {
    return cached("rendered", el, computeRendered);
  }

  function boxWidth(el) {
    return cached("width", el, computeBoxWidth);
  }

  function layoutBox(el) {
    return cached("box", el, computeLayoutBox);
  }

  function offsetLeftOf(el) {
    return cached("left", el, computeOffsetLeft);
  }

  function computeFont(el) {
    var size = px(inheritedCss(el, "fontSize")) || 10;
    var mono = /monospace/i.test(inheritedCss(el, "fontFamily"));
    var lh = px(inheritedCss(el, "lineHeight"));
    return { size: size, mono: mono, line: lh != null ? lh : size * ROBOTO_LINE };
  }

  function advance(code, font) {
    if (font.mono) return MONO_ADVANCE * font.size;
    if (code >= 32 && code < 127) return (ROBOTO_10PX[code - 32] * font.size) / 10;
    if (code >= 0x2e80) return font.size;
    return 0.56 * font.size;
  }

  function collapse(text) {
    return String(text).replace(/\s+/g, " ");
  }

  function textWidth(text, font) {
    var w = 0,
      i;
    for (i = 0; i < text.length; i++) w += advance(text.charCodeAt(i), font);
    return w;
  }

  function lineCount(text, font, width, hyphenate) {
    var words = collapse(text).trim().split(" "),
      space = advance(32, font),
      lines = 1,
      x = 0,
      i,
      ww,
      extra;
    if (!words[0]) return 0;
    for (i = 0; i < words.length; i++) {
      ww = textWidth(words[i], font);
      if (x > 0 && x + space + ww > width) {
        lines++;
        x = 0;
      } else if (x > 0) x += space;
      if (ww > width && hyphenate && width > 0) {
        extra = Math.ceil(ww / width) - 1;
        lines += extra;
        x = ww - extra * width;
      } else x += ww;
    }
    return lines;
  }

  function styleDisplay(el) {
    return css(el, "display");
  }

  function isInline(el) {
    var d = styleDisplay(el);
    var pos = css(el, "position");
    if (pos === "fixed" || pos === "absolute") return false;
    if (d) return d === "inline";
    return !!INLINE_TAGS[tagOf(el)];
  }

  function textOf(node) {
    var kids, out, i;
    if (!node) return "";
    if (node.nodeType === 3) return String(node.data != null ? node.data : node.textContent || "");
    if (HIDDEN_TAGS[tagOf(node)]) return "";
    kids = childNodesOf(node);
    if (kids && kids.length) {
      out = "";
      for (i = 0; i < kids.length; i++) out += textOf(kids[i]);
      return out;
    }
    return String(node.textContent || "");
  }

  function computeRendered(el) {
    var root = typeof document !== "undefined" && document ? document.documentElement : null;
    var n;
    for (n = el; n; n = n.parentNode) {
      if (n.nodeType === 1 && (styleDisplay(n) === "none" || HIDDEN_TAGS[tagOf(n)])) return false;
      if (n === root) return true;
    }
    return false;
  }

  function layoutOverride(el, name) {
    var o = Object.prototype.hasOwnProperty.call(el, "_ymSize") ? el._ymSize : null;
    return o && typeof o[name] === "number" ? o[name] : null;
  }

  function containerWidth(el) {
    var p = el.parentNode;
    while (p && p.nodeType === 1 && isInline(p)) p = p.parentNode;
    if (!p || p.nodeType !== 1) return 1280;
    return boxWidth(p);
  }

  /* layoutBox(el).w without the height: a width depends only on ancestors, never on children,
     so containerWidth must not go through layoutBox (parent height -> child width -> parent). */
  function computeBoxWidth(el) {
    var ow = layoutOverride(el, "offsetWidth");
    var tag, w;
    if (ow == null) ow = layoutOverride(el, "clientWidth");
    if (ow != null) return ow;
    if (!rendered(el)) return 0;
    tag = tagOf(el);
    if (REPLACED_SIZE[tag]) return REPLACED_SIZE[tag][0];
    if (isInline(el)) return textWidth(collapse(textOf(el)).trim(), fontOf(el));
    w = styleWidth(el, fontOf(el));
    return w != null ? w : containerWidth(el);
  }

  function styleWidth(el, font) {
    var v = css(el, "width");
    var m = /^([\d.]+)ch$/.exec(v);
    if (m) return +m[1] * advance(48, font);
    return px(v);
  }

  /* {w, h, inline}, fractional like getBoundingClientRect. */
  function computeLayoutBox(el) {
    var ow = layoutOverride(el, "offsetWidth"),
      oh = layoutOverride(el, "offsetHeight");
    if (ow == null) ow = layoutOverride(el, "clientWidth");
    if (oh == null) oh = layoutOverride(el, "clientHeight");
    var inline = isInline(el);
    var tag = tagOf(el);
    var font, w, h, kids, i, run, hyphenate;
    if (ow != null && oh != null) return { w: ow, h: oh, inline: inline };
    if (!rendered(el)) return { w: ow || 0, h: oh || 0, inline: inline };
    font = fontOf(el);
    if (REPLACED_SIZE[tag]) {
      w = REPLACED_SIZE[tag][0];
      h = REPLACED_SIZE[tag][1];
    } else if (inline) {
      w = textWidth(collapse(textOf(el)).trim(), font);
      h = w > 0 ? font.line : 0;
    } else {
      w = styleWidth(el, font);
      if (w == null) w = containerWidth(el);
      hyphenate = inheritedCss(el, "hyphens") === "auto";
      h = 0;
      run = "";
      kids = childNodesOf(el);
      if (!kids.length) kids = [{ nodeType: 3, data: textOf(el) }];
      for (i = 0; i <= kids.length; i++) {
        if (i < kids.length && (kids[i].nodeType !== 1 || isInline(kids[i]))) {
          run += textOf(kids[i]);
          continue;
        }
        h += lineCount(run, font, w, hyphenate) * font.line;
        run = "";
        if (i < kids.length) h += layoutBox(kids[i]).h;
      }
    }
    return { w: ow != null ? ow : w, h: oh != null ? oh : h, inline: inline };
  }

  function positioned(el) {
    var pos = css(el, "position");
    return pos !== "" && pos !== "static";
  }

  function offsetParentOf(el) {
    var body = typeof document !== "undefined" && document ? document.body : null;
    var p;
    if (!rendered(el) || el === body || el === document.documentElement) return null;
    if (css(el, "position") === "fixed") return null;
    for (p = el.parentNode; p && p.nodeType === 1; p = p.parentNode) {
      if (p === body || positioned(p)) return p;
    }
    return body;
  }

  function margin(el, side) {
    var v = px(css(el, "margin" + side));
    if (v == null) v = px(css(el, "margin").split(/\s+/)[0] || "");
    return v || 0;
  }

  /* x of el's border box in its offsetParent: inline boxes laid out left to right on one line. */
  function computeOffsetLeft(el) {
    var parent = offsetParentOf(el);
    var x = 0,
      found = null;
    if (!parent) return 0;
    (function walk(n) {
      var kids = childNodesOf(n),
        i,
        c;
      for (i = 0; i < kids.length && found === null; i++) {
        c = kids[i];
        if (!c) continue;
        if (c.nodeType === 3) {
          x += textWidth(collapse(textOf(c)), fontOf(n));
          continue;
        }
        if (c.nodeType !== 1 || styleDisplay(c) === "none" || HIDDEN_TAGS[tagOf(c)]) continue;
        if (isInline(c)) {
          x += margin(c, "Left");
          if (c === el) found = x;
          else {
            walk(c);
            x += margin(c, "Right");
          }
        } else {
          x = margin(c, "Left");
          if (c === el) found = x;
          else walk(c);
          x = 0;
        }
      }
    })(parent);
    return found === null ? 0 : Math.round(found);
  }

  function parseStyleAttr(el, text) {
    var st = cssStyle();
    String(text == null ? "" : text)
      .split(";")
      .forEach(function (decl) {
        var i = decl.indexOf(":");
        var name, value;
        if (i < 0) return;
        name = decl.slice(0, i).trim().toLowerCase();
        value = decl.slice(i + 1).trim();
        if (!name) return;
        st[
          name.replace(/-([a-z])/g, function (_m, c) {
            return c.toUpperCase();
          })
        ] = value;
      });
    st.cssText = String(text == null ? "" : text);
    el.style = st;
  }

  function installLayout() {
    LAYOUT_PROPS.forEach(function (name) {
      var height = name.indexOf("Height") > 0;
      var offset = name.indexOf("offset") === 0;
      Object.defineProperty(htmlElProto, name, {
        configurable: true,
        enumerable: true,
        get: native("get " + name, function () {
          var o = layoutOverride(this, name),
            box;
          if (o != null) return o;
          if (!layoutProbes) return 0;
          if (name === "offsetLeft") return offsetLeftOf(this);
          box = layoutBox(this);
          if (box.inline && !offset) return 0;
          return Math.round(height ? box.h : box.w);
        }),
        set: native("set " + name, function (v) {
          if (!Object.prototype.hasOwnProperty.call(this, "_ymSize")) hide(this, "_ymSize", {});
          this._ymSize[name] = +v;
        }),
      });
    });
    Object.defineProperty(htmlElProto, "offsetParent", {
      configurable: true,
      enumerable: true,
      get: native("get offsetParent", function () {
        return offsetParentOf(this);
      }),
    });
  }

  /* Chrome elements are instances of their interface. Before idl_stubs runs the interfaces
   * don't exist yet; installChromeBackedApis adopts those early elements. */
  function adoptInterface(el) {
    var C = globalThis["HTML" + (ELEMENT_INTERFACES[el.tagName] || "") + "Element"];
    var proto = typeof C === "function" ? C.prototype : null;
    var target = el.tagName === "IFRAME" ? iframeProto : el;
    if (!proto || proto === target || proto.isPrototypeOf(target) || target.isPrototypeOf(proto))
      return;
    Object.setPrototypeOf(target, proto);
  }
  function attachFrame(c) {
    if (!c || c.tagName !== "IFRAME" || c.contentWindow) return;
    c.contentWindow = makeIframeWindow();
    c.contentDocument = c.contentWindow.document;
    globalThis[0] = c.contentWindow;
    globalThis.length = 1;
  }
  (function installElementProto() {
    var n, impl;
    impl = {
      appendChild: function (c) {
        ownChildNodes(this).push(c);
        syncElementChildren(this);
        if (c) c.parentNode = this;
        attachFrame(c);
        runScript(c);
        return c;
      },
      insertBefore: function (c, ref) {
        var i, nodes;
        if (!c) return c;
        nodes = ownChildNodes(this);
        i = ref ? nodes.indexOf(ref) : -1;
        if (i < 0) nodes.push(c);
        else nodes.splice(i, 0, c);
        syncElementChildren(this);
        c.parentNode = this;
        attachFrame(c);
        runScript(c);
        return c;
      },
      removeChild: function (c) {
        var nodes = ownChildNodes(this),
          i = nodes.indexOf(c);
        if (i >= 0) {
          nodes.splice(i, 1);
          syncElementChildren(this);
        }
        if (c) c.parentNode = null;
        if (c && c.tagName === "IFRAME") {
          c.contentWindow = null;
          c.contentDocument = null;
          delete globalThis[0];
          globalThis.length = 0;
        }
        return c;
      },
      contains: function (n) {
        var i,
          nodes = childNodesOf(this);
        if (n === this) return true;
        for (i = 0; i < nodes.length; i++) {
          if (nodes[i] === n) return true;
          if (nodes[i].contains && nodes[i].contains(n)) return true;
        }
        return false;
      },
      hasChildNodes: function () {
        return childNodesOf(this).length > 0;
      },
      hasAttributes: function () {
        return !!(this.attributes && this.attributes.length);
      },
      setAttribute: function (n, v) {
        if (String(n).toLowerCase() === "style") parseStyleAttr(this, v);
        else this[n] = v;
      },
      setAttributeNS: function (ns, n, v) {
        this[n] = v;
      },
      getAttribute: function (n) {
        if (String(n).toLowerCase() === "style")
          return this.style && this.style.cssText ? String(this.style.cssText) : null;
        return this[n] == null ? null : String(this[n]);
      },
      getAttributeNS: function (ns, n) {
        return this[n] == null ? null : String(this[n]);
      },
      hasAttribute: function (n) {
        return this[n] != null && this[n] !== "";
      },
      hasAttributeNS: function (ns, n) {
        return this[n] != null && this[n] !== "";
      },
      removeAttribute: function (n) {
        delete this[n];
      },
      removeAttributeNS: function (ns, n) {
        delete this[n];
      },
      getBoundingClientRect: function () {
        var box = layoutProbes
            ? layoutBox(this)
            : { w: this.clientWidth || 0, h: this.clientHeight || 0 },
          w = box.w,
          h = box.h;
        return { x: 0, y: 0, width: w, height: h, top: 0, left: 0, bottom: h, right: w };
      },
      getClientRects: function () {
        var r = this.getBoundingClientRect();
        var a = [r];
        a.item = function (i) {
          return this[i];
        };
        return a;
      },
      getElementsByClassName: function (name) {
        var self = this,
          list,
          dummy;
        name = String(name || "");
        list = collectNodes(this, function (n) {
          return n !== self && n.nodeType === 1 && hasClass(n, name);
        });
        if (list.length) return list;
        dummy = element("div");
        dummy.className = name;
        dummy.ownerDocument = document;
        return nodeListFrom([dummy]);
      },
      getElementsByTagName: function (name) {
        var self = this,
          want = String(name || "*").toUpperCase();
        return collectNodes(this, function (n) {
          return n !== self && n.nodeType === 1 && (want === "*" || n.tagName === want);
        });
      },
      querySelector: function (sel) {
        return queryOne(this, sel);
      },
      querySelectorAll: function (sel) {
        return queryAll(this, sel);
      },
      matches: function (sel) {
        return matchSimple(this, sel);
      },
      webkitMatchesSelector: function (sel) {
        return matchSimple(this, sel);
      },
      closest: function (sel) {
        var n = this;
        sel = String(sel || "").trim();
        while (n && n.nodeType === 1) {
          if (matchSimple(n, sel)) return n;
          n = n.parentNode;
        }
        return null;
      },
      getRootNode: function () {
        return document && document.documentElement ? document.documentElement : this;
      },
      addEventListener: globalThis.addEventListener,
      removeEventListener: globalThis.removeEventListener,
      dispatchEvent: globalThis.dispatchEvent,
    };
    for (n in impl) hide(htmlElProto, n, native(n, impl[n]));
    hide(
      htmlElProto,
      "click",
      native("click", function () {
        dispatchNative.call(
          this,
          newEvent(globalThis.PointerEvent.prototype, {
            type: "click",
            init: true,
            bubbles: true,
            cancelable: true,
            composed: true,
            view: globalThis,
          }),
        );
      }),
    );
    hide(htmlElProto, "constructor", globalThis.HTMLElement);
    Object.defineProperty(htmlElProto, "dataset", {
      configurable: true,
      enumerable: true,
      get: function () {
        if (!this._bbdDataset) this._bbdDataset = {};
        return this._bbdDataset;
      },
    });
    hide(iframeProto, "constructor", globalThis.HTMLIFrameElement);
    installLayout();
    Object.defineProperty(htmlElProto, "childNodes", {
      configurable: true,
      enumerable: false,
      get: native("get childNodes", function () {
        return nodeListFrom(childNodesOf(this));
      }),
    });
    Object.defineProperty(htmlElProto, "firstElementChild", {
      configurable: true,
      enumerable: true,
      get: native("get firstElementChild", function () {
        var c = this.children;
        return c && c.length ? c[0] : null;
      }),
    });
    Object.defineProperty(htmlElProto, "lastElementChild", {
      configurable: true,
      enumerable: true,
      get: native("get lastElementChild", function () {
        var c = this.children;
        return c && c.length ? c[c.length - 1] : null;
      }),
    });
    Object.defineProperty(htmlElProto, "childElementCount", {
      configurable: true,
      enumerable: true,
      get: native("get childElementCount", function () {
        return (this.children && this.children.length) || 0;
      }),
    });
    Object.defineProperty(htmlElProto, "parentElement", {
      configurable: true,
      enumerable: true,
      get: native("get parentElement", function () {
        var p = this.parentNode;
        return p && p.nodeType === 1 ? p : null;
      }),
    });
    Object.defineProperty(htmlElProto, "firstChild", {
      configurable: true,
      enumerable: true,
      get: native("get firstChild", function () {
        var c = childNodesOf(this);
        return c && c.length ? c[0] : null;
      }),
    });
    Object.defineProperty(htmlElProto, "lastChild", {
      configurable: true,
      enumerable: true,
      get: native("get lastChild", function () {
        var c = childNodesOf(this);
        return c && c.length ? c[c.length - 1] : null;
      }),
    });
    Object.defineProperty(htmlElProto, "previousSibling", {
      configurable: true,
      enumerable: true,
      get: native("get previousSibling", function () {
        return siblingAt(this, -1, true);
      }),
    });
    Object.defineProperty(htmlElProto, "nextSibling", {
      configurable: true,
      enumerable: true,
      get: native("get nextSibling", function () {
        return siblingAt(this, 1, true);
      }),
    });
    Object.defineProperty(htmlElProto, "previousElementSibling", {
      configurable: true,
      enumerable: true,
      get: native("get previousElementSibling", function () {
        return siblingAt(this, -1);
      }),
    });
    Object.defineProperty(htmlElProto, "nextElementSibling", {
      configurable: true,
      enumerable: true,
      get: native("get nextElementSibling", function () {
        return siblingAt(this, 1);
      }),
    });
  })();

  function ownNativeStatic(dst, src) {
    var names, i, n, orig;
    names = Object.getOwnPropertyNames(src);
    for (i = 0; i < names.length; i++) {
      n = names[i];
      if (
        n === "prototype" ||
        n === "length" ||
        n === "name" ||
        n === "caller" ||
        n === "arguments"
      )
        continue;
      orig = src[n];
      if (typeof orig !== "function") continue;
      hide(
        dst,
        n,
        native(
          n,
          (function (fn) {
            return function () {
              return fn.apply(src, arguments);
            };
          })(orig),
        ),
      );
    }
  }

  /* Chrome window names the page itself must not gain: base.js feature-tests several of them. */
  var IFRAME_ONLY_NAMES =
    "CSS WebAssembly SuppressedError DisposableStack AsyncDisposableStack webkitURL webkitRTCPeerConnection " +
    "webkitMediaStream offscreenBuffering styleMedia onsearch onappinstalled onbeforeinstallprompt " +
    "oncontentvisibilityautostatechange onmousewheel ongotpointercapture onlostpointercapture " +
    "onpointerdown onpointermove onpointerup onpointercancel onpointerover onpointerout " +
    "onpointerenter onpointerleave onselectstart onselectionchange onanimationcancel onanimationend " +
    "onanimationiteration onanimationstart ontransitionrun ontransitionstart ontransitionend " +
    "ontransitioncancel onbeforexrselect scheduler indexedDB find Temporal crashReport cookieStore " +
    "ondevicemotion ondeviceorientation ondeviceorientationabsolute onpointerrawupdate caches " +
    "documentPictureInPicture GPUBufferUsage GPUColorWrite GPUMapMode GPUShaderStage GPUTextureUsage " +
    "fetchLater getScreenDetails queryLocalFonts showDirectoryPicker showOpenFilePicker " +
    "showSaveFilePicker viewport credentialless fence launchQueue speechSynthesis onscrollsnapchange " +
    "onscrollsnapchanging ongamepadconnected ongamepaddisconnected webkitSpeechGrammar " +
    "webkitSpeechGrammarList webkitSpeechRecognitionError webkitSpeechRecognitionEvent requestResize " +
    "webkitRequestFileSystem webkitResolveLocalFileSystemURL";
  var iframeGlobalNames = [];

  /* Each realm owns its namespace objects. Their constructors can't be wrapped: native() has no `new`. */
  function ownNamespace(name) {
    var src = globalThis[name];
    var own = {};
    ownNativeStatic(own, src);
    Object.getOwnPropertyNames(src).forEach(function (n) {
      if (typeof src[n] !== "function" || /^[A-Z]/.test(n)) hide(own, n, src[n]);
    });
    Object.defineProperty(own, Symbol.toStringTag, { value: name, configurable: true });
    return own;
  }

  /* Unset camelCase / vendor CSS is "" and `in` is true (Chrome CSSOM). join/push stay absent. */
  var CSS_DENY = {
    concat: 1,
    join: 1,
    push: 1,
    pop: 1,
    splice: 1,
    slice: 1,
    shift: 1,
    unshift: 1,
    indexOf: 1,
    lastIndexOf: 1,
    map: 1,
    filter: 1,
    forEach: 1,
    reduce: 1,
    reduceRight: 1,
    some: 1,
    every: 1,
    find: 1,
    findIndex: 1,
    includes: 1,
    fill: 1,
    copyWithin: 1,
    entries: 1,
    keys: 1,
    values: 1,
    flat: 1,
    flatMap: 1,
    toString: 1,
    valueOf: 1,
    constructor: 1,
    then: 1,
    toJSON: 1,
    hasOwnProperty: 1,
    propertyIsEnumerable: 1,
    isPrototypeOf: 1,
    toLocaleString: 1,
    item: 1,
    getPropertyValue: 1,
    setProperty: 1,
    removeProperty: 1,
    getPropertyPriority: 1,
    parentRule: 1,
    cssText: 1,
    length: 1,
    apply: 1,
    call: 1,
    bind: 1,
    name: 1,
    prototype: 1,
    arguments: 1,
    caller: 1,
  };
  function cssIndexKey(k) {
    var i;
    if (typeof k !== "string" || !k) return false;
    for (i = 0; i < k.length; i++) {
      var c = k.charCodeAt(i);
      if (c < 48 || c > 57) return false;
    }
    return true;
  }
  function looksLikeCss(k) {
    var i, hasUpper, c;
    if (typeof k !== "string" || !k || k.charAt(0) === "_") return false;
    if (CSS_DENY[k]) return false;
    if (
      k.indexOf("webkit") === 0 ||
      k.indexOf("Webkit") === 0 ||
      k.indexOf("moz") === 0 ||
      k.indexOf("Moz") === 0 ||
      k.indexOf("ms") === 0
    )
      return true;
    hasUpper = false;
    for (i = 0; i < k.length; i++) {
      c = k.charCodeAt(i);
      if (c >= 65 && c <= 90) hasUpper = true;
      else if (!((c >= 97 && c <= 122) || (c >= 48 && c <= 57))) return false;
    }
    return hasUpper;
  }
  function cssStyle() {
    var s = { cssText: "", length: 0 };
    s.setProperty = native("setProperty", function (n, v) {
      n = String(n == null ? "" : n);
      v = String(v == null ? "" : v);
      this[n] = v;
    });
    s.removeProperty = native("removeProperty", function (n) {
      n = String(n == null ? "" : n);
      var old = this[n];
      delete this[n];
      return old == null ? "" : String(old);
    });
    s.getPropertyValue = native("getPropertyValue", function (n) {
      n = String(n == null ? "" : n);
      var v = this[n];
      return v == null ? "" : String(v);
    });
    return new Proxy(s, {
      get: function (t, k) {
        if (Object.prototype.hasOwnProperty.call(t, k)) return t[k];
        if (typeof k !== "string") return t[k];
        if (cssIndexKey(k)) return undefined;
        if (looksLikeCss(k)) return "";
        return t[k];
      },
      has: function (t, k) {
        if (Object.prototype.hasOwnProperty.call(t, k)) return true;
        if (typeof k !== "string") return k in t;
        if (cssIndexKey(k)) return false;
        if (looksLikeCss(k)) return true;
        return k in t;
      },
    });
  }

  /* Chrome canvas.getContext('webgl'|'webgl2'): fingerprint via getExtension. */
  function webglContext(canvas, version) {
    var gl = { canvas: canvas };
    var debugInfo = { UNMASKED_VENDOR_WEBGL: 37445, UNMASKED_RENDERER_WEBGL: 37446 };
    var extMap = {
      WEBGL_debug_renderer_info: debugInfo,
      WEBGL_debug_shaders: {},
      EXT_texture_filter_anisotropic: { MAX_TEXTURE_MAX_ANISOTROPY_EXT: 34047 },
      OES_texture_float: {},
      OES_standard_derivatives: {},
      WEBGL_lose_context: {
        loseContext: native("loseContext", function () {}),
        restoreContext: native("restoreContext", function () {}),
      },
    };
    gl.VENDOR = 7936;
    gl.RENDERER = 7937;
    gl.VERSION = 7938;
    gl.SHADING_LANGUAGE_VERSION = 35724;
    gl.MAX_TEXTURE_SIZE = 3379;
    gl.VERTEX_SHADER = 35633;
    gl.FRAGMENT_SHADER = 35632;
    gl.HIGH_FLOAT = 36338;
    gl.MEDIUM_FLOAT = 36337;
    gl.LOW_FLOAT = 36336;
    gl.HIGH_INT = 36341;
    gl.MEDIUM_INT = 36340;
    gl.LOW_INT = 36339;
    gl.getExtension = native("getExtension", function (name) {
      name = String(name == null ? "" : name);
      return Object.prototype.hasOwnProperty.call(extMap, name) ? extMap[name] : null;
    });
    gl.getSupportedExtensions = native("getSupportedExtensions", function () {
      return Object.keys(extMap);
    });
    gl.getParameter = native("getParameter", function (pname) {
      if (pname === 37445) return "Google Inc. (Apple)";
      if (pname === 37446)
        return "ANGLE (Apple, ANGLE Metal Renderer: Apple M3, Unspecified Version)";
      if (pname === 7936) return "WebKit";
      if (pname === 7937) return "WebKit WebGL";
      if (pname === 7938)
        return version === 2
          ? "WebGL 2.0 (OpenGL ES 3.0 Chromium)"
          : "WebGL 1.0 (OpenGL ES 2.0 Chromium)";
      if (pname === 35724)
        return version === 2
          ? "WebGL GLSL ES 3.00 (OpenGL ES GLSL ES 3.0 Chromium)"
          : "WebGL GLSL ES 1.0 (OpenGL ES GLSL ES 1.0 Chromium)";
      if (pname === 3379 || pname === 34076 || pname === 34024) return 16384;
      if (pname === 34921 || pname === 34930 || pname === 35660) return 16;
      if (pname === 36347) return 4095;
      if (pname === 36348 || pname === 36349) return 1024;
      if (pname === 35661) return 32;
      if (pname === 3386) return new Int32Array([16384, 16384]);
      if (pname === 2978) return new Int32Array([0, 0, canvas.width || 300, canvas.height || 150]);
      return null;
    });
    gl.getContextAttributes = native("getContextAttributes", function () {
      return {
        alpha: true,
        antialias: true,
        depth: true,
        stencil: false,
        premultipliedAlpha: true,
        preserveDrawingBuffer: false,
        failIfMajorPerformanceCaveat: false,
        powerPreference: "default",
        desynchronized: false,
        xrCompatible: false,
      };
    });
    gl.getShaderPrecisionFormat = native("getShaderPrecisionFormat", function () {
      return { rangeMin: 127, rangeMax: 127, precision: 23 };
    });
    return gl;
  }

  function nodeListFrom(arr) {
    var nl = { length: arr.length };
    var i;
    for (i = 0; i < arr.length; i++) nl[i] = arr[i];
    nl.item = native("item", function (i) {
      return i >= 0 && i < this.length ? this[i] : null;
    });
    return nl;
  }

  function hasClass(n, name) {
    return (" " + String((n && n.className) || "") + " ").indexOf(" " + name + " ") >= 0;
  }

  /* Chrome keeps text nodes in childNodes only; children is the element-only view of it. */
  function childNodesOf(n) {
    return (n && (n._ymNodes || n.children)) || [];
  }

  function ownChildNodes(n) {
    if (!n.children) n.children = [];
    if (!n._ymNodes) hide(n, "_ymNodes", n.children.slice());
    return n._ymNodes;
  }

  function syncElementChildren(n) {
    var nodes = n._ymNodes,
      kids = n.children,
      i;
    kids.length = 0;
    for (i = 0; i < nodes.length; i++) if (nodes[i] && nodes[i].nodeType === 1) kids.push(nodes[i]);
  }

  function siblingAt(n, dir, anyNode) {
    var p, kids, i;
    p = n && n.parentNode;
    kids = p && (anyNode ? childNodesOf(p) : p.children);
    if (!kids) return null;
    i = kids.indexOf(n);
    if (i < 0) return null;
    i += dir;
    return i >= 0 && i < kids.length ? kids[i] : null;
  }

  function attrOf(n, name) {
    if (!n) return null;
    if (typeof n.getAttribute === "function") {
      var v = n.getAttribute(name);
      if (v != null) return v;
    }
    if (n[name] == null) return null;
    return String(n[name]);
  }

  function matchSimple(n, sel) {
    var i, tag, rest, m, re, have;
    if (!n || n.nodeType !== 1) return false;
    sel = String(sel || "").trim();
    if (!sel) return false;
    i = 0;
    tag = "";
    if (sel.charAt(0) !== "." && sel.charAt(0) !== "#" && sel.charAt(0) !== "[") {
      while (i < sel.length && /[A-Za-z0-9_-]/.test(sel.charAt(i))) i++;
      tag = sel.slice(0, i).toUpperCase();
      if (tag && n.tagName !== tag) return false;
    }
    rest = sel.slice(i);
    re = /([.#])([A-Za-z0-9_-]+)/g;
    while ((m = re.exec(rest))) {
      if (m[1] === "#" && n.id !== m[2]) return false;
      if (m[1] === "." && !hasClass(n, m[2])) return false;
    }
    re = /\[([A-Za-z0-9_-]+)(?:([~|^$*]?=)"([^"]*)")?\]/g;
    while ((m = re.exec(rest))) {
      have = attrOf(n, m[1]);
      if (!m[2]) {
        if (have == null || have === "") return false;
      } else if (String(have) !== m[3]) return false;
    }
    return true;
  }

  function queryAll(root, sel) {
    var parts, i;
    sel = String(sel || "").trim();
    parts = sel.split(",");
    return collectNodes(root, function (n) {
      if (n === root || !n || n.nodeType !== 1) return false;
      for (i = 0; i < parts.length; i++) {
        if (matchSimple(n, parts[i].trim())) return true;
      }
      return false;
    });
  }

  function queryOne(root, sel) {
    var found = null,
      parts,
      i;
    sel = String(sel || "").trim();
    parts = sel.split(",");
    collectNodes(root, function (n) {
      if (found || n === root || !n || n.nodeType !== 1) return false;
      for (i = 0; i < parts.length; i++) {
        if (matchSimple(n, parts[i].trim())) {
          found = n;
          return false;
        }
      }
      return false;
    });
    if (found) return found;
    found = element("div");
    found.ownerDocument = document;
    return found;
  }

  function collectNodes(root, pred) {
    var out = [];
    function walk(n) {
      var i, kids;
      if (!n) return;
      if (pred(n)) out.push(n);
      kids = n.children || [];
      for (i = 0; i < kids.length; i++) walk(kids[i]);
    }
    walk(root);
    return nodeListFrom(out);
  }

  /* Chrome: iframe.contentWindow !== window, own Array/eval, own document.
   * YouTube Trusted Types: iframe.eval(string) throws EvalError. */
  function makeIframeWindow() {
    var inner = {};
    var innerDoc;
    inner.window = inner;
    inner.self = inner;
    inner.globalThis = inner;
    inner.parent = globalThis;
    inner.top = globalThis;
    inner.frames = inner;
    inner.length = 0;
    inner.innerWidth = 1280;
    inner.innerHeight = 800;
    inner.outerWidth = 1280;
    inner.outerHeight = 860;
    inner.devicePixelRatio = 2;
    inner.screenX = 0;
    inner.screenY = 0;
    inner.screenLeft = 0;
    inner.screenTop = 0;
    inner.pageXOffset = 0;
    inner.pageYOffset = 0;
    inner.scrollX = 0;
    inner.scrollY = 0;
    inner.name = "";
    inner.status = "";
    inner.closed = false;
    inner.opener = null;
    inner.menubar = globalThis.menubar;
    inner.toolbar = globalThis.toolbar;
    inner.locationbar = globalThis.locationbar;
    inner.personalbar = globalThis.personalbar;
    inner.scrollbars = globalThis.scrollbars;
    inner.statusbar = globalThis.statusbar;
    inner.isSecureContext = true;
    inner.crossOriginIsolated = false;
    inner.screen = globalThis.screen;
    inner.location = {
      href: "about:blank",
      protocol: "about:",
      host: "",
      hostname: "",
      pathname: "blank",
      search: "",
      hash: "",
      origin: "null",
      toString: native("toString", locHref),
      valueOf: native("valueOf", locHref),
    };
    inner.origin = inner.location.origin;
    inner.history = globalThis.history;
    inner.Array = native("Array", function Array() {
      if (arguments.length === 1 && typeof arguments[0] === "number")
        return new Array(arguments[0]);
      return Array.prototype.slice.call(arguments);
    });
    inner.Array.prototype = ownNativeProto(Array.prototype);
    inner.Array.prototype.constructor = inner.Array;
    ownNativeStatic(inner.Array, Array);
    inner.Object = native("Object", function () {
      return arguments[0] == null ? {} : Object(arguments[0]);
    });
    inner.Object.prototype = ownNativeProto(Object.prototype);
    inner.Object.prototype.constructor = inner.Object;
    ownNativeStatic(inner.Object, Object);
    inner.String = native("String", function String(v) {
      return "" + v;
    });
    inner.String.prototype = ownNativeProto(String.prototype);
    inner.String.prototype.constructor = inner.String;
    ownNativeStatic(inner.String, String);
    inner.Number = native("Number", function (v) {
      return arguments.length ? +v : 0;
    });
    inner.Number.prototype = ownNativeProto(Number.prototype);
    inner.Number.prototype.constructor = inner.Number;
    ownNativeStatic(inner.Number, Number);
    inner.Boolean = native("Boolean", function (v) {
      return !!v;
    });
    inner.Boolean.prototype = ownNativeProto(Boolean.prototype);
    inner.Boolean.prototype.constructor = inner.Boolean;
    inner.Error = native("Error", function (m) {
      return new Error(m);
    });
    inner.Error.prototype = ownNativeProto(Error.prototype);
    inner.Error.prototype.constructor = inner.Error;
    ownNativeStatic(inner.Error, Error);
    inner.Function = native("Function", function Function() {
      throw new EvalError(
        "Evaluating a string as JavaScript violates this document's Trusted Type assignment requirements.",
      );
    });
    inner.eval = native("eval", function (code) {
      var src, g;
      if (typeof code === "string") {
        throw new EvalError(
          "Evaluating a string as JavaScript violates this document's Trusted Type assignment requirements.",
        );
      }
      src = unwrapScript(code);
      if (typeof src !== "string") return code;
      if (src === "this") return inner;
      g = inner;
      return function () {
        with (this) {
          return eval(src);
        }
      }.call(g);
    });
    inner.addEventListener = globalThis.addEventListener;
    inner.removeEventListener = globalThis.removeEventListener;
    inner.dispatchEvent = globalThis.dispatchEvent;
    innerDoc = {
      nodeType: 9,
      readyState: "complete",
      hidden: false,
      visibilityState: "visible",
      body: null,
      documentElement: null,
      defaultView: inner,
      cookie: "",
      URL: "about:blank",
      documentURI: "about:blank",
    };
    inner.document = innerDoc;
    innerDoc.defaultView = inner;
    /* Same-origin about:blank iframe: BotGuard lists these names. Identity may match the parent;
     * Array/eval stay separate. */
    iframeGlobalNames.forEach(function (n) {
      if (!(n in inner)) inner[n] = globalThis[n];
    });
    IFRAME_ONLY_NAMES.split(" ").forEach(function (n) {
      if (n in inner) return;
      if (n.indexOf("on") === 0) inner[n] = null;
      else inner[n] = /^(CSS|GPU|Temporal|WebAssembly)/.test(n) ? {} : native(n, function () {});
    });
    ["JSON", "Math", "Intl", "Atomics", "Reflect", "CSS", "WebAssembly"].forEach(function (n) {
      if (globalThis[n] != null) inner[n] = ownNamespace(n);
    });
    return inner;
  }

  var ELEMENT_INTERFACES = {
    A: "Anchor",
    AUDIO: "Audio",
    BODY: "Body",
    BR: "BR",
    BUTTON: "Button",
    CANVAS: "Canvas",
    DIV: "Div",
    FORM: "Form",
    HEAD: "Head",
    HTML: "Html",
    IFRAME: "IFrame",
    IMG: "Image",
    INPUT: "Input",
    LABEL: "Label",
    LI: "LI",
    LINK: "Link",
    META: "Meta",
    OPTION: "Option",
    P: "Paragraph",
    PRE: "Pre",
    SCRIPT: "Script",
    SELECT: "Select",
    SPAN: "Span",
    STYLE: "Style",
    TEXTAREA: "TextArea",
    TITLE: "Title",
    UL: "UList",
    VIDEO: "Video",
  };

  function element(tagName) {
    var t = String(tagName || "div").toUpperCase();
    if (t === "VIDEO") {
      var H = globalThis.HTMLMediaElement;
      if (H && H.main) return H.main;
    }
    var kids = [];
    var hostName = "HTML" + (ELEMENT_INTERFACES[t] || "") + "Element";
    var el = Object.create(t === "IFRAME" ? iframeProto : htmlElProto);
    el.tagName = t;
    el.nodeName = t;
    el.nodeType = 1;
    el.children = kids;
    el.className = "";
    el.id = "";
    el.name = "";
    el.innerHTML = "";
    el.textContent = "";
    el.style = cssStyle();
    el.attributes = {
      length: 0,
      item: native("item", function () {
        return null;
      }),
    };
    el.ownerDocument = null;
    el.parentNode = null;
    el.dir = "";
    el.scrollTop = 0;
    el.scrollLeft = 0;
    el.offsetTop = 0;
    el.tabIndex = -1;
    if (typeof Symbol === "function" && Symbol.toStringTag) {
      Object.defineProperty(el, Symbol.toStringTag, {
        configurable: true,
        enumerable: false,
        value: hostName,
      });
    }
    if (t === "STYLE" || t === "LINK") {
      el.type = t === "STYLE" ? "text/css" : "";
      el.rel = t === "LINK" ? "" : undefined;
      el.sheet = {
        type: "text/css",
        href: null,
        disabled: false,
        cssRules: {
          length: 0,
          item: native("item", function () {
            return null;
          }),
        },
        rules: {
          length: 0,
          item: native("item", function () {
            return null;
          }),
        },
        insertRule: native("insertRule", function () {
          return 0;
        }),
        deleteRule: native("deleteRule", function () {}),
      };
    }
    if (t === "SCRIPT") {
      el.type = "";
      var scriptSrc = "";
      Object.defineProperty(el, "src", {
        configurable: true,
        enumerable: true,
        get: function () {
          return scriptSrc;
        },
        set: function (v) {
          scriptSrc = absScriptUrl(v);
          if (el.parentNode) runScript(el);
        },
      });
    }
    if (t === "IMG") {
      var imgSrc;
      Object.defineProperty(el, "src", {
        configurable: true,
        enumerable: true,
        get: function () {
          return imgSrc;
        },
        set: function (v) {
          var url = absScriptUrl(v);
          imgSrc = v;
          /* BotGuard's ping, nothing else: a 204 is never an image, so Chrome fires error. */
          if (!imageRequest || url.indexOf("https://www.youtube.com/generate_204") !== 0) return;
          imgSrc = url;
          imageRequest(url, function () {
            dispatchNative.call(
              el,
              newEvent(globalThis.Event.prototype, { type: "error", init: true, trusted: true }),
            );
          });
        },
      });
    }
    if (t === "IFRAME") {
      el.src = "about:blank";
      el.contentWindow = null;
      el.contentDocument = null;
      hide(el, "longDesc", "");
      hide(el, "vspace", 0);
      hide(el, "hspace", 0);
      el.sandbox = {
        length: 0,
        value: "",
        supports: native("supports", function () {
          return true;
        }),
        add: native("add", function (tok) {
          if (!this.value) this.value = String(tok);
          else this.value += " " + tok;
        }),
        remove: native("remove", function () {}),
        contains: native("contains", function () {
          return false;
        }),
        toString: native("toString", function () {
          return this.value || "";
        }),
      };
    }
    if (t === "IMG") {
      el.complete = true;
      el.naturalWidth = 0;
      el.naturalHeight = 0;
      el.width = 0;
      el.height = 0;
      el.src = "";
      el.alt = "";
      el.currentSrc = "";
      hide(el, "longDesc", "");
      hide(el, "vspace", 0);
      hide(el, "hspace", 0);
      hide(el, "isMap", false);
      hide(el, "useMap", "");
      hide(el, "align", "");
      hide(el, "border", "");
    }
    if (t === "INPUT" || t === "TEXTAREA" || t === "SELECT") {
      el.value = "";
      el.defaultValue = "";
      el.type = t === "INPUT" ? "text" : t.toLowerCase();
      el.checked = false;
      el.disabled = false;
    }
    el.classList = {
      add: native("add", function () {
        var i,
          s = String(el.className || "");
        for (i = 0; i < arguments.length; i++) {
          if (s.indexOf(arguments[i]) < 0) s += (s ? " " : "") + arguments[i];
        }
        el.className = s;
      }),
      remove: native("remove", function () {
        var i,
          s = " " + String(el.className || "") + " ";
        for (i = 0; i < arguments.length; i++) s = s.split(" " + arguments[i] + " ").join(" ");
        el.className = s.replace(/^\s+|\s+$/g, "");
      }),
      contains: native("contains", function (c) {
        return (" " + String(el.className || "") + " ").indexOf(" " + c + " ") >= 0;
      }),
      toggle: native("toggle", function (c) {
        if (this.contains(c)) this.remove(c);
        else this.add(c);
      }),
      toString: native("toString", function () {
        return String(el.className || "");
      }),
    };
    if (
      (t === "VIDEO" || t === "AUDIO") &&
      globalThis.HTMLMediaElement &&
      typeof globalThis.HTMLMediaElement.attach === "function"
    ) {
      globalThis.HTMLMediaElement.attach(el);
      if (t === "VIDEO") globalThis.HTMLMediaElement.main = el;
    }
    if (t === "CANVAS") {
      el.width = 300;
      el.height = 150;
      el.getContext = native("getContext", function (kind) {
        kind = String(kind == null ? "2d" : kind).toLowerCase();
        if (kind === "webgl" || kind === "experimental-webgl") return webglContext(el, 1);
        if (kind === "webgl2") return webglContext(el, 2);
        var ctx = { canvas: el };
        ctx.fillRect = native("fillRect", function () {});
        ctx.fillText = native("fillText", function () {});
        ctx.getImageData = native("getImageData", function () {
          return { data: new Uint8ClampedArray(4), width: 1, height: 1 };
        });
        ctx.measureText = native("measureText", function () {
          return { width: 0 };
        });
        return ctx;
      });
      el.toDataURL = native("toDataURL", function () {
        return "data:image/png;base64,";
      });
    }
    adoptInterface(el);
    return el;
  }

  var html = element("html");
  var head = element("head");
  var body = element("body");
  html.appendChild(head);
  html.appendChild(body);

  var document = {
    nodeType: 9,
    nodeName: "#document",
    readyState: "complete",
    hidden: false,
    visibilityState: "visible",
    cookie: "",
    referrer: "https://www.youtube.com/",
    title: "YouTube",
    domain: "www.youtube.com",
    URL: "https://www.youtube.com/",
    documentURI: "https://www.youtube.com/",
    characterSet: "UTF-8",
    charset: "UTF-8",
    compatMode: "CSS1Compat",
    contentType: "text/html",
    documentElement: html,
    head: head,
    body: body,
    location: globalThis.location,
    defaultView: globalThis,
  };
  var dk;
  for (dk in NODE_CONST) hide(document, dk, NODE_CONST[dk]);
  html.ownerDocument = document;
  head.ownerDocument = document;
  body.ownerDocument = document;
  hide(
    document,
    "createElement",
    native("createElement", function (name) {
      var el = element(name);
      el.ownerDocument = document;
      return el;
    }),
  );
  /* Chrome Image/Audio/Option are constructable; illegalCtor would throw. */
  globalThis.Image = native("Image", function Image(w, h) {
    var el = element("img");
    el.ownerDocument = document;
    if (w != null) el.width = +w;
    if (h != null) el.height = +h;
    return el;
  });
  globalThis.Audio = native("Audio", function Audio(src) {
    var el = element("audio");
    el.ownerDocument = document;
    if (src != null) el.src = String(src);
    return el;
  });
  var createEvent = __nativeFn(
    "createEvent",
    function (type) {
      var prefix = "Failed to execute 'createEvent' on 'Document': ";
      var name, proto;
      if (!arguments.length)
        throw new TypeError(prefix + "1 argument required, but only 0 present.");
      name = String(type);
      proto = eventProtoByName[name.toLowerCase()];
      if (!proto)
        throw domError(
          "NotSupportedError",
          9,
          prefix + "The provided event type ('" + name + "') is invalid.",
        );
      return newEvent(proto, {});
    },
    1,
  );
  hide(document, "createEvent", createEvent);
  hide(globalThis.Document.prototype, "createEvent", createEvent);
  hide(
    document,
    "createElementNS",
    native("createElementNS", function (ns, name) {
      return document.createElement(name);
    }),
  );
  hide(
    document,
    "createTextNode",
    native("createTextNode", function (s) {
      return {
        nodeType: 3,
        data: String(s == null ? "" : s),
        textContent: String(s == null ? "" : s),
      };
    }),
  );
  hide(
    document,
    "getElementById",
    native("getElementById", function (id) {
      id = String(id == null ? "" : id);
      var found = null;
      collectNodes(html, function (n) {
        if (!found && n && n.id === id) found = n;
        return false;
      });
      return found;
    }),
  );
  hide(
    document,
    "getElementsByTagName",
    native("getElementsByTagName", function (name) {
      name = String(name || "*").toUpperCase();
      return collectNodes(html, function (n) {
        return n && n.nodeType === 1 && (name === "*" || n.tagName === name);
      });
    }),
  );
  hide(
    document,
    "getElementsByClassName",
    native("getElementsByClassName", function (name) {
      name = String(name || "");
      return collectNodes(html, function (n) {
        return n && n.nodeType === 1 && hasClass(n, name);
      });
    }),
  );
  hide(
    document,
    "querySelector",
    native("querySelector", function (sel) {
      return queryOne(html, sel);
    }),
  );
  hide(
    document,
    "querySelectorAll",
    native("querySelectorAll", function (sel) {
      return queryAll(html, sel);
    }),
  );
  hide(document, "styleSheets", {
    length: 0,
    item: native("item", function () {
      return null;
    }),
  });
  hide(document, "children", html.children);
  hide(document, "firstElementChild", html);
  hide(document, "lastElementChild", html);
  hide(document, "childElementCount", 1);
  hide(document, "addEventListener", globalThis.addEventListener);
  hide(document, "removeEventListener", globalThis.removeEventListener);
  hide(
    document,
    "hasFocus",
    native("hasFocus", function () {
      return true;
    }),
  );
  html.clientWidth = html.offsetWidth = 1280;
  html.clientHeight = html.offsetHeight = 720;
  body.clientWidth = body.offsetWidth = 1280;
  body.clientHeight = body.offsetHeight = 720;
  globalThis.focus = native("focus", function () {
    return true;
  });
  globalThis.CLOSURE_FLAGS = globalThis.CLOSURE_FLAGS || {};
  globalThis.document = document;

  /* Chrome MessageChannel is constructable. illegalCtor left a thrower; YouTube live
   * schedules playVideo via port1.onmessage (DDy/mF). If `new MessageChannel` throws,
   * heartbeat onload never reaches Y3Q/ge/`new MediaSource`. */
  function MessagePort() {
    var o = this && this !== globalThis ? this : {};
    o._peer = null;
    o._closed = false;
    o.onmessage = null;
    return o;
  }
  MessagePort.prototype.postMessage = native("postMessage", function (data) {
    var peer = this._peer;
    if (!peer || this._closed || peer._closed) return;
    setTimeout(function () {
      var ev, fn;
      if (peer._closed) return;
      ev = { type: "message", data: data };
      fn = peer.onmessage;
      if (typeof fn === "function") {
        try {
          fn.call(peer, ev);
        } catch (e) {}
      }
    }, 0);
  });
  MessagePort.prototype.start = native("start", function () {});
  MessagePort.prototype.close = native("close", function () {
    this._closed = true;
  });
  function MessageChannel() {
    var o = this && this !== globalThis ? this : {};
    var a = new MessagePort();
    var b = new MessagePort();
    a._peer = b;
    b._peer = a;
    o.port1 = a;
    o.port2 = b;
    return o;
  }
  globalThis.MessagePort = native("MessagePort", MessagePort);
  globalThis.MessagePort.prototype = MessagePort.prototype;
  globalThis.MessageChannel = native("MessageChannel", MessageChannel);
  globalThis.MessageChannel.prototype = MessageChannel.prototype;

  /* The ES prototypes Chrome leaves as [object Object]. */
  var UNTAGGED_PROTOS =
    /^(Object|Date|RegExp|(Aggregate|Eval|Range|Reference|Syntax|Type|URI)?Error|(Big)?U?[Ii]nt\d+(Clamped)?Array|Float\d+Array)$/;

  function tagInterfacePrototypes() {
    Object.getOwnPropertyNames(globalThis).forEach(function (n) {
      var ctor = globalThis[n];
      var proto = typeof ctor === "function" && /^[A-Z]/.test(n) ? ctor.prototype : null;
      if (!proto || UNTAGGED_PROTOS.test(n) || Symbol.toStringTag in proto) return;
      if (Object.prototype.toString.call(proto) !== "[object Object]") return;
      Object.defineProperty(proto, Symbol.toStringTag, { value: n, configurable: true });
    });
  }

  function illegalUnless(self, owner) {
    if (self !== owner) throw new TypeError("Illegal invocation");
  }

  /* ES class names don't survive Closure, and Chrome keeps constructor last on these. */
  function nameClasses() {
    ["HTMLMediaElement", "MediaSource", "SourceBuffer"].forEach(function (n) {
      Object.defineProperty(globalThis[n], "name", { value: n, configurable: true });
    });
    ["HTMLVideoElement", "SpeechSynthesis", "SpeechSynthesisEvent"].forEach(function (n) {
      var proto = globalThis[n].prototype;
      delete proto.constructor;
      hide(proto, "constructor", globalThis[n]);
    });
  }
  function readOnlyAttributes(proto, owner, values) {
    Object.keys(values).forEach(function (key) {
      Object.defineProperty(proto, key, {
        get: native("get " + key, function () {
          illegalUnless(this, owner);
          return values[key];
        }),
        enumerable: true,
        configurable: true,
      });
    });
  }

  function installNavigationEntry() {
    var perf = globalThis.performance;
    var navProto = globalThis.PerformanceNavigationTiming.prototype;
    var resourceProto = globalThis.PerformanceResourceTiming.prototype;
    var entryProto = globalThis.PerformanceEntry.prototype;
    var entry = Object.create(navProto);
    var all = {
      name: String(globalThis.location.href),
      entryType: "navigation",
      startTime: 0,
      duration: navTiming.loadEventEnd,
      navigationId: 1,
    };
    readOnlyAttributes(entryProto, entry, all);
    var resource = {
      initiatorType: "navigation",
      nextHopProtocol: "h2",
      deliveryType: "",
      workerStart: 0,
      redirectStart: 0,
      redirectEnd: 0,
      fetchStart: navTiming.fetchStart,
      domainLookupStart: navTiming.domainLookupStart,
      domainLookupEnd: navTiming.domainLookupEnd,
      connectStart: navTiming.connectStart,
      connectEnd: navTiming.connectEnd,
      secureConnectionStart: navTiming.secureConnectionStart,
      requestStart: navTiming.requestStart,
      responseStart: navTiming.responseStart,
      responseEnd: navTiming.responseEnd,
      transferSize: 300 + Math.floor(Math.random() * 200000),
      serverTiming: [],
      renderBlockingStatus: "non-blocking",
      responseStatus: 200,
      contentType: "text/html",
      contentEncoding: "br",
      finalResponseHeadersStart: 0,
      firstInterimResponseStart: 0,
    };
    resource.encodedBodySize = resource.transferSize - 300;
    resource.decodedBodySize = resource.encodedBodySize * 6;
    readOnlyAttributes(resourceProto, entry, resource);
    var navigation = {
      unloadEventStart: 0,
      unloadEventEnd: 0,
      domInteractive: navTiming.domInteractive,
      domContentLoadedEventStart: navTiming.domContentLoadedEventStart,
      domContentLoadedEventEnd: navTiming.domContentLoadedEventEnd,
      domComplete: navTiming.domComplete,
      loadEventStart: navTiming.loadEventStart,
      loadEventEnd: navTiming.loadEventEnd,
      type: "navigate",
      redirectCount: 0,
      criticalCHRestart: 0,
      activationStart: 0,
      notRestoredReasons: null,
    };
    readOnlyAttributes(navProto, entry, navigation);
    hide(
      navProto,
      "toJSON",
      native("toJSON", function () {
        illegalUnless(this, entry);
        return Object.assign({}, all, resource, navigation);
      }),
    );
    hide(
      perf,
      "getEntriesByType",
      native("getEntriesByType", function (type) {
        return String(type) === "navigation" ? [entry] : [];
      }),
    );
    hide(
      perf,
      "getEntries",
      native("getEntries", function () {
        return [entry];
      }),
    );
    hide(
      perf,
      "getEntriesByName",
      native("getEntriesByName", function (name) {
        return String(name) === all.name ? [entry] : [];
      }),
    );
  }

  function installPermissions() {
    var permissions = Object.create(globalThis.Permissions.prototype);
    hide(
      globalThis.Permissions.prototype,
      "query",
      native("query", function (descriptor) {
        if (this !== permissions)
          return Promise.reject(
            new TypeError("Failed to execute 'query' on 'Permissions': Illegal invocation"),
          );
        var status = Object.create(globalThis.PermissionStatus.prototype);
        status.name = String(descriptor && descriptor.name);
        status.state = "prompt";
        status.onchange = null;
        return Promise.resolve(status);
      }),
    );
    readOnlyAttributes(globalThis.Navigator.prototype, globalThis.navigator, {
      permissions: permissions,
    });
  }

  /* macOS US layout, in the order Chrome enumerates it. */
  var US_LAYOUT =
    "KeyK k KeyG g Digit2 2 Digit0 0 KeyV v KeyA a Backquote ` KeyL l IntlBackslash \u00a7 Quote ' " +
    "KeyW w Digit8 8 KeyM m KeyH h Period . Digit7 7 Digit1 1 KeyP p KeyD d KeyF f KeyO o KeyQ q " +
    "KeyC c KeyN n BracketLeft [ KeyZ z KeyY y Digit3 3 Digit6 6 Digit5 5 KeyX x Slash / " +
    "Backslash \\ Comma , Minus - Digit4 4 KeyB b KeyT t Digit9 9 KeyS s KeyI i KeyU u Equal = " +
    "KeyJ j Semicolon ; KeyR r BracketRight ] KeyE e";

  function installKeyboardLayoutMap() {
    var proto = globalThis.KeyboardLayoutMap.prototype;
    var layout = US_LAYOUT.split(" ");
    var keys = new Map();
    for (var i = 0; i < layout.length; i += 2) keys.set(layout[i], layout[i + 1]);
    keyboardLayoutMap = Object.create(proto);
    readOnlyAttributes(proto, keyboardLayoutMap, { size: keys.size });
    var iteratorProto = Object.create(Object.getPrototypeOf(Object.getPrototypeOf([].keys())));
    Object.defineProperty(iteratorProto, Symbol.toStringTag, {
      value: "KeyboardLayoutMap Iterator",
      configurable: true,
    });
    hide(
      iteratorProto,
      "next",
      native("next", function () {
        return iterators.get(this).next();
      }),
    );
    var iterators = new WeakMap();
    function iterating(method) {
      return native(method, function () {
        illegalUnless(this, keyboardLayoutMap);
        var iterator = Object.create(iteratorProto);
        iterators.set(iterator, keys[method]());
        return iterator;
      });
    }
    hide(proto, "entries", iterating("entries"));
    hide(proto, "keys", iterating("keys"));
    hide(proto, "values", iterating("values"));
    hide(proto, Symbol.iterator, proto.entries);
    hide(
      proto,
      "get",
      native("get", function (key) {
        illegalUnless(this, keyboardLayoutMap);
        return keys.get(String(key));
      }),
    );
    hide(
      proto,
      "has",
      native("has", function (key) {
        illegalUnless(this, keyboardLayoutMap);
        return keys.has(String(key));
      }),
    );
    hide(
      proto,
      "forEach",
      native("forEach", function (callback, thisArg) {
        illegalUnless(this, keyboardLayoutMap);
        keys.forEach(function (value, key) {
          callback.call(thisArg, value, key, keyboardLayoutMap);
        });
      }),
    );
  }

  return {
    setImageRequest: function (fn) {
      imageRequest = fn;
    },
    /* The interfaces these fill only exist once chrome_globals.raw.js has run. */
    installChromeBackedApis: function () {
      nameClasses();
      [document.documentElement, document.head, document.body].forEach(function (el) {
        if (el) adoptInterface(el);
      });
      installNavigationEntry();
      installKeyboardLayoutMap();
      tagInterfacePrototypes();
      installPermissions();
      Object.setPrototypeOf(globalThis.navigator, globalThis.Navigator.prototype);
      delete globalThis.navigator.webdriver;
      readOnlyAttributes(globalThis.Navigator.prototype, globalThis.navigator, {
        webdriver: false,
      });
      iframeGlobalNames = Object.getOwnPropertyNames(globalThis).filter(function (n) {
        return !/^(__|\d|versions$|ymix$|yt$|ytcfg$|CLOSURE_FLAGS$|webkitAudioContext$|on(copy|cut|paste)$)/.test(
          n,
        );
      });
    },
    endLayoutProbes: function () {
      layoutProbes = false;
    },
  };
})();

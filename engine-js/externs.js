/**
 * @fileoverview Closure externs — the ONLY names the ADVANCED pass must not rename. This is the
 * complete outside world of the bundle: the C-registered natives (called as bare identifiers), the
 * transport globals C captures by name right after the bundle evals, and the host-evaled
 * `versions` object. Mirrors src/natives.d.ts (the TypeScript view of the same boundary).
 * Wire JSON keys are NOT listed here — they stay literal because the code accesses them
 * quoted/bracketed only (Closure never renames quoted properties).
 * @externs
 */

/* ---- host transport natives (quickjs_native.c / quickjs_jni.c) ---- */

/**
 * @param {number} id
 * @param {string} command
 * @return {number}
 */
function __hostOpen(id, command) {}
/**
 * @param {number} id
 * @param {!Uint8Array} bytes
 * @param {number} off
 * @param {number} len
 * @return {number}
 */
function __hostData(id, bytes, off, len) {}
/**
 * @param {number} id
 * @param {?string} reason
 */
function __hostClose(id, reason) {}
/** @param {number} id */
function __hostCloseRead(id) {}

/* ---- C pipe buffer (quickjs_pipe.c) ---- */

/**
 * @param {number} id
 * @return {*}
 */
function __pbNew(id) {}
/**
 * @param {*} pb
 * @param {!Uint8Array} buf
 * @param {number} off
 * @param {number} len
 * @return {number|!Promise<number>}
 */
function __pbRecv(pb, buf, off, len) {}
/**
 * @param {*} pb
 * @return {number|!Promise<number>}
 */
function __pbInt(pb) {}
/**
 * @param {*} pb
 * @return {number|!Promise<number>}
 */
function __pbLong(pb) {}
/**
 * @param {*} pb
 * @param {boolean} strict
 * @return {?string|!Promise<?string>}
 */
function __pbLine(pb, strict) {}
/**
 * @param {*} pb
 * @param {number} rootType
 * @return {*}
 */
function __pbJson(pb, rootType) {}
/**
 * @param {*} pb
 * @return {string|!Promise<string>}
 */
function __pbUtf8(pb) {}
/**
 * @param {*} pb
 * @return {!Uint8Array|!Promise<!Uint8Array>}
 */
function __pbBytes(pb) {}
/** @param {*} pb */
function __pbCloseRead(pb) {}

/* ---- native UTF-8 (quickjs_pipe.c) ---- */

/**
 * @param {!Uint8Array} bytes
 * @param {number} off
 * @param {number} len
 * @return {string}
 */
function __utf8Decode(bytes, off, len) {}
/**
 * @param {string} str
 * @param {number} from
 * @param {number} to
 * @param {!Uint8Array} buf
 * @param {number} off
 * @return {number}
 */
function __utf8Into(str, from, to, buf, off) {}
/**
 * @param {string} str
 * @param {number} from
 * @param {number} to
 * @return {!Uint8Array}
 */
function __utf8Encode(str, from, to) {}
/**
 * @param {string} str
 * @param {number} from
 * @param {number} to
 * @return {number}
 */
function __utf8Length(str, from, to) {}
/**
 * @param {!Uint8Array} src
 * @param {number} srcOff
 * @param {number} srcLen
 * @param {!Uint8Array} dst
 * @param {number} dstOff
 * @return {number}
 */
function __b64urlInto(src, srcOff, srcLen, dst, dstOff) {}

/* ---- native streaming JSON encoder (quickjs_json.c) ---- */

/**
 * @param {*} value
 * @return {*}
 */
function __jsonEncNew(value) {}
/**
 * @param {*} enc
 * @param {!Uint8Array} buf
 * @param {number} off
 * @return {number}
 */
function __jsonEncFill(enc, buf, off) {}
/**
 * @param {*} enc
 * @return {boolean}
 */
function __jsonEncDone(enc) {}

/**
 * @param {!Uint8Array} bytes
 * @return {*}
 */
function __parseJson(bytes) {}
/**
 * @param {*} value
 * @return {!Uint8Array}
 */
function __stringifyJson(value) {}
/**
 * @param {number=} rootType
 * @return {*}
 */
function __jsonNew(rootType) {}
/**
 * @param {*} p
 * @param {!Uint8Array} bytes
 * @param {number} off
 * @param {number} len
 * @return {number}
 */
function __jsonPush(p, bytes, off, len) {}
/**
 * @param {*} p
 * @return {*}
 */
function __jsonEnd(p) {}

/* ---- transport globals the bundle DEFINES and C captures by name after the eval ---- */

/** @type {function(number, string): number} */
var __jsOpen;
/** @type {function(number)} */
var __jsClose;
/** @type {function(number)} */
var __jsCloseRead;
/** @type {function(number)} */
var __jsDrain;

/* ---- console/diagnostics bridge (quickjs_native.c / quickjs_jni.c) ---- */

/**
 * @param {number} level
 * @param {string} msg
 * @return {undefined}
 */
function __consoleLog(level, msg) {}
/**
 * @param {string} name
 * @param {Function=} impl
 * @param {number=} length
 * @return {!Function}
 */
function __nativeFn(name, impl, length) {}
function __timeZone() {}
function __mseReady() {}
function __mseCurrentTime() {}
function __mseSetCurrentTime(seconds) {}
/**
 * @param {number} speed
 * @param {number} tempo
 * @return {undefined}
 */
function __mseSetSpeed(speed, tempo) {}
function __mseSetPitch(pitch) {}
function __msePlaybackRate() {}
function __mseDuration() {}
function __mseSetDuration(seconds) {}
function __mseEnded() {}
function __mseEndOfStream() {}
function __msePaused() {}
function __mseReadyState() {}
function __msePlay() {}
function __msePause() {}
function __msePrepareAppend(audio, nbytes) {}
function __mseAppend(audio, bytes, tsOff, winStart, winEnd) {}
function __mseRemove(audio, start, end) {}
function __mseAbort(audio) {}
function __mseChangeType(audio) {}
function __mseRestVideo(on) {}
function __mseBuffered(audio) {}
function __mseReset() {}
function __mseAv1Ok() {}
function __mseVp9Ok() {}
function __mseAvcMaxLevel() {}
function __mseDecodingInfo(contentType, width, height, framerate, bitrate) {}
function __mseVideoQuality() {}
/** @return {?} */
function __bgEntropyStart() {}
function __bgEntropyBegin() {}
function __bgEntropyEnd() {}
function __bgEntropyStop() {}
/** @return {?string} */
function __bgEntropyDump() {}

/**
 * @param {string} data
 * @return {string}
 */
function atob(data) {}
/**
 * @param {string} data
 * @return {string}
 */
function btoa(data) {}
/** @constructor */
function TextEncoder() {}
/** @type {string} */
TextEncoder.prototype.encoding;
/**
 * @param {string=} input
 * @return {!Uint8Array}
 */
TextEncoder.prototype.encode = function (input) {};
/**
 * @param {string} input
 * @param {!Uint8Array} dest
 * @return {{read: number, written: number}}
 */
TextEncoder.prototype.encodeInto = function (input, dest) {};
/** @constructor */
function TextDecoder(label) {}
/** @type {string} */
TextDecoder.prototype.encoding;
/**
 * @param {*=} input
 * @return {string}
 */
TextDecoder.prototype.decode = function (input) {};

/* The bundle ASSIGNS globalThis.console (console.ts) — externed so the name and its method
 * names survive the ADVANCED pass (the CUSTOM environment ships no browser console extern). */
var console = {};
/** @param {...*} var_args */
console.log = function (var_args) {};
/** @param {...*} var_args */
console.info = function (var_args) {};
/** @param {...*} var_args */
console.warn = function (var_args) {};
/** @param {...*} var_args */
console.error = function (var_args) {};

/* Host-scheduled timers (transport.ts over setTimeout/setInterval pipes). */
/**
 * @param {function(...*): void} cb
 * @param {number=} ms
 * @param {...*} var_args
 * @return {*}
 */
function setTimeout(cb, ms, var_args) {}
/**
 * @param {function(...*): void} cb
 * @param {number=} ms
 * @param {...*} var_args
 * @return {*}
 */
function setInterval(cb, ms, var_args) {}
/** @param {*=} handle */
function clearTimeout(handle) {}
/** @param {*=} handle */
function clearInterval(handle) {}

/* ---- WHATWG fetch (logic/yt/fetch.ts) — eval'd base.js looks these up on globalThis ---- */

/**
 * @param {*} input
 * @param {*=} init
 * @return {!Promise<*>}
 */
function fetch(input, init) {}

/** @constructor */
function Headers(init) {}
/**
 * @param {string} name
 * @return {?string}
 */
Headers.prototype.get = function (name) {};
/**
 * @param {string} name
 * @return {boolean}
 */
Headers.prototype.has = function (name) {};
/**
 * @param {string} name
 * @param {string} value
 */
Headers.prototype.set = function (name, value) {};
/**
 * @param {string} name
 * @param {string} value
 */
Headers.prototype.append = function (name, value) {};
/** @param {string} name */
Headers.prototype.delete = function (name) {};
/** @param {function(string, string)} cb */
Headers.prototype.forEach = function (cb) {};

/**
 * @constructor
 * @param {*} input
 * @param {*=} init
 */
function Request(input, init) {}
/** @type {string} */
Request.prototype.url;
/** @type {string} */
Request.prototype.method;
/** @type {!Headers} */
Request.prototype.headers;
/** @type {*} */
Request.prototype.body;
/** @type {*} */
Request.prototype.signal;

/**
 * @constructor
 * @param {*=} body
 * @param {*=} init
 */
function Response(body, init) {}
/** @type {number} */
Response.prototype.status;
/** @type {boolean} */
Response.prototype.ok;
/** @type {string} */
Response.prototype.statusText;
/** @type {string} */
Response.prototype.url;
/** @type {!Headers} */
Response.prototype.headers;
/** @type {*} */
Response.prototype.body;
/** @type {boolean} */
Response.prototype.bodyUsed;
/** @type {boolean} */
Response.prototype.redirected;
/** @type {string} */
Response.prototype.type;
/** @return {!Promise<string>} */
Response.prototype.text = function () {};
/** @return {!Promise<*>} */
Response.prototype.json = function () {};
/** @return {!Promise<!ArrayBuffer>} */
Response.prototype.arrayBuffer = function () {};
/** @return {!Response} */
Response.prototype.clone = function () {};

/** @constructor */
function ReadableStream(underlying) {}
/** @type {boolean} */
ReadableStream.prototype.locked;
/** @return {*} */
ReadableStream.prototype.getReader = function () {};
/** @return {!Promise<*>} */
ReadableStream.prototype.cancel = function () {};

/** @constructor */
function ReadableStreamDefaultReader() {}
/** @return {!Promise<*>} */
ReadableStreamDefaultReader.prototype.read = function () {};
/** @return {!Promise<*>} */
ReadableStreamDefaultReader.prototype.cancel = function () {};
ReadableStreamDefaultReader.prototype.releaseLock = function () {};

/** @constructor */
function AbortSignal() {}
/** @type {boolean} */
AbortSignal.prototype.aborted;
/** @type {?function()} */
AbortSignal.prototype.onabort;
/**
 * @param {string} type
 * @param {function()} fn
 */
AbortSignal.prototype.addEventListener = function (type, fn) {};
/**
 * @param {string} type
 * @param {function()} fn
 */
AbortSignal.prototype.removeEventListener = function (type, fn) {};

/** @constructor */
function AbortController() {}
/** @type {!AbortSignal} */
AbortController.prototype.signal;
AbortController.prototype.abort = function () {};

/** @constructor */
function XMLHttpRequest() {}
/** @type {number} */
XMLHttpRequest.UNSENT;
/** @type {number} */
XMLHttpRequest.OPENED;
/** @type {number} */
XMLHttpRequest.HEADERS_RECEIVED;
/** @type {number} */
XMLHttpRequest.LOADING;
/** @type {number} */
XMLHttpRequest.DONE;
/** @type {number} */
XMLHttpRequest.prototype.readyState;
/** @type {number} */
XMLHttpRequest.prototype.status;
/** @type {string} */
XMLHttpRequest.prototype.statusText;
/** @type {*} */
XMLHttpRequest.prototype.response;
/** @type {string} */
XMLHttpRequest.prototype.responseText;
/** @type {string} */
XMLHttpRequest.prototype.responseType;
/** @type {number} */
XMLHttpRequest.prototype.timeout;
/** @type {boolean} */
XMLHttpRequest.prototype.withCredentials;
/** @type {?function()} */
XMLHttpRequest.prototype.onreadystatechange;
/** @type {?function()} */
XMLHttpRequest.prototype.onload;
/** @type {?function()} */
XMLHttpRequest.prototype.onerror;
/** @type {?function()} */
XMLHttpRequest.prototype.ontimeout;
/** @type {?function()} */
XMLHttpRequest.prototype.onabort;
/**
 * @param {string} method
 * @param {string} url
 */
XMLHttpRequest.prototype.open = function (method, url) {};
/** @param {*=} body */
XMLHttpRequest.prototype.send = function (body) {};
/**
 * @param {string} name
 * @param {string} value
 */
XMLHttpRequest.prototype.setRequestHeader = function (name, value) {};
/**
 * @param {string} name
 * @return {?string}
 */
XMLHttpRequest.prototype.getResponseHeader = function (name) {};
/** @return {string} */
XMLHttpRequest.prototype.getAllResponseHeaders = function () {};
XMLHttpRequest.prototype.abort = function () {};
/**
 * @param {string} type
 * @param {function()} fn
 */
XMLHttpRequest.prototype.addEventListener = function (type, fn) {};
/**
 * @param {string} type
 * @param {function()} fn
 */
XMLHttpRequest.prototype.removeEventListener = function (type, fn) {};

/* ---- fake page DOM (env.raw.js) — in-bundle player boot uses dotted names ---- */

/** @constructor */
function Element() {}
/** @type {string} */
Element.prototype.id;
/** @type {string} */
Element.prototype.className;
/** @type {string} */
Element.prototype.tagName;
/** @type {number} */
Element.prototype.clientWidth;
/** @type {number} */
Element.prototype.clientHeight;
/** @type {number} */
Element.prototype.offsetWidth;
/** @type {number} */
Element.prototype.offsetHeight;
/** @type {?Element} */
Element.prototype.parentNode;
/**
 * @param {!Element} child
 * @return {!Element}
 */
Element.prototype.appendChild = function (child) {};

/** @constructor */
function Location() {}
/** @type {string} */
Location.prototype.href;
/** @type {string} */
Location.prototype.protocol;
/** @type {string} */
Location.prototype.host;
/** @type {string} */
Location.prototype.hostname;
/** @type {string} */
Location.prototype.port;
/** @type {string} */
Location.prototype.pathname;
/** @type {string} */
Location.prototype.search;
/** @type {string} */
Location.prototype.hash;
/** @type {string} */
Location.prototype.origin;
/** @return {string} */
Location.prototype.toString = function () {};
/** @return {string} */
Location.prototype.valueOf = function () {};

/** @constructor */
function Document() {}
/** @type {string} */
Document.prototype.URL;
/** @type {string} */
Document.prototype.documentURI;
/** @type {string} */
Document.prototype.domain;
/** @type {!Location} */
Document.prototype.location;
/** @type {!Element} */
Document.prototype.body;
/** @type {!Element} */
Document.prototype.head;
/** @type {!Element} */
Document.prototype.documentElement;
/**
 * @param {string} id
 * @return {?Element}
 */
Document.prototype.getElementById = function (id) {};
/**
 * @param {string} name
 * @return {{length: number}}
 */
Document.prototype.getElementsByTagName = function (name) {};
/**
 * @param {string} name
 * @return {{length: number}}
 */
Document.prototype.getElementsByClassName = function (name) {};
/**
 * @param {string} tagName
 * @return {!Element}
 */
Document.prototype.createElement = function (tagName) {};

/** @type {!Document} */
var document;
/** @type {!Location} */
var location;
/** @type {string} */
var origin;

/* ---- backward-compat versions, installed frozen from C before the bundle runs ---- */

/** @type {{app: number}} */
var versions;

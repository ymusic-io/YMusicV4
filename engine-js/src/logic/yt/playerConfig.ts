// Parse WEB player boot fields out of the youtube.com homepage HTML (ytcfg, EVENT_ID, BotGuard
// program, jsUrl, inline scripts). session.ts stores the result next to the InnerTube session.

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

function extractBalanced(src: string, start: number): string | null {
  let i = start;
  let depth = 0;
  let inStr = false;
  let esc = false;
  let q = "";
  if (src.charAt(i) !== "{") return null;
  for (; i < src.length; i++) {
    const c = src.charAt(i);
    if (inStr) {
      if (esc) esc = false;
      else if (c === "\\") esc = true;
      else if (c === q) inStr = false;
      continue;
    }
    if (c === '"' || c === "'") {
      inStr = true;
      q = c;
      continue;
    }
    if (c === "{") depth++;
    else if (c === "}") {
      depth--;
      if (depth === 0) return src.slice(start, i + 1);
    }
  }
  return null;
}

export function parseYtcfg(html: string): Record<string, unknown> {
  const merged: Record<string, unknown> = {};
  let from = 0;
  for (;;) {
    const i = html.indexOf("ytcfg.set(", from);
    if (i < 0) break;
    let p = i + 10;
    while (p < html.length && html.charAt(p) === " ") p++;
    if (html.charAt(p) === "{") {
      const raw = extractBalanced(html, p);
      if (raw) {
        try {
          const obj = JSON.parse(raw) as Record<string, unknown>;
          const keys = Object.keys(obj);
          for (let k = 0; k < keys.length; k++) merged[keys[k]!] = obj[keys[k]!];
        } catch (_) {}
      }
    }
    from = i + 10;
  }
  return merged;
}

function ytcfgField(html: string, key: string): string {
  const needle = '"' + key + '"';
  const at = html.indexOf(needle);
  if (at < 0) return "";
  const colon = html.indexOf(":", at + needle.length);
  if (colon < 0) return "";
  let i = colon + 1;
  while (i < html.length && (html.charAt(i) === " " || html.charAt(i) === "\n")) i++;
  if (html.charAt(i) === '"') {
    const end = html.indexOf('"', i + 1);
    return end > i ? html.slice(i + 1, end) : "";
  }
  let j = i;
  while (j < html.length && /[0-9A-Za-z._-]/.test(html.charAt(j))) j++;
  return html.slice(i, j);
}

function extractJsQuoted(src: string, start: number): { value: string; end: number } | null {
  const q = src.charAt(start);
  if (q !== "'" && q !== '"') return null;
  let i = start + 1;
  let out = "";
  for (; i < src.length; i++) {
    const c = src.charAt(i);
    if (c === "\\") {
      out += c;
      if (i + 1 < src.length) {
        out += src.charAt(i + 1);
        i++;
      }
      continue;
    }
    if (c === q) return { value: out, end: i + 1 };
    out += c;
  }
  return null;
}

function decodeJsString(s: string): string {
  let out = "";
  for (let i = 0; i < s.length; i++) {
    const c = s.charAt(i);
    if (c !== "\\") {
      out += c;
      continue;
    }
    const n = s.charAt(i + 1);
    if (n === "x" && i + 3 < s.length) {
      const h = parseInt(s.slice(i + 2, i + 4), 16);
      if (!isNaN(h)) {
        out += String.fromCharCode(h);
        i += 3;
        continue;
      }
    }
    if (n === "u" && i + 5 < s.length) {
      const h = parseInt(s.slice(i + 2, i + 6), 16);
      if (!isNaN(h)) {
        out += String.fromCharCode(h);
        i += 5;
        continue;
      }
    }
    if (n === "n") {
      out += "\n";
      i++;
      continue;
    }
    if (n === "r") {
      out += "\r";
      i++;
      continue;
    }
    if (n === "t") {
      out += "\t";
      i++;
      continue;
    }
    out += n || "";
    i++;
  }
  return out;
}

function unwrapTru(v: unknown): string {
  if (typeof v === "string") return v;
  if (v && typeof v === "object") {
    const o = v as Record<string, unknown>;
    const w =
      o["privateDoNotAccessOrElseTrustedResourceUrlWrappedValue"] ??
      o["privateDoNotAccessOrElseSafeScriptWrappedValue"];
    if (typeof w === "string") return w;
  }
  return "";
}

/** The `'T'` value after ytAtN's `'R'` response: the eacrToken that binds an att/get challenge to
 *  this page's EVENT_ID. */
function ytAtNToken(html: string, from: number): string {
  const single = html.indexOf("'T':", from);
  const at = single >= 0 && single - from <= 16 ? single : html.indexOf('"T":', from);
  if (at < 0 || at - from > 16) return "";
  let p = at + 4;
  while (p < html.length && html.charAt(p) === " ") p++;
  const quoted = extractJsQuoted(html, p);
  return quoted ? decodeJsString(quoted.value) : "";
}

function parseYtAtN(html: string): Record<string, unknown> | null {
  const markers = ["ytAtN({'R':", 'ytAtN({"R":'];
  for (let k = 0; k < markers.length; k++) {
    const i = html.indexOf(markers[k]!);
    if (i < 0) continue;
    let p = i + markers[k]!.length;
    while (p < html.length && html.charAt(p) === " ") p++;
    const quoted = extractJsQuoted(html, p);
    if (!quoted) continue;
    try {
      const obj = JSON.parse(decodeJsString(quoted.value)) as Record<string, unknown>;
      const bg = obj["bgChallenge"];
      if (
        bg &&
        typeof bg === "object" &&
        typeof (bg as Record<string, unknown>)["program"] === "string"
      ) {
        const eacr = ytAtNToken(html, quoted.end);
        if (eacr) (bg as Record<string, unknown>)["eacrToken"] = eacr;
        return bg as Record<string, unknown>;
      }
    } catch (_) {}
  }
  return null;
}

function jsUrlFromCtx(ctx: Record<string, unknown> | null): string {
  if (!ctx) return "";
  const trusted = unwrapTru(ctx["trustedJsUrl"]);
  if (trusted) return iasPlayerUrl(trusted);
  return typeof ctx["jsUrl"] === "string" ? iasPlayerUrl(ctx["jsUrl"] as string) : "";
}

function iasPlayerUrl(src: string): string {
  return src.replace(/\/player_\w+\.vflset/g, "/player_es6.vflset");
}

function extractJsUrl(ytcfg: Record<string, unknown>, html: string): string {
  const ctxs = asRec(ytcfg["WEB_PLAYER_CONTEXT_CONFIGS"]);
  if (ctxs) {
    const watch = jsUrlFromCtx(asRec(ctxs["WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH"]));
    if (watch) return watch;
    const keys = Object.keys(ctxs);
    for (let i = 0; i < keys.length; i++) {
      const u = jsUrlFromCtx(asRec(ctxs[keys[i]!]));
      if (u) return u;
    }
  }
  const needle = '"jsUrl"';
  const at = html.indexOf(needle);
  if (at < 0) return "";
  const colon = html.indexOf(":", at + needle.length);
  if (colon < 0) return "";
  let i = colon + 1;
  while (i < html.length && (html.charAt(i) === " " || html.charAt(i) === "\n")) i++;
  const quoted = extractJsQuoted(html, i);
  return quoted ? iasPlayerUrl(quoted.value) : "";
}

function extractInlineScripts(html: string): string[] {
  const out: string[] = [];
  const needles = [
    "ytcfg",
    "ytcsi",
    "PLAYER_VARS",
    "ytAtN",
    "ytAtP",
    "bgChallenge",
    "WIZ_global",
    "yt.player",
  ];
  const lower = html.toLowerCase();
  let from = 0;
  for (;;) {
    const open = lower.indexOf("<script", from);
    if (open < 0) break;
    const tagEnd = html.indexOf(">", open);
    if (tagEnd < 0) break;
    const attrs = lower.slice(open, tagEnd);
    const close = lower.indexOf("</script>", tagEnd);
    if (close < 0) break;
    from = close + 9;
    if (attrs.indexOf("src=") >= 0) continue;
    const body = html.slice(tagEnd + 1, close).trim();
    if (!body) continue;
    for (let i = 0; i < needles.length; i++) {
      if (body.indexOf(needles[i]!) >= 0) {
        out.push(body);
        break;
      }
    }
  }
  return out;
}

/** Null when the homepage has no BotGuard program or player jsUrl. */
export function parsePlayerConfig(html: string): Record<string, unknown> | null {
  const ytcfg = parseYtcfg(html);
  const eventId =
    (typeof ytcfg["EVENT_ID"] === "string" ? (ytcfg["EVENT_ID"] as string) : "") ||
    ytcfgField(html, "EVENT_ID");
  if (eventId && ytcfg["EVENT_ID"] === undefined) ytcfg["EVENT_ID"] = eventId;
  const bg = parseYtAtN(html);
  if (!bg || typeof bg["program"] !== "string") return null;
  const jsUrl = extractJsUrl(ytcfg, html);
  if (!jsUrl || !eventId) return null;
  const challenge: Record<string, unknown> = {
    "program": bg["program"],
    "globalName": String(bg["globalName"] ?? "trayride"),
  };
  const interpJs = unwrapTru(bg["interpreterJavascript"]);
  const interpUrl = unwrapTru(bg["interpreterUrl"]) || unwrapTru(bg["interpreterSafeUrl"]);
  if (typeof bg["interpreterHash"] === "string")
    challenge["interpreterHash"] = bg["interpreterHash"];
  if (typeof bg["eacrToken"] === "string") challenge["eacrToken"] = bg["eacrToken"];
  if (interpJs) challenge["interpreterJavascript"] = interpJs;
  if (interpUrl) challenge["interpreterUrl"] = interpUrl;
  return {
    "ytcfg": ytcfg,
    "eventId": eventId,
    "bgChallenge": challenge,
    "jsUrl": jsUrl,
    "inlineScripts": extractInlineScripts(html),
  };
}

// The JS half of the logging-context channel (HOST_PROTOCOLS "Logging context"): an immutable
// crumb-trail logger mirroring kmpcommon.log.Logger. A host command mints one from its args head
// ("logKey" — the key of the Kotlin caller's parked logger), forks its own hops, and attaches it
// to host calls; the wire shape ("log": {"key","trail"}) grafts the trail back onto the parked
// logger so host birth-site logs join the caller's story. There is no log() here — console output
// already crosses on its own channel.

// One trail hop: [tag, props | null, enteredAtMillis]. Tags are STABLE names (crash grouping keys are
// built from tag paths); anything variable (ids, params) goes in the props object, values JSON
// primitives. Hosts before 4.0.12 read only [0] and an object [1]. Wire keys quoted — these cross
// to Kotlin as JSON (LogContext.kt).
export type LogCrumb = [string, Record<string, unknown> | null, number];

// A crumb minted during a BotGuard mill must not draw on the recorded Date.now; the global goes
// before BotGuard can enumerate it.
const hostNow: () => number = takeHostNow();

function takeHostNow(): () => number {
  if (typeof __hostNow !== "function") return Date.now;
  const fn = __hostNow;
  delete (globalThis as unknown as Record<string, unknown>)["__hostNow"];
  return fn;
}

function crumb(tag: string, prop?: Record<string, unknown>): LogCrumb {
  return [tag, prop === undefined ? null : prop, hostNow()];
}

// Every JS-side tag is "js:"-prefixed (JS hops stay distinguishable from Kotlin ones in a trail)
// and slash-free ("/" is the rendered path separator — a slash inside a tag would fake two hops).
function jsTag(tag: string): string {
  const t = tag.replace(/\//g, "-");
  return t.indexOf("js:") === 0 ? t : "js:" + t;
}

// Every trail this bundle emits opens with its own name + revision (engine:<version>/js:<job>/…, or
// player:<version>/…) so a report names the bundle that produced it. Baked here, not host-side:
// the version is the bundle's fact.
export const VERSION_TAG: string = BUNDLE_NAME + ":" + BACKEND_VERSION;

// The trail of a request made without a logger: just the bundle-revision root.
export function rootTrail(): LogWire {
  return { "trail": [crumb(VERSION_TAG)] };
}

export interface LogWire {
  "key"?: number;
  "trail": LogCrumb[];
}

export class JsLogger {
  private key: number | null;
  private trail: LogCrumb[];

  constructor(key: number | null, trail: LogCrumb[]) {
    this.key = key;
    this.trail = trail;
  }

  fork(tag: string, prop?: Record<string, unknown>): JsLogger {
    const t = this.trail.slice();
    t.push(crumb(jsTag(tag), prop));
    return new JsLogger(this.key, t);
  }

  wire(): LogWire {
    const w: LogWire = { "trail": this.trail };
    if (this.key !== null) w["key"] = this.key;
    return w;
  }
}

// The logger for one host-command invocation: the Kotlin caller's parked key from the args head
// (absent on JS-initiated work) plus this command's own hop — always, even when the Kotlin hop
// names the same call: the boundary crossing is its own event, and this side's name is the
// BUNDLE's (remotely updatable, free to diverge from the app-side function).
export function hostLogger(args: Record<string, unknown>, tag: string): JsLogger {
  const k = args["logKey"];
  return new JsLogger(typeof k === "number" ? k : null, [crumb(VERSION_TAG), crumb(jsTag(tag))]);
}

// A keyless logger for JS-initiated work (session bootstrap, crons) — resolves to the engine's
// root logger host-side.
export function jsLogger(tag: string): JsLogger {
  return new JsLogger(null, [crumb(VERSION_TAG), crumb(jsTag(tag))]);
}

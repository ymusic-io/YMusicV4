// localStorage for eval'd base.js. Keys are yt-player-* (volume, quality, bandwidth, memory, …).
// Sync Storage API, hydrated from / flushed to the host cache scope `yt.player.ls`.

import { scopedCache, keepCount } from "../../protocol/cache";

const SCOPE = "yt.player.ls";
const KEEP = 64;

function makeMemoryStorage(): Record<string, unknown> {
  const data: Record<string, string> = {};
  const keys: string[] = [];
  const s: Record<string, unknown> = {};
  s["getItem"] = function (k: string): string | null {
    k = String(k);
    return Object.prototype.hasOwnProperty.call(data, k) ? data[k]! : null;
  };
  s["setItem"] = function (k: string, v: string): void {
    k = String(k);
    v = String(v);
    if (!Object.prototype.hasOwnProperty.call(data, k)) keys.push(k);
    data[k] = v;
    s[k] = v;
  };
  s["removeItem"] = function (k: string): void {
    k = String(k);
    delete data[k];
    delete s[k];
    const i = keys.indexOf(k);
    if (i >= 0) keys.splice(i, 1);
  };
  s["clear"] = function (): void {
    for (let i = 0; i < keys.length; i++) delete s[keys[i]!];
    keys.length = 0;
    const ks = Object.keys(data);
    for (let i = 0; i < ks.length; i++) delete data[ks[i]!];
  };
  s["key"] = function (i: number): string | null {
    return i >= 0 && i < keys.length ? keys[i]! : null;
  };
  Object.defineProperty(s, "length", {
    configurable: true,
    get: function () {
      return keys.length;
    },
  });
  return s;
}

/** Hydrate localStorage from cache (miss / no cache host → empty). sessionStorage stays memory.
 *  Once per engine — not per player/load. */
async function hydrate(): Promise<void> {
  const g = globalThis as unknown as Record<string, unknown>;
  const ls = makeMemoryStorage();
  const ss = makeMemoryStorage();
  let store: Awaited<ReturnType<typeof scopedCache>> | null = null;
  try {
    store = await scopedCache(SCOPE, keepCount(KEEP));
    const entries = await store.list();
    for (let i = 0; i < entries.length; i++) {
      const k = entries[i]!.key;
      const v = await store.getText(k);
      if (v != null) (ls["setItem"] as (a: string, b: string) => void)(k, v);
    }
  } catch (e) {
    store = null;
    console.warn(
      "player localStorage cache unavailable: " + (e instanceof Error ? e.message : String(e)),
    );
  }
  if (store) {
    const persist = store;
    const origSet = ls["setItem"] as (k: string, v: string) => void;
    const origRemove = ls["removeItem"] as (k: string) => void;
    ls["setItem"] = function (k: string, v: string): void {
      origSet(k, v);
      persist.setText(String(k), String(v)).then(undefined, function (err: unknown) {
        console.warn(
          "player localStorage persist failed: " +
            (err instanceof Error ? err.message : String(err)),
        );
      });
    };
    ls["removeItem"] = function (k: string): void {
      origRemove(k);
      persist.delete(String(k)).then(undefined, function (err: unknown) {
        console.warn(
          "player localStorage delete failed: " +
            (err instanceof Error ? err.message : String(err)),
        );
      });
    };
  }
  g["localStorage"] = ls;
  g["sessionStorage"] = ss;
}

/** True when base.js's Storage rejected a saved value: it throws on every load until the value is gone. */
export function isBadStoredValue(e: unknown): boolean {
  return (e instanceof Error ? e.message : String(e)).indexOf("Storage: Invalid value") >= 0;
}

export function clearPlayerStorage(): void {
  const ls = (globalThis as unknown as Record<string, unknown>)["localStorage"] as Record<
    string,
    unknown
  >;
  const key = ls["key"] as (i: number) => string | null;
  const remove = ls["removeItem"] as (k: string) => void;
  for (let k = key(0); k !== null; k = key(0)) remove(k);
}

let ready: Promise<void> | undefined;

export function playerStorageReady(): Promise<void> {
  if (!ready) ready = hydrate();
  return ready;
}

playerStorageReady();

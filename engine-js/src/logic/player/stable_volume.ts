// The app's Stable volume setting as base.js's Stable Volume preference (the DRC audio track).
// The loudness gain is the host's to drop.

import { Settings } from "../../protocol/settings";
import { onEvent } from "../../transport";

const STABLE_VOLUME_KEY = "playback.stableVolume";

// base.js: g.Iw("yt-player-drc-pref", 1|0, 31536E3); unset reads as 1 (on).
const DRC_PREF_KEY = "yt-player-drc-pref";
const DRC_PREF_TTL_MS = 31536e6;

let stableVolume = true;

/** The setting as last pulled; on until a pull says otherwise. */
export function stableVolumeOn(): boolean {
  return stableVolume;
}

/** Re-reads the host setting. A host without a settings store answers on (today's behaviour). */
export async function pullStableVolume(): Promise<boolean> {
  try {
    stableVolume = (await Settings.get<boolean>(STABLE_VOLUME_KEY, true)) !== false;
  } catch (e) {
    console.warn(
      "stable volume setting unavailable, keeping on: " +
        (e instanceof Error ? e.message : String(e)),
    );
    stableVolume = true;
  }
  return stableVolume;
}

/** Puts base.js's preference in storage before Application.create reads it: on = unset, off = 0
 *  in base.js's own expiring-storage encoding. */
export function writeDrcPref(): void {
  const ls = (globalThis as unknown as Record<string, unknown>)["localStorage"] as Record<
    string,
    unknown
  >;
  if (stableVolume) {
    const getItem = ls["getItem"] as (k: string) => string | null;
    if (getItem(DRC_PREF_KEY) !== null) (ls["removeItem"] as (k: string) => void)(DRC_PREF_KEY);
    return;
  }
  const now = Date.now();
  (ls["setItem"] as (k: string, v: string) => void)(
    DRC_PREF_KEY,
    JSON.stringify({ "data": "0", "expiration": now + DRC_PREF_TTL_MS, "creation": now }),
  );
}

/** The host fires `settings_changed` after the user flips a setting this bundle reads. */
export function onStableVolumeChange(apply: (on: boolean) => void): void {
  onEvent("settings_changed", function () {
    const before = stableVolume;
    pullStableVolume().then(function (on) {
      if (on !== before) apply(on);
    });
  });
}

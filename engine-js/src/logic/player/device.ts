// The host device from `versions` (QuickJsRuntime.hostVersions). The screen replaces env.raw.js's
// fixed desktop one only after the first BotGuard snapshot and is swapped back for re-mints, so every
// snapshot sees the same env on every host; brand/model are there for device-specific fixes.

export interface HostDevice {
  os: string;
  osVersion: string;
  brand: string;
  model: string;
}

export function hostDevice(): HostDevice {
  return {
    os: versions.os ?? "",
    osVersion: versions.osVersion ?? "",
    brand: versions.brand ?? "",
    model: versions.model ?? "",
  };
}

const SCREEN_KEYS = ["width", "height", "availWidth", "availHeight"];
const WINDOW_KEYS = ["innerWidth", "innerHeight", "outerWidth", "outerHeight", "devicePixelRatio"];

function screenObject(): Record<string, unknown> | undefined {
  return (globalThis as unknown as Record<string, unknown>)["screen"] as
    Record<string, unknown> | undefined;
}

/** Screen + window geometry as BotGuard reads it, keyed "screen.width" / "innerWidth" / … */
function captureScreen(): Record<string, unknown> {
  const g = globalThis as unknown as Record<string, unknown>;
  const screen = screenObject();
  const out: Record<string, unknown> = {};
  if (screen) {
    SCREEN_KEYS.forEach(function (k) {
      out["screen." + k] = screen[k];
    });
    const orientation = screen["orientation"] as Record<string, unknown> | undefined;
    if (orientation) out["screen.orientation"] = orientation["type"];
  }
  WINDOW_KEYS.forEach(function (k) {
    out[k] = g[k];
  });
  return out;
}

function restoreScreen(values: Record<string, unknown>): void {
  const g = globalThis as unknown as Record<string, unknown>;
  const screen = screenObject();
  if (screen) {
    SCREEN_KEYS.forEach(function (k) {
      screen[k] = values["screen." + k];
    });
    const orientation = screen["orientation"] as Record<string, unknown> | undefined;
    if (orientation) orientation["type"] = values["screen.orientation"];
  }
  WINDOW_KEYS.forEach(function (k) {
    g[k] = values[k];
  });
}

/** The env's desktop geometry and the host's, swapped back for re-mints: BotGuard reads both. */
let envScreen: Record<string, unknown> | null = null;
let hostScreen: Record<string, unknown> | null = null;

/** The real display as YouTube's quality cap reads it (screen, window size, devicePixelRatio). */
export function applyHostScreen(): void {
  const w = versions.screenWidth;
  const h = versions.screenHeight;
  const density = versions.screenDensity;
  if (!(
    w !== undefined &&
    w > 0 &&
    h !== undefined &&
    h > 0 &&
    density !== undefined &&
    density > 0
  )) {
    console.info("host screen: none from this host, env screen kept");
    return;
  }
  const cssW = Math.round(w / density);
  const cssH = Math.round(h / density);
  envScreen = captureScreen();
  restoreScreen({
    "screen.width": cssW,
    "screen.height": cssH,
    "screen.availWidth": cssW,
    "screen.availHeight": cssH,
    "screen.orientation": cssW >= cssH ? "landscape-primary" : "portrait-primary",
    "innerWidth": cssW,
    "innerHeight": cssH,
    "outerWidth": cssW,
    "outerHeight": cssH,
    "devicePixelRatio": density,
  });
  hostScreen = captureScreen();
  const device = hostDevice();
  console.info(
    "host screen " + cssW + "x" + cssH + "@" + density + " " + device.os + " " + device.osVersion,
  );
}

/** Runs [fn] with the env's desktop geometry back in place, as the first snapshot saw it. */
export function withEnvScreen<T>(fn: () => Promise<T>): Promise<T> {
  const env = envScreen;
  const host = hostScreen;
  if (!env || !host) return fn();
  restoreScreen(env);
  return fn().then(
    function (value) {
      restoreScreen(host);
      return value;
    },
    function (e: unknown) {
      restoreScreen(host);
      throw e;
    },
  );
}

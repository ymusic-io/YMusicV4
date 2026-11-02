// The host device from `versions` (QuickJsRuntime.hostVersions). The screen replaces env.raw.js's
// fixed desktop one only after the BotGuard snapshot, so the snapshot sees the same env on every
// host; brand/model are there for device-specific fixes in a remote bundle.

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
  const g = globalThis as unknown as Record<string, unknown>;
  const screen = g["screen"] as Record<string, unknown> | undefined;
  if (screen) {
    screen["width"] = cssW;
    screen["height"] = cssH;
    screen["availWidth"] = cssW;
    screen["availHeight"] = cssH;
    const orientation = screen["orientation"] as Record<string, unknown> | undefined;
    if (orientation) orientation["type"] = cssW >= cssH ? "landscape-primary" : "portrait-primary";
  }
  g["innerWidth"] = cssW;
  g["innerHeight"] = cssH;
  g["outerWidth"] = cssW;
  g["outerHeight"] = cssH;
  g["devicePixelRatio"] = density;
  const device = hostDevice();
  console.info(
    "host screen " + cssW + "x" + cssH + "@" + density + " " + device.os + " " + device.osVersion,
  );
}

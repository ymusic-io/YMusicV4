// Display viewport YouTube ABR sees (player clientWidth/Height).
// Picture size (adaptiveFormats width/height) is listen/player/status videoWidth/Height.
// Default 360p; host overrides with the real hole size.

const DEFAULT_W = 640;
const DEFAULT_H = 360;
/** Real pixels (CSS × devicePixelRatio): a smaller hole (mini player, PiP) still reports 240p. */
const MIN_REAL_H = 240;

let viewportW = DEFAULT_W;
let viewportH = DEFAULT_H;

/** CSS pixels per the floor at the current devicePixelRatio (real after the BotGuard snapshot). */
function floorScale(): number {
  const dpr = Number((globalThis as unknown as Record<string, unknown>)["devicePixelRatio"]) || 1;
  const minCssH = MIN_REAL_H / dpr;
  return viewportH < minCssH ? minCssH / viewportH : 1;
}

export function viewportWidth(): number {
  return Math.round(viewportW * floorScale());
}

export function viewportHeight(): number {
  return Math.round(viewportH * floorScale());
}

/** Missing/invalid values keep the current size (starts at 640×360). */
export function applyViewport(width: unknown, height: unknown): void {
  const w = Number(width);
  const h = Number(height);
  if (isFinite(w) && w > 0) viewportW = Math.round(w);
  if (isFinite(h) && h > 0) viewportH = Math.round(h);
}

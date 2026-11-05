/** Minimum and maximum glide while a token receives a stream of updates (e.g. another player dragging it). */
const STREAM_MIN_MS = 50;
const STREAM_MAX_MS = 250;
/** An update arriving later than this after the previous one starts a fresh glide sized by distance. */
const IDLE_GAP_MS = 400;
const GLIDE_MS_PER_CELL = 60;
const GLIDE_MIN_MS = 120;
const GLIDE_MAX_MS = 400;
/** Jumps further than this (in grid cells) snap instead of sliding across the map. */
const SNAP_CELLS = 40;

interface Motion {
  fromX: number;
  fromY: number;
  toX: number;
  toY: number;
  start: number;
  duration: number;
  changed: number;
}

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

/**
 * Display positions for tokens whose authoritative position changes in steps. Each new target glides linearly from
 * the currently shown position; for a stream of updates the glide spans roughly the update interval, so motion is
 * continuous instead of jumping at the network rate.
 */
export class TokenMotion {
  private motions = new Map<string, Motion>();
  /** True when the last {@link position} calls left at least one token mid-glide. */
  animating = false;

  /** Returns where the token should be drawn at `now`; call once per token per frame. */
  position(id: string, x: number, y: number, cellSize: number, now: number): { x: number; y: number } {
    let m = this.motions.get(id);
    if (!m) {
      this.motions.set(id, { fromX: x, fromY: y, toX: x, toY: y, start: now, duration: 0, changed: -Infinity });
      return { x, y };
    }
    if (m.toX !== x || m.toY !== y) {
      const shown = this.at(m, now);
      const distance = Math.hypot(x - shown.x, y - shown.y);
      const gap = now - m.changed;
      const duration = distance > SNAP_CELLS * cellSize ? 0
        : gap < IDLE_GAP_MS ? clamp(gap * 1.25, STREAM_MIN_MS, STREAM_MAX_MS)
          : clamp(distance / Math.max(1, cellSize) * GLIDE_MS_PER_CELL, GLIDE_MIN_MS, GLIDE_MAX_MS);
      m = { fromX: shown.x, fromY: shown.y, toX: x, toY: y, start: now, duration, changed: now };
      this.motions.set(id, m);
    }
    const p = this.at(m, now);
    if (p.x !== m.toX || p.y !== m.toY) this.animating = true;
    return p;
  }

  /** Shows the token at its current position from now on (e.g. while it is dragged locally). */
  settle(id: string, x: number, y: number, now: number): void {
    this.motions.set(id, { fromX: x, fromY: y, toX: x, toY: y, start: now, duration: 0, changed: now });
  }

  /** Forgets tokens that no longer exist; with no ids, forgets all (map change). */
  retain(ids?: Set<string>): void {
    if (!ids) this.motions.clear();
    else for (const id of this.motions.keys()) if (!ids.has(id)) this.motions.delete(id);
  }

  private at(m: Motion, now: number): { x: number; y: number } {
    const k = m.duration <= 0 ? 1 : clamp((now - m.start) / m.duration, 0, 1);
    return k >= 1 ? { x: m.toX, y: m.toY } : { x: m.fromX + (m.toX - m.fromX) * k, y: m.fromY + (m.toY - m.fromY) * k };
  }
}

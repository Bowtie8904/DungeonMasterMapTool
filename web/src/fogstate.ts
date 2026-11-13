import { Grid } from './vision';

export function gridKey(g: Grid): string {
  return `${g.originX},${g.originY},${g.cellSize},${g.cols},${g.rows}`;
}

/** Explored/visible/revealed cell sets for one fog grid, plus the offscreen fog bitmap. */
export class FogState {
  grid: Grid | null = null;
  key = '';
  memory: Uint8Array = new Uint8Array(0);
  dmRevealed: Uint8Array = new Uint8Array(0);
  /** When the DM drew a fog mask it decides what is hidden apart from temporary sight and light reveals. */
  dmControlled = false;
  explored: Uint8Array = new Uint8Array(0);
  visible: Uint8Array = new Uint8Array(0);
  private pending: Uint8Array = new Uint8Array(0);
  private hasPending = false;
  private canvas: HTMLCanvasElement | null = null;
  private softCanvas: HTMLCanvasElement | null = null;
  private softCells = 0;
  private current: Float32Array = new Float32Array(0);
  private prevVisible: Uint8Array = new Uint8Array(0);
  private primed = false;
  private lastTick = 0;
  /** True while a fade is still running and the fog needs another frame. */
  animating = false;
  bitmapDirty = true;

  /** Reallocates when the grid (and therefore the cell indexing) changes. Returns true when it changed. */
  configure(grid: Grid | null): boolean {
    const key = grid ? gridKey(grid) : '';
    if (key === this.key) {
      return false;
    }
    this.key = key;
    this.grid = grid;
    const n = grid ? grid.cols * grid.rows : 0;
    this.memory = new Uint8Array(n);
    this.dmRevealed = new Uint8Array(n);
    this.explored = new Uint8Array(n);
    this.visible = new Uint8Array(n);
    this.pending = new Uint8Array(n);
    this.hasPending = false;
    this.canvas = null;
    this.softCanvas = null;
    this.bitmapDirty = true;
    return true;
  }

  setMemory(flags: Uint8Array): void {
    if (flags.length !== this.memory.length) return;
    this.memory = flags;
    for (let i = 0; i < flags.length; i++) {
      if (flags[i]) this.explored[i] = 1;
    }
    this.bitmapDirty = true;
  }

  setDmRevealed(flags: Uint8Array): void {
    if (flags.length !== this.dmRevealed.length) return;
    this.dmRevealed = flags;
    this.bitmapDirty = true;
  }

  /** Stores the currently visible cells and queues newly explored ones for the host. */
  setVisible(visible: Uint8Array, commit = true, exploredVisible = visible): boolean {
    if (visible.length !== this.visible.length) return false;
    this.visible = visible;
    let added = false;
    if (commit) for (let i = 0; i < visible.length; i++) {
      if (exploredVisible[i] && !this.explored[i]) {
        this.explored[i] = 1;
        this.pending[i] = 1;
        added = true;
      }
    }
    this.hasPending ||= added;
    this.bitmapDirty = true;
    return added;
  }

  get hasNewExplored(): boolean {
    return this.hasPending;
  }

  takePending(): Uint8Array | null {
    if (!this.hasPending) return null;
    const out = this.pending;
    this.pending = new Uint8Array(out.length);
    this.hasPending = false;
    return out;
  }

  /** Puts cells back after a failed send. */
  restorePending(cells: Uint8Array): void {
    if (cells.length !== this.pending.length) return;
    for (let i = 0; i < cells.length; i++) if (cells[i]) this.pending[i] = 1;
    this.hasPending = true;
  }

  cellIndex(x: number, y: number): number {
    const g = this.grid;
    if (!g) return -1;
    const c = Math.floor((x - g.originX) / g.cellSize);
    const r = Math.floor((y - g.originY) / g.cellSize);
    if (c < 0 || r < 0 || c >= g.cols || r >= g.rows) return -1;
    return r * g.cols + c;
  }

  isVisibleOrRevealed(x: number, y: number): boolean {
    const i = this.cellIndex(x, y);
    return i >= 0 && (this.visible[i] === 1 || this.dmRevealed[i] === 1);
  }

  /** Uses the displayed, unblurred fog opacity so soft edges cannot expose hidden objects. */
  hasUncoveredCircle(x: number, y: number, radius: number): boolean {
    const g = this.grid;
    if (!g || !this.canvas || radius <= 0) return false;
    const left = Math.max(0, Math.floor((x - radius - g.originX) / g.cellSize));
    const right = Math.min(g.cols - 1, Math.floor((x + radius - g.originX) / g.cellSize));
    const top = Math.max(0, Math.floor((y - radius - g.originY) / g.cellSize));
    const bottom = Math.min(g.rows - 1, Math.floor((y + radius - g.originY) / g.cellSize));
    for (let row = top; row <= bottom; row++) {
      for (let col = left; col <= right; col++) {
        if (Math.round(this.current[row * g.cols + col]) >= 255) continue;
        const x1 = g.originX + col * g.cellSize;
        const y1 = g.originY + row * g.cellSize;
        const dx = Math.max(x1 - x, 0, x - (x1 + g.cellSize));
        const dy = Math.max(y1 - y, 0, y - (y1 + g.cellSize));
        if (dx * dx + dy * dy < radius * radius) return true;
      }
    }
    return false;
  }

  /**
   * Alpha per cell: visible 0, remembered/revealed ~55% black, unknown opaque. With `fade` the alpha eases towards
   * that target (linear, like the desktop), and `softCells` blurs the edge. Check `animating` to keep redrawing.
   */
  bitmap(opts: { softCells?: number; fade?: { reveal: number; hide: number }; nowMs?: number } = {}): HTMLCanvasElement | null {
    const g = this.grid;
    if (!g || g.cols <= 0 || g.rows <= 0) return null;
    const n = g.cols * g.rows;
    if (!this.canvas) {
      this.canvas = document.createElement('canvas');
      this.canvas.width = g.cols;
      this.canvas.height = g.rows;
      this.bitmapDirty = true;
      this.current = new Float32Array(n);
      this.primed = false;
    }
    const now = opts.nowMs ?? performance.now();
    const dt = this.animating && this.lastTick !== 0 ? Math.min(0.1, (now - this.lastTick) / 1000) : 1 / 60;
    this.lastTick = now;
    let moving = false;
    if (this.bitmapDirty || this.animating) {
      const cur = this.current;
      const fade = this.primed ? opts.fade : undefined;
      for (let i = 0; i < n; i++) {
        const target = this.dmControlled
          ? (this.visible[i] || this.dmRevealed[i] ? 0 : 255)
          : this.visible[i] || this.dmRevealed[i] ? 0 : this.explored[i] ? 140 : 255;
        // Changes caused by the player's own vision are instant; only DM reveals fade.
        const visionChanged = this.visible[i] !== this.prevVisible[i];
        if (!fade || this.visible[i] || visionChanged) {
          cur[i] = target;
          continue;
        }
        const diff = target - cur[i];
        if (diff === 0) continue;
        const seconds = Math.max(0.01, diff < 0 ? fade.reveal : fade.hide);
        const step = (255 * dt) / seconds;
        if (Math.abs(diff) <= step) {
          cur[i] = target;
        } else {
          cur[i] += Math.sign(diff) * step;
          moving = true;
        }
      }
      this.primed = true;
      this.prevVisible = this.visible.slice();
      const ctx = this.canvas.getContext('2d')!;
      const img = ctx.createImageData(g.cols, g.rows);
      const d = img.data;
      for (let i = 0; i < n; i++) d[i * 4 + 3] = Math.round(cur[i]);
      ctx.putImageData(img, 0, 0);
      this.bitmapDirty = false;
      this.softCanvas = null;
    }
    this.animating = moving;
    const soft = opts.softCells ?? 0;
    if (soft <= 0.2) return this.canvas;
    if (!this.softCanvas || this.softCells !== soft) {
      this.softCells = soft;
      // Blur on a black-padded copy so the grid edge does not leak transparency inwards.
      const pad = Math.ceil(soft * 3);
      const tmp = document.createElement('canvas');
      tmp.width = g.cols + pad * 2;
      tmp.height = g.rows + pad * 2;
      const tctx = tmp.getContext('2d')!;
      // opaque black ring around the transparent-where-visible bitmap
      tctx.fillStyle = '#000';
      tctx.fillRect(0, 0, tmp.width, pad);
      tctx.fillRect(0, tmp.height - pad, tmp.width, pad);
      tctx.fillRect(0, pad, pad, g.rows);
      tctx.fillRect(tmp.width - pad, pad, pad, g.rows);
      tctx.drawImage(this.canvas, pad, pad);
      const blurred = document.createElement('canvas');
      blurred.width = tmp.width;
      blurred.height = tmp.height;
      const bctx = blurred.getContext('2d')!;
      bctx.filter = `blur(${soft}px)`;
      bctx.drawImage(tmp, 0, 0);
      const out = document.createElement('canvas');
      out.width = g.cols;
      out.height = g.rows;
      out.getContext('2d')!.drawImage(blurred, -pad, -pad);
      this.softCanvas = out;
    }
    return this.softCanvas;
  }
}

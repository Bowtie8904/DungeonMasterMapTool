import { base64ToBytes, bitsToFlags } from './bitset';
import { flickerAmount } from './flicker';
import { FogState } from './fogstate';
import { TokenMotion } from './motion';
import { lightActive, rasterLightTint, TintEnvironment } from './lighting';
import { Door, GameState, IndoorData, Measurement, Overlay, TextBoxState, Token } from './protocol';
import { DrawMode, DrawingStyle, MapTool, drawingBounds, drawingHit, drawingId, measurementBounds, measurementHit, measurementLabel, measurementPath, ownsDrawing, resizeDrawing, translateDrawing } from './tools';
import { chooseLevel, PyramidInfo, tileUrl, visibleTiles } from './pyramid';
import {
  blockingSegments,
  cellsInPolygons,
  computeVisibility,
  computeVisibleCells,
  Grid,
  Polygon,
} from './vision';
import { applyWeatherConfig, drawWeather, flashStyle, lightningFlash, weatherIndoors, weatherKind } from './weather';
import { conditionIconId, conditionIconUrl } from './conditions';

const AMBIENT_SIGHT_TILES = 40;
const MIN_FRAME_MS = 33;
const MOVE_TICK_MS = 50;

export interface MoveCommit {
  tokenId: string;
  x: number;
  y: number;
  prevX: number;
  prevY: number;
}

interface Camera {
  x: number;
  y: number;
  scale: number;
}

export class MapView {
  state: GameState | null = null;
  playerId = '';
  campaignId = '';
  tool: MapTool = 'navigate';
  drawMode: DrawMode = 'pen';
  measureMode: Measurement['kind'] = 'distance';
  measurementColor = '#80d8ff';
  style: DrawingStyle = { color: '#55aa33', alpha: 1, texture: 'none', size: 2, border: false, emits: false, above: true };
  measurements: Measurement[] = [];
  onDrawing: (action: 'create' | 'update' | 'delete', id: string, shape?: Overlay) => void = () => {};
  onMeasurement: (value: Measurement, remove: boolean) => void = () => {};
  onSelection: (shape: Overlay | null, measurement: boolean, count?: number) => void = () => {};
  onSelectTool: () => void = () => {};
  canPing = false;
  onPing: (x: number, y: number) => void = () => {};
  private pingHold: { timer: ReturnType<typeof setTimeout>; pointerId: number; x: number; y: number } | null = null;
  onUndo: () => void = () => {};
  private selectedDrawings = new Set<string>();
  private selectedTokens = new Set<string>();
  private selectedMeasures = new Set<string>();
  private marquee: { start: { x: number; y: number }; end: { x: number; y: number } } | null = null;
  private group: { start: { x: number; y: number }; end: { x: number; y: number }; shapes: Overlay[]; tokens: Token[]; measures: Measurement[] } | null = null;
  private hover: { x: number; y: number } | null = null;
  private selectedDrawing: string | null = null;
  private selectedMeasure: string | null = null;
  private gesture: { start: { x: number; y: number }; shape?: Overlay; measurement?: Measurement; before?: Overlay; move?: Measurement; resize?: boolean; published?: boolean; create: boolean } | null = null;
  private lastMeasureTick = 0;
  private destroyed = false;
  private resizeObserver: ResizeObserver;
  paused = false;
  readonly fog = new FogState();
  maxParticles = 1500;
  /** Glide other tokens between position updates instead of jumping; off saves redraws on slow devices. */
  smoothMovement = true;
  private readonly motion = new TokenMotion();
  /** Positions tokens are drawn at this frame (interpolated); hit-testing and vision use the real positions. */
  private shown = new Map<string, { x: number; y: number }>();
  private moving = false;
  serverNow: () => number = () => Date.now();
  onMove: (m: MoveCommit) => void = () => {};
  canRotateTokens = false;
  canControlPortals = false;
  onPortal: (id: string) => void = () => {};
  onRotate: (tokenId: string, rotationDeg: number) => void = () => {};
  canEditConditions = false;
  onTokenContext: (token: Token, screenX: number, screenY: number) => boolean = () => false;
  private rotation: { tokenId: string; startAngle: number; before: number; angle: number } | null = null;
  /** Fired (throttled by the caller) when new explored cells exist. */
  onExplored: () => void = () => {};

  private ctx: CanvasRenderingContext2D;
  private light: HTMLCanvasElement = document.createElement('canvas');
  private tmp: HTMLCanvasElement = document.createElement('canvas');
  private lightTint: HTMLCanvasElement = document.createElement('canvas');
  private fx: HTMLCanvasElement = document.createElement('canvas');
  private fxMask: HTMLCanvasElement = document.createElement('canvas');
  private brushMask = document.createElement('canvas');
  private brushBorder = document.createElement('canvas');
  private brushBorderKey = '';
  private maskCache = new Map<string, { key: string; canvas: HTMLCanvasElement; ox: number; oy: number; cell: number }>();
  private zoneCache: { src: IndoorData; outside: Path2D; exposure: Map<string, HTMLCanvasElement> } | null = null;
  private indoorCache: { key: string; src: IndoorData; ambient: HTMLCanvasElement; inside: HTMLCanvasElement; flags: Uint8Array } | null = null;
  private textureActive = false;
  private pingActive = false;
  private cam: Camera = { x: 0, y: 0, scale: 1 };
  private dpr = 1;
  private width = 0;
  private height = 0;
  private dirty = true;
  private visionDirty = true;
  private lastTick = 0;
  private inFlight = 0;
  private lastFrame = 0;
  private images = new Map<string, HTMLImageElement>();
  private tiles = new Map<string, HTMLImageElement>();
  private static readonly MAX_TILES = 128;
  private lightPolys = new Map<string, Polygon>();
  private sights: Array<{ token: Token; sight: Polygon }> = [];
  private segmentsCache: number[] = [];
  private fitted = '';
  private drag: { kind: 'pan' | 'token'; moved?: boolean; token?: Token; startX: number; startY: number; prevX: number; prevY: number; camX: number; camY: number } | null = null;

  constructor(private canvas: HTMLCanvasElement) {
    this.ctx = canvas.getContext('2d')!;
    canvas.style.touchAction = 'none';
    canvas.tabIndex = 0;
    canvas.addEventListener('pointerdown', (e) => { this.pointerDown(e); this.updateCursor(); });
    canvas.addEventListener('pointermove', (e) => this.pointerMove(e));
    canvas.addEventListener('pointerup', (e) => { this.pointerUp(e); this.updateCursor(); });
    canvas.addEventListener('pointercancel', e => {
      this.cancelPingHold();
      if (this.drag?.kind === 'pan') {
        this.drag = null;
        if (canvas.hasPointerCapture(e.pointerId)) canvas.releasePointerCapture(e.pointerId);
        this.invalidate();
        return;
      }
      if (this.rotation || this.gesture || this.marquee || this.group) this.cancelGesture(e);
      else this.pointerUp(e);
    });
    canvas.addEventListener('contextmenu', e => {
      e.preventDefault();
      const world = this.toWorld(e);
      const token = this.state ? this.tokenAt(world.x, world.y) : null;
      if (token && this.canEditConditions) this.onTokenContext(token, e.clientX, e.clientY);
    });
    canvas.addEventListener('pointerleave', () => { this.hover = null; this.invalidate(); });
    canvas.addEventListener('blur', () => { this.cancelPingHold(); if (this.rotation) this.cancelGesture(); });
    canvas.addEventListener('lostpointercapture', () => this.cancelPingHold());
    canvas.addEventListener('keydown', (e) => {
      if (this.nudgeTokens(e)) return;
      if ((e.ctrlKey || e.metaKey) && !e.shiftKey && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        if (this.gesture?.shape) this.cancelGesture();
        else this.onUndo();
      }
      if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); this.deleteSelection(); }
      if (e.key === 'Escape') { this.cancelGesture(); this.setTool('navigate'); this.onSelectTool(); }
    });
    canvas.addEventListener('wheel', (e) => this.wheel(e), { passive: false });
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(canvas);
    this.resize();
    requestAnimationFrame((t) => this.frame(t));
  }

  invalidate(): void {
    if (this.paused || !this.canPing) this.cancelPingHold();
    this.dirty = true;
    this.updateCursor();
  }

  destroy(): void {
    this.cancelPingHold();
    this.destroyed = true;
    this.resizeObserver.disconnect();
    this.images.clear(); this.tiles.clear(); this.maskCache.clear();
  }

  /** Call after walls/doors/lights/tokens/lighting/fog changes. */
  invalidateVision(): void {
    this.visionDirty = true;
    this.invalidate();
  }

  setState(state: GameState | null): void {
    if (!state || state.paused || state.mapId !== this.state?.mapId || state.levelId !== this.state?.levelId) this.cancelPingHold();
    if (state && this.state && (state.mapId !== this.state.mapId || state.levelId !== this.state.levelId)) {
      this.gesture = null;
      this.rotation = null;
      this.drag = null;
      this.marquee = this.group = null;
      this.clearSelection();
      this.selectedDrawing = this.selectedMeasure = null;
      this.measurements = [];
      this.onSelection(null, false);
      this.motion.retain();
    }
    if (state) this.motion.retain(new Set(state.tokens.map((t) => t.id)));
    if (this.selectedDrawing && !state?.overlays?.some(o => o.id === this.selectedDrawing && ownsDrawing(o, this.playerId, this.campaignId))) {
      this.selectedDrawing = null; this.onSelection(null, false);
    }
    this.state = state;
    for (const id of this.selectedTokens) {
      if (!state?.tokens.some(t => t.id === id && this.isMine(t))) this.selectedTokens.delete(id);
    }
    if (this.rotation && !state?.tokens.some(t => t.id === this.rotation?.tokenId && this.isMine(t))) this.rotation = null;
    const d = this.drag;
    if (state && d?.kind === 'token' && d.token) {
      // Host updates lag behind the live drag; keep the dragged token under the pointer.
      const fresh = state.tokens.find((t) => t.id === d.token!.id);
      if (fresh) {
        fresh.x = d.token.x;
        fresh.y = d.token.y;
        d.token = fresh;
      }
    }
    if (state) {
      const key = `${state.mapId}/${state.levelId}`;
      if (key !== this.fitted) {
        this.fitted = key;
        this.fit();
      }
    }
    this.lightPolys.clear();
    this.invalidateVision();
  }

  setTool(tool: MapTool): void {
    this.cancelGesture();
    this.tool = tool;
    this.marquee = this.group = null;
    if (tool !== 'draw') {
      this.clearSelection();
      this.onSelection(null, false);
    }
    this.invalidate();
  }

  editStyle(): void {
    const shape = this.state?.overlays?.find(o => o.id === this.selectedDrawing);
    if (!shape || this.paused || !ownsDrawing(shape, this.playerId, this.campaignId)) return;
    const ink = shape.type === 'pen' || shape.type === 'line';
    this.onDrawing('update', shape.id, { ...shape, color: this.style.color, alpha: this.style.alpha,
      texture: ink ? 'none' : this.style.texture, border: !ink && this.style.border,
      emits: !ink && this.style.emits, above: ink || this.style.above,
      strokeWidth: shape.type === 'pen' ? shape.strokeWidth : this.style.size * this.state!.grid.pixelsPerCell });
  }

  refreshSelection(): void {
    if (this.gesture) return;
    const shape = this.state?.overlays?.find(o => o.id === this.selectedDrawing && ownsDrawing(o, this.playerId, this.campaignId));
    if (shape) this.onSelection(shape, false);
  }

  deleteSelection(): void {
    if (this.paused) return;
    if (this.selectedDrawings.size || this.selectedMeasures.size) {
      for (const shape of this.state?.overlays ?? []) {
        if (this.selectedDrawings.has(shape.id) && ownsDrawing(shape, this.playerId, this.campaignId)) this.onDrawing('delete', shape.id);
      }
      for (const m of this.measurements) {
        if (this.selectedMeasures.has(m.id) && m.ownerId === this.playerId) this.onMeasurement(m, true);
      }
      this.clearSelection();
    }
    const shape = this.state?.overlays?.find(o => o.id === this.selectedDrawing);
    if (shape && ownsDrawing(shape, this.playerId, this.campaignId)) this.onDrawing('delete', shape.id);
    const measure = this.measurements.find(m => m.id === this.selectedMeasure && m.ownerId === this.playerId);
    if (measure) this.onMeasurement(measure, true);
    this.selectedDrawing = this.selectedMeasure = null;
    this.onSelection(null, false);
    this.invalidate();
  }

  private clearSelection(): void {
    this.selectedDrawings.clear(); this.selectedTokens.clear(); this.selectedMeasures.clear();
    this.selectedDrawing = this.selectedMeasure = null;
  }

  private measurementDeleteAt(x: number, y: number): Measurement | undefined {
    return [...this.measurements].reverse().find(m => m.ownerId === this.playerId
      && Math.abs(x - m.x2 - 12 / this.cam.scale) <= 9 / this.cam.scale
      && Math.abs(y - m.y2 - 12 / this.cam.scale) <= 9 / this.cam.scale);
  }

  setMeasurements(items: Measurement[]): void {
    this.measurements = items;
    if (this.selectedMeasure && !items.some(m => m.id === this.selectedMeasure)) {
      this.selectedMeasure = null;
      this.onSelection(null, false);
    }
    if (this.gesture?.move && !items.some(m => m.id === this.gesture?.move?.id)) this.gesture = null;
    this.invalidate();
  }

  private cancelPingHold(): void {
    if (this.pingHold) clearTimeout(this.pingHold.timer);
    this.pingHold = null;
  }

  private cancelGesture(e?: PointerEvent): void {
    this.cancelPingHold();
    this.rotation = null;
    this.marquee = this.group = null;
    const g = this.gesture;
    if (g?.published && g.measurement && !this.paused) {
      if (g.create) this.onMeasurement(g.measurement, true);
      else if (g.move) this.onMeasurement(g.move, false);
    }
    this.gesture = null;
    if (e && this.canvas.hasPointerCapture(e.pointerId)) this.canvas.releasePointerCapture(e.pointerId);
    this.invalidate();
  }

  /** Applies a relayed move of another connection's token ahead of the next state update. */
  tokenMoved(t: Token, x: number, y: number): void {
    const d = this.drag;
    if (d?.kind === 'token' && d.token?.id === t.id) return;
    if (t.x === x && t.y === y) return;
    t.x = x;
    t.y = y;
    if (this.isMine(t)) this.invalidateVision();
    else this.invalidate();
  }

  /** Called when the host corrects a token position; keeps an active drag consistent with it. */
  corrected(tokenId: string, x: number, y: number): void {
    const d = this.drag;
    if (d?.kind === 'token' && d.token?.id === tokenId) {
      d.prevX = x;
      d.prevY = y;
    }
  }

  /** Number of unacknowledged move commands; while non-zero (or dragging) newly seen cells are not committed. */
  setMovesInFlight(n: number): void {
    const wasTentative = this.tentative();
    this.inFlight = n;
    if (wasTentative && !this.tentative()) this.invalidateVision();
  }

  private tentative(): boolean {
    return this.inFlight > 0 || this.drag?.kind === 'token';
  }

  isMine(t: Token): boolean {
    return !!t.controlledBy && t.controlledBy.includes(this.playerId);
  }

  /** Centre the camera on a token. */
  focus(t: Token): void {
    this.cam.x = this.width / 2 - t.x * this.cam.scale;
    this.cam.y = this.height / 2 - t.y * this.cam.scale;
    this.dirty = true;
  }

  private fit(): void {
    const s = this.state;
    if (!s) return;
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const im of s.images) {
      minX = Math.min(minX, im.x);
      minY = Math.min(minY, im.y);
      maxX = Math.max(maxX, im.x + im.width);
      maxY = Math.max(maxY, im.y + im.height);
    }
    if (!Number.isFinite(minX)) {
      minX = 0;
      minY = 0;
      maxX = 1000;
      maxY = 1000;
    }
    const w = Math.max(1, maxX - minX);
    const h = Math.max(1, maxY - minY);
    this.cam.scale = Math.min(this.width / w, this.height / h) * 0.95 || 1;
    this.cam.x = (this.width - w * this.cam.scale) / 2 - minX * this.cam.scale;
    this.cam.y = (this.height - h * this.cam.scale) / 2 - minY * this.cam.scale;
  }

  private resize(): void {
    this.dpr = window.devicePixelRatio || 1;
    this.width = this.canvas.clientWidth;
    this.height = this.canvas.clientHeight;
    this.canvas.width = Math.max(1, Math.round(this.width * this.dpr));
    this.canvas.height = Math.max(1, Math.round(this.height * this.dpr));
    this.light.width = this.canvas.width;
    this.light.height = this.canvas.height;
    for (const c of [this.tmp, this.fx, this.fxMask]) {
      c.width = this.canvas.width;
      c.height = this.canvas.height;
    }
    if (this.fitted && this.state && this.cam.scale === 1) this.fit();
    this.dirty = true;
  }

  private toWorld(e: { clientX: number; clientY: number }): { x: number; y: number } {
    const r = this.canvas.getBoundingClientRect();
    return { x: (e.clientX - r.left - this.cam.x) / this.cam.scale, y: (e.clientY - r.top - this.cam.y) / this.cam.scale };
  }

  private tokenAt(x: number, y: number): Token | null {
    const s = this.state;
    if (!s) return null;
    for (let i = s.tokens.length - 1; i >= 0; i--) {
      const t = s.tokens[i];
      if (!this.isMine(t)) continue;
      if (this.tokenHit(t, x, y)) return t;
    }
    return null;
  }

  private portalAt(x: number, y: number): Door | undefined {
    if (!this.canControlPortals) return undefined;
    return [...this.state?.doors ?? []].reverse().find(d =>
      Math.hypot(x - (d.x1 + d.x2) / 2, y - (d.y1 + d.y2) / 2) <= 12 / this.cam.scale);
  }

  private tokenHit(t: Token, x: number, y: number): boolean {
    const r = t.sizeTiles * this.state!.grid.pixelsPerCell / 2;
    const dx = x - t.x, dy = y - t.y;
    if (t.shape !== 'square') return Math.hypot(dx, dy) <= r;
    const angle = (t.rotationDeg ?? 0) * Math.PI / 180;
    return Math.abs(dx * Math.cos(angle) + dy * Math.sin(angle)) <= r
      && Math.abs(-dx * Math.sin(angle) + dy * Math.cos(angle)) <= r;
  }

  private nudgeTokens(e: KeyboardEvent): boolean {
    const directions: Record<string, [number, number]> = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
    const direction = directions[e.key];
    if (!direction || e.ctrlKey || e.metaKey || e.altKey || this.paused || this.tool !== 'navigate'
      || this.drag || this.rotation || this.gesture || this.group || this.marquee || !this.state) return false;
    const tokens = this.state.tokens.filter(t => this.selectedTokens.has(t.id) && this.isMine(t));
    if (!tokens.length) return false;
    e.preventDefault();
    const step = this.state.grid.pixelsPerCell / 2;
    for (const t of tokens) {
      const prevX = t.x, prevY = t.y;
      t.x += direction[0] * step; t.y += direction[1] * step;
      this.onMove({ tokenId: t.id, x: t.x, y: t.y, prevX, prevY });
    }
    this.invalidateVision();
    return true;
  }

  private rotationHandle(t: Token): { x: number; y: number } {
    const radius = t.sizeTiles * this.state!.grid.pixelsPerCell / 2 + 24 / this.cam.scale;
    const angle = (this.rotation?.tokenId === t.id ? this.rotation.angle : t.rotationDeg ?? 0) * Math.PI / 180;
    return { x: t.x + Math.sin(angle) * radius, y: t.y - Math.cos(angle) * radius };
  }

  private selectionAt(x: number, y: number) {
    const s = this.state;
    if (!s) return null;
    if (this.canRotateTokens) {
      const token = [...s.tokens].reverse().find(t => {
        if (!this.selectedTokens.has(t.id) || !this.isMine(t)) return false;
        const handle = this.rotationHandle(t);
        return Math.hypot(x - handle.x, y - handle.y) <= 10 / this.cam.scale;
      });
      if (token) return { kind: 'rotation', token } as const;
    }
    const hitGroup = (s.overlays ?? []).some(o => this.selectedDrawings.has(o.id) && ownsDrawing(o, this.playerId, this.campaignId)
      && drawingHit(o, x, y, 6 / this.cam.scale))
      || s.tokens.some(t => this.selectedTokens.has(t.id) && this.isMine(t)
        && this.tokenHit(t, x, y))
      || this.measurements.some(m => this.selectedMeasures.has(m.id) && m.ownerId === this.playerId
        && measurementHit(m, x, y, 10 / this.cam.scale));
    if (hitGroup && !(this.selectedTokens.size === 1 && !this.selectedDrawings.size && !this.selectedMeasures.size))
      return { kind: 'group' } as const;
    const measure = [...this.measurements].reverse().find(m => m.ownerId === this.playerId && measurementHit(m, x, y, 10 / this.cam.scale));
    if (measure) return { kind: 'measure', measure } as const;
    const selected = s.overlays?.find(o => o.id === this.selectedDrawing && ownsDrawing(o, this.playerId, this.campaignId));
    const bounds = selected ? drawingBounds(selected) : null;
    const resize = !!bounds && Math.hypot(x - bounds[2], y - bounds[3]) < 12 / this.cam.scale;
    const shape = resize ? selected : [...s.overlays ?? []].reverse().find(o =>
      ownsDrawing(o, this.playerId, this.campaignId) && drawingHit(o, x, y, 6 / this.cam.scale));
    const token = this.tokenAt(x, y);
    if (shape && (resize || !token)) return { kind: 'shape', shape, resize } as const;
    return token ? { kind: 'token', token } as const : null;
  }

  private updateCursor(): void {
    let cursor = 'default';
    if (this.drag?.kind === 'pan') cursor = 'grabbing';
    else if (!this.paused && this.state) {
      if (this.tool === 'ping') cursor = 'crosshair';
      else if (this.rotation) cursor = 'grabbing';
      else if (this.gesture?.resize) cursor = 'nwse-resize';
      else if (this.group || this.drag?.kind === 'token' || this.gesture && !this.gesture.create) cursor = 'grabbing';
      else if (this.gesture?.create) cursor = 'crosshair';
      else if (this.hover && this.measurementDeleteAt(this.hover.x, this.hover.y)) cursor = 'pointer';
      else if (this.tool !== 'navigate') cursor = 'crosshair';
      else if (this.hover && !this.marquee) {
        const hit = this.selectionAt(this.hover.x, this.hover.y);
        if (hit) cursor = hit.kind === 'shape' && hit.resize ? 'nwse-resize' : 'grab';
        else if (this.portalAt(this.hover.x, this.hover.y)) cursor = 'pointer';
      }
    }
    this.canvas.style.cursor = cursor;
  }

  private pointerDown(e: PointerEvent): void {
    this.cancelPingHold();
    this.canvas.focus();
    this.canvas.setPointerCapture(e.pointerId);
    const w = this.toWorld(e);
    this.hover = w;
    const s = this.state;
    if (e.button === 2) {
      const token = s ? this.tokenAt(w.x, w.y) : undefined;
      if (token && this.canEditConditions) {
        e.preventDefault();
        return;
      }
      this.cancelGesture();
      this.drag = { kind: 'pan', startX: e.clientX, startY: e.clientY, prevX: 0, prevY: 0, camX: this.cam.x, camY: this.cam.y };
      return;
    }
    if (e.button !== 0) return;
    if (s && !this.paused && e.button === 0) {
      if (this.tool === 'ping') {
        if (this.canPing) this.onPing(w.x, w.y);
        this.setTool('navigate');
        this.onSelectTool();
        return;
      }
      const remove = this.measurementDeleteAt(w.x, w.y);
      if (remove) { this.onMeasurement(remove, true); this.invalidate(); return; }
      if (this.tool !== 'navigate') {
        this.clearSelection();
        this.onSelection(null, false);
      }
      if (this.tool === 'draw') {
        const ink = this.drawMode === 'pen' || this.drawMode === 'line';
        const shape: Overlay = { id: drawingId(), ownerId: this.playerId, ownerCampaignId: this.campaignId,
          type: this.drawMode, x: w.x, y: w.y, width: 0, height: 0, radius: 0,
          points: ['pen', 'brush', 'line'].includes(this.drawMode) ? [w.x, w.y] : [],
          strokeWidth: (this.drawMode === 'pen' ? s.penWidthCells ?? 0.06 : this.style.size) * s.grid.pixelsPerCell,
          color: this.style.color, alpha: this.style.alpha, texture: ink ? 'none' : this.style.texture,
          border: !ink && this.style.border, emits: !ink && this.style.emits, above: ink || this.style.above };
        if (this.drawMode === 'line') shape.points.push(w.x, w.y);
        this.gesture = { start: w, shape, create: true };
        this.invalidate(); return;
      }
      if (this.tool === 'measure') {
        this.gesture = { start: w, create: true, measurement: { id: drawingId(), ownerId: this.playerId,
          kind: this.measureMode, color: this.measurementColor, x1: w.x, y1: w.y, x2: w.x, y2: w.y } };
        this.invalidate(); return;
      }
      const hit = this.selectionAt(w.x, w.y);
      const portal = !hit ? this.portalAt(w.x, w.y) : undefined;
      if (portal) {
        if (!portal.locked) this.onPortal(portal.id);
        return;
      }
      if (hit?.kind === 'rotation') {
        const t = hit.token;
        this.rotation = { tokenId: t.id, startAngle: Math.atan2(w.y - t.y, w.x - t.x),
          before: t.rotationDeg ?? 0, angle: t.rotationDeg ?? 0 };
        this.invalidate(); return;
      }
      if (this.canPing && this.tool === 'navigate' && !e.shiftKey && !e.ctrlKey && !e.metaKey) {
        this.pingHold = { pointerId: e.pointerId, x: e.clientX, y: e.clientY,
          timer: setTimeout(() => {
            this.pingHold = null;
            if (this.destroyed || this.paused || !this.canPing || this.tool !== 'navigate'
              || this.state?.mapId !== s.mapId || this.state?.levelId !== s.levelId) return;
            this.cancelGesture();
            this.drag = null;
            this.onPing(w.x, w.y);
          }, 600) };
      }
      if (hit?.kind === 'group') {
        this.group = { start: w, end: w,
          shapes: (s.overlays ?? []).filter(o => this.selectedDrawings.has(o.id) && ownsDrawing(o, this.playerId, this.campaignId)),
          tokens: s.tokens.filter(t => this.selectedTokens.has(t.id) && this.isMine(t)).map(t => ({ ...t })),
          measures: this.measurements.filter(m => this.selectedMeasures.has(m.id) && m.ownerId === this.playerId) };
        return;
      }
      this.selectedDrawings.clear(); this.selectedTokens.clear(); this.selectedMeasures.clear();
      const measure = hit?.kind === 'measure' ? hit.measure : null;
      if (measure) {
        this.selectedMeasure = measure.id; this.selectedDrawing = null;
        this.gesture = { start: w, measurement: { ...measure }, move: { ...measure }, create: false };
        this.onSelection(null, true); this.invalidate(); return;
      }
      const shape = hit?.kind === 'shape' ? hit.shape : null;
      const resize = hit?.kind === 'shape' && hit.resize;
      if (shape) {
        this.selectedDrawing = shape.id; this.selectedMeasure = null;
        this.gesture = { start: w, shape: { ...shape, points: [...shape.points] }, before: shape, resize, create: false };
        this.onSelection(shape, false); this.invalidate(); return;
      }
      this.selectedDrawing = this.selectedMeasure = null;
      this.onSelection(null, false);
    }
    const t = this.paused ? null : this.tokenAt(w.x, w.y);
    if (t) { this.selectedTokens.add(t.id); this.onSelection(null, false, 1); this.invalidate(); }
    this.drag = t
      ? { kind: 'token', token: t, startX: e.clientX, startY: e.clientY, prevX: t.x, prevY: t.y, camX: 0, camY: 0 }
      : null;
    if (!t && s && !this.paused) this.marquee = { start: w, end: w };
  }

  private pointerMove(e: PointerEvent): void {
    if (this.pingHold) {
      if (e.pointerId !== this.pingHold.pointerId || Math.hypot(e.clientX - this.pingHold.x, e.clientY - this.pingHold.y) > 4) {
        this.cancelPingHold();
      } else return;
    }
    this.hover = this.toWorld(e);
    if (this.drag?.kind === 'pan') {
      const d = this.drag;
      d.moved ||= Math.hypot(e.clientX - d.startX, e.clientY - d.startY) > 4;
      if (d.moved) {
        this.cam.x = d.camX + e.clientX - d.startX;
        this.cam.y = d.camY + e.clientY - d.startY;
      }
      this.hover = this.toWorld(e);
      this.invalidate();
      return;
    }
    this.updateCursor();
    if (this.rotation) {
      const token = this.state?.tokens.find(t => t.id === this.rotation?.tokenId && this.isMine(t));
      if (token && !this.paused && this.canRotateTokens && Math.hypot(this.hover.x - token.x, this.hover.y - token.y) > 0.001) {
        const angle = Math.atan2(this.hover.y - token.y, this.hover.x - token.x);
        this.rotation.angle = (this.rotation.before + (angle - this.rotation.startAngle) * 180 / Math.PI + 360) % 360;
      }
      this.invalidate(); return;
    }
    if (this.marquee || this.group) {
      if (this.marquee) this.marquee.end = this.hover;
      if (this.group) this.group.end = this.hover;
      this.invalidate(); return;
    }
    if (this.tool === 'draw') this.invalidate();
    const g = this.gesture;
    if (g && this.state && !this.paused) {
      const w = this.toWorld(e);
      if (g.shape) {
        if (g.before) g.shape = g.resize ? resizeDrawing(g.before, w.x, w.y) : translateDrawing(g.before, w.x - g.start.x, w.y - g.start.y);
        else if (g.shape.type === 'circle') g.shape.radius = Math.hypot(w.x - g.start.x, w.y - g.start.y);
        else if (g.shape.type === 'rect') { g.shape.x = Math.min(w.x, g.start.x); g.shape.y = Math.min(w.y, g.start.y); g.shape.width = Math.abs(w.x - g.start.x); g.shape.height = Math.abs(w.y - g.start.y); }
        else if (g.shape.type === 'line') g.shape.points = [g.start.x, g.start.y, w.x, w.y];
        else {
          const p = g.shape.points;
          if (Math.hypot(w.x - p[p.length - 2], w.y - p[p.length - 1]) * this.cam.scale > 2) {
            if (p.length >= 2048) g.shape.points = p.filter((_, i) => Math.floor(i / 2) % 2 === 0);
            g.shape.points.push(Math.round(w.x * 100) / 100, Math.round(w.y * 100) / 100);
          }
        }
      }
      if (g.measurement) {
        g.measurement = g.move ? { ...g.move, x1: g.move.x1 + w.x - g.start.x, y1: g.move.y1 + w.y - g.start.y,
          x2: g.move.x2 + w.x - g.start.x, y2: g.move.y2 + w.y - g.start.y } : { ...g.measurement, x2: w.x, y2: w.y };
        if (performance.now() - this.lastMeasureTick > 100) {
          this.lastMeasureTick = performance.now(); this.onMeasurement(g.measurement, false);
          g.published = true;
        }
      }
      this.invalidate(); return;
    }
    const d = this.drag;
    if (!d || !this.state) return;
    if (d.token && !this.paused) {
      const w = this.toWorld(e);
      const step = this.state.grid.pixelsPerCell / 2;
      const nx = e.shiftKey ? w.x : Math.round(w.x / step) * step;
      const ny = e.shiftKey ? w.y : Math.round(w.y / step) * step;
      if (nx !== d.token.x || ny !== d.token.y) {
        d.token.x = nx;
        d.token.y = ny;
        this.invalidateVision();
        this.sendTick(d, false);
      }
    }
    this.dirty = true;
  }

  private pointerUp(e: PointerEvent): void {
    this.cancelPingHold();
    if (this.canvas.hasPointerCapture(e.pointerId)) this.canvas.releasePointerCapture(e.pointerId);
    if (this.rotation) {
      const rotation = this.rotation; this.rotation = null;
      if (!this.paused && this.canRotateTokens && this.state?.tokens.some(t => t.id === rotation.tokenId && this.isMine(t))
        && rotation.angle !== rotation.before) this.onRotate(rotation.tokenId, rotation.angle);
      this.invalidate(); return;
    }
    if (this.drag?.kind === 'pan') {
      const d = this.drag;
      this.drag = null;
      const moved = d.moved || Math.hypot(e.clientX - d.startX, e.clientY - d.startY) > 4;
      if (moved) {
        this.cam.x = d.camX + e.clientX - d.startX;
        this.cam.y = d.camY + e.clientY - d.startY;
      } else {
        this.setTool('navigate');
        this.onSelectTool();
      }
      this.hover = this.toWorld(e);
      this.invalidate();
      return;
    }
    if (this.marquee) {
      const { start, end } = this.marquee; this.marquee = null;
      this.clearSelection();
      const left = Math.min(start.x, end.x), right = Math.max(start.x, end.x);
      const top = Math.min(start.y, end.y), bottom = Math.max(start.y, end.y);
      const inside = (x1: number, y1: number, x2: number, y2: number) => x1 >= left && y1 >= top && x2 <= right && y2 <= bottom;
      if (!this.paused && Math.hypot(end.x - start.x, end.y - start.y) * this.cam.scale > 3) {
        for (const o of this.state?.overlays ?? []) {
          if (ownsDrawing(o, this.playerId, this.campaignId) && inside(...drawingBounds(o))) this.selectedDrawings.add(o.id);
        }
        for (const t of this.state?.tokens ?? []) {
          const r = t.sizeTiles * (this.state?.grid.pixelsPerCell ?? 70) / 2;
          if (this.isMine(t) && inside(t.x - r, t.y - r, t.x + r, t.y + r)) this.selectedTokens.add(t.id);
        }
        for (const m of this.measurements) {
          if (m.ownerId === this.playerId && inside(...measurementBounds(m))) this.selectedMeasures.add(m.id);
        }
      }
      this.onSelection(null, this.selectedDrawings.size + this.selectedMeasures.size > 0,
        this.selectedDrawings.size + this.selectedMeasures.size + this.selectedTokens.size);
      this.invalidate(); return;
    }
    if (this.group) {
      const g = this.group; this.group = null;
      const dx = g.end.x - g.start.x, dy = g.end.y - g.start.y;
      if (!this.paused && (dx || dy)) {
        for (const o of g.shapes) {
          if (this.state?.overlays?.some(current => current.id === o.id && ownsDrawing(current, this.playerId, this.campaignId)))
            this.onDrawing('update', o.id, translateDrawing(o, dx, dy));
        }
        for (const t of g.tokens) {
          if (this.state?.tokens.some(current => current.id === t.id && this.isMine(current)))
            this.onMove({ tokenId: t.id, x: t.x + dx, y: t.y + dy, prevX: t.x, prevY: t.y });
        }
        for (const m of g.measures) {
          if (this.measurements.some(current => current.id === m.id && current.ownerId === this.playerId))
            this.onMeasurement({ ...m, x1: m.x1 + dx, y1: m.y1 + dy, x2: m.x2 + dx, y2: m.y2 + dy }, false);
        }
      }
      this.invalidate(); return;
    }
    if (this.gesture) {
      const g = this.gesture; this.gesture = null;
      if (this.canvas.hasPointerCapture(e.pointerId)) this.canvas.releasePointerCapture(e.pointerId);
      if (!this.paused) {
        if (g.shape && (g.create || JSON.stringify(g.shape) !== JSON.stringify(g.before)))
          this.onDrawing(g.create ? 'create' : 'update', g.shape.id, g.shape);
        if (g.measurement) this.onMeasurement(g.measurement, false);
      }
      this.invalidate(); return;
    }
    const d = this.drag;
    this.drag = null;
    if (this.canvas.hasPointerCapture(e.pointerId)) this.canvas.releasePointerCapture(e.pointerId);
    if (d?.kind === 'token' && d.token) this.sendTick(d, true);
    if (d?.kind === 'token') this.invalidateVision();
  }

  /** Sends the current drag position, throttled during the drag and always flushed on release. */
  private sendTick(d: NonNullable<MapView['drag']>, final: boolean): void {
    const t = d.token;
    if (!t) return;
    const now = performance.now();
    if (!final && now - this.lastTick < MOVE_TICK_MS) return;
    if (t.x === d.prevX && t.y === d.prevY) return;
    this.lastTick = now;
    this.onMove({ tokenId: t.id, x: t.x, y: t.y, prevX: d.prevX, prevY: d.prevY });
    d.prevX = t.x;
    d.prevY = t.y;
  }

  private wheel(e: WheelEvent): void {
    this.cancelPingHold();
    e.preventDefault();
    const r = this.canvas.getBoundingClientRect();
    const px = e.clientX - r.left;
    const py = e.clientY - r.top;
    const factor = Math.exp(-e.deltaY * 0.0015);
    const next = Math.max(0.02, Math.min(8, this.cam.scale * factor));
    const k = next / this.cam.scale;
    this.cam.x = px - (px - this.cam.x) * k;
    this.cam.y = py - (py - this.cam.y) * k;
    this.cam.scale = next;
    this.hover = this.toWorld(e);
    this.invalidate();
  }

  private getImage(url: string): HTMLImageElement {
    let img = this.images.get(url);
    if (!img) {
      img = new Image();
      img.onload = () => {
        this.dirty = true;
      };
      img.src = url;
      this.images.set(url, img);
    }
    return img;
  }

  /** Pyramid tiles live in a small LRU so panning a huge map does not keep every decoded tile alive. */
  private getTile(url: string): HTMLImageElement {
    let img = this.tiles.get(url);
    if (img) {
      this.tiles.delete(url);
      this.tiles.set(url, img);
      return img;
    }
    img = new Image();
    img.decoding = 'async';
    img.onload = () => {
      this.dirty = true;
    };
    img.src = url;
    this.tiles.set(url, img);
    while (this.tiles.size > MapView.MAX_TILES) {
      const oldest = this.tiles.keys().next().value as string;
      const evicted = this.tiles.get(oldest);
      this.tiles.delete(oldest);
      if (evicted) evicted.src = '';
    }
    return img;
  }

  /** Draws the overview, then the full-detail tiles of the level matching the current zoom over it. */
  private drawPyramid(ctx: CanvasRenderingContext2D, p: PyramidInfo, fallbackUrl: string, dw: number, dh: number): void {
    const base = this.getImage(tileUrl(p, p.levels, 0, 0));
    if (base.complete && base.naturalWidth > 0) {
      ctx.drawImage(base, -dw / 2, -dh / 2, dw, dh);
    } else {
      const fallback = this.images.get(fallbackUrl);
      if (fallback && fallback.complete && fallback.naturalWidth > 0) ctx.drawImage(fallback, -dw / 2, -dh / 2, dw, dh);
    }
    const m = ctx.getTransform();
    const sx = dw / p.width;
    const sy = dh / p.height;
    const level = chooseLevel(p, Math.hypot(m.a, m.b) * sx);
    if (level >= p.levels) return;
    const inv = m.inverse();
    let x0 = Infinity;
    let y0 = Infinity;
    let x1 = -Infinity;
    let y1 = -Infinity;
    for (const [cx, cy] of [[0, 0], [this.canvas.width, 0], [0, this.canvas.height], [this.canvas.width, this.canvas.height]]) {
      const pt = inv.transformPoint(new DOMPoint(cx, cy));
      const ix = (pt.x + dw / 2) / sx;
      const iy = (pt.y + dh / 2) / sy;
      x0 = Math.min(x0, ix);
      y0 = Math.min(y0, iy);
      x1 = Math.max(x1, ix);
      y1 = Math.max(y1, iy);
    }
    for (const t of visibleTiles(p, level, x0, y0, x1, y1)) {
      const img = this.getTile(tileUrl(p, level, t.tx, t.ty));
      if (img.complete && img.naturalWidth > 0) {
        ctx.drawImage(img, -dw / 2 + t.x * sx, -dh / 2 + t.y * sy, t.w * sx, t.h * sy);
      }
    }
  }

  private fogGridOf(s: GameState): Grid | null {
    const g = s.fogGrid ?? s.dmFog;
    return g ? { originX: g.originX, originY: g.originY, cellSize: g.cellSize, cols: g.cols, rows: g.rows } : null;
  }

  /**
   * Token lights follow reconciled token positions even after release while published light positions still lag.
   * When drawing (`shown`), they also follow the token's interpolated position.
   */
  private liveLights(s: GameState, shown = false): GameState['lights'] {
    const d = this.drag;
    const dragged = d?.kind === 'token' && d.token ? d.token : null;
    const tokens = new Map(s.tokens.map(t => [t.id, t]));
    return s.lights.map((l) => {
      if (!l.id.startsWith('token-light-')) return l;
      const id = l.id.slice('token-light-'.length);
      const p = dragged?.id === id ? dragged : (shown ? this.shown.get(id) : undefined) ?? tokens.get(id);
      return p && (p.x !== l.x || p.y !== l.y) ? { ...l, x: p.x, y: p.y } : l;
    });
  }

  /** Interpolated draw positions for this frame; sets {@link moving} while any token is still gliding. */
  private updateShown(s: GameState): void {
    this.shown.clear();
    this.motion.animating = false;
    const now = performance.now();
    const d = this.drag;
    for (const t of s.tokens) {
      if (!this.smoothMovement || (d?.kind === 'token' && d.token?.id === t.id)) {
        this.motion.settle(t.id, t.x, t.y, now);
        this.shown.set(t.id, { x: t.x, y: t.y });
      } else {
        this.shown.set(t.id, this.motion.position(t.id, t.x, t.y, s.grid.pixelsPerCell, now));
      }
    }
    this.moving = this.motion.animating;
  }

  private lightPolygon(l: { id: string; x: number; y: number; range: number }): Polygon {
    const key = `${l.id}:${l.x},${l.y},${l.range}`;
    let p = this.lightPolys.get(key);
    if (!p) {
      if (this.lightPolys.size > 400) this.lightPolys.clear();
      p = computeVisibility(l.x, l.y, l.range, this.segmentsCache);
      this.lightPolys.set(key, p);
    }
    return p;
  }

  /** Recomputes sight polygons, visible cells and queues explored cells. */
  private recomputeVision(s: GameState): void {
    this.segmentsCache = blockingSegments(s.walls, s.doors);
    const ppc = s.grid.pixelsPerCell;
    const mine = s.tokens.filter((t) => this.isMine(t));
    this.sights = mine.map((token) => ({ token, sight: computeVisibility(token.x, token.y, AMBIENT_SIGHT_TILES * ppc, this.segmentsCache) }));
    const grid = this.fogGridOf(s);
    this.fog.configure(grid);
    if (this.fog.dmControlled !== !!s.dmFog) {
      this.fog.dmControlled = !!s.dmFog;
      this.fog.bitmapDirty = true;
    }
    if (grid) {
      const dmControlled = !!s.dmFog;
      const dark = s.lighting.ambient < 0.5;
      const mineIds = new Set(mine.map((t) => t.id));
      // Lights attached to another player's token must not clear fog for this player.
      const revealsForMe = (id: string): boolean => !id.startsWith('token-light-') || mineIds.has(id.slice('token-light-'.length));
      const polygons = this.liveLights(s)
        .filter((l) => l.on && (dmControlled ? l.revealMode === 'WHILE_LIT' && revealsForMe(l.id) : dark))
        .map((l) => this.lightPolygon(l));
      const visible = dmControlled
        ? cellsInPolygons(grid, polygons)
        : computeVisibleCells({
          grid,
          sources: this.sights.map((x) => ({ x: x.token.x, y: x.token.y, sight: x.sight })),
          // In the dark only lights show anything; the token itself emits no light.
          darkVisionRadius: dark ? 0 : null,
          lightPolygons: polygons,
        });
      if (this.fog.setVisible(visible, !dmControlled && !this.tentative())) this.onExplored();
    }
  }

  private frame(time: number): void {
    if (this.destroyed) return;
    requestAnimationFrame((t) => this.frame(t));
    if (document.hidden) {
      this.dirty = true;
      return;
    }
    const s = this.state;
    if (!s) return;
    const weatherActive = weatherKind(s.weather.type) !== 'none';
    const flickering = s.lighting.flicker !== false && (s.lights.some((l) => l.on && l.flicker)
      || (s.overlays ?? []).some((o) => o.emits && (o.flicker ?? 0) > 0));
    if (!this.dirty && !weatherActive && !flickering && !this.textureActive && !this.pingActive && !this.fog.animating
      && !this.moving) return;
    if (time - this.lastFrame < MIN_FRAME_MS) return;
    this.lastFrame = time;
    if (this.visionDirty) {
      this.visionDirty = false;
      this.recomputeVision(s);
    }
    this.dirty = false;
    this.draw(s);
  }

  private world(ctx: CanvasRenderingContext2D): void {
    const k = this.dpr * this.cam.scale;
    ctx.setTransform(k, 0, 0, k, this.dpr * this.cam.x, this.dpr * this.cam.y);
  }

  private polyPath(ctx: CanvasRenderingContext2D, p: Polygon): void {
    ctx.beginPath();
    for (let i = 0; i < p.xs.length; i++) {
      if (i === 0) ctx.moveTo(p.xs[i], p.ys[i]);
      else ctx.lineTo(p.xs[i], p.ys[i]);
    }
    ctx.closePath();
  }

  private draw(s: GameState): void {
    applyWeatherConfig(s.weather);
    this.updateShown(s);
    const ctx = this.ctx;
    const ppc = s.grid.pixelsPerCell;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#0b0d10';
    ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);
    this.world(ctx);

    const layers = [...s.images].sort((a, b) => a.zIndex - b.zIndex);
    for (const im of layers) {
      ctx.save();
      ctx.translate(im.x + im.width / 2, im.y + im.height / 2);
      ctx.rotate(((im.rotationDeg || 0) * Math.PI) / 180);
      const sideways = Math.round((im.rotationDeg || 0) / 90) % 2 !== 0;
      const dw = sideways ? im.height : im.width;
      const dh = sideways ? im.width : im.height;
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = 'high';
      if (im.pyramid) {
        this.drawPyramid(ctx, im.pyramid, im.url, dw, dh);
      } else {
        const img = this.getImage(im.url);
        if (img.complete && img.naturalWidth > 0) ctx.drawImage(img, -dw / 2, -dh / 2, dw, dh);
      }
      ctx.restore();
    }

    this.textureActive = (s.overlays ?? []).some((o) => o.tile && (o.layers ?? []).some((l) => l[0] !== 0 || l[1] !== 0 || l[4] > 0));
    if (s.view?.showGrid === true) this.drawGrid(ctx, ppc, s.view?.gridOpacity ?? 0.08);
    this.drawDoors(ctx, s);
    this.drawFloorOverlays(ctx, s);
    this.drawTokens(ctx, s, ppc);
    this.drawLighting(ctx, s, () => this.drawOverlays(ctx, s, false));
    this.drawOverlays(ctx, s, true);
    // Lightning is drawn below fog so it cannot reveal unexplored walls and windows.
    if (weatherKind(s.weather.type) === 'thunderstorm') {
      const flash = lightningFlash(this.serverNow() / 1000 + (s.weather.seed || 0), s.weather.lightningIntervalSeconds);
      if (flash > 0 && flashStyle.opacity > 0) {
        this.fillExposure(ctx, s, flash * flashStyle.opacity, flashStyle.color);
      }
    }
    this.drawTexts(ctx, s);
    this.drawFog(ctx, s);
    this.drawPings(ctx, s);
    this.drawToolAnnotations(ctx, s);

    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    const seconds = this.serverNow() / 1000 + (s.weather.seed || 0);
    const kind = weatherKind(s.weather.type);
    ctx.save();
    if (kind !== 'none' && !weatherIndoors(s.weather)) {
      const zones = s.indoor ? this.indoorZones(s.indoor) : null;
      if (zones) {
        this.world(ctx);
        ctx.clip(zones.outside, 'evenodd');
        ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      }

    }
    drawWeather(ctx, s.weather.type, s.weather.intensity, this.width, this.height, seconds, this.maxParticles);
    ctx.restore();
  }

  private drawToolAnnotations(ctx: CanvasRenderingContext2D, s: GameState): void {
    ctx.save();
    this.world(ctx);
    if (this.gesture?.shape) this.drawOverlay(ctx, this.gesture.shape, s.grid.pixelsPerCell);
    const selected = this.gesture?.create ? null : this.gesture?.shape ?? s.overlays?.find(o => o.id === this.selectedDrawing);
    if (selected) {
      const [x, y, right, bottom] = drawingBounds(selected);
      ctx.strokeStyle = '#eeeeee'; ctx.lineWidth = 1.5 / this.cam.scale;
      ctx.setLineDash([5 / this.cam.scale, 4 / this.cam.scale]);
      ctx.strokeRect(x, y, right - x, bottom - y); ctx.setLineDash([]);
      ctx.fillStyle = '#eeeeee'; ctx.fillRect(right - 4 / this.cam.scale, bottom - 4 / this.cam.scale, 8 / this.cam.scale, 8 / this.cam.scale);
    }
    ctx.strokeStyle = '#80d8ff'; ctx.lineWidth = 1 / this.cam.scale;
    for (const o of s.overlays ?? []) {
      if (this.selectedDrawings.has(o.id) && ownsDrawing(o, this.playerId, this.campaignId)) {
        const [x, y, r, b] = drawingBounds(o); ctx.strokeRect(x, y, r - x, b - y);
      }
    }
    for (const t of s.tokens) {
      if (this.selectedTokens.has(t.id) && this.isMine(t)) {
        this.drawTokenSelection(ctx, t, s.grid.pixelsPerCell, this.canRotateTokens && !this.paused);
      }
    }
    if (this.marquee) {
      const { start, end } = this.marquee;
      ctx.fillStyle = '#80d8ff18'; ctx.fillRect(start.x, start.y, end.x - start.x, end.y - start.y);
      ctx.strokeRect(start.x, start.y, end.x - start.x, end.y - start.y);
    }
    if (this.group) {
      const g = this.group, dx = g.end.x - g.start.x, dy = g.end.y - g.start.y;
      for (const o of g.shapes) this.drawOverlay(ctx, translateDrawing(o, dx, dy), s.grid.pixelsPerCell);
      for (const t of g.tokens) {
        this.drawTokenSelection(ctx, { ...t, x: t.x + dx, y: t.y + dy }, s.grid.pixelsPerCell, false);
      }
      for (const m of g.measures) {
        measurementPath(ctx, { ...m, x1: m.x1 + dx, y1: m.y1 + dy, x2: m.x2 + dx, y2: m.y2 + dy }); ctx.stroke();
      }
    }
    if (this.hover && this.tool === 'draw' && !this.paused && (this.drawMode === 'brush' || this.drawMode === 'pen')) {
      const radius = (this.drawMode === 'pen' ? s.penWidthCells ?? 0.06 : this.style.size) * s.grid.pixelsPerCell / 2;
      ctx.beginPath(); ctx.arc(this.hover.x, this.hover.y, radius, 0, Math.PI * 2);
      ctx.strokeStyle = '#000a'; ctx.lineWidth = 3 / this.cam.scale; ctx.stroke();
      ctx.strokeStyle = '#fffd'; ctx.lineWidth = 1 / this.cam.scale; ctx.stroke();
    }
    const measures = [...this.measurements.filter(m => m.id !== this.gesture?.measurement?.id)];
    if (this.gesture?.measurement) measures.push(this.gesture.measurement);
    for (const m of measures) {
      const color = m.color ?? '#80d8ff';
      ctx.strokeStyle = m.id === this.selectedMeasure || this.selectedMeasures.has(m.id) ? '#fff' : color;
      ctx.fillStyle = `${color}22`; ctx.lineWidth = 2 / this.cam.scale;
      measurementPath(ctx, m);
      if (m.kind !== 'distance') ctx.fill();
      ctx.stroke();
      const label = measurementLabel(m, s.grid.pixelsPerCell);
      ctx.font = `${12 / this.cam.scale}px system-ui`;
      const width = ctx.measureText(label).width;
      ctx.fillStyle = '#151a20e8'; ctx.fillRect(m.x2 - 5 / this.cam.scale, m.y2 - 22 / this.cam.scale, width + 10 / this.cam.scale, 20 / this.cam.scale);
      ctx.fillStyle = '#fff'; ctx.fillText(label, m.x2, m.y2 - 8 / this.cam.scale);
      if (m.ownerId === this.playerId && m.id !== this.gesture?.measurement?.id) {
        const x = m.x2 + 12 / this.cam.scale, y = m.y2 + 12 / this.cam.scale, r = 8 / this.cam.scale;
        ctx.fillStyle = '#151a2099'; ctx.fillRect(x - r, y - r, r * 2, r * 2);
        ctx.strokeStyle = '#cbd5e1aa'; ctx.lineWidth = 1.2 / this.cam.scale;
        ctx.beginPath(); ctx.moveTo(x - r / 3, y - r / 3); ctx.lineTo(x + r / 3, y + r / 3);
        ctx.moveTo(x + r / 3, y - r / 3); ctx.lineTo(x - r / 3, y + r / 3); ctx.stroke();
      }
    }
    ctx.restore();
  }

  /** Cached outdoor clip path (everything except enclosed rooms) and per-colour lightning exposure images. */
  private indoorZones(ind: IndoorData): { outside: Path2D; exposure: Map<string, HTMLCanvasElement> } {
    if (this.zoneCache && this.zoneCache.src === ind) return this.zoneCache;
    const inside = bitsToFlags(base64ToBytes(ind.inside), ind.cols * ind.rows);
    const outside = new Path2D();
    outside.rect(-1e7, -1e7, 2e7, 2e7);
    for (let r = 0; r < ind.rows; r++) {
      for (let c = 0; c < ind.cols; ) {
        if (!inside[r * ind.cols + c]) {
          c++;
          continue;
        }
        let end = c;
        while (end < ind.cols && inside[r * ind.cols + end]) end++;
        outside.rect(ind.originX + c * ind.cellSize, ind.originY + r * ind.cellSize, (end - c) * ind.cellSize, ind.cellSize);
        c = end;
      }
    }
    this.zoneCache = { src: ind, outside, exposure: new Map() };
    return this.zoneCache;
  }

  /** Per-cell lightning exposure image: daylight through openings inside rooms, nothing elsewhere. */
  private exposureImage(ind: IndoorData, color: string): HTMLCanvasElement {
    const zones = this.indoorZones(ind);
    const cached = zones.exposure.get(color);
    if (cached) return cached;
    const n = ind.cols * ind.rows;
    const inside = bitsToFlags(base64ToBytes(ind.inside), n);
    const day = ind.daylight ? base64ToBytes(ind.daylight) : null;
    const canvas = document.createElement('canvas');
    canvas.width = ind.cols;
    canvas.height = ind.rows;
    const g = canvas.getContext('2d')!;
    const data = g.createImageData(ind.cols, ind.rows);
    const rgb = parseCssColor(color);
    for (let i = 0; i < n; i++) {
      data.data[i * 4] = rgb[0];
      data.data[i * 4 + 1] = rgb[1];
      data.data[i * 4 + 2] = rgb[2];
      data.data[i * 4 + 3] = inside[i] !== 0 && day ? day[i] ?? 0 : 0;
    }
    g.putImageData(data, 0, 0);
    zones.exposure.set(color, canvas);
    return canvas;
  }

  /**
   * Paints a lightning layer with the given alpha: fully outside and in every room when the storm also renders
   * indoors, otherwise only outdoors and through open exterior doors/windows (the cached daylight footprint).
   */
  private fillExposure(c: CanvasRenderingContext2D, s: GameState, alpha: number, color: string): void {
    const ind = s.indoor;
    c.save();
    c.globalAlpha = Math.max(0, Math.min(1, alpha));
    c.fillStyle = color;
    if (!ind || weatherIndoors(s.weather)) {
      c.setTransform(1, 0, 0, 1, 0, 0);
      c.fillRect(0, 0, c.canvas.width, c.canvas.height);
      c.restore();
      return;
    }
    const zones = this.indoorZones(ind);
    c.save();
    this.world(c);
    c.clip(zones.outside, 'evenodd');
    c.setTransform(1, 0, 0, 1, 0, 0);
    c.fillRect(0, 0, c.canvas.width, c.canvas.height);
    c.restore();
    this.world(c);
    c.imageSmoothingEnabled = true;
    c.drawImage(this.exposureImage(ind, color), ind.originX, ind.originY, ind.cols * ind.cellSize, ind.rows * ind.cellSize);
    c.restore();
  }

  private drawGrid(ctx: CanvasRenderingContext2D, ppc: number, opacity: number): void {
    if (ppc * this.cam.scale < 8) return;
    const x0 = -this.cam.x / this.cam.scale;
    const y0 = -this.cam.y / this.cam.scale;
    const x1 = x0 + this.width / this.cam.scale;
    const y1 = y0 + this.height / this.cam.scale;
    ctx.strokeStyle = `rgba(255,255,255,${opacity})`;
    ctx.lineWidth = 1 / this.cam.scale;
    ctx.beginPath();
    for (let x = Math.floor(x0 / ppc) * ppc; x <= x1; x += ppc) {
      ctx.moveTo(x, y0);
      ctx.lineTo(x, y1);
    }
    for (let y = Math.floor(y0 / ppc) * ppc; y <= y1; y += ppc) {
      ctx.moveTo(x0, y);
      ctx.lineTo(x1, y);
    }
    ctx.stroke();
  }

  private drawDoors(ctx: CanvasRenderingContext2D, s: GameState): void {
    for (const d of s.doors) {
      const window = (d.kind ?? '').toLowerCase() === 'window';
      this.drawBadge(ctx, (d.x1 + d.x2) / 2, (d.y1 + d.y2) / 2, '#dddddd', window, d.open);
    }
  }

  private drawTokenSelection(ctx: CanvasRenderingContext2D, t: Token, ppc: number, handle: boolean): void {
    const r = t.sizeTiles * ppc / 2;
    const angle = this.rotation?.tokenId === t.id ? this.rotation.angle : t.rotationDeg ?? 0;
    ctx.save();
    ctx.translate(t.x, t.y); ctx.rotate(angle * Math.PI / 180);
    ctx.strokeStyle = '#eeeeee'; ctx.lineWidth = 1.5 / this.cam.scale;
    ctx.setLineDash([5 / this.cam.scale, 4 / this.cam.scale]);
    ctx.strokeRect(-r, -r, r * 2, r * 2); ctx.setLineDash([]);
    if (handle) {
      const y = -r - 24 / this.cam.scale;
      ctx.beginPath(); ctx.moveTo(0, -r); ctx.lineTo(0, y); ctx.stroke();
      ctx.beginPath(); ctx.arc(0, y, 5 / this.cam.scale, 0, Math.PI * 2);
      ctx.fillStyle = '#eeeeee'; ctx.fill(); ctx.stroke();
    }
    ctx.restore();
  }

  /** Screen-sized door/window badge: closed shows a shut panel, open shows an ajar one. */
  private drawBadge(ctx: CanvasRenderingContext2D, cx: number, cy: number, color: string, window: boolean, open: boolean): void {
    const k = 1 / this.cam.scale;
    const r = 11 * k;
    ctx.save();
    ctx.translate(cx, cy);
    ctx.scale(k, k);
    ctx.beginPath();
    ctx.arc(0, 0, 11, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(26,26,31,0.88)';
    ctx.fill();
    ctx.lineWidth = 2;
    ctx.strokeStyle = color;
    ctx.stroke();
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.lineWidth = 1.6;
    ctx.lineJoin = 'round';
    if (window) {
      ctx.strokeRect(-5, -5, 10, 10);
      ctx.beginPath();
      if (open) {
        ctx.moveTo(-5, -5);
        ctx.lineTo(-8, -2);
        ctx.lineTo(-8, 8);
        ctx.lineTo(-5, 5);
      } else {
        ctx.moveTo(0, -5);
        ctx.lineTo(0, 5);
        ctx.moveTo(-5, 0);
        ctx.lineTo(5, 0);
      }
      ctx.stroke();
    } else if (open) {
      ctx.strokeRect(-5, -6, 10, 12);
      ctx.beginPath();
      ctx.moveTo(-5, -6);
      ctx.lineTo(3, -4);
      ctx.lineTo(3, 8);
      ctx.lineTo(-5, 6);
      ctx.closePath();
      ctx.fill();
    } else {
      ctx.strokeRect(-5, -6, 10, 12);
      ctx.beginPath();
      ctx.arc(2, 1, 1.2, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.restore();
    void r;
  }

  private drawTokens(ctx: CanvasRenderingContext2D, s: GameState, ppc: number): void {
    const fogOn = this.fog.grid !== null && this.state?.view?.fogEnabled !== false;
    for (const t of s.tokens) {
      const mine = this.isMine(t);
      if (!mine && fogOn && !this.fog.isVisibleOrRevealed(t.x, t.y)) continue;
      const pos = this.shown.get(t.id) ?? t;
      const diameter = t.sizeTiles * ppc;
      const r = diameter / 2;
      const color = t.color ?? '#5599CC';
      const square = t.shape === 'square';
      const border = t.border !== false;
      ctx.save();
      ctx.translate(pos.x, pos.y);
      const rotation = this.rotation?.tokenId === t.id ? this.rotation.angle : t.rotationDeg ?? 0;
      ctx.rotate(rotation * Math.PI / 180);
      ctx.beginPath();
      if (square) ctx.rect(-r, -r, diameter, diameter);
      else ctx.arc(0, 0, r, 0, Math.PI * 2);
      ctx.save();
      ctx.clip();
      if (border) {
        ctx.fillStyle = shade(color, 0.49);
        ctx.fillRect(-r, -r, diameter, diameter);
      }
      const img = t.image ? this.getImage(t.image) : null;
      if (img && img.complete && img.naturalWidth > 0) {
        // The token image is already the stored crop; the desktop draws it whole into the token box.
        ctx.drawImage(img, -r, -r, diameter, diameter);
      } else if (!t.image) {
        ctx.fillStyle = shade(color, 1 / 0.7);
        ctx.beginPath();
        ctx.ellipse(0, -diameter * 0.16, diameter * 0.14, diameter * 0.14, 0, 0, Math.PI * 2);
        ctx.fill();
        ctx.beginPath();
        ctx.ellipse(0, diameter * 0.305, diameter * 0.31, diameter * 0.275, 0, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.restore();
      if (border) {
        ctx.lineWidth = Math.max(2, diameter * 0.055);
        ctx.strokeStyle = color;
        if (square) ctx.strokeRect(-r + 1, -r + 1, diameter - 2, diameter - 2);
        else {
          ctx.beginPath();
          ctx.arc(0, 0, r - 1, 0, Math.PI * 2);
          ctx.stroke();
        }
      }
      ctx.restore();
      const conditions = t.conditions ?? [];
      const badge = 18 / this.cam.scale;
      conditions.slice(0, 5).forEach((condition, index) => {
        const x = pos.x + r - badge / 2;
        const y = pos.y - r + badge / 2 + index * badge * 0.9;
        const image = this.getImage(conditionIconUrl(conditionIconId(condition.type, condition.iconId)));
        if (image.complete && image.naturalWidth > 0) {
          ctx.drawImage(image, x - badge / 2, y - badge / 2, badge, badge);
        }
      });
    }
  }
  private flickerOf(s: GameState, l: { id: string; flicker?: { strength: number; speed: number } }): number {
    if (!l.flicker || s.lighting.flicker === false) return 0;
    const step = 1000 / 30;
    const now = Math.floor(this.serverNow() / step) * step;
    return flickerAmount(l.id, l.flicker.strength, l.flicker.speed, now);
  }

  /** Effects on the floor: under the tokens and the ambient multiply, so lights and darkness act on them. */
  private drawFloorOverlays(ctx: CanvasRenderingContext2D, s: GameState): void {
    for (const o of s.overlays ?? []) {
      if (o.above === false && !o.emits) this.drawOverlay(ctx, o, s.grid.pixelsPerCell);
    }
    for (const o of s.overlays ?? []) {
      if (o.above === false && o.emits) this.drawOverlay(ctx, o, s.grid.pixelsPerCell);
    }
  }

  private drawOverlays(ctx: CanvasRenderingContext2D, s: GameState, emitting: boolean): void {
    for (const o of s.overlays ?? []) {
      if (o.above !== false && !!o.emits === emitting) this.drawOverlay(ctx, o, s.grid.pixelsPerCell);
    }
  }

  private shapePath(c: CanvasRenderingContext2D, o: Overlay): boolean {
    c.beginPath();
    if (o.type === 'circle') {
      c.arc(o.x, o.y, o.radius, 0, Math.PI * 2);
      return true;
    }
    if (o.type === 'rect') {
      c.rect(o.x, o.y, o.width, o.height);
      return true;
    }
    if (o.points.length < 2) return false;
    if (o.points.length === 2) {
      c.arc(o.points[0], o.points[1], Math.max(0.5 / this.cam.scale, o.strokeWidth / 2), 0, Math.PI * 2);
      return true;
    }
    c.moveTo(o.points[0], o.points[1]);
    for (let i = 2; i + 1 < o.points.length; i += 2) c.lineTo(o.points[i], o.points[i + 1]);
    return true;
  }

  /** Fills closed shapes and strokes brush/line shapes with the current fill/stroke style. */
  private paintShape(c: CanvasRenderingContext2D, o: Overlay): void {
    if (!this.shapePath(c, o)) return;
    if (o.type === 'circle' || o.type === 'rect' || o.points.length === 2) {
      c.fill();
    } else {
      c.lineJoin = 'round';
      c.lineCap = 'round';
      c.lineWidth = Math.max(1 / this.cam.scale, o.strokeWidth);
      c.stroke();
    }
  }

  /** The scrolling, pulsing texture layers of a shape (the real generated tile from the host). */
  private paintLayers(c: CanvasRenderingContext2D, o: Overlay, ppc: number): void {
    const img = this.getImage(o.tile!);
    if (!img.complete || img.naturalWidth === 0) return;
    const seconds = this.serverNow() / 1000;
    const base = Math.max(0.05, Math.min(1, o.alpha));
    const tileWorld = ppc * (o.tileCells || 4);
    for (const [vx, vy, scale, layerAlpha, pulse, pulseHz] of o.layers!) {
      const size = tileWorld * scale;
      const pattern = c.createPattern(img, 'repeat');
      if (!pattern) continue;
      const fract = (v: number): number => v - Math.floor(v);
      pattern.setTransform(
        new DOMMatrix().translate(fract(seconds * vx) * size, fract(seconds * vy) * size).scale(size / img.naturalWidth),
      );
      const wave = pulse > 0 ? 1 - pulse * (0.5 - 0.5 * Math.cos(2 * Math.PI * pulseHz * seconds)) : 1;
      c.globalAlpha = base * layerAlpha * wave;
      c.fillStyle = pattern;
      c.strokeStyle = pattern;
      this.paintShape(c, o);
    }
    c.globalAlpha = 1;
  }

  /** Same soft-edge alpha mask as the desktop SoftEffectRenderer: smoothstep of the depth inside the shape. */
  private softMask(o: Overlay, featherWorld: number, passes: number): { canvas: HTMLCanvasElement; ox: number; oy: number; cell: number } | null {
    const key = [o.type, o.x, o.y, o.width, o.height, o.radius, o.strokeWidth, featherWorld, passes, o.points.join(',')].join('|');
    const hit = this.maskCache.get(o.id);
    if (hit && hit.key === key) return hit;
    this.maskCache.delete(o.id);
    const pts = o.points;
    let minX: number, minY: number, maxX: number, maxY: number;
    let half = 0;
    if (o.type === 'circle') {
      minX = o.x - o.radius; maxX = o.x + o.radius; minY = o.y - o.radius; maxY = o.y + o.radius;
    } else if (o.type === 'rect') {
      minX = o.x; minY = o.y; maxX = o.x + o.width; maxY = o.y + o.height;
    } else {
      if (pts.length < 2) return null;
      half = o.strokeWidth / 2;
      minX = Infinity; minY = Infinity; maxX = -Infinity; maxY = -Infinity;
      for (let i = 0; i + 1 < pts.length; i += 2) {
        minX = Math.min(minX, pts[i]); maxX = Math.max(maxX, pts[i]);
        minY = Math.min(minY, pts[i + 1]); maxY = Math.max(maxY, pts[i + 1]);
      }
      minX -= half; minY -= half; maxX += half; maxY += half;
    }
    const spanX = maxX - minX;
    const spanY = maxY - minY;
    if (!(spanX > 0) || !(spanY > 0)) return null;
    let cell = featherWorld > 0 ? featherWorld / 4 : Math.max(spanX, spanY) / 256;
    cell = Math.max(cell, Math.sqrt((spanX * spanY) / 250_000));
    const ox = minX - cell;
    const oy = minY - cell;
    const w = Math.ceil(spanX / cell) + 2;
    const h = Math.ceil(spanY / cell) + 2;
    const depth = new Float32Array(w * h).fill(-Infinity);
    if (o.type === 'circle') {
      for (let y = 0; y < h; y++) {
        const dy = oy + (y + 0.5) * cell - o.y;
        for (let x = 0; x < w; x++) {
          const dx = ox + (x + 0.5) * cell - o.x;
          depth[y * w + x] = o.radius - Math.hypot(dx, dy);
        }
      }
    } else if (o.type === 'rect') {
      for (let y = 0; y < h; y++) {
        const wy = oy + (y + 0.5) * cell;
        const dy = Math.min(wy - minY, maxY - wy);
        for (let x = 0; x < w; x++) {
          const wx = ox + (x + 0.5) * cell;
          depth[y * w + x] = Math.min(dy, wx - minX, maxX - wx);
        }
      }
    } else {
      const n = pts.length / 2;
      for (let s = 0; s < Math.max(1, n - 1); s++) {
        const ax = pts[2 * s], ay = pts[2 * s + 1];
        const bx = n > 1 ? pts[2 * s + 2] : ax;
        const by = n > 1 ? pts[2 * s + 3] : ay;
        const cx0 = Math.max(0, Math.floor((Math.min(ax, bx) - half - ox) / cell));
        const cx1 = Math.min(w - 1, Math.ceil((Math.max(ax, bx) + half - ox) / cell));
        const cy0 = Math.max(0, Math.floor((Math.min(ay, by) - half - oy) / cell));
        const cy1 = Math.min(h - 1, Math.ceil((Math.max(ay, by) + half - oy) / cell));
        const dx = bx - ax, dy = by - ay;
        const len2 = dx * dx + dy * dy;
        for (let y = cy0; y <= cy1; y++) {
          const py = oy + (y + 0.5) * cell;
          for (let x = cx0; x <= cx1; x++) {
            const px = ox + (x + 0.5) * cell;
            const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
            const d = half - Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
            if (d > depth[y * w + x]) depth[y * w + x] = d;
          }
        }
      }
    }
    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    const cctx = canvas.getContext('2d')!;
    const img = cctx.createImageData(w, h);
    const first = 1 / Math.max(1, passes);
    for (let i = 0; i < w * h; i++) {
      const d = depth[i];
      if (!(d >= 0)) continue;
      const t = featherWorld > 0 ? Math.min(1, d / featherWorld + first) : 1;
      img.data[i * 4 + 3] = Math.round(255 * t * t * (3 - 2 * t));
    }
    cctx.putImageData(img, 0, 0);
    const entry = { key, canvas, ox, oy, cell };
    this.maskCache.set(o.id, entry);
    return entry;
  }

  /** Soft textures (fire, smoke...) fade out towards the shape edge instead of ending in a hard cut. */
  private paintSoft(ctx: CanvasRenderingContext2D, o: Overlay, ppc: number): void {
    const dim = o.type === 'circle' ? o.radius : o.type === 'rect' ? Math.min(o.width, o.height) : o.strokeWidth;
    const featherWorld = Math.min((o.featherCells ?? 1) * ppc, 0.4 * dim);
    const mask = this.softMask(o, featherWorld, o.featherPasses ?? 8);
    if (!mask) return;
    const fx = this.fx.getContext('2d')!;
    fx.setTransform(1, 0, 0, 1, 0, 0);
    fx.globalCompositeOperation = 'source-over';
    fx.globalAlpha = 1;
    fx.filter = 'none';
    fx.clearRect(0, 0, this.fx.width, this.fx.height);
    this.world(fx);
    this.paintLayers(fx, o, ppc);
    fx.globalCompositeOperation = 'destination-in';
    fx.imageSmoothingEnabled = true;
    fx.drawImage(mask.canvas, mask.ox, mask.oy, mask.canvas.width * mask.cell, mask.canvas.height * mask.cell);
    fx.globalCompositeOperation = 'source-over';
    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.drawImage(this.fx, 0, 0);
    ctx.restore();
  }

  private drawOverlay(ctx: CanvasRenderingContext2D, o: Overlay, ppc: number): void {
    const alpha = Math.max(0.05, Math.min(1, o.alpha));
    const textured = o.texture && o.texture !== 'none';
    if (o.tile && o.layers && o.layers.length > 0) {
      if (o.soft) {
        this.paintSoft(ctx, o, ppc);
      } else {
        ctx.save();
        this.paintLayers(ctx, o, ppc);
        ctx.restore();
      }
      if (o.border && (o.type === 'circle' || o.type === 'rect')) {
        ctx.save();
        ctx.strokeStyle = withAlpha(o.color, Math.min(1, alpha + 0.35));
        ctx.lineWidth = 2 / this.cam.scale;
        this.shapePath(ctx, o);
        ctx.stroke();
        ctx.restore();
      }
      if (o.border && o.type === 'brush') this.drawBrushBorder(ctx, o);
      return;
    }
    ctx.save();
    ctx.fillStyle = withAlpha(o.color, alpha);
    ctx.strokeStyle = withAlpha(o.color, Math.min(1, alpha + 0.35));
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    if (o.type === 'circle') {
      ctx.beginPath();
      ctx.arc(o.x, o.y, o.radius, 0, Math.PI * 2);
      ctx.fill();
      if (!textured || o.border) {
        ctx.lineWidth = 2 / this.cam.scale;
        ctx.stroke();
      }
    } else if (o.type === 'rect') {
      ctx.fillRect(o.x, o.y, o.width, o.height);
      if (!textured || o.border) {
        ctx.lineWidth = 2 / this.cam.scale;
        ctx.strokeRect(o.x, o.y, o.width, o.height);
      }
    } else if (o.points.length === 2) {
      ctx.beginPath();
      ctx.arc(o.points[0], o.points[1], Math.max(0.5 / this.cam.scale, o.strokeWidth / 2), 0, Math.PI * 2);
      ctx.fillStyle = withAlpha(o.color, alpha);
      ctx.fill();
    } else if (o.points.length >= 2) {
      ctx.beginPath();
      ctx.moveTo(o.points[0], o.points[1]);
      for (let i = 2; i + 1 < o.points.length; i += 2) ctx.lineTo(o.points[i], o.points[i + 1]);
      ctx.lineWidth = Math.max(1 / this.cam.scale, o.strokeWidth);
      ctx.strokeStyle = withAlpha(o.color, alpha);
      ctx.stroke();
    }
    ctx.restore();
    if (o.border && o.type === 'brush') this.drawBrushBorder(ctx, o);
  }

  private drawBrushBorder(ctx: CanvasRenderingContext2D, o: Overlay): void {
    if (o.points.length < 2) return;
    const pad = Math.ceil(4 * this.dpr);
    const key = JSON.stringify([o.points, o.strokeWidth, o.color, o.alpha, this.cam, this.dpr, this.canvas.width, this.canvas.height]);
    if (key !== this.brushBorderKey) {
      for (const canvas of [this.brushMask, this.brushBorder]) {
        canvas.width = this.canvas.width + pad * 2; canvas.height = this.canvas.height + pad * 2;
      }
      const mask = this.brushMask.getContext('2d')!;
      this.world(mask);
      mask.translate(pad / (this.dpr * this.cam.scale), pad / (this.dpr * this.cam.scale));
      mask.fillStyle = mask.strokeStyle = '#fff';
      this.paintShape(mask, o);
      const edge = this.brushBorder.getContext('2d')!;
      edge.drawImage(this.brushMask, 0, 0);
      // Erode the union, then subtract it from the original mask: crossings have no internal edges.
      edge.globalCompositeOperation = 'destination-in';
      const inset = 3 * this.dpr;
      for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1], [Math.SQRT1_2, Math.SQRT1_2],
        [-Math.SQRT1_2, Math.SQRT1_2], [Math.SQRT1_2, -Math.SQRT1_2], [-Math.SQRT1_2, -Math.SQRT1_2]]) {
        edge.drawImage(this.brushMask, dx * inset, dy * inset);
      }
      edge.globalCompositeOperation = 'source-out';
      edge.drawImage(this.brushMask, 0, 0);
      edge.globalCompositeOperation = 'source-in';
      edge.fillStyle = withAlpha(o.color, Math.min(1, Math.max(0.05, o.alpha) + 0.35));
      edge.fillRect(0, 0, this.brushBorder.width, this.brushBorder.height);
      edge.globalCompositeOperation = 'source-over';
      this.brushBorderKey = key;
    }
    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.drawImage(this.brushBorder, -pad, -pad);
    ctx.restore();
  }

  private drawTexts(ctx: CanvasRenderingContext2D, s: GameState): void {
    for (const box of s.texts ?? []) this.drawText(ctx, box);
  }

  private drawText(ctx: CanvasRenderingContext2D, box: TextBoxState): void {
    const pad = 12;
    const lineW = box.border && withAlphaValue(box.border) > 0 ? 6 : 0;
    ctx.save();
    ctx.beginPath();
    ctx.roundRect(box.x + lineW / 2, box.y + lineW / 2, box.width - lineW, box.height - lineW, 12);
    if (withAlphaValue(box.background) > 0) {
      ctx.fillStyle = cssColor(box.background);
      ctx.fill();
    }
    if (lineW > 0) {
      ctx.lineWidth = lineW;
      ctx.strokeStyle = cssColor(box.border);
      ctx.stroke();
    }
    ctx.beginPath();
    ctx.rect(box.x, box.y, box.width, box.height);
    ctx.clip();
    ctx.textBaseline = 'alphabetic';
    // Lay the runs out line by line, wrapping on spaces within the inner width.
    const inner = box.width - pad * 2;
    const lines: Array<{ parts: Array<{ text: string; size: number; color: string; w: number }>; h: number }> = [];
    let cur = { parts: [] as Array<{ text: string; size: number; color: string; w: number }>, h: 0 };
    let x = 0;
    const flush = (): void => {
      lines.push(cur);
      cur = { parts: [], h: 0 };
      x = 0;
    };
    for (const run of box.runs) {
      ctx.font = `${run.size}px sans-serif`;
      const paragraphs = run.text.split('\n');
      paragraphs.forEach((para, pi) => {
        if (pi > 0) flush();
        for (const word of para.split(/(?<= )/)) {
          const w = ctx.measureText(word).width;
          if (x + w > inner && x > 0) flush();
          cur.parts.push({ text: word, size: run.size, color: run.color, w });
          cur.h = Math.max(cur.h, run.size * 1.2);
          x += w;
        }
      });
    }
    flush();
    const total = lines.reduce((a, l) => a + (l.h || 0), 0);
    let y = box.y + pad + Math.max(0, (box.height - pad * 2 - total) / 2);
    for (const line of lines) {
      let px = box.x + pad;
      const h = line.h || 0;
      for (const p of line.parts) {
        ctx.font = `${p.size}px sans-serif`;
        ctx.fillStyle = cssColor(p.color);
        ctx.fillText(p.text, px, y + h * 0.8);
        px += p.w;
      }
      y += h;
    }
    ctx.restore();
  }

  private indoorMaps(ind: IndoorData, darkness: number, tint: number[]): { ambient: HTMLCanvasElement; inside: HTMLCanvasElement; flags: Uint8Array } {
    const key = `${darkness.toFixed(4)}|${tint.join(',')}`;
    const cached = this.indoorCache;
    if (cached && cached.key === key && cached.src === ind) return cached;
    const n = ind.cols * ind.rows;
    const inside = bitsToFlags(base64ToBytes(ind.inside), n);
    const day = ind.daylight ? base64ToBytes(ind.daylight) : null;
    const ambient = document.createElement('canvas');
    ambient.width = ind.cols;
    ambient.height = ind.rows;
    const mask = document.createElement('canvas');
    mask.width = ind.cols;
    mask.height = ind.rows;
    const ac = ambient.getContext('2d')!;
    const mc = mask.getContext('2d')!;
    const ai = ac.createImageData(ind.cols, ind.rows);
    const mi = mc.createImageData(ind.cols, ind.rows);
    for (let i = 0; i < n; i++) {
      const isIn = inside[i] !== 0;
      const sun = isIn && day ? (day[i] ?? 0) / 255 : 0;
      const base = isIn ? ind.nightDarkness : darkness;
      const dark = isIn ? base * (1 - sun) + darkness * sun : darkness;
      for (let c = 0; c < 3; c++) {
        const amb = tint[c];
        const t = isIn ? ind.night[c] * (1 - sun) + amb * sun : amb;
        ai.data[i * 4 + c] = Math.round(255 * Math.max(0, Math.min(1, 1 - dark + dark * t)));
      }
      ai.data[i * 4 + 3] = 255;
      mi.data[i * 4 + 3] = isIn ? 255 : 0;
    }
    ac.putImageData(ai, 0, 0);
    mc.putImageData(mi, 0, 0);
    this.indoorCache = { key, src: ind, ambient, inside: mask, flags: inside };
    return this.indoorCache;
  }

  private drawLighting(ctx: CanvasRenderingContext2D, s: GameState, afterAmbient: () => void): void {
    const darkness = 1 - Math.max(0, Math.min(1, s.lighting.ambient));
    const ind = s.indoor && s.indoor.lighting !== false ? s.indoor : null;
    const tint = s.lighting.tint ?? [0.1, 0.12, 0.2];
    const indoorMaps = ind ? this.indoorMaps(ind, darkness, tint) : null;
    const environment: TintEnvironment = {
      night: String(s.lighting.timeOfDay ?? 'NIGHT').toUpperCase() === 'NIGHT',
      darkness,
      indoor: ind && indoorMaps ? { grid: ind, inside: indoorMaps.flags, darkness: ind.nightDarkness } : undefined,
    };
    const lights = this.liveLights(s, true).filter((l) => l.on && l.range > 0 && lightActive(environment, l.x, l.y));
    const emitters = (s.overlays ?? []).filter((o) => o.emits && (o.lightStrength ?? 0) > 0);
    const indoorDark = ind ? ind.nightDarkness : 0;
    if (darkness <= 0.01 && indoorDark <= 0.01 && lights.length === 0 && emitters.length === 0) {
      afterAmbient();
      return;
    }
    const g = this.light.getContext('2d')!;
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.globalCompositeOperation = 'source-over';
    g.clearRect(0, 0, this.light.width, this.light.height);
    // Multiply tint like the desktop ambient layer: 1 - dark + dark * presetTint per channel.
    const ch = (t: number): number => Math.round(255 * Math.max(0, Math.min(1, 1 - darkness + darkness * t)));
    g.fillStyle = `rgb(${ch(tint[0])},${ch(tint[1])},${ch(tint[2])})`;
    g.fillRect(0, 0, this.light.width, this.light.height);
    let insideMask: HTMLCanvasElement | null = null;
    if (ind && indoorMaps) {
      insideMask = indoorMaps.inside;
      this.world(g);
      g.imageSmoothingEnabled = true;
      g.drawImage(indoorMaps.ambient, ind.originX, ind.originY, ind.cols * ind.cellSize, ind.rows * ind.cellSize);
    }
    const t2 = this.tmp.getContext('2d')!;
    const drawPunches = (c: CanvasRenderingContext2D): void => {
      for (const l of lights) {
        const f = this.flickerOf(s, l);
        const strength = (0.35 + 0.65 * Math.min(1, Math.max(0, l.brightness))) * (1 - 0.6 * f);
        this.punchLight(c, l.x, l.y, l.range * (1 - 0.25 * f), strength, this.lightPolygon(l));
      }
      const scale = this.cam.scale * this.dpr;
      const ppc = s.grid.pixelsPerCell;
      const seconds = Math.floor(this.serverNow() / MIN_FRAME_MS) * MIN_FRAME_MS;
      for (const o of emitters) {
        const f = s.lighting.flicker !== false && (o.flicker ?? 0) > 0
          ? flickerAmount(o.id, o.flicker ?? 0, o.flickerSpeed ?? 1, seconds)
          : 0;
        const strength = Math.min(1, (o.lightStrength ?? 0) * (1 - 0.6) * (1 - 0.6 * f));
        const range = (o.lightRange ?? 0) * ppc * (1 - 0.25) * (1 - 0.25 * f);
        c.save();
        c.fillStyle = `rgba(0,0,0,${strength})`;
        c.strokeStyle = `rgba(0,0,0,${strength})`;
        c.shadowColor = `rgba(0,0,0,${strength})`;
        c.shadowBlur = range * scale;
        this.paintShape(c, o);
        c.restore();
      }
    };
    g.globalCompositeOperation = 'destination-out';
    if (insideMask && ind) {
      // Lights only work inside rooms while the preset itself is not night.
      t2.setTransform(1, 0, 0, 1, 0, 0);
      t2.globalCompositeOperation = 'source-over';
      t2.clearRect(0, 0, this.tmp.width, this.tmp.height);
      this.world(t2);
      drawPunches(t2);
      t2.setTransform(1, 0, 0, 1, 0, 0);
      t2.globalCompositeOperation = 'destination-in';
      t2.imageSmoothingEnabled = false;
      const k = this.dpr * this.cam.scale;
      t2.drawImage(insideMask, this.dpr * this.cam.x + ind.originX * k, this.dpr * this.cam.y + ind.originY * k,
        ind.cols * ind.cellSize * k, ind.rows * ind.cellSize * k);
      t2.globalCompositeOperation = 'source-over';
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.drawImage(this.tmp, 0, 0);
      this.world(g);
    } else {
      this.world(g);
      drawPunches(g);
    }
    g.globalCompositeOperation = 'source-over';

    // Lightning lifts the ambient darkness where it is exposed (outdoors, and indoors through open apertures).
    if (weatherKind(s.weather.type) === 'thunderstorm' && flashStyle.brightness > 0) {
      const lift = lightningFlash(this.serverNow() / 1000 + (s.weather.seed || 0), s.weather.lightningIntervalSeconds)
        * flashStyle.brightness;
      if (lift > 0) {
        g.setTransform(1, 0, 0, 1, 0, 0);
        g.globalCompositeOperation = 'destination-out';
        this.fillExposure(g, s, lift, '#000000');
        g.globalCompositeOperation = 'source-over';
      }
    }
    const core = Math.max(0, Math.min(1, s.lighting.brightCore ?? 0.15));
    const showLights = !((darkness < 0.01 && !ind) || lights.length === 0);
    const fraction = s.lighting.brightCoreRadius ?? 0.3;
    const tintStrength = Math.max(0, s.lighting.lightTint ?? 0.05);

    // The desktop bright core is additive and sits under the ambient multiply and under every effect.
    if (showLights && core > 0.002) {
      ctx.save();
      ctx.globalCompositeOperation = 'lighter';
      for (const l of lights) {
        if (!l.color) continue;
        const f = this.flickerOf(s, l);
        const strength = (0.35 + 0.65 * Math.min(1, Math.max(0, l.brightness))) * (1 - 0.6 * f);
        const peak = Math.min(1, core * strength);
        const radius = l.range * fraction * (1 - 0.25 * f);
        if (peak <= 0) continue;
        ctx.save();
        this.polyPath(ctx, this.lightPolygon(l));
        ctx.clip();
        const grad = ctx.createRadialGradient(l.x, l.y, 0, l.x, l.y, radius);
        grad.addColorStop(0, withAlpha(l.color, peak));
        grad.addColorStop(1, withAlpha(l.color, 0));
        ctx.fillStyle = grad;
        ctx.beginPath();
        ctx.arc(l.x, l.y, radius, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
      }
      ctx.restore();
    }

    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalCompositeOperation = 'multiply';
    ctx.drawImage(this.light, 0, 0);
    ctx.restore();

    // Like the desktop effects layer, non-emitting effects sit above the ambient darkness but below the glow.
    afterAmbient();

    if (!showLights || tintStrength <= 0) return;
    let scale = 4;
    while (Math.ceil(this.light.width / scale) * Math.ceil(this.light.height / scale) > 20000) scale++;
    const grid: Grid = {
      originX: -this.cam.x / this.cam.scale,
      originY: -this.cam.y / this.cam.scale,
      cellSize: scale / (this.cam.scale * this.dpr),
      cols: Math.max(1, Math.ceil(this.light.width / scale)),
      rows: Math.max(1, Math.ceil(this.light.height / scale)),
    };
    this.lightTint.width = grid.cols;
    this.lightTint.height = grid.rows;
    const tg = this.lightTint.getContext('2d')!;
    const image = tg.createImageData(grid.cols, grid.rows);
    image.data.set(rasterLightTint(grid, lights.map((l) => {
      const f = this.flickerOf(s, l);
      return {
        x: l.x, y: l.y, range: l.range * (1 - 0.25 * f),
        strength: (0.35 + 0.65 * Math.min(1, Math.max(0, l.brightness))) * (1 - 0.6 * f),
        color: parseCssColor(l.color ?? '#FFD9A0'),
        polygon: this.lightPolygon(l),
      };
    }), environment, tintStrength, s.lighting.tintDarknessFactor ?? 1));
    tg.putImageData(image, 0, 0);
    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalCompositeOperation = 'source-over';
    ctx.imageSmoothingEnabled = true;
    ctx.drawImage(this.lightTint, 0, 0, grid.cols * scale, grid.rows * scale);
    ctx.restore();
  }

  private punchLight(g: CanvasRenderingContext2D, x: number, y: number, range: number, strength: number, poly: Polygon | null): void {
    g.save();
    if (poly) {
      this.polyPath(g, poly);
      g.clip();
    }
    const grad = g.createRadialGradient(x, y, 0, x, y, range);
    grad.addColorStop(0, `rgba(0,0,0,${strength})`);
    grad.addColorStop(0.65, `rgba(0,0,0,${strength * 0.7})`);
    grad.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = grad;
    g.beginPath();
    g.arc(x, y, range, 0, Math.PI * 2);
    g.fill();
    g.restore();
  }

  private drawPings(ctx: CanvasRenderingContext2D, s: GameState): void {
    const now = this.serverNow();
    const cfg = s.view?.ping;
    this.pingActive = false;
    if (!cfg) return;
    for (const p of s.pings ?? []) {
      const progress = (now - p.startMs) / p.durationMs;
      if (progress < 0 || progress >= 1) continue;
      this.pingActive = true;
      const x = this.cam.x + p.x * this.cam.scale;
      const y = this.cam.y + p.y * this.cam.scale;
      const radius = cfg.startRadius + cfg.grow * progress;
      ctx.save();
      ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      ctx.strokeStyle = withAlpha(cfg.color, 1 - progress);
      ctx.lineWidth = 3;
      ctx.beginPath();
      ctx.arc(x, y, radius, 0, Math.PI * 2);
      ctx.stroke();
      ctx.fillStyle = withAlpha(cfg.color, (1 - progress) * 0.7);
      ctx.beginPath();
      ctx.arc(x, y, 6, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    }
  }

  private drawFog(ctx: CanvasRenderingContext2D, s: GameState): void {
    const grid = this.fog.grid;
    const v = s.view;
    if (v?.fogEnabled === false) return;
    const soft = v?.fogSoftness ?? 0;
    const bmp = this.fog.bitmap({
      softCells: grid && soft > 0 ? (soft * s.grid.pixelsPerCell * 0.5) / grid.cellSize : 0,
      fade: v?.fogFade ? { reveal: v.fogRevealSeconds ?? 0.5, hide: v.fogHideSeconds ?? 0.5 } : undefined,
      nowMs: performance.now(),
    });
    if (!grid || !bmp) return;
    ctx.save();
    this.world(ctx);
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(bmp, grid.originX, grid.originY, grid.cols * grid.cellSize, grid.rows * grid.cellSize);
    // outside the fog grid nothing is known
    const big = 1e7;
    ctx.beginPath();
    ctx.rect(-big, -big, big * 2, big * 2);
    ctx.rect(grid.originX, grid.originY, grid.cols * grid.cellSize, grid.rows * grid.cellSize);
    ctx.fillStyle = '#000';
    ctx.fill('evenodd');
    ctx.restore();
  }
}

export function withAlpha(color: string, alpha: number): string {
  const m = /^#?([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(color.trim());
  if (!m) return `rgba(255,255,255,${alpha})`;
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${Math.max(0, Math.min(1, alpha))})`;
}

/** Alpha (0..1) of a #RRGGBB or #RRGGBBAA colour; unreadable values count as transparent. */
function withAlphaValue(hex: string | undefined): number {
  const m = hex ? /^#?([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(hex.trim()) : null;
  if (!m) return 0;
  return m[2] ? parseInt(m[2], 16) / 255 : 1;
}

function parseCssColor(hex: string): [number, number, number] {
  const m = /^#?([0-9a-f]{6})/i.exec(hex.trim());
  const n = m ? parseInt(m[1], 16) : 0xffffff;
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function cssColor(hex: string): string {
  const m = /^#?([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(hex.trim());
  if (!m) return 'rgba(0,0,0,0)';
  const n = parseInt(m[1], 16);
  const a = m[2] ? parseInt(m[2], 16) / 255 : 1;
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

/** Multiplies the RGB channels of a #rrggbb colour (JavaFX darker/brighter use 0.7 / 1/0.7). */
function shade(hex: string, factor: number): string {
  const m = /^#?([0-9a-f]{6})/i.exec(hex);
  const n = m ? parseInt(m[1], 16) : 0x5599cc;
  const ch = (v: number) => Math.max(0, Math.min(255, Math.round(v * factor)));
  return `rgb(${ch((n >> 16) & 255)},${ch((n >> 8) & 255)},${ch(n & 255)})`;
}

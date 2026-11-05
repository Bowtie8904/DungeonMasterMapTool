import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GameState, Measurement, Overlay } from './protocol';
import { MapView } from './render';

class CanvasStub {
  style = { cursor: '', touchAction: '' };
  clientWidth = 1000;
  clientHeight = 1000;
  width = 1000;
  height = 1000;
  tabIndex = 0;
  private listeners = new Map<string, (event: Event) => void>();
  private captured = false;
  getContext() { return {}; }
  getBoundingClientRect() { return { left: 0, top: 0 }; }
  addEventListener(type: string, listener: (event: Event) => void) { this.listeners.set(type, listener); }
  focus() {}
  setPointerCapture() { this.captured = true; }
  hasPointerCapture() { return this.captured; }
  releasePointerCapture() { this.captured = false; }
  pointer(type: string, x: number, y: number, button = 0) {
    this.listeners.get(type)?.(Object.assign(new Event(type), { clientX: x, clientY: y, button, pointerId: 1 }));
  }
  key(key: string, modifiers: Partial<KeyboardEvent> = {}) {
    const event = Object.assign(new Event('keydown', { cancelable: true }), { key, ...modifiers });
    this.listeners.get('keydown')?.(event);
    return event;
  }
  blur() { this.listeners.get('blur')?.(new Event('blur')); }
}

const shape: Overlay = { id: 'shape', ownerId: 'player', ownerCampaignId: 'campaign', type: 'rect',
  x: 200, y: 200, width: 100, height: 100, radius: 0, strokeWidth: 4, color: '#ffffff',
  alpha: 1, texture: 'none', border: false, points: [] };
const measure: Measurement = { id: 'measure', ownerId: 'player', kind: 'distance',
  x1: 400, y1: 400, x2: 500, y2: 400 };

function state(): GameState {
  return { mapId: 'map', levelId: 'level', grid: { pixelsPerCell: 50 }, images: [], walls: [], doors: [],
    lights: [], lighting: { ambient: 1 }, weather: { type: 'NONE', intensity: 0, lightningIntervalSeconds: 0, seed: 0 },
    tokens: [{ id: 'token', x: 100, y: 100, sizeTiles: 1, controlledBy: ['player'] },
      { id: 'other', x: 600, y: 100, sizeTiles: 1, controlledBy: ['other'] }],
    overlays: [{ ...shape }], dmFog: null, fogMemory: null, actors: [], paused: false, visionTiles: 8 };
}

describe('map interaction cursors', () => {
  let canvas: CanvasStub;
  let view: MapView;
  let current: GameState;
  beforeEach(() => {
    vi.stubGlobal('document', { createElement: () => new CanvasStub() });
    vi.stubGlobal('window', { devicePixelRatio: 1 });
    vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} });
    vi.stubGlobal('requestAnimationFrame', vi.fn());
    const element = document.createElement('canvas');
    // Keep a handle to the stub's pointer dispatcher without requiring a browser or canvas renderer.
    if (!(element instanceof CanvasStub)) throw new Error('Expected the test canvas');
    canvas = element;
    view = new MapView(element);
    view.playerId = 'player';
    view.campaignId = 'campaign';
    current = state();
    // A fitted 1000x1000 map keeps the test's world and screen coordinates identical.
    current.images = [{ id: 'map-image', url: '', x: 25, y: 25, width: 950, height: 950, rotationDeg: 0, zIndex: 0 }];
    view.setState(current);
    view.setMeasurements([{ ...measure }]);
  });
  afterEach(() => { view.destroy(); vi.unstubAllGlobals(); });

  it('only advertises controlled tokens, owned drawings and owned measurements', () => {
    for (const [x, y] of [[100, 100], [250, 250], [450, 400]]) {
      canvas.pointer('pointermove', x, y);
      expect(canvas.style.cursor).toBe('grab');
    }
    canvas.pointer('pointermove', 600, 100);
    expect(canvas.style.cursor).toBe('default');
    current.overlays = [{ ...shape, ownerId: 'other' }];
    view.setState(current);
    canvas.pointer('pointermove', 250, 250);
    expect(canvas.style.cursor).toBe('default');
    view.setMeasurements([{ ...measure, ownerId: 'other' }]);
    canvas.pointer('pointermove', 450, 400);
    expect(canvas.style.cursor).toBe('default');
    canvas.pointer('pointermove', 800, 800);
    expect(canvas.style.cursor).toBe('default');
  });

  it('refreshes stationary hover on grants, pause, measurement deletion and tool changes', () => {
    canvas.pointer('pointermove', 100, 100);
    current.tokens[0].controlledBy = [];
    view.setState(current);
    expect(canvas.style.cursor).toBe('default');
    current.tokens[0].controlledBy = ['player'];
    view.setState(current);
    expect(canvas.style.cursor).toBe('grab');
    view.paused = true; view.invalidate();
    expect(canvas.style.cursor).toBe('default');
    view.paused = false; view.invalidate();
    expect(canvas.style.cursor).toBe('grab');
    view.setTool('draw');
    expect(canvas.style.cursor).toBe('crosshair');
    view.setTool('measure');
    expect(canvas.style.cursor).toBe('crosshair');
    canvas.pointer('pointermove', 512, 412);
    expect(canvas.style.cursor).toBe('pointer');
    view.setMeasurements([]);
    expect(canvas.style.cursor).toBe('crosshair');
    view.setTool('navigate');
    expect(canvas.style.cursor).toBe('default');
  });

  it('shows grabbing through token, drawing, measurement and selected-group drags', () => {
    for (const [x, y] of [[100, 100], [250, 250], [450, 400]]) {
      canvas.pointer('pointerdown', x, y);
      expect(canvas.style.cursor).toBe('grabbing');
      canvas.pointer('pointermove', x + 1, y + 1);
      expect(canvas.style.cursor).toBe('grabbing');
      canvas.pointer('pointerup', x + 1, y + 1);
      expect(canvas.style.cursor).toBe('grab');
    }
    canvas.pointer('pointerdown', 50, 50);
    canvas.pointer('pointermove', 150, 150);
    canvas.pointer('pointerup', 150, 150);
    canvas.pointer('pointermove', 100, 100);
    expect(canvas.style.cursor).toBe('grab');
    canvas.pointer('pointerdown', 100, 100);
    expect(canvas.style.cursor).toBe('grabbing');
    canvas.pointer('pointerup', 100, 100);
    expect(canvas.style.cursor).toBe('grab');
  });

  it('matches resize and measurement delete action priority and ownership', () => {
    canvas.pointer('pointerdown', 250, 250);
    canvas.pointer('pointerup', 250, 250);
    canvas.pointer('pointermove', 305, 305);
    expect(canvas.style.cursor).toBe('nwse-resize');
    canvas.pointer('pointerdown', 305, 305);
    expect(canvas.style.cursor).toBe('nwse-resize');
    canvas.pointer('pointerup', 305, 305);
    canvas.pointer('pointermove', 512, 412);
    expect(canvas.style.cursor).toBe('pointer');
    view.setMeasurements([{ ...measure, ownerId: 'other' }]);
    expect(canvas.style.cursor).toBe('default');
    current.overlays = [{ ...shape, ownerCampaignId: 'other' }];
    view.setState(current);
    canvas.pointer('pointermove', 305, 305);
    expect(canvas.style.cursor).toBe('default');
  });

  it('resets panning and cancelled gestures without another pointer move', () => {
    canvas.pointer('pointermove', 100, 100);
    canvas.pointer('pointerdown', 100, 100, 2);
    expect(canvas.style.cursor).toBe('grabbing');
    canvas.pointer('pointercancel', 100, 100, 2);
    expect(canvas.style.cursor).toBe('grab');
    canvas.pointer('pointerdown', 250, 250);
    expect(canvas.style.cursor).toBe('grabbing');
    canvas.pointer('pointercancel', 250, 250);
    expect(canvas.style.cursor).toBe('grab');
    canvas.pointer('pointerleave', 250, 250);
    expect(canvas.style.cursor).toBe('default');
  });

  it('keeps creation crosshairs even while passing over an existing delete button', () => {
    view.setTool('draw');
    canvas.pointer('pointerdown', 700, 700);
    canvas.pointer('pointermove', 512, 412);
    expect(canvas.style.cursor).toBe('crosshair');
    canvas.pointer('pointercancel', 512, 412);
    expect(canvas.style.cursor).toBe('pointer');
    view.setTool('measure');
    canvas.pointer('pointerdown', 700, 700);
    canvas.pointer('pointermove', 512, 412);
    expect(canvas.style.cursor).toBe('crosshair');
  });

  it('selects by left click without sending a move and nudges by exactly half a tile', () => {
    view.onMove = vi.fn();
    expect(canvas.key('ArrowRight').defaultPrevented).toBe(false);
    canvas.pointer('pointerdown', 100, 100);
    expect(canvas.key('ArrowRight').defaultPrevented).toBe(false);
    canvas.pointer('pointerup', 100, 100);
    expect(view.onMove).not.toHaveBeenCalled();
    expect(canvas.key('ArrowRight').defaultPrevented).toBe(true);
    expect(current.tokens[0]).toMatchObject({ x: 125, y: 100 });
    expect(view.onMove).toHaveBeenLastCalledWith({ tokenId: 'token', x: 125, y: 100, prevX: 100, prevY: 100 });
    canvas.key('ArrowDown', { shiftKey: true });
    canvas.key('ArrowLeft');
    canvas.key('ArrowUp', { repeat: true });
    expect(current.tokens[0]).toMatchObject({ x: 100, y: 100 });
    expect(view.onMove).toHaveBeenCalledTimes(4);
    canvas.pointer('pointerdown', 800, 800);
    canvas.pointer('pointerup', 800, 800);
    expect(canvas.key('ArrowRight').defaultPrevented).toBe(false);
  });

  it('nudges only selected controlled tokens and respects pause, tools, modifiers and revoked control', () => {
    current.tokens.push({ id: 'second', x: 150, y: 100, sizeTiles: 1, controlledBy: ['player'] });
    view.onMove = vi.fn();
    canvas.pointer('pointerdown', 50, 50);
    canvas.pointer('pointermove', 650, 150);
    canvas.pointer('pointerup', 650, 150);
    canvas.key('ArrowRight');
    expect(current.tokens.map(t => t.x)).toEqual([125, 600, 175]);
    expect(view.onMove).toHaveBeenCalledTimes(2);
    view.paused = true;
    expect(canvas.key('ArrowRight').defaultPrevented).toBe(false);
    view.paused = false;
    for (const modifier of [{ ctrlKey: true }, { altKey: true }, { metaKey: true }]) {
      expect(canvas.key('ArrowRight', modifier).defaultPrevented).toBe(false);
    }
    canvas.pointer('pointerdown', 125, 100, 2);
    expect(canvas.key('ArrowRight').defaultPrevented).toBe(false);
    canvas.pointer('pointercancel', 125, 100, 2);
    current.tokens.forEach(t => t.controlledBy = []);
    view.setState(current);
    expect(canvas.key('ArrowRight').defaultPrevented).toBe(false);
    view.setTool('draw');
    expect(canvas.key('ArrowRight').defaultPrevented).toBe(false);
    expect(view.onMove).toHaveBeenCalledTimes(2);
  });

  it('selects the visible corners of rotated square tokens instead of using a circular hit area', () => {
    current.tokens[0].shape = 'square';
    current.tokens[0].rotationDeg = 45;
    view.onMove = vi.fn();
    canvas.pointer('pointerdown', 100, 131);
    canvas.pointer('pointerup', 100, 131);
    expect(canvas.key('ArrowRight').defaultPrevented).toBe(true);
    expect(view.onMove).toHaveBeenCalledWith({ tokenId: 'token', x: 125, y: 100, prevX: 100, prevY: 100 });
  });

  it('previews rotation from a selected handle and commits only on release without changing position', () => {
    view.canRotateTokens = true;
    view.onRotate = vi.fn();
    view.onMove = vi.fn();
    canvas.pointer('pointerdown', 100, 100);
    canvas.pointer('pointerup', 100, 100);
    canvas.pointer('pointerdown', 100, 51);
    canvas.pointer('pointermove', 149, 100);
    expect(canvas.style.cursor).toBe('grabbing');
    expect(canvas.key('ArrowRight').defaultPrevented).toBe(false);
    expect(view.onRotate).not.toHaveBeenCalled();
    expect(current.tokens[0]).toMatchObject({ x: 100, y: 100 });
    expect(current.tokens[0].rotationDeg).toBeUndefined();
    canvas.pointer('pointerup', 149, 100);
    expect(view.onRotate).toHaveBeenCalledTimes(1);
    expect(view.onRotate).toHaveBeenCalledWith('token', 90);
    expect(view.onMove).not.toHaveBeenCalled();
    // A host update rotates the handle as well as the artwork.
    current.tokens[0].rotationDeg = 90;
    view.setState(current);
    canvas.pointer('pointerdown', 149, 100);
    canvas.pointer('pointermove', 100, 149);
    canvas.pointer('pointerup', 100, 149);
    expect(vi.mocked(view.onRotate).mock.lastCall?.[0]).toBe('token');
    expect(vi.mocked(view.onRotate).mock.lastCall?.[1]).toBeCloseTo(180);
  });

  it('does not rotate on older hosts, cancellation, focus loss, pause, revoked control or map switch', () => {
    view.onRotate = vi.fn();
    const select = () => {
      canvas.pointer('pointerdown', 100, 100);
      canvas.pointer('pointerup', 100, 100);
    };
    select();
    canvas.pointer('pointerdown', 100, 51);
    canvas.pointer('pointermove', 149, 100);
    canvas.pointer('pointerup', 149, 100);
    expect(view.onRotate).not.toHaveBeenCalled();
    view.canRotateTokens = true;
    for (const cancel of [
      () => canvas.pointer('pointercancel', 149, 100),
      () => canvas.key('Escape'),
      () => canvas.blur(),
      () => { view.paused = true; },
      () => { current.tokens[0].controlledBy = []; view.setState(current); },
      () => { view.setState({ ...current, mapId: 'new-map' }); },
    ]) {
      current.tokens[0].controlledBy = ['player'];
      view.paused = false; view.setState(current); select();
      canvas.pointer('pointerdown', 100, 51);
      canvas.pointer('pointermove', 149, 100);
      cancel();
      canvas.pointer('pointerup', 149, 100);
      expect(view.onRotate).not.toHaveBeenCalled();
    }
  });

  it('draws dashed square outlines for click and box selection and a screen-sized rotation anchor', () => {
    const ctx = Object.assign(document.createElement('canvas').getContext('2d')!, {
      save: vi.fn(), restore: vi.fn(), setTransform: vi.fn(), translate: vi.fn(), rotate: vi.fn(),
      setLineDash: vi.fn(), strokeRect: vi.fn(), beginPath: vi.fn(), moveTo: vi.fn(), lineTo: vi.fn(),
      stroke: vi.fn(), fill: vi.fn(), arc: vi.fn(),
    });
    view.setMeasurements([]);
    view.canRotateTokens = true;
    for (const select of [
      () => { canvas.pointer('pointerdown', 100, 100); canvas.pointer('pointerup', 100, 100); },
      () => { canvas.pointer('pointerdown', 50, 50); canvas.pointer('pointermove', 150, 150); canvas.pointer('pointerup', 150, 150); },
    ]) {
      select();
      vi.clearAllMocks();
      view['drawToolAnnotations'](ctx, current);
      expect(ctx.setLineDash.mock.calls[0][0][0]).toBeCloseTo(5);
      expect(ctx.setLineDash.mock.calls[0][0][1]).toBeCloseTo(4);
      expect(ctx.strokeRect).toHaveBeenCalledWith(-25, -25, 50, 50);
      expect(ctx.arc).toHaveBeenCalledTimes(1);
      expect(ctx.arc.mock.calls[0][0]).toBe(0);
      expect(ctx.arc.mock.calls[0][1]).toBeCloseTo(-49);
      expect(ctx.arc.mock.calls[0][2]).toBeCloseTo(5);
    }
  });

  it('renders only light-gray door/window badges with distinct open and closed glyphs', () => {
    current.doors = [
      { id: 'door', x1: 10, y1: 20, x2: 90, y2: 20, kind: 'door', open: false },
      { id: 'window', x1: 10, y1: 40, x2: 90, y2: 40, kind: 'window', open: true },
    ];
    const ctx = Object.assign(document.createElement('canvas').getContext('2d')!, {
      save: vi.fn(), restore: vi.fn(), translate: vi.fn(), scale: vi.fn(), beginPath: vi.fn(),
      arc: vi.fn(), fill: vi.fn(), stroke: vi.fn(), strokeRect: vi.fn(), moveTo: vi.fn(),
      lineTo: vi.fn(), closePath: vi.fn(), strokeStyle: '', fillStyle: '',
    });
    view['drawDoors'](ctx, current);
    expect(ctx.strokeStyle).toBe('#dddddd');
    expect(ctx.fillStyle).toBe('#dddddd');
    expect(ctx.translate.mock.calls).toEqual([[50, 20], [50, 40]]);
    expect(ctx.moveTo.mock.calls).toEqual([[-5, -5]]);
    expect(ctx.arc.mock.calls).toEqual([[0, 0, 11, 0, Math.PI * 2], [2, 1, 1.2, 0, Math.PI * 2], [0, 0, 11, 0, Math.PI * 2]]);
    vi.clearAllMocks();
    current.doors[0].open = true; current.doors[1].open = false;
    view['drawDoors'](ctx, current);
    expect(ctx.strokeStyle).toBe('#dddddd');
    expect(ctx.fillStyle).toBe('#dddddd');
    expect(ctx.translate.mock.calls).toEqual([[50, 20], [50, 40]]);
    expect(ctx.moveTo.mock.calls).toEqual([[-5, -6], [0, -5], [-5, 0]]);
    expect(ctx.arc.mock.calls).toEqual([[0, 0, 11, 0, Math.PI * 2], [0, 0, 11, 0, Math.PI * 2]]);
  });
});

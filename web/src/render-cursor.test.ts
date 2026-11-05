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
});

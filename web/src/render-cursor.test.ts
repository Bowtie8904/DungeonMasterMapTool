import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GameState, Measurement, Overlay } from './protocol';
import { MapView } from './render';
import { Store } from './store';

class CanvasStub {
  style = { cursor: '', touchAction: '' };
  clientWidth = 1000;
  clientHeight = 1000;
  width = 1000;
  height = 1000;
  tabIndex = 0;
  private listeners = new Map<string, (event: Event) => void>();
  private captured = false;
  getContext() {
    return {
      createImageData: (w: number, h: number) => ({ data: new Uint8ClampedArray(w * h * 4) }),
      putImageData: vi.fn(),
    };
  }
  getBoundingClientRect() { return { left: 0, top: 0 }; }
  addEventListener(type: string, listener: (event: Event) => void) { this.listeners.set(type, listener); }
  focus() {}
  setPointerCapture() { this.captured = true; }
  hasPointerCapture() { return this.captured; }
  releasePointerCapture() { this.captured = false; }
  pointer(type: string, x: number, y: number, button = 0, shiftKey = false) {
    const event = Object.assign(new Event(type, { cancelable: true }), { clientX: x, clientY: y, button, pointerId: 1, shiftKey });
    this.listeners.get(type)?.(event);
    return event;
  }
  doubleClick(x: number, y: number) {
    const event = Object.assign(new Event('dblclick', { cancelable: true }), { clientX: x, clientY: y });
    this.listeners.get('dblclick')?.(event);
    return event;
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
    tokens: [{ id: 'token', x: 100, y: 100, sizeTiles: 1, controlledBy: ['player'], phaseMovement: false },
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
  afterEach(() => { view.destroy(); vi.useRealTimers(); vi.unstubAllGlobals(); });

  it.each([false, true])('clips fast drags before vision, attached lighting and move commands (Shift=%s)', shift => {
    current.walls = [150, -10000, 150, 10000];
    current.fogGrid = { originX: 0, originY: 0, cellSize: 10, cols: 50, rows: 50 };
    current.lights = [{ id: 'token-light-token', x: 100, y: 100, range: 200, on: true,
      color: '#ffffff', brightness: 1, revealMode: 'WHILE_LIT' }];
    view.setState(current);
    const moves = vi.fn();
    view.onMove = moves;
    canvas.pointer('pointerdown', 100, 100);
    canvas.pointer('pointermove', 900, 100, 0, shift);
    const token = current.tokens[0];
    expect(token.x).toBeCloseTo(149.9999, 7);
    Reflect.get(view, 'recomputeVision').call(view, current);
    const sight = Reflect.get(view, 'sights')[0];
    expect(sight.token.x).toBe(token.x);
    expect(Math.max(...sight.sight.xs)).toBeLessThanOrEqual(150.000001);
    expect(Reflect.get(view, 'liveLights').call(view, current)[0].x).toBe(token.x);
    expect(view.fog.visible[10 * 50 + 10]).toBe(1);
    expect(view.fog.visible[10 * 50 + 20]).toBe(0);
    expect(view.fog.hasNewExplored).toBe(false);
    canvas.pointer('pointerup', 900, 100);
    expect(moves).toHaveBeenCalled();
    expect(moves.mock.calls.every(([move]) => move.x < 150)).toBe(true);
    expect(token.x).toBeLessThan(150);
    expect(Reflect.get(view, 'liveLights').call(view, current)[0].x).toBe(token.x);
  });

  it('refreshes closed door/window collisions during a drag and passes an open portal', () => {
    current.doors = [{ id: 'door', x1: 150, y1: 0, x2: 150, y2: 1000, open: false, kind: 'window' }];
    view.setState(current);
    canvas.pointer('pointerdown', 100, 100);
    canvas.pointer('pointermove', 200, 100);
    expect(current.tokens[0].x).toBeLessThan(150);
    current.doors[0].open = true;
    view.setState(current);
    canvas.pointer('pointermove', 200, 100);
    expect(current.tokens[0].x).toBe(200);
    current.doors[0].open = false;
    view.setState(current);
    canvas.pointer('pointermove', 100, 100);
    expect(current.tokens[0].x).toBeGreaterThan(150);
  });

  it('retains snapping, free movement, spectral passage and legacy-host prediction', () => {
    canvas.pointer('pointerdown', 100, 100);
    canvas.pointer('pointermove', 133, 100);
    expect(current.tokens[0].x).toBe(125);
    canvas.pointer('pointermove', 133, 100, 0, true);
    expect(current.tokens[0].x).toBeCloseTo(133);
    current.walls = [150, 0, 150, 1000];
    current.tokens[0].phaseMovement = true;
    view.setState(current);
    canvas.pointer('pointermove', 200, 100);
    expect(current.tokens[0].x).toBe(200);
    delete current.tokens[0].phaseMovement;
    view.setState(current);
    canvas.pointer('pointermove', 100, 100);
    expect(current.tokens[0].x).toBe(100);
  });

  it.each(['effect', 'restrained'])('prevents local movement under a %s restriction, even when spectral', restriction => {
    current.tokens[0].phaseMovement = true;
    if (restriction === 'effect') current.tokens[0].movementBlocked = 'Cannot move';
    else current.tokens[0].conditions = [{ id: 'restrained', type: 'restrained', name: 'Restrained' }];
    view.setState(current);
    const move = vi.fn(); view.onMove = move;
    canvas.pointer('pointerdown', 100, 100);
    canvas.pointer('pointermove', 200, 100);
    canvas.pointer('pointerup', 200, 100);
    canvas.key('ArrowRight');
    expect(current.tokens[0].x).toBe(100);
    expect(move).not.toHaveBeenCalled();
  });

  it('clips keyboard nudges and group token commits with the same movement rules', () => {
    current.walls = [110, 0, 110, 1000];
    view.setState(current);
    const move = vi.fn(); view.onMove = move;
    canvas.pointer('pointerdown', 100, 100);
    canvas.pointer('pointerup', 100, 100);
    canvas.key('ArrowRight');
    expect(current.tokens[0].x).toBeCloseTo(109.9999, 7);
    expect(move.mock.calls[0][0].x).toBeLessThan(110);
    move.mockClear();
    current.tokens[0].x = 100;
    canvas.pointer('pointerdown', 50, 50);
    canvas.pointer('pointermove', 350, 350);
    canvas.pointer('pointerup', 350, 350);
    canvas.pointer('pointerdown', 100, 100);
    canvas.pointer('pointermove', 200, 100);
    const group = Reflect.get(view, 'group');
    expect(group).not.toBeNull();
    const preview = Reflect.get(view, 'movementPosition').call(view, group.tokens[0], 200, 100);
    expect(preview.x).toBeLessThan(110);
    canvas.pointer('pointerup', 200, 100);
    expect(move).toHaveBeenCalledOnce();
    expect(move.mock.calls[0][0].x).toBe(preview.x);
  });

  it('draws a cached smooth glow with runtime strength and invalidates it for range and geometry changes', () => {
    const context = { save: vi.fn(), restore: vi.fn(), drawImage: vi.fn(), globalAlpha: 1, imageSmoothingEnabled: false };
    const punch = Reflect.get(view, 'punchShapeLight').bind(view);
    punch(context, shape, 100, 0.7);
    expect(context.globalAlpha).toBe(0.7);
    expect(context.imageSmoothingEnabled).toBe(true);
    expect(context.drawImage).toHaveBeenCalledOnce();
    const cache = Reflect.get(view, 'glowCache');
    const first = cache.get(shape.id);
    punch(context, shape, 100, 0.5);
    expect(cache.get(shape.id)).toBe(first);
    expect(context.globalAlpha).toBe(0.5);
    punch(context, shape, 75, 0.5);
    expect(cache.get(shape.id)).not.toBe(first);
    const flickering = cache.get(shape.id);
    punch(context, { ...shape, width: 200 }, 75, 0.5);
    expect(cache.get(shape.id)).not.toBe(flickering);
    view.destroy();
    expect(cache.size).toBe(0);
  });

  it('retains the inexpensive flat shape in simplified lighting', () => {
    view.simpleLighting = true;
    const context = { save: vi.fn(), restore: vi.fn(), fillStyle: '', strokeStyle: '', drawImage: vi.fn(),
      beginPath: vi.fn(), rect: vi.fn(), fill: vi.fn() };
    Reflect.get(view, 'punchShapeLight').call(view, context, shape, 100, 0.5);
    expect(context.fillStyle).toBe('rgba(0,0,0,0.2)');
    expect(context.rect).toHaveBeenCalledWith(shape.x, shape.y, shape.width, shape.height);
    expect(context.fill).toHaveBeenCalledOnce();
    expect(context.drawImage).not.toHaveBeenCalled();
  });

  it('keeps unsent pointer movement ahead of stale state and acknowledgements', () => {
    const store = new Store();
    store.applySnapshot(1, current);
    view.setState(store.state);
    view.onMove = move => store.beginMove('move', move, 'player');
    canvas.pointer('pointerdown', 100, 100);
    canvas.pointer('pointermove', 200, 100);
    // Model a newer pointer position which has not passed the drag's send throttle yet.
    const drag = Reflect.get(view, 'drag');
    drag.token.x = 225;
    store.applyDelta(2, [{ op: 'tokens', upsert: [{ ...current.tokens[0], x: 150 }] }]);
    view.setState(store.state);
    expect(store.state!.tokens[0].x).toBe(225);
    store.acknowledgeMove('move', 2);
    view.setState(store.state);
    expect(store.state!.tokens[0].x).toBe(225);
    expect(Reflect.get(view, 'drag').token).toBe(store.state!.tokens[0]);
  });

  it('opens only the signed-in player’s controlled actor token on double-click', () => {
    current.actors = [
      { id: 'owned', name: 'Owned', ownerId: 'player' },
      { id: 'other', name: 'Other', ownerId: 'other-player' },
    ];
    current.tokens[0].actorId = 'owned';
    const openActor = vi.fn();
    view.onOpenActor = openActor;

    expect(canvas.doubleClick(100, 100).defaultPrevented).toBe(true);
    expect(openActor).toHaveBeenCalledWith('owned');

    openActor.mockClear();
    current.tokens[0].controlledBy = ['other-player'];
    canvas.doubleClick(100, 100);
    expect(openActor).not.toHaveBeenCalled();

    current.tokens[0].controlledBy = ['player'];
    current.tokens[0].actorId = 'other';
    canvas.doubleClick(100, 100);
    expect(openActor).not.toHaveBeenCalled();

    current.tokens[0].actorId = 'owned';
    view.paused = true;
    canvas.doubleClick(100, 100);
    expect(openActor).not.toHaveBeenCalled();
  });

  it('keeps attached lights and fog vision at the token position after release', () => {
    current.lights = [{ id: 'token-light-token', x: 0, y: 0, range: 200, on: true,
      color: '#ffffff', brightness: 1, revealMode: 'WHILE_LIT' }];
    view.onMove = vi.fn();
    canvas.pointer('pointerdown', 100, 100);
    canvas.pointer('pointermove', 200, 100);
    canvas.pointer('pointerup', 200, 100);
    const light = Reflect.get(view, 'liveLights').call(view, current)[0];
    expect(light).toMatchObject({ x: current.tokens[0].x, y: current.tokens[0].y });
    expect(current.lights[0]).toMatchObject({ x: 0, y: 0 });
    const polygon = vi.fn(() => ({ xs: [], ys: [] }));
    Reflect.set(view, 'lightPolygon', polygon);
    current.dmFog = { originX: 0, originY: 0, cellSize: 25, cols: 40, rows: 40, revealed: '' };
    Reflect.get(view, 'recomputeVision').call(view, current);
    expect(polygon).toHaveBeenCalledWith(expect.objectContaining({ x: current.tokens[0].x, y: current.tokens[0].y }));
  });

  it('does not persist tentative vision before authoritative movement catches up', () => {
    current.fogGrid = { originX: 0, originY: 0, cellSize: 25, cols: 40, rows: 40 };
    view.setMovesInFlight(1);
    Reflect.get(view, 'recomputeVision').call(view, current);
    expect(view.fog.visible.some(cell => cell === 1)).toBe(true);
    expect(view.fog.hasNewExplored).toBe(false);
    view.setMovesInFlight(0);
    Reflect.get(view, 'recomputeVision').call(view, current);
    expect(view.fog.hasNewExplored).toBe(true);
  });

  it('reveals only controlled actor sight temporarily in darkness, respecting doors and range', () => {
    current.tokens[0].actorId = 'owned';
    current.tokens[1].actorId = 'other-actor';
    current.lighting.ambient = 0;
    current.dmFog = { originX: 0, originY: 0, cellSize: 50, cols: 80, rows: 20, revealed: '' };
    current.doors = [{ id: 'door', x1: 500, y1: 0, x2: 500, y2: 1000, open: false, kind: 'door' }];
    const visibleAt = (x: number, y: number) => view.fog.visible[Math.floor(y / 50) * 80 + Math.floor(x / 50)];
    Reflect.get(view, 'recomputeVision').call(view, current);
    expect(visibleAt(300, 100)).toBe(1);
    expect(visibleAt(600, 100)).toBe(0);
    expect(view.fog.hasNewExplored).toBe(false);

    current.doors[0].open = true;
    Reflect.get(view, 'recomputeVision').call(view, current);
    expect(visibleAt(600, 100)).toBe(1);
    expect(visibleAt(2200, 100)).toBe(0);
    current.tokens[0].controlledBy = ['other'];
    Reflect.get(view, 'recomputeVision').call(view, current);
    expect(visibleAt(300, 100)).toBe(0);
  });

  it('does not add sight-only cells to explored memory when there is no DM mask', () => {
    current.tokens[0].actorId = 'owned';
    current.lighting.ambient = 0;
    current.fogGrid = { originX: 0, originY: 0, cellSize: 50, cols: 40, rows: 20 };
    Reflect.get(view, 'recomputeVision').call(view, current);
    expect(view.fog.visible[2 * 40 + 6]).toBe(1);
    expect(view.fog.explored[2 * 40 + 6]).toBe(0);
    expect(view.fog.hasNewExplored).toBe(false);
    current.tokens[0].actorId = undefined;
    Reflect.get(view, 'recomputeVision').call(view, current);
    expect(view.fog.visible[2 * 40 + 6]).toBe(0);
  });

  it('updates controlled actor sight from the host view while retaining the older host default', () => {
    current.tokens[0].actorId = 'owned';
    current.lighting.ambient = 0;
    current.dmFog = { originX: 0, originY: 0, cellSize: 50, cols: 80, rows: 20, revealed: '' };
    const at = (col: number) => view.fog.visible[2 * 80 + col];
    Reflect.get(view, 'recomputeVision').call(view, current);
    expect(at(30)).toBe(1);
    current.view = { gridOpacity: 0, actorSightTiles: 5 };
    view.setState(current);
    Reflect.get(view, 'recomputeVision').call(view, current);
    expect(at(8)).toBe(0);
    current.view.actorSightTiles = 60;
    view.setState(current);
    Reflect.get(view, 'recomputeVision').call(view, current);
    expect(at(50)).toBe(1);
    expect(view.fog.hasNewExplored).toBe(false);
  });

  it('limits the legacy ambient sight to the configured actor range without changing regular tokens', () => {
    current.tokens[0].actorId = 'owned';
    current.view = { gridOpacity: 0, actorSightTiles: 5 };
    current.fogGrid = { originX: 0, originY: 0, cellSize: 50, cols: 40, rows: 20 };
    Reflect.get(view, 'recomputeVision').call(view, current);
    expect(view.fog.visible[2 * 40 + 8]).toBe(0);
    current.tokens[0].actorId = undefined;
    Reflect.get(view, 'recomputeVision').call(view, current);
    expect(view.fog.visible[2 * 40 + 8]).toBe(1);
  });

  it('skips hidden-tab canvas and vision work but redraws the latest state on visibility', () => {
    const draw = vi.fn();
    const vision = vi.fn();
    Reflect.set(view, 'draw', draw);
    Reflect.set(view, 'recomputeVision', vision);
    Reflect.set(document, 'hidden', true);
    Reflect.get(view, 'frame').call(view, 1000);
    expect(draw).not.toHaveBeenCalled();
    expect(vision).not.toHaveBeenCalled();
    expect(Reflect.get(view, 'dirty')).toBe(true);
    Reflect.set(document, 'hidden', false);
    Reflect.get(view, 'frame').call(view, 2000);
    expect(draw).toHaveBeenCalledWith(current);
    expect(vision).toHaveBeenCalledOnce();
    Reflect.get(view, 'frame').call(view, 3000);
    expect(draw).toHaveBeenCalledOnce();
  });

  it('creates drawings and measurements with the chosen account-default or tool-override color', () => {
    view.style.color = '#2979ff';
    view.onDrawing = vi.fn();
    view.setTool('draw');
    canvas.pointer('pointerdown', 700, 700);
    canvas.pointer('pointermove', 750, 750);
    canvas.pointer('pointerup', 750, 750);
    expect(view.onDrawing).toHaveBeenCalledWith('create', expect.any(String), expect.objectContaining({ color: '#2979ff' }));
    view.measurementColor = '#2979ff';
    view.onMeasurement = vi.fn();
    view.setTool('measure');
    canvas.pointer('pointerdown', 700, 700);
    canvas.pointer('pointermove', 750, 750);
    canvas.pointer('pointerup', 750, 750);
    expect(view.onMeasurement).toHaveBeenLastCalledWith(expect.objectContaining({ color: '#2979ff' }), false);
    view.measurementColor = '#ff9100';
    canvas.pointer('pointerdown', 700, 700);
    canvas.pointer('pointermove', 800, 800);
    canvas.pointer('pointerup', 800, 800);
    expect(view.onMeasurement).toHaveBeenLastCalledWith(expect.objectContaining({ color: '#ff9100' }), false);
  });

  it('places one tool ping at world coordinates and restores Select and its cursor', () => {
    const ping = vi.fn(), select = vi.fn();
    view.canPing = true; view.onPing = ping; view.onSelectTool = select;
    view.setTool('ping');
    canvas.pointer('pointermove', 100, 100);
    expect(canvas.style.cursor).toBe('crosshair');
    canvas.pointer('pointerdown', 100, 100);
    canvas.pointer('pointerup', 100, 100);
    expect(ping).toHaveBeenCalledOnce();
    expect(ping.mock.calls[0][0]).toBeCloseTo(100);
    expect(ping.mock.calls[0][1]).toBeCloseTo(100);
    expect(select).toHaveBeenCalledOnce();
    expect(view.tool).toBe('navigate');
    expect(canvas.style.cursor).toBe('grab');
  });

  it.each([[800, 800], [100, 100], [250, 250], [450, 400]])('pings once after a stationary 600 ms Select hold at %s,%s', (x, y) => {
    vi.useFakeTimers();
    const ping = vi.fn(), move = vi.fn(), drawing = vi.fn(), measurement = vi.fn();
    view.canPing = true; view.onPing = ping; view.onMove = move;
    view.onDrawing = drawing; view.onMeasurement = measurement;
    canvas.pointer('pointerdown', x, y);
    canvas.pointer('pointermove', x + 2, y);
    vi.advanceTimersByTime(599);
    expect(ping).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(ping).toHaveBeenCalledOnce();
    expect(ping.mock.calls[0][0]).toBeCloseTo(x);
    expect(ping.mock.calls[0][1]).toBeCloseTo(y);
    vi.advanceTimersByTime(2000);
    canvas.pointer('pointermove', x + 100, y + 100);
    canvas.pointer('pointerup', x + 100, y + 100);
    expect(ping).toHaveBeenCalledOnce();
    expect(view.tool).toBe('navigate');
    expect(move).not.toHaveBeenCalled();
    expect(drawing).not.toHaveBeenCalled();
    expect(measurement).not.toHaveBeenCalled();
  });

  it('cancels the hold beyond four screen pixels while preserving token dragging', () => {
    vi.useFakeTimers();
    const ping = vi.fn(), move = vi.fn();
    view.canPing = true; view.onPing = ping; view.onMove = move;
    canvas.pointer('pointerdown', 100, 100);
    canvas.pointer('pointermove', 104, 100);
    expect(current.tokens[0].x).toBe(100);
    canvas.pointer('pointermove', 125, 100);
    vi.advanceTimersByTime(1000);
    canvas.pointer('pointerup', 125, 100);
    expect(ping).not.toHaveBeenCalled();
    expect(current.tokens[0].x).toBe(125);
    expect(move).toHaveBeenCalled();
  });

  it.each(['release', 'cancel', 'blur', 'tool', 'map', 'level', 'pause', 'destroy', 'unsupported'])('cancels pending holds on %s', reason => {
    vi.useFakeTimers();
    const ping = vi.fn();
    view.canPing = true; view.onPing = ping;
    canvas.pointer('pointerdown', 800, 800);
    if (reason === 'release') canvas.pointer('pointerup', 800, 800);
    if (reason === 'cancel') canvas.pointer('pointercancel', 800, 800);
    if (reason === 'blur') canvas.blur();
    if (reason === 'tool') view.setTool('draw');
    if (reason === 'map') view.setState({ ...current, mapId: 'next' });
    if (reason === 'level') view.setState({ ...current, levelId: 'next' });
    if (reason === 'pause') { view.paused = true; view.invalidate(); }
    if (reason === 'destroy') view.destroy();
    if (reason === 'unsupported') { view.canPing = false; view.invalidate(); }
    vi.advanceTimersByTime(1000);
    expect(ping).not.toHaveBeenCalled();
  });

  it('opens the token menu only after canceling the browser context menu', () => {
    view.canEditConditions = true;
    const open = vi.fn(() => true);
    view.onTokenContext = open;
    canvas.pointer('pointerdown', 100, 100, 2);
    expect(open).not.toHaveBeenCalled();
    const context = canvas.pointer('contextmenu', 100, 100, 2);
    expect(context.defaultPrevented).toBe(true);
    expect(open).toHaveBeenCalledWith(current.tokens[0], 100, 100);
    canvas.pointer('pointerup', 100, 100, 2);
    expect(open).toHaveBeenCalledTimes(1);
  });

  it('suppresses native menus without exposing uncontrolled tokens or unsupported controls', () => {
    const open = vi.fn(() => true);
    view.onTokenContext = open;
    expect(canvas.pointer('contextmenu', 100, 100, 2).defaultPrevented).toBe(true);
    view.canEditConditions = true;
    expect(canvas.pointer('contextmenu', 600, 100, 2).defaultPrevented).toBe(true);
    expect(open).not.toHaveBeenCalled();
  });

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
    const unlockedCalls = ctx.arc.mock.calls.map(call => [...call]);
    vi.clearAllMocks();
    current.doors.forEach(d => { d.locked = true; });
    view['drawDoors'](ctx, current);
    expect(ctx.arc.mock.calls).toEqual(unlockedCalls);
    expect(ctx.strokeStyle).toBe('#dddddd');
    expect(ctx.fillStyle).toBe('#dddddd');
    vi.clearAllMocks();
    current.doors[0].open = true; current.doors[1].open = false;
    view['drawDoors'](ctx, current);
    expect(ctx.strokeStyle).toBe('#dddddd');
    expect(ctx.fillStyle).toBe('#dddddd');
    expect(ctx.translate.mock.calls).toEqual([[50, 20], [50, 40]]);
    expect(ctx.moveTo.mock.calls).toEqual([[-5, -6], [0, -5], [-5, 0]]);
    expect(ctx.arc.mock.calls).toEqual([[0, 0, 11, 0, Math.PI * 2], [0, 0, 11, 0, Math.PI * 2]]);
  });

  it('does not render token names in the player map', () => {
    current.tokens[0].name = 'Private DM label';
    const ctx = Object.assign(document.createElement('canvas').getContext('2d')!, {
      save: vi.fn(), restore: vi.fn(), translate: vi.fn(), rotate: vi.fn(), beginPath: vi.fn(),
      arc: vi.fn(), rect: vi.fn(), clip: vi.fn(), fill: vi.fn(), fillRect: vi.fn(), ellipse: vi.fn(),
      stroke: vi.fn(), strokeRect: vi.fn(), fillText: vi.fn(), strokeText: vi.fn(),
    });

    view['drawTokens'](ctx, current, current.grid.pixelsPerCell);

    expect(ctx.arc).toHaveBeenCalled();
    expect(ctx.fillText).not.toHaveBeenCalled();
    expect(ctx.strokeText).not.toHaveBeenCalled();
  });

  it.each(['door', 'window'])('only toggles unlocked %s badges in unpaused Select on supporting hosts', kind => {
    current.doors = [{ id: 'portal', x1: 650, y1: 700, x2: 750, y2: 700, kind, open: false }];
    view.setState(current);
    const toggle = vi.fn();
    view.onPortal = toggle;
    canvas.pointer('pointerdown', 700, 700); canvas.pointer('pointerup', 700, 700);
    expect(toggle).not.toHaveBeenCalled();
    view.canControlPortals = true;
    canvas.pointer('pointermove', 700, 700);
    expect(canvas.style.cursor).toBe('pointer');
    canvas.pointer('pointerdown', 700, 700); canvas.pointer('pointerup', 700, 700);
    expect(toggle).toHaveBeenCalledTimes(1);
    expect(toggle).toHaveBeenCalledWith('portal');
    expect(current.doors[0].open).toBe(false);
    toggle.mockClear();
    current.doors[0].locked = true;
    view.setState(current);
    expect(canvas.style.cursor).toBe('pointer');
    canvas.pointer('pointerdown', 700, 700); canvas.pointer('pointerup', 700, 700);
    expect(toggle).not.toHaveBeenCalled();
    current.doors[0].locked = false;
    view.paused = true; view.invalidate();
    expect(canvas.style.cursor).toBe('default');
    canvas.pointer('pointerdown', 700, 700); canvas.pointer('pointerup', 700, 700);
    expect(toggle).not.toHaveBeenCalled();
    view.paused = false; view.setTool('draw');
    canvas.pointer('pointerdown', 700, 700); canvas.pointer('pointercancel', 700, 700);
    expect(toggle).not.toHaveBeenCalled();
    view.setTool('navigate');
    canvas.pointer('pointerdown', 700, 700, 2); canvas.pointer('pointerup', 700, 700, 2);
    expect(toggle).not.toHaveBeenCalled();
  });
});

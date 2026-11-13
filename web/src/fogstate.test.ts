import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FogState } from './fogstate';

describe('fog circle visibility', () => {
  let fog: FogState;
  beforeEach(() => {
    vi.stubGlobal('document', { createElement: () => ({
      width: 0, height: 0,
      getContext: () => ({
        createImageData: (w: number, h: number) => ({ data: new Uint8ClampedArray(w * h * 4) }),
        putImageData: vi.fn(), fillRect: vi.fn(), drawImage: vi.fn(),
      }),
    }) });
    fog = new FogState();
    fog.configure({ originX: 100, originY: 200, cellSize: 10, cols: 4, rows: 4 });
  });
  afterEach(() => vi.unstubAllGlobals());

  it('only detects non-opaque cells intersecting the actual circle, not its bounding box', () => {
    fog.visible[0] = 1;
    fog.bitmap();
    expect(fog.hasUncoveredCircle(111, 211, 2)).toBe(true);
    expect(fog.hasUncoveredCircle(112, 212, 2)).toBe(false);
    expect(fog.hasUncoveredCircle(120, 205, 10)).toBe(false);
    expect(fog.hasUncoveredCircle(80, 205, 5)).toBe(false);
    expect(fog.hasUncoveredCircle(125, 225, 2)).toBe(false);
  });

  it('includes explored and DM-revealed cells, but not explored cells under DM-controlled fog', () => {
    fog.explored[5] = 1;
    fog.bitmap();
    expect(fog.hasUncoveredCircle(115, 215, 1)).toBe(true);
    fog.dmControlled = true;
    fog.bitmapDirty = true;
    fog.bitmap();
    expect(fog.hasUncoveredCircle(115, 215, 1)).toBe(false);
    fog.dmRevealed[5] = 1;
    fog.bitmapDirty = true;
    fog.bitmap();
    expect(fog.hasUncoveredCircle(115, 215, 1)).toBe(true);
  });

  it('does not expose badges from soft-edge blur alone', () => {
    fog.visible[0] = 1;
    fog.bitmap({ softCells: 2 });
    expect(fog.hasUncoveredCircle(125, 225, 2)).toBe(false);
    expect(fog.hasUncoveredCircle(109, 205, 2)).toBe(true);
  });

  it('follows displayed reveal and hide fades, and fails closed before the first bitmap', () => {
    expect(fog.hasUncoveredCircle(115, 215, 1)).toBe(false);
    fog.bitmap({ nowMs: 1000 });
    fog.dmRevealed[5] = 1;
    fog.bitmapDirty = true;
    fog.bitmap({ fade: { reveal: 1, hide: 1 }, nowMs: 1016 });
    expect(fog.hasUncoveredCircle(115, 215, 1)).toBe(true);
    fog.dmRevealed[5] = 0;
    fog.bitmapDirty = true;
    fog.bitmap({ fade: { reveal: 1, hide: 1 }, nowMs: 1116 });
    expect(fog.hasUncoveredCircle(115, 215, 1)).toBe(false);
    fog.dmRevealed[5] = 1;
    fog.bitmapDirty = true;
    fog.bitmap({ nowMs: 1200 });
    fog.dmRevealed[5] = 0;
    fog.bitmapDirty = true;
    fog.bitmap({ fade: { reveal: 1, hide: 1 }, nowMs: 1216 });
    expect(fog.hasUncoveredCircle(115, 215, 1)).toBe(true);
    for (let time = 1316; time <= 2316; time += 100) {
      fog.bitmap({ fade: { reveal: 1, hide: 1 }, nowMs: time });
    }
    expect(fog.hasUncoveredCircle(115, 215, 1)).toBe(false);
  });
});

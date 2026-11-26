import { describe, expect, it } from 'vitest';
import { canvasBackingScale } from './render';

describe('map backing resolution', () => {
  it('caps regular rendering at 2x', () => {
    expect(canvasBackingScale(1)).toBe(1);
    expect(canvasBackingScale(2)).toBe(2);
    expect(canvasBackingScale(3)).toBe(2);
  });

  it('uses 1x in low-resource mode', () => {
    expect(canvasBackingScale(3, true)).toBe(1);
  });
});

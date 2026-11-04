import { describe, expect, it } from 'vitest';
import { Measurement, Overlay } from './protocol';
import { distanceToSegment, drawingBounds, drawingHit, measurementBounds, measurementLabel, ownsDrawing, resizeDrawing, translateDrawing } from './tools';

const shape: Overlay = { id: 's', ownerId: 'alice', ownerCampaignId: 'c', type: 'rect', x: 10, y: 20,
  width: 30, height: 40, radius: 0, strokeWidth: 4, color: '#ffffff', alpha: 0.4,
  texture: 'none', border: false, points: [] };

describe('owned map drawings', () => {
  it('only treats matching player and campaign as owned; legacy DM shapes are not editable', () => {
    expect(ownsDrawing(shape, 'alice', 'c')).toBe(true);
    expect(ownsDrawing(shape, 'bob', 'c')).toBe(false);
    expect(ownsDrawing(shape, 'alice', 'other')).toBe(false);
    expect(ownsDrawing({ ...shape, ownerId: null }, 'alice', 'c')).toBe(false);
  });
  it('moves and resizes shapes without changing the source or ownership', () => {
    expect(drawingBounds(translateDrawing(shape, 5, 6))).toEqual([15, 26, 45, 66]);
    const resized = resizeDrawing(shape, 70, 100);
    expect(drawingBounds(resized)).toEqual([10, 20, 70, 100]);
    expect(resized.ownerId).toBe('alice');
    expect(shape.width).toBe(30);
  });
  it('picks stroke segments rather than the whole bounding box', () => {
    const line = { ...shape, type: 'line', points: [0, 0, 100, 100] };
    expect(drawingHit(line, 50, 50, 2)).toBe(true);
    expect(drawingHit(line, 0, 100, 2)).toBe(false);
    expect(distanceToSegment(3, 4, 0, 0, 0, 0)).toBe(5);
  });
  it('supports brush dots and circle selection', () => {
    expect(drawingHit({ ...shape, type: 'brush', points: [10, 20] }, 10, 20, 1)).toBe(true);
    expect(drawingHit({ ...shape, type: 'circle', radius: 10 }, 10, 20, 1)).toBe(true);
    expect(drawingHit({ ...shape, type: 'circle', radius: 10 }, 30, 40, 1)).toBe(false);
  });
});

describe('shared measurement labels', () => {
  const m: Measurement = { id: 'm', ownerId: 'alice', kind: 'distance', x1: 0, y1: 0, x2: 30, y2: 40 };
  it('uses Euclidean grid-tile distance, including radius and cone length', () => {
    expect(measurementLabel(m, 10)).toBe('5.0 tiles · Close');
    expect(measurementLabel({ ...m, kind: 'radius' }, 10)).toBe('r = 5.0 tiles · Close');
    expect(measurementLabel({ ...m, kind: 'cone' }, 10)).toBe('5.0 tiles · Close');
  });
  it('shows both rectangle dimensions independently of drag direction', () => {
    expect(measurementLabel({ ...m, kind: 'rect', x2: -30 }, 10)).toBe('3.0 x 4.0 tiles · Close');
  });
  it('maps tile distances to the Daggerheart SRD ranges', () => {
    const at = (tiles: number) => measurementLabel({ ...m, x2: tiles * 10, y2: 0 }, 10);
    expect(at(1)).toBe('1.0 tiles · Melee');
    expect(at(3)).toBe('3.0 tiles · Very Close');
    expect(at(6)).toBe('6.0 tiles · Close');
    expect(at(12)).toBe('12.0 tiles · Far');
    expect(at(13)).toBe('13.0 tiles · Very Far');
  });
  it('uses the entire measured geometry for box selection, not just endpoints', () => {
    expect(measurementBounds(m)).toEqual([0, 0, 30, 40]);
    expect(measurementBounds({ ...m, kind: 'radius' })).toEqual([-50, -50, 50, 50]);
    const cone = measurementBounds({ ...m, kind: 'cone', x2: 100, y2: 0 });
    expect(cone[0]).toBe(0);
    expect(cone[1]).toBeCloseTo(-50);
    expect(cone[2]).toBe(100);
    expect(cone[3]).toBeCloseTo(50);
  });
});

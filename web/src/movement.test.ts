import { describe, expect, it } from 'vitest';
import { stopAtObstacle } from './movement';

describe('token center collision sweep', () => {
  const start = { x: 0, y: 0 };

  it('keeps unobstructed and stationary moves unchanged', () => {
    expect(stopAtObstacle(start, 10, 5, [])).toEqual({ x: 10, y: 5 });
    expect(stopAtObstacle(start, 0, 0, [0, -5, 0, 5])).toEqual(start);
    expect(stopAtObstacle(start, 10, 0, [5, 1, 5, 5])).toEqual({ x: 10, y: 0 });
    expect(stopAtObstacle(start, 10, 0, [-5, -5, -5, 5])).toEqual({ x: 10, y: 0 });
  });

  it.each([
    [5, -5, 5, 5],
    [5, 5, 5, -5],
    [5, 0, 5, 5],
    [5, -5, 5, 0],
    [5, 0, 8, 0],
    [8, 0, 5, 0],
    [5, 0, 5, 0],
  ])('stops before wall/endpoint/collinear segment %j', (...segment) => {
    const next = stopAtObstacle(start, 1000, 0, segment);
    expect(next.x).toBeCloseTo(4.9999, 8);
    expect(next.y).toBe(0);
    expect(stopAtObstacle(next, 1000, 0, segment).x).toBeCloseTo(next.x, 8);
  });

  it('chooses the first collision regardless of geometry order', () => {
    expect(stopAtObstacle(start, 10, 0, [8, -5, 8, 5, 3, -5, 3, 5]).x).toBeCloseTo(2.9999, 8);
  });

  it('stops before a diagonal wall in either movement direction', () => {
    const wall = [0, 10, 10, 0];
    const forward = stopAtObstacle(start, 10, 10, wall);
    expect(forward.x).toBeLessThan(5);
    expect(forward.y).toBeCloseTo(forward.x, 8);
    expect(Math.hypot(forward.x - 5, forward.y - 5)).toBeCloseTo(0.0001, 8);
    const backward = stopAtObstacle({ x: 10, y: 10 }, 0, 0, wall);
    expect(backward.x).toBeGreaterThan(5);
  });

  it('matches the host when starting on a wall or overlapping a collinear wall', () => {
    expect(stopAtObstacle(start, 10, 0, [0, -5, 0, 5])).toEqual(start);
    expect(stopAtObstacle(start, 10, 0, [-5, 0, 5, 0])).toEqual(start);
  });

  it('can retreat from a blocked position and go around a wall endpoint', () => {
    const wall = [5, -5, 5, 5];
    const stopped = stopAtObstacle(start, 10, 0, wall);
    expect(stopAtObstacle(stopped, 0, 0, wall)).toEqual(start);
    const around = stopAtObstacle(stopped, 4, 6, wall);
    expect(stopAtObstacle(around, 10, 6, wall)).toEqual({ x: 10, y: 6 });
  });
});

/** Sweep a token center, matching host segment collisions rather than imposing a token-radius clearance. */
export function stopAtObstacle(
  from: { x: number; y: number }, x: number, y: number, segments: ArrayLike<number>,
): { x: number; y: number } {
  const rx = x - from.x, ry = y - from.y;
  const length = Math.hypot(rx, ry);
  if (!length) return { x: from.x, y: from.y };
  let first = Infinity;
  for (let i = 0; i + 3 < segments.length; i += 4) {
    const ax = segments[i], ay = segments[i + 1], bx = segments[i + 2], by = segments[i + 3];
    if (Math.max(from.x, x) < Math.min(ax, bx) || Math.min(from.x, x) > Math.max(ax, bx)
      || Math.max(from.y, y) < Math.min(ay, by) || Math.min(from.y, y) > Math.max(ay, by)) continue;
    const sx = bx - ax, sy = by - ay, qx = ax - from.x, qy = ay - from.y;
    const cross = rx * sy - ry * sx;
    if (cross === 0) {
      if (qx * ry - qy * rx !== 0) continue;
      const a = (qx * rx + qy * ry) / (length * length);
      const b = ((bx - from.x) * rx + (by - from.y) * ry) / (length * length);
      if (Math.max(a, b) >= 0 && Math.min(a, b) <= 1) first = Math.min(first, Math.max(0, Math.min(a, b)));
    } else {
      const t = (qx * sy - qy * sx) / cross;
      const u = (qx * ry - qy * rx) / cross;
      if (t >= 0 && t <= 1 && u >= 0 && u <= 1) first = Math.min(first, t);
    }
  }
  if (!Number.isFinite(first)) return { x, y };
  // Keep the accepted center off the wall: the host also rejects touching an endpoint.
  const t = Math.max(0, first - 0.0001 / length);
  return { x: from.x + rx * t, y: from.y + ry * t };
}

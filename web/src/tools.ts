import { Measurement, Overlay } from './protocol';

export type DrawMode = 'pen' | 'brush' | 'line' | 'circle' | 'rect';
export type MapTool = 'navigate' | 'draw' | 'measure';
export interface DrawingStyle {
  color: string;
  alpha: number;
  texture: string;
  size: number;
  border: boolean;
  emits: boolean;
  above: boolean;
}

/** randomUUID is unavailable on ordinary HTTP LAN origins; getRandomValues is available there. */
export function drawingId(): string {
  return Array.from(crypto.getRandomValues(new Uint32Array(4)), n => n.toString(16).padStart(8, '0')).join('');
}

export function ownsDrawing(shape: Overlay, playerId: string, campaignId: string): boolean {
  return !!playerId && !!campaignId && shape.ownerId === playerId && shape.ownerCampaignId === campaignId;
}

export function drawingBounds(o: Overlay): [number, number, number, number] {
  if (o.type === 'circle') return [o.x - o.radius, o.y - o.radius, o.x + o.radius, o.y + o.radius];
  if (o.type === 'rect') return [o.x, o.y, o.x + o.width, o.y + o.height];
  const xs = o.points.filter((_, i) => i % 2 === 0);
  const ys = o.points.filter((_, i) => i % 2 === 1);
  const half = o.strokeWidth / 2;
  return [Math.min(...xs) - half, Math.min(...ys) - half, Math.max(...xs) + half, Math.max(...ys) + half];
}

export function translateDrawing(o: Overlay, dx: number, dy: number): Overlay {
  return { ...o, x: o.x + dx, y: o.y + dy, points: o.points.map((v, i) => v + (i % 2 ? dy : dx)) };
}

export function resizeDrawing(o: Overlay, x: number, y: number): Overlay {
  const [left, top, right, bottom] = drawingBounds(o);
  const sx = Math.max(0.01, (x - left) / Math.max(0.01, right - left));
  const sy = Math.max(0.01, (y - top) / Math.max(0.01, bottom - top));
  if (o.type === 'circle') return { ...o, radius: Math.max(0.1, Math.hypot(x - o.x, y - o.y) / Math.SQRT2) };
  return { ...o, x: left + (o.x - left) * sx, y: top + (o.y - top) * sy,
    width: o.width * sx, height: o.height * sy,
    points: o.points.map((v, i) => i % 2 ? top + (v - top) * sy : left + (v - left) * sx) };
}

export function distanceToSegment(x: number, y: number, x1: number, y1: number, x2: number, y2: number): number {
  const dx = x2 - x1, dy = y2 - y1;
  const t = Math.max(0, Math.min(1, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy || 1)));
  return Math.hypot(x - x1 - t * dx, y - y1 - t * dy);
}

export function drawingHit(o: Overlay, x: number, y: number, tolerance: number): boolean {
  if (o.type === 'circle') return Math.hypot(x - o.x, y - o.y) <= o.radius + tolerance;
  if (o.type === 'rect') return x >= o.x - tolerance && x <= o.x + o.width + tolerance && y >= o.y - tolerance && y <= o.y + o.height + tolerance;
  if (o.points.length === 2) return Math.hypot(x - o.points[0], y - o.points[1]) <= o.strokeWidth / 2 + tolerance;
  for (let i = 2; i < o.points.length; i += 2) {
    if (distanceToSegment(x, y, o.points[i - 2], o.points[i - 1], o.points[i], o.points[i + 1]) <= o.strokeWidth / 2 + tolerance) return true;
  }
  return false;
}

/** Daggerheart SRD defined ranges on a one-inch grid: Melee 1, Very Close 3, Close 6, Far 12 squares, then Very Far. */
export function daggerheartRange(tiles: number): string {
  if (tiles <= 1) return 'Melee';
  if (tiles <= 3) return 'Very Close';
  if (tiles <= 6) return 'Close';
  if (tiles <= 12) return 'Far';
  return 'Very Far';
}

export function measurementLabel(m: Measurement, ppc: number): string {
  const length = Math.hypot(m.x2 - m.x1, m.y2 - m.y1) / ppc;
  const range = daggerheartRange(Number(length.toFixed(1)));
  if (m.kind === 'rect') return `${(Math.abs(m.x2 - m.x1) / ppc).toFixed(1)} x ${(Math.abs(m.y2 - m.y1) / ppc).toFixed(1)} tiles · ${range}`;
  return `${m.kind === 'radius' ? 'r = ' : ''}${length.toFixed(1)} tiles · ${range}`;
}

export function measurementBounds(m: Measurement): [number, number, number, number] {
  const radius = Math.hypot(m.x2 - m.x1, m.y2 - m.y1);
  if (m.kind === 'radius') return [m.x1 - radius, m.y1 - radius, m.x1 + radius, m.y1 + radius];
  if (m.kind !== 'cone') return [Math.min(m.x1, m.x2), Math.min(m.y1, m.y2), Math.max(m.x1, m.x2), Math.max(m.y1, m.y2)];
  const angle = Math.atan2(m.y2 - m.y1, m.x2 - m.x1);
  const angles = [angle - Math.PI / 6, angle + Math.PI / 6];
  for (const a of [0, Math.PI / 2, Math.PI, -Math.PI / 2]) {
    if (Math.abs(Math.atan2(Math.sin(a - angle), Math.cos(a - angle))) <= Math.PI / 6) angles.push(a);
  }
  const xs = [m.x1, ...angles.map(a => m.x1 + radius * Math.cos(a))];
  const ys = [m.y1, ...angles.map(a => m.y1 + radius * Math.sin(a))];
  return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)];
}

export function measurementHit(m: Measurement, x: number, y: number, tolerance: number): boolean {
  if (m.kind === 'rect') return x >= Math.min(m.x1, m.x2) - tolerance && x <= Math.max(m.x1, m.x2) + tolerance
    && y >= Math.min(m.y1, m.y2) - tolerance && y <= Math.max(m.y1, m.y2) + tolerance;
  const radius = Math.hypot(m.x2 - m.x1, m.y2 - m.y1);
  if (m.kind === 'radius') return Math.hypot(x - m.x1, y - m.y1) <= radius + tolerance;
  if (m.kind === 'cone') {
    const angle = Math.atan2(m.y2 - m.y1, m.x2 - m.x1);
    const delta = Math.atan2(Math.sin(Math.atan2(y - m.y1, x - m.x1) - angle), Math.cos(Math.atan2(y - m.y1, x - m.x1) - angle));
    if (Math.hypot(x - m.x1, y - m.y1) <= radius + tolerance && Math.abs(delta) <= Math.PI / 6) return true;
  }
  return distanceToSegment(x, y, m.x1, m.y1, m.x2, m.y2) <= tolerance
    || Math.hypot(x - m.x2, y - m.y2) <= tolerance * 3;
}

export function measurementPath(ctx: CanvasRenderingContext2D, m: Measurement): void {
  ctx.beginPath();
  const r = Math.hypot(m.x2 - m.x1, m.y2 - m.y1);
  if (m.kind === 'rect') ctx.rect(m.x1, m.y1, m.x2 - m.x1, m.y2 - m.y1);
  else if (m.kind === 'radius') { ctx.arc(m.x1, m.y1, r, 0, Math.PI * 2); ctx.moveTo(m.x1, m.y1); ctx.lineTo(m.x2, m.y2); }
  else if (m.kind === 'cone') {
    const angle = Math.atan2(m.y2 - m.y1, m.x2 - m.x1);
    ctx.moveTo(m.x1, m.y1); ctx.arc(m.x1, m.y1, r, angle - Math.PI / 6, angle + Math.PI / 6); ctx.closePath();
  } else { ctx.moveTo(m.x1, m.y1); ctx.lineTo(m.x2, m.y2); }
}

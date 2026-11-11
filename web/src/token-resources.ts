import type { Track } from './daggerheart/data';

/** Values drawn on a player's own token, matching the DM view's HUD (marked HP/Stress, Armor left, Hope held). */
export interface TokenResources {
  hp: number;
  maxHp: number;
  stress: number;
  maxStress: number;
  armor: number;
  hope: number;
}

const count = (value: number): number => (Number.isFinite(value) ? Math.max(0, Math.floor(value)) : 0);

/** Null when the sheet has no HP capacity yet, like the host's DM-only reader. */
export function tokenResourcesOf(c: { hp: Track; stress: Track; armorTrack: Track; hope: Track }): TokenResources | null {
  const maxHp = count(c.hp.slots);
  if (maxHp === 0) return null;
  const maxStress = count(c.stress.slots);
  return {
    hp: Math.min(count(c.hp.on), maxHp),
    maxHp,
    stress: Math.min(count(c.stress.on), maxStress),
    maxStress,
    armor: Math.max(0, count(c.armorTrack.slots) - count(c.armorTrack.on)),
    hope: count(c.hope.on),
  };
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, r);
}

function bar(ctx: CanvasRenderingContext2D, x: number, y: number, width: number, s: number,
  value: number, max: number, color: string): void {
  const left = x - width / 2;
  const height = 9 * s;
  const rounding = 2 * s;
  ctx.fillStyle = '#151a20';
  roundRect(ctx, left, y, width, height, rounding);
  ctx.fill();
  const filled = max > 0 ? width * value / max : 0;
  if (filled > 0) {
    ctx.fillStyle = color;
    roundRect(ctx, left, y, filled, height, rounding);
    ctx.fill();
  }
  ctx.strokeStyle = 'rgba(255,255,255,0.65)';
  ctx.lineWidth = 0.5 * s;
  roundRect(ctx, left, y, width, height, rounding);
  ctx.stroke();
  const text = `${value}/${max}`;
  ctx.font = `bold ${9 * s}px system-ui, sans-serif`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.strokeStyle = 'rgba(0,0,0,0.85)';
  ctx.lineWidth = 1.5 * s;
  ctx.strokeText(text, x, y + height / 2, width - 2 * s);
  ctx.fillStyle = '#ffffff';
  ctx.fillText(text, x, y + height / 2, width - 2 * s);
}

function counter(ctx: CanvasRenderingContext2D, x: number, y: number, s: number, value: number, hope: boolean): void {
  ctx.save();
  ctx.translate(x, y + 9.5 * s);
  ctx.scale(s, s);
  ctx.beginPath();
  if (hope) {
    for (let i = 0; i < 10; i++) {
      const angle = -Math.PI / 2 + i * Math.PI / 5;
      const radius = i % 2 === 0 ? 11 : 6.5;
      const px = Math.cos(angle) * radius, py = Math.sin(angle) * radius;
      if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
    }
  } else {
    ctx.moveTo(-10, -11);
    ctx.lineTo(10, -11);
    ctx.lineTo(10, 1);
    ctx.quadraticCurveTo(10, 7, 0, 11);
    ctx.quadraticCurveTo(-10, 7, -10, 1);
  }
  ctx.closePath();
  ctx.fillStyle = hope ? '#f5b942' : '#78909c';
  ctx.fill();
  ctx.strokeStyle = '#151a20';
  ctx.lineWidth = 0.7;
  ctx.stroke();
  ctx.font = 'bold 14px system-ui, sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = '#151a20';
  ctx.fillText(String(value), 0, 0, 16);
  ctx.restore();
}

/**
 * Draws the HUD centred on a token. Sizes are screen pixels (the DM renderer's layout), so the caller passes the
 * token's on-screen diameter and a context whose origin is the token centre in screen units.
 */
export function drawTokenResources(ctx: CanvasRenderingContext2D, r: TokenResources, diameter: number): void {
  const s = Math.min(1, diameter / 64);
  const width = Math.min(56, diameter - 48 * s);
  if (width <= 0) return;
  const y = diameter / 2 - 22 * s;
  ctx.save();
  bar(ctx, 0, y, width, s, r.hp, r.maxHp, '#ef5350');
  bar(ctx, 0, y + 10 * s, width, s, r.stress, r.maxStress, '#ab7df0');
  const offset = width / 2 + 12 * s;
  counter(ctx, -offset, y, s, r.armor, false);
  counter(ctx, offset, y, s, r.hope, true);
  ctx.restore();
}

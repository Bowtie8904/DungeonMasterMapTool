/** Port of dmmt.render.WeatherEffects (stateless particles driven by index and clock). */

export type WeatherKind = 'none' | 'rain' | 'thunderstorm' | 'snow' | 'mist' | 'dust' | 'embers';

export interface WeatherStyle {
  particles: number;
  color: string;
  opacity: number;
}

/** Defaults of the Java weather.<type>.* settings (the host cannot override them yet). */
export const WEATHER_STYLES: Record<Exclude<WeatherKind, 'none'>, WeatherStyle> = {
  rain: { particles: 600, color: '#C8D7EB', opacity: 0.42 },
  thunderstorm: { particles: 600, color: '#C8D7EB', opacity: 0.42 },
  snow: { particles: 610, color: '#F5F8FF', opacity: 0.5 },
  mist: { particles: 70, color: '#DBE3ED', opacity: 0.15 },
  dust: { particles: 500, color: '#FFF0CD', opacity: 0.48 },
  embers: { particles: 295, color: '#FF963C', opacity: 0.75 },
};

export const FLASH_DURATION = 0.7;
export const FLASH_OPACITY = 0.25;
export const FLASH_COLOR = '#E8EEFF';

/** Lightning look; replaced by the host's weather.thunderstorm.* settings. */
export const flashStyle = { duration: FLASH_DURATION, opacity: FLASH_OPACITY, brightness: 1, color: FLASH_COLOR };

/** Defaults of the weather.<type>.indoors settings. */
const DEFAULT_INDOORS: Record<string, boolean> = {
  rain: false, thunderstorm: false, snow: false, mist: true, dust: true, embers: true,
};

export interface WeatherConfig {
  type?: string;
  indoors?: boolean;
  particles?: number;
  color?: string;
  opacity?: number;
  flash?: { duration?: number; opacity?: number; brightness?: number; color?: string };
}

/** Whether the weather type is also drawn inside enclosed rooms. */
export function weatherIndoors(cfg: WeatherConfig): boolean {
  return cfg.indoors ?? DEFAULT_INDOORS[(cfg.type ?? '').trim().toLowerCase()] ?? true;
}

/** Applies the host's per-type weather settings and lightning look. */
export function applyWeatherConfig(cfg: WeatherConfig): void {
  const kind = weatherKind(cfg.type);
  if (kind !== 'none') {
    const style = WEATHER_STYLES[kind];
    if (typeof cfg.particles === 'number') style.particles = cfg.particles;
    if (typeof cfg.opacity === 'number') style.opacity = cfg.opacity;
    if (typeof cfg.color === 'string' && cfg.color !== style.color) {
      style.color = cfg.color;
      if (kind === 'mist') mistTextures = null;
    }
  }
  const f = cfg.flash;
  if (f) {
    if (typeof f.duration === 'number') flashStyle.duration = f.duration;
    if (typeof f.opacity === 'number') flashStyle.opacity = f.opacity;
    if (typeof f.brightness === 'number') flashStyle.brightness = f.brightness;
    if (typeof f.color === 'string') flashStyle.color = f.color;
  }
}
export const MIN_INTENSITY = 0.1;
export const MAX_INTENSITY = 1.0;
export const MIN_LIGHTNING_INTERVAL = 2;
export const MAX_LIGHTNING_INTERVAL = 120;
export const DEFAULT_MAX_PARTICLES = 1500;

const REFERENCE_AREA = 1920 * 1080;
const EDGE_MARGIN = 40;

const M64 = (1n << 64n) - 1n;
const C1 = 0x9e3779b97f4a7c15n;
const C2 = 0xbf58476d1ce4e5b9n;
const C3 = 0x94d049bb133111ebn;
const unitCache = new Map<number, number>();

/** Bit-exact port of WeatherEffects.unit(int index, int salt): deterministic value in [0, 1). */
export function unit(index: number, salt: number): number {
  const key = (index | 0) * 4096 + (salt | 0);
  const hit = unitCache.get(key);
  if (hit !== undefined) {
    return hit;
  }
  let z = ((BigInt(index | 0) * C1) ^ (BigInt(salt | 0) * C2)) & M64;
  z = ((z ^ (z >> 30n)) * C2) & M64;
  z = ((z ^ (z >> 27n)) * C3) & M64;
  z ^= z >> 31n;
  const value = Number(z >> 11n) * 2 ** -53;
  if (unitCache.size > 300000) {
    unitCache.clear();
  }
  unitCache.set(key, value);
  return value;
}

/** Wraps value into [0, span). */
export function wrap(value: number, span: number): number {
  const result = value % span;
  return result < 0 ? result + span : result;
}

export function clampIntensity(i: number): number {
  return Math.max(MIN_INTENSITY, Math.min(MAX_INTENSITY, i));
}

export function clampLightningInterval(s: number): number {
  return Math.max(MIN_LIGHTNING_INTERVAL, Math.min(MAX_LIGHTNING_INTERVAL, s));
}

export function weatherKind(type: string | undefined): WeatherKind {
  const k = (type ?? 'none').trim().toLowerCase();
  return k in WEATHER_STYLES ? (k as WeatherKind) : 'none';
}

/** Particle count for the screen size; `maxParticles` is the per-client cap (reduces proportionally). */
export function particleCount(type: WeatherKind, intensity: number, width: number, height: number, maxParticles = Infinity): number {
  if (type === 'none') {
    return 0;
  }
  const full = WEATHER_STYLES[type].particles;
  if (full <= 0) {
    return 0;
  }
  if (type === 'mist') {
    return full;
  }
  const area = Math.max(0.3, Math.min(2.0, (width * height) / REFERENCE_AREA));
  const count = Math.max(1, Math.round(full * clampIntensity(intensity) * area));
  if (count > maxParticles) {
    return Math.max(1, Math.round(maxParticles));
  }
  return count;
}

function flashInCycle(seconds: number, interval: number, cycle: number): number {
  if (cycle < 0) {
    return 0;
  }
  const strike = (0.25 + 0.5 * unit(cycle, 83)) * interval;
  const age = seconds - cycle * interval - strike;
  const duration = flashStyle.duration;
  if (age < 0 || age >= duration) {
    return 0;
  }
  const seed = cycle;
  const pulses = 3 + Math.trunc(unit(seed, 84) * 3);
  let totalWeight = 0;
  for (let p = 0; p < pulses; p++) {
    totalWeight += 0.7 + unit(seed, 90 + p);
  }
  let start = 0;
  let flash = 0;
  for (let p = 0; p < pulses; p++) {
    const segment = (duration * 0.4 * (0.7 + unit(seed, 90 + p))) / totalWeight;
    if (age >= start) {
      const strength = p === 0 ? 1 : 0.35 + 0.5 * unit(seed, 110 + p);
      const progress = (age - start) / (duration - start);
      const brightness = strength * (1 - progress) * (1 - progress);
      flash += (1 - flash) * brightness;
    }
    start += segment;
  }
  return flash;
}

export function lightningFlash(seconds: number, intervalSeconds: number): number {
  if (!Number.isFinite(seconds) || seconds <= 0 || !Number.isFinite(intervalSeconds)) {
    return 0;
  }
  const interval = clampLightningInterval(intervalSeconds);
  const cycle = Math.floor(seconds / interval);
  return Math.max(flashInCycle(seconds, interval, cycle), flashInCycle(seconds, interval, cycle - 1));
}

export function drawLightning(ctx: CanvasRenderingContext2D, width: number, height: number, flash: number): void {
  if (flash <= 0) {
    return;
  }
  ctx.save();
  ctx.globalAlpha = flash * flashStyle.opacity;
  ctx.fillStyle = flashStyle.color;
  ctx.fillRect(0, 0, width, height);
  ctx.restore();
}

let mistTextures: HTMLCanvasElement[] | null = null;

function mistTexture(variant: number): HTMLCanvasElement {
  if (!mistTextures) {
    mistTextures = [];
    for (let v = 0; v < 4; v++) {
      const c = document.createElement('canvas');
      c.width = c.height = 128;
      const g = c.getContext('2d')!;
      const color = WEATHER_STYLES.mist.color;
      for (let k = 0; k < 6; k++) {
        const cx = 64 + (unit(v * 10 + k, 201) - 0.5) * 44;
        const cy = 64 + (unit(v * 10 + k, 202) - 0.5) * 44;
        const r = 30 + unit(v * 10 + k, 203) * 26;
        const grad = g.createRadialGradient(cx, cy, 0, cx, cy, r);
        grad.addColorStop(0, color + 'b0');
        grad.addColorStop(1, color + '00');
        g.fillStyle = grad;
        g.fillRect(0, 0, 128, 128);
      }
      mistTextures.push(c);
    }
  }
  return mistTextures[variant % 4];
}

/** Draws weather in screen space; `seconds` is the (seed shifted) host clock in seconds. */
export function drawWeather(
  ctx: CanvasRenderingContext2D,
  typeName: string,
  intensity: number,
  width: number,
  height: number,
  seconds: number,
  maxParticles: number,
): void {
  const type = weatherKind(typeName);
  if (type === 'none' || width <= 0 || height <= 0) {
    return;
  }
  const level = clampIntensity(intensity);
  const count = particleCount(type, level, width, height, maxParticles);
  if (count <= 0) {
    return;
  }
  const scale = Math.max(0.6, Math.min(2.0, Math.min(width, height) / 900));
  const style = WEATHER_STYLES[type];
  ctx.save();
  switch (type) {
    case 'rain':
    case 'thunderstorm':
      drawRain(ctx, style, count, width, height, seconds, scale);
      break;
    case 'snow':
      drawSnow(ctx, style, count, width, height, seconds, scale);
      break;
    case 'dust':
      drawDust(ctx, style, count, width, height, seconds, scale);
      break;
    case 'embers':
      drawEmbers(ctx, style, count, width, height, seconds, scale);
      break;
    case 'mist':
      drawMist(ctx, style, level, width, height, seconds, scale);
      break;
  }
  ctx.restore();
}

function drawRain(ctx: CanvasRenderingContext2D, s: WeatherStyle, count: number, w: number, h: number, t: number, scale: number): void {
  ctx.strokeStyle = s.color;
  ctx.lineWidth = Math.max(1, scale);
  ctx.globalAlpha = s.opacity;
  const spanX = w + 2 * EDGE_MARGIN;
  const spanY = h + 2 * EDGE_MARGIN;
  ctx.beginPath();
  for (let i = 0; i < count; i++) {
    const speed = (650 + 350 * unit(i, 2)) * scale;
    const length = (9 + 12 * unit(i, 3)) * scale;
    const vx = speed * 0.22;
    const x = wrap(unit(i, 1) * spanX + vx * t, spanX) - EDGE_MARGIN;
    const y = wrap(unit(i, 4) * spanY + speed * t, spanY) - EDGE_MARGIN;
    const norm = Math.hypot(vx, speed);
    ctx.moveTo(x, y);
    ctx.lineTo(x - (length * vx) / norm, y - (length * speed) / norm);
  }
  ctx.stroke();
}

function dot(ctx: CanvasRenderingContext2D, x: number, y: number, r: number): void {
  ctx.beginPath();
  ctx.arc(x, y, r, 0, Math.PI * 2);
  ctx.fill();
}

function drawSnow(ctx: CanvasRenderingContext2D, s: WeatherStyle, count: number, w: number, h: number, t: number, scale: number): void {
  ctx.fillStyle = s.color;
  ctx.globalAlpha = s.opacity;
  const spanX = w + 2 * EDGE_MARGIN;
  const spanY = h + 2 * EDGE_MARGIN;
  for (let i = 0; i < count; i++) {
    const radius = (1.1 + 1.7 * unit(i, 3)) * scale;
    const fall = (22 + 34 * unit(i, 2)) * scale;
    const sway = Math.sin(t * (0.4 + unit(i, 5)) + unit(i, 6) * Math.PI * 2) * 16 * scale;
    const x = wrap(unit(i, 1) * spanX, spanX) - EDGE_MARGIN + sway;
    const y = wrap(unit(i, 4) * spanY + fall * t, spanY) - EDGE_MARGIN;
    dot(ctx, x, y, radius);
  }
}

function drawDust(ctx: CanvasRenderingContext2D, s: WeatherStyle, count: number, w: number, h: number, t: number, scale: number): void {
  ctx.fillStyle = s.color;
  for (let i = 0; i < count; i++) {
    const radius = (0.8 + 1.2 * unit(i, 3)) * scale;
    const vx = (5 + 8 * unit(i, 2)) * scale;
    const vy = -(2 + 5 * unit(i, 7)) * scale;
    const x = wrap(unit(i, 1) * w + vx * t, w);
    const y = wrap(unit(i, 4) * h + vy * t, h);
    const twinkle = 0.5 + 0.5 * Math.sin(t * (0.3 + unit(i, 5)) + unit(i, 6) * Math.PI * 2);
    ctx.globalAlpha = s.opacity * (0.125 + 0.875 * twinkle);
    dot(ctx, x, y, radius);
  }
}

function drawEmbers(ctx: CanvasRenderingContext2D, s: WeatherStyle, count: number, w: number, h: number, t: number, scale: number): void {
  ctx.fillStyle = s.color;
  const spanY = h + 2 * EDGE_MARGIN;
  for (let i = 0; i < count; i++) {
    const radius = (1 + 1.5 * unit(i, 3)) * scale;
    const rise = (28 + 42 * unit(i, 2)) * scale;
    const sway = Math.sin(t * (0.6 + unit(i, 5)) + unit(i, 6) * Math.PI * 2) * 20 * scale;
    const x = wrap(unit(i, 1) * w + sway, w);
    const y = spanY - wrap(unit(i, 4) * spanY + rise * t, spanY) - EDGE_MARGIN;
    const flicker = 0.5 + 0.5 * Math.sin(t * (2 + 3 * unit(i, 7)) + unit(i, 6) * 9);
    ctx.globalAlpha = s.opacity * (0.2 + 0.8 * flicker);
    dot(ctx, x, y, radius);
  }
}

export function mistBank(index: number, w: number, h: number, t: number, scale: number) {
  const drift = (5 + 9 * unit(index, 2)) * scale;
  const x = wrap((unit(0, 1) + index * 0.754877666) * w + drift * t, w);
  const y = wrap(
    (unit(0, 4) + index * 0.569840291) * h + (2 + 4 * unit(index, 7)) * scale * t + Math.sin(t * 0.05 + unit(index, 6) * Math.PI * 2) * 0.04 * h,
    h,
  );
  const breath = 1 + 0.06 * Math.sin(t * 0.07 + unit(index, 8) * Math.PI * 2);
  return { x, y, radiusX: (0.18 + 0.16 * unit(index, 3)) * w * breath, radiusY: ((0.18 + 0.16 * unit(index, 5)) * h) / breath };
}

function drawMist(ctx: CanvasRenderingContext2D, s: WeatherStyle, level: number, w: number, h: number, t: number, scale: number): void {
  const alpha = s.opacity * Math.sqrt(level);
  ctx.globalAlpha = level * level;
  ctx.fillStyle = '#2b3036';
  ctx.fillRect(0, 0, w, h);
  ctx.globalAlpha = alpha;
  for (let i = 0; i < s.particles; i++) {
    const b = mistBank(i, w, h, t, scale);
    for (let dy = -1; dy <= 1; dy++) {
      const top = b.y + dy * h - b.radiusY;
      if (top >= h || top + b.radiusY * 2 <= 0) continue;
      for (let dx = -1; dx <= 1; dx++) {
        const left = b.x + dx * w - b.radiusX;
        if (left >= w || left + b.radiusX * 2 <= 0) continue;
        ctx.drawImage(mistTexture(i), left, top, b.radiusX * 2, b.radiusY * 2);
      }
    }
  }
}

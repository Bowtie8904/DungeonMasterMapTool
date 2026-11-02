/** Java's String.hashCode(), so the flicker phase matches the desktop renderer. */
export function javaHash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) {
    h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
  }
  return h;
}

/** Port of dmmt.lighting.LightFlicker.amount: 0 = steady, 1 = deepest dip. */
export function flickerAmount(id: string, strength: number, speed: number, nowMillis: number): number {
  if (strength <= 0) return 0;
  const phase = (javaHash(id) & 0xffff) / 1000;
  const t = (nowMillis / 1000) * Math.max(0.05, speed);
  const n = 0.5 * Math.sin(t * 6.3 + phase) + 0.3 * Math.sin(t * 13.7 + phase * 1.7) + 0.2 * Math.sin(t * 23.1 + phase * 2.3);
  return Math.min(1, strength * (0.5 + 0.5 * n));
}

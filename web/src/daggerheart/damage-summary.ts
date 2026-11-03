// Format of the automated damage report posted to the chat. It is plain chat text (readable on any client);
// clients that know the format render it as a distinct report card instead of a message a player typed.
export interface DamageReport {
  name: string;
  input: number;
  damage: number;
  type: string;
  /** "Major", "Major → Minor" or "Negated". */
  severity: string;
  hp: number;
  lines: string[];
}

const HEAD = 'DAMAGE TAKEN';
const STATS = /^(\d+) (\w+) damage(?: → (\d+))? · (.+) · (\d+) HP marked$/;

export function formatDamageReport(r: DamageReport): string {
  const out = [HEAD, r.name,
    `${r.input} ${r.type} damage${r.damage !== r.input ? ` → ${r.damage}` : ''} · ${r.severity} · ${r.hp} HP marked`];
  let used = out.join('\n').length;
  for (const line of r.lines) {
    if (used + line.length + 3 > 990) { out.push('• ...'); break; }
    out.push(`• ${line}`);
    used += line.length + 3;
  }
  return out.join('\n');
}

export function parseDamageReport(text: string): DamageReport | null {
  const lines = text.split('\n');
  if (lines[0] !== HEAD || lines.length < 3) return null;
  const stats = STATS.exec(lines[2]);
  if (!stats) return null;
  const input = Number(stats[1]);
  return {
    name: lines[1],
    input,
    damage: stats[3] === undefined ? input : Number(stats[3]),
    type: stats[2],
    severity: stats[4],
    hp: Number(stats[5]),
    lines: lines.slice(3).map((l) => l.replace(/^• ?/, '')),
  };
}

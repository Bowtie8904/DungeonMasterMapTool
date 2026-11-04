// Format of the automated "action used" report (consuming an item, using a feature) posted to the chat. It is plain
// chat text (readable on any client); clients that know the format render it as a distinct card.
import type { CharacterSheet } from './data';
import { featureRollOrigin, sourceActionLabel } from './engine';

export function actionReportTitle(c: CharacterSheet, key: string, source: string, action: string): string {
  const feature = [...c.features, ...c.heritage].find(f => key === `feat:${f.name}`);
  return sourceActionLabel(feature ? featureRollOrigin(c, feature) : undefined, source, action);
}

export interface ActionReport {
  title: string;
  character: string;
  lines: string[];
}

const HEAD = 'ACTION USED';

export function formatActionReport(r: ActionReport): string {
  const out = [HEAD, r.title, r.character];
  let used = out.join('\n').length;
  for (const line of r.lines) {
    if (used + line.length + 3 > 990) { out.push('• ...'); break; }
    out.push(`• ${line}`);
    used += line.length + 3;
  }
  return out.join('\n');
}

export function parseActionReport(text: string): ActionReport | null {
  const lines = text.split('\n');
  if (lines[0] !== HEAD || lines.length < 3) return null;
  return { title: lines[1], character: lines[2], lines: lines.slice(3).map((l) => l.replace(/^• ?/, '')) };
}

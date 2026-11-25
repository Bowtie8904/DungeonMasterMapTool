// Format of the automated "action used" report (consuming an item, using a feature) posted to the chat. It is plain
// chat text (readable on any client); clients that know the format render it as a distinct card.
import type { CharacterSheet } from './data';
import { actionTextVars, featureRollOrigin, sourceActionLabel } from './engine';
import type { ActionDef } from './engine';
import { formatText } from './texts';
import type { ActionTexts } from './texts';

export function actionReportTitle(c: CharacterSheet, key: string, source: string, action: string, texts?: ActionTexts, def?: ActionDef): string {
  const feature = [...c.features, ...c.heritage].find(f => key === `feat:${f.name}`);
  const origin = feature ? featureRollOrigin(c, feature) : undefined;
  return texts?.reportTitle
    ? formatText(texts.reportTitle, actionTextVars({ actor: c.name, source, origin, action: def ?? { label: action } }))
    : sourceActionLabel(origin, source, action);
}

/** Title for an action-used report built at one of the many action sites: `texts.reportTitle` or the given default. */
export function configuredReportTitle(c: CharacterSheet, key: string, source: string, action: ActionDef, fallback: string, featureTexts?: ActionTexts): string {
  const template = action.texts?.reportTitle ?? featureTexts?.reportTitle;
  if (!template) return fallback;
  const feature = [...c.features, ...c.heritage].find(f => key === `feat:${f.name}`);
  return formatText(template, actionTextVars({ actor: c.name, source, origin: feature ? featureRollOrigin(c, feature) : undefined, action }));
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

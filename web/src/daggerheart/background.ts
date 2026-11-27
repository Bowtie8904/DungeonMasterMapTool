import { CharacterSheet, COMMON_BACKGROUND_FIELDS, multiclassesOf } from './data';
import type { CompendiumEntry } from './compendium';

export function backgroundFieldKey(label: string): string {
  const key = label.trim().toLowerCase().replace(/[\s_-]+/g, ' ');
  switch (key) {
    case 'clothes': return 'attire';
    case 'eyes like': return 'eyes';
    case 'body': case 'body thats': case "body that's": return 'physique';
    case 'skin': case 'color': case 'the color of': case 'skin/fur': return 'skin / fur';
    case 'attitude like': case 'attittude': return 'attitude';
    default: return key;
  }
}

export function backgroundFields(classes: CompendiumEntry[], classNames: string[]): string[] {
  const fields = [...COMMON_BACKGROUND_FIELDS];
  const keys = new Set(fields.map(backgroundFieldKey));
  for (const name of classNames) {
    const entry = classes.find(e => e.kind === 'class' && e.name === name);
    const configured = entry?.backgroundFields;
    if (configured === undefined) continue;
    if (!Array.isArray(configured) || configured.some(field => typeof field !== 'string' || !field.trim()))
      throw new Error(`Class "${name}": backgroundFields must be a list of non-empty text labels.`);
    for (const field of configured) {
      const key = backgroundFieldKey(field);
      if (keys.has(key)) continue;
      fields.push(field.trim());
      keys.add(key);
    }
  }
  return fields;
}

export function characterBackgroundFields(classes: CompendiumEntry[], c: CharacterSheet): string[] {
  return backgroundFields(classes, [c.className, ...multiclassesOf(c).map(m => m.className)]);
}

export function additionalBackgroundFields(classes: CompendiumEntry[], c: CharacterSheet, classNames: string[]): string[] {
  const existing = new Set(characterBackgroundFields(classes, c).map(backgroundFieldKey));
  return backgroundFields(classes, classNames).filter(field => !existing.has(backgroundFieldKey(field)));
}

export function backgroundValue(values: Record<string, string>, field: string): string {
  const key = backgroundFieldKey(field);
  return Object.entries(values).find(([label, value]) => backgroundFieldKey(label) === key && value.trim())?.[1] ?? '';
}

/** Keep obsolete field answers as prose rather than import-only sheet rows. */
export function syncBackgroundDetails(classes: CompendiumEntry[], c: CharacterSheet, answers: Record<string, string> = {}): void {
  const fields = characterBackgroundFields(classes, c);
  const description = Object.fromEntries(fields.map(field => [field, backgroundValue(answers, field) || backgroundValue(c.description, field)]));
  for (const [field, value] of Object.entries(answers)) {
    const label = fields.find(label => backgroundFieldKey(label) === backgroundFieldKey(field));
    if (label !== undefined) description[label] = value;
  }
  const retained: string[] = [];
  for (const [label, value] of Object.entries(c.description)) {
    if (!value.trim() || ['name', 'pronouns'].includes(backgroundFieldKey(label))) continue;
    const field = fields.find(field => backgroundFieldKey(field) === backgroundFieldKey(label));
    if (field === undefined || (description[field] !== value && !Object.keys(answers).some(key => backgroundFieldKey(key) === backgroundFieldKey(label))))
      retained.push(`${label}: ${value}`);
  }
  if (retained.length) c.otherDetails = [c.otherDetails, ...retained].filter(Boolean).join('\n');
  description.Name = c.name;
  description.Pronouns = c.description.Pronouns?.trim() ? c.description.Pronouns : c.pronouns;
  c.description = description;
}

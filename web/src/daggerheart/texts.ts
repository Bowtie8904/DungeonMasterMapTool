// Configurable automation texts. Catalog entries may carry an optional `texts` object; every field replaces only the
// displayed wording at one site. Costs are never part of a template: the app keeps appending them itself.

export interface ActionTexts {
  /** Sheet button text. Cost is appended automatically as " (<cost>)". */
  button?: string;
  /** Tooltip prefix when the button is disabled; the specific blocker reason is still shown after it. */
  blocked?: string;
  /** Roll-card / chat button text. Cost and actor suffixes are appended automatically. */
  chatButton?: string;
  /** Title of the roll sent by this action (roll dialog heading and chat roll label). */
  roll?: string;
  /** "ACTION USED" card title. */
  reportTitle?: string;
  /** "ACTION USED" lines replacing the generated effect lines. "spent <cost>" stays first; "{effects}" inserts the generated lines. */
  report?: string[];
  /** Roll-dialog option row text. Value/dice/cost/"per token" notes are appended automatically. */
  option?: string;
  /** Extra non-cost note in the option row, replacing the generated effect wording. */
  optionNote?: string;
  /** Label of the applied contribution in the chat roll breakdown. */
  applied?: string;
}

export type TextVars = Record<string, string | number | undefined>;

export const TEXT_FIELDS: ReadonlyArray<keyof ActionTexts> =
  ['button', 'blocked', 'chatButton', 'roll', 'reportTitle', 'report', 'option', 'optionNote', 'applied'];

export const TEXT_PLACEHOLDERS: readonly string[] = ['actor', 'target', 'source', 'origin', 'label', 'amount', 'dice', 'result',
  'trait', 'traitValue', 'effect', 'item', 'qty', 'uses', 'usesMax', 'effects'];

const TOKEN = /\{\{|\}\}|\{([A-Za-z_][A-Za-z0-9_]*)\}/g;

/** Replaces `{name}` placeholders. `{{` and `}}` are literal braces; unknown or undefined placeholders stay literal. */
export function formatText(template: string, vars: TextVars = {}): string {
  return template.replace(TOKEN, (token, name?: string) => {
    if (token === '{{') return '{';
    if (token === '}}') return '}';
    const value = Object.prototype.hasOwnProperty.call(vars, name!) ? vars[name!] : undefined;
    return value === undefined ? token : String(value);
  });
}

/** Names of all placeholders used by a template, in order of appearance. */
export function templatePlaceholders(template: string): string[] {
  const names: string[] = [];
  for (const m of template.matchAll(TOKEN)) if (m[1]) names.push(m[1]);
  return names;
}

/** Diagnostics for a texts object: unknown placeholders, wrong types and unknown fields. */
export function validateTexts(texts: unknown, where: string): string[] {
  if (texts === undefined) return [];
  if (!texts || typeof texts !== 'object' || Array.isArray(texts)) return [`${where}: texts must be an object`];
  const problems: string[] = [];
  for (const [field, value] of Object.entries(texts)) {
    if (!(TEXT_FIELDS as readonly string[]).includes(field)) { problems.push(`${where}: unknown texts field "${field}"`); continue; }
    const templates = field === 'report' ? Array.isArray(value) && value.every(v => typeof v === 'string') ? value as string[] : null
      : typeof value === 'string' ? [value] : null;
    if (!templates) { problems.push(`${where}: texts.${field} must be ${field === 'report' ? 'an array of strings' : 'a string'}`); continue; }
    for (const name of templates.flatMap(templatePlaceholders)) {
      if (!TEXT_PLACEHOLDERS.includes(name) || name === 'effects' && field !== 'report') {
        problems.push(`${where}: texts.${field} uses unknown placeholder {${name}}`);
      }
    }
  }
  return problems;
}

/**
 * Applies `texts.report` to generated action lines. "spent <cost>" stays first; empty results are dropped.
 * Returns the generated lines unchanged without a configured report.
 */
export function applyReportLines(texts: ActionTexts | undefined, generated: string[], vars: TextVars, costLine?: string): string[] {
  if (!texts?.report) return generated;
  const keepCost = !!costLine && generated[0] === costLine;
  const rest = keepCost ? generated.slice(1) : generated;
  const lines = texts.report.flatMap(line => line.trim() === '{effects}' ? rest : [formatText(line, vars)]).filter(line => line.trim());
  return keepCost ? [costLine!, ...lines] : lines;
}

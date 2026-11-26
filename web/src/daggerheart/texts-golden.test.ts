import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { mockCharacter } from './data';
import { actionBlocker, actionButtonText, actionCost, costText, rollOptionLabel, runActionDetailed, sourceActionLabel } from './engine';
import type { ActionDef, Automation, AvailableRollOption } from './engine';
import { formatActionReport } from './action-summary';

// Characterization of automation-generated texts, including configured sheet button labels, for the shipped catalogs.
const directory = new URL('../../../dmmap-daggerheart/', import.meta.url);
const files = readdirSync(directory).filter(name => name.endsWith('.json')).sort();

interface Owner { file: string; name: string; automation: Automation }

function collect(value: unknown, file: string, owners: Owner[], name = ''): void {
  if (Array.isArray(value)) { for (const item of value) collect(item, file, owners, name); return; }
  if (!value || typeof value !== 'object') return;
  const record = value as Record<string, unknown>;
  const own = typeof record.name === 'string' ? record.name : name;
  if (record.automation && typeof record.automation === 'object' && typeof record.name === 'string') {
    owners.push({ file, name: own, automation: record.automation as Automation });
  }
  for (const [key, child] of Object.entries(record)) if (key !== 'automation') collect(child, file, owners, own);
}

const fixed = () => 4;

function freshCharacter() {
  const c = mockCharacter();
  c.hope.on = c.hope.slots;
  c.stress.on = 0;
  return c;
}

function actionTexts(owner: Owner, action: ActionDef): Record<string, unknown> {
  const c = freshCharacter();
  const cost = costText(actionCost(c, action), c);
  const key = `feat:${owner.name}`;
  const blocker = actionBlocker(c, key, owner.automation, action);
  let lines: string[] | null | string;
  try { lines = runActionDetailed(c, key, owner.automation, structuredClone(action), action.label, fixed); }
  catch (error) { lines = `error: ${(error as Error).message}`; }
  const report = Array.isArray(lines) ? formatActionReport({ title: sourceActionLabel(undefined, owner.name, action.label), character: c.name, lines }) : null;
  return { button: actionButtonText(action.texts?.button, action.label, { actor: c.name, source: owner.name }, cost), blocker, lines, report };
}

describe('golden automation texts', () => {
  it('keeps generated texts unchanged for the shipped catalogs', async () => {
    const result: Record<string, unknown> = {};
    for (const file of files) {
      const owners: Owner[] = [];
      collect(JSON.parse(readFileSync(new URL(file, directory), 'utf8')), file, owners);
      for (const owner of owners) {
        const a = owner.automation;
        const entry: Record<string, unknown> = {};
        for (const action of [...(a.actions ?? []), ...(a.conditionalActions ?? [])]) entry[`action:${action.id}`] = actionTexts(owner, action);
        for (const option of a.rollOptions ?? []) {
          const row = { source: owner.name, origin: '', option } as unknown as AvailableRollOption;
          entry[`option:${option.id}`] = rollOptionLabel(row);
        }
        for (const trigger of a.rollTriggers ?? []) {
          entry[`trigger:${trigger.id}`] = { button: trigger.label, cost: costText(trigger.cost), choices: (trigger.choices ?? []).map(choice => choice.label) };
        }
        for (const mitigation of a.mitigation ?? []) entry[`mitigation:${mitigation.id}`] = mitigation.label;
        if (Object.keys(entry).length) result[`${file}/${owner.name}`] = entry;
      }
    }
    expect(Object.keys(result).length).toBeGreaterThan(100);
    await expect(JSON.stringify(result, null, 1)).toMatchFileSnapshot('__snapshots__/texts-golden.json');
  });
});

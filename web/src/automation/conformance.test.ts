import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Context, Json, checkJson, evaluate, renderTemplate } from './values';
import { StoredRoll, deriveRoll, resolveRoll, rollDice } from './roll';

const fixtures = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'src', 'test', 'resources', 'dmmt', 'automation');
interface ExpressionCase { name: string; expression: Json; context: Context; expected?: Json; error?: boolean }
interface TemplateCase { name: string; template: string; context: Context; expected?: string; error?: boolean }
const values: { expressions: ExpressionCase[]; templates: TemplateCase[] } = JSON.parse(readFileSync(resolve(fixtures, 'values.json'), 'utf8'));
const rolls: { roll: StoredRoll; total: number; eligible: string[]; derivations: Array<{ keep: string[]; expected?: number; error?: boolean }> } =
  JSON.parse(readFileSync(resolve(fixtures, 'rolls.json'), 'utf8'));

describe('host/browser expression and text conformance', () => {
  for (const example of values.expressions) it(example.name, () => {
    const run = () => evaluate(example.expression, example.context);
    if (example.error) expect(run).toThrow();
    else expect(run()).toEqual(example.expected);
  });
  for (const example of values.templates) it(example.name, () => {
    const run = () => renderTemplate(example.template, example.context);
    if (example.error) expect(run).toThrow();
    else expect(run()).toBe(example.expected);
  });
  it('rejects invalid numbers, reserved properties and excessive depth', () => {
    expect(() => checkJson({ value: Infinity })).toThrow();
    expect(() => checkJson(JSON.parse('{"__proto__":{}}'))).toThrow();
    let node: Json = 1;
    for (let i = 0; i < 40; i++) node = { negate: node };
    expect(() => evaluate(node, {})).toThrow();
  });
  it('copies values instead of exposing host snapshots through a read', () => {
    const context: Context = { state: { list: [1, 2] } };
    const value = evaluate({ read: 'state.list' }, context);
    expect(Array.isArray(value)).toBe(true);
    if (Array.isArray(value)) value.push(3);
    expect(context).toEqual({ state: { list: [1, 2] } });
  });
});

describe('host/browser structured roll conformance', () => {
  it('uses pool and die identities rather than labels or expressions', () => {
    expect(resolveRoll(rolls.roll).total).toBe(46);
    expect(rollDice(rolls.roll, 'weaponBase').map(die => die.id)).toEqual(rolls.eligible);
    const original = structuredClone(rolls.roll);
    for (const example of rolls.derivations) {
      const run = () => deriveRoll(rolls.roll, rolls.eligible, example.keep);
      if (example.error) expect(run).toThrow();
      else expect(run().total).toBe(example.expected);
    }
    expect(rolls.roll).toEqual(original);
  });
  it('renaming every row never changes the damage', () => {
    const renamed = structuredClone(rolls.roll);
    renamed.contributions.forEach(part => { part.label = 'Different name'; });
    expect(deriveRoll(renamed, rolls.eligible, rolls.derivations[0].keep).total).toBe(40);
  });
  it('validates totals, dropped dice, identities and dependency order', () => {
    expect(() => resolveRoll({ ...rolls.roll, total: 999 })).toThrow();
    const bad = structuredClone(rolls.roll);
    const part = bad.contributions[3];
    if (part.kind === 'scale') part.of = [part.id];
    expect(() => resolveRoll(bad)).toThrow();
    expect(() => deriveRoll(rolls.roll, ['a3'], [])).toThrow();
    expect(() => deriveRoll(rolls.roll, ['a1', 'a1'], ['a1'])).toThrow();
  });
  it('retains independently rolled bonuses and supports negative bonus dice', () => {
    const roll = structuredClone(rolls.roll);
    roll.contributions.push({ id: 'penalty', kind: 'dice', role: 'bonus', sourceId: 'other', label: 'Penalty', sign: -1,
      dice: [{ id: 'penalty1', sides: 6, face: 5, kept: true, origin: 'stored' }] });
    expect(deriveRoll(roll, rolls.eligible, rolls.derivations[0].keep).total).toBe(35);
  });
});

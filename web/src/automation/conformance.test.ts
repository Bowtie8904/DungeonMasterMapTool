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
  it('preserves flat damage when a scaled dice contribution resolves to zero', () => {
    const roll = resolveRoll({ id: 'zero', contributions: [
      { id: 'scaled', kind: 'dice', role: 'damage', sourceId: 'feature', label: 'Scaled', dice: [] },
      { id: 'flat', kind: 'flat', role: 'damage', sourceId: 'feature', label: 'Bonus', value: 6 },
    ] });
    expect(roll.total).toBe(6);
  });
  it('scales the whole earlier roll when a scale names all contributions', () => {
    const roll = resolveRoll({ id: 'all', contributions: [
      { id: 'bonus', kind: 'flat', role: 'bonus', sourceId: 'weapon', label: 'Flat', value: 4 },
      { id: 'half', kind: 'scale', role: 'scale', sourceId: 'other', label: 'Half', of: ['bonus'], factor: 2 },
      { id: 'extra', kind: 'flat', role: 'bonus', sourceId: 'weapon', label: 'Extra', value: 1 },
      { id: 'triple', kind: 'scale', role: 'multiplier', sourceId: 'other', label: 'Triple', of: 'all', factor: 3 },
    ] });
    expect(roll.total).toBe((4 + 4 + 1) * 3);
    expect(roll.contributions[3]).toMatchObject({ of: 'all' });
    expect(resolveRoll(roll).total).toBe(27);
  });
  it('preserves generic roll metadata through validation and derivation', () => {
    const roll = { ...structuredClone(rolls.roll), metadata: { kind: 'reaction', trait: 'Agility', difficulty: 12 } };
    expect(resolveRoll(roll).metadata).toEqual(roll.metadata);
    expect(deriveRoll(roll, rolls.eligible, rolls.derivations[0].keep).metadata).toEqual(roll.metadata);
    expect(() => resolveRoll({ ...roll, metadata: [] })).toThrow();
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
  it('accepts host-edited dice with bounded history and rejects forged history', () => {
    const roll = structuredClone(rolls.roll);
    roll.contributions.push({ id: 'smite', kind: 'dice', role: 'damage', sourceId: 'features:smite', label: 'Smite', editable: false,
      dice: [{ id: 'smite1', sides: 6, face: 6, kept: true, origin: 'rerolled', previous: [2], editedBy: ['features:lucky'] },
        { id: 'smite1.x1', sides: 6, face: 3, kept: true, origin: 'exploded', editedBy: ['features:brutal'] }] });
    const resolved = resolveRoll(roll, false);
    expect(resolved.total).toBe(46 + 9);
    const smite = resolved.contributions.at(-1);
    expect(smite?.kind === 'dice' && smite.dice[0].previous).toEqual([2]);
    const forged = structuredClone(roll);
    const part = forged.contributions.at(-1);
    if (part?.kind === 'dice') part.dice[0].previous = [7];
    expect(() => resolveRoll(forged, false)).toThrow();
    const unknown = structuredClone(roll) as unknown as { contributions: Array<{ dice?: Array<{ origin: string }> }> };
    const dice = unknown.contributions.at(-1)?.dice;
    if (dice) dice[0].origin = 'wished';
    expect(() => resolveRoll(unknown, false)).toThrow();
  });
});

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { derivedStats, type Automation } from './engine';
import { mockCharacter, type CharacterSheet } from './data';
import { applyDamage, availableMitigations, computeDamage, severityOf, type DamageInput } from './damage';
import { formatDamageReport, parseDamageReport } from './damage-summary';

function rules(category: string, name: string): Automation {
  const file: { entries: Array<{ name: string; automation?: Automation; feature?: { name: string }; features?: Array<{ name: string; automation?: Automation }> }> } =
    JSON.parse(readFileSync(new URL(`../../../dmmap-daggerheart/${category}.json`, import.meta.url), 'utf8'));
  type Source = { name: string; automation?: Automation; feature?: { name: string } };
  const source = file.entries.flatMap((e): Source[] => [e, ...(e.features ?? [])]).find((e) => e.name === name || e.feature?.name === name);
  if (!source?.automation) throw new Error(`Missing automation: ${category}/${name}`);
  return structuredClone(source.automation);
}
const fixed = (...faces: number[]) => { let i = 0; return () => faces[i++ % faces.length]; };

function setup(mutate?: (c: CharacterSheet) => void) {
  const c = mockCharacter();
  c.heritage = []; c.features = []; c.loadout = []; c.inventory = []; c.activeWeapons = [];
  if (c.activeArmor) c.activeArmor = { ...c.activeArmor, automation: {} };
  c.hp.on = 0; c.stress.on = 0; c.hope.on = 3; c.armorTrack.on = 0; c.usage = {};
  mutate?.(c);
  const d = derivedStats(c);
  return { c, major: d.major, severe: d.severe };
}
const run = (c: CharacterSheet, input: Partial<DamageInput>, roll = fixed(1)) =>
  computeDamage(c, availableMitigations(c), { damage: 0, type: 'physical', armor: false, selected: new Set(), ...input }, roll);
const feat = (name: string, category = 'ancestries') => ({ source: 'test', name, text: '', automation: rules(category, name) });

describe('damage calculation', () => {
  it('uses catalog Spectral Form resistance only with its effect and only against physical damage', () => {
    const { c, severe } = setup(x => x.features.push(feat('Spectral Form', 'classes')));
    const mitigation = c.features[0].automation!.mitigation![0];
    expect(mitigation.requiresEffect).toBe('Spectral Form');
    const input = { damage: severe, selected: new Set([`feat:Spectral Form/${mitigation.id}`]) };
    expect(run(c, input).damage).toBe(severe);
    expect(availableMitigations(c)).toHaveLength(0);
    c.effects.push({ id: 'spectral', name: 'Spectral Form', kind: 'buff', spectralHp: c.hp.on, phaseMovement: true });
    expect(run(c, input).damage).toBe(Math.floor(severe / 2));
    expect(run(c, { ...input, type: 'magic' }).damage).toBe(severe);
    c.effects = [];
    expect(run(c, input).damage).toBe(severe);
  });
  it('round-trips the chat damage report, including reduced and negated damage', () => {
    const report = { name: 'Ari · the Bold', input: 14, damage: 7, type: 'magic', severity: 'Negated', hp: 0, lines: ['Used Rune Ward', 'Paid 1 Hope'] };
    expect(parseDamageReport(formatDamageReport(report))).toEqual(report);
  });
  it('maps damage onto thresholds including the massive-damage rule', () => {
    expect(severityOf(0, 5, 10)).toBe(0);
    expect(severityOf(4, 5, 10)).toBe(1);
    expect(severityOf(5, 5, 10)).toBe(2);
    expect(severityOf(10, 5, 10)).toBe(3);
    expect(severityOf(20, 5, 10)).toBe(4);
    const { c, severe } = setup();
    expect(run(c, { damage: severe * 2 }).hp).toBe(4);
  });

  it('an Armor Slot lowers Major damage to 1 HP and is marked on apply', () => {
    const { c, major } = setup();
    expect(run(c, { damage: major }).hp).toBe(2);
    const r = run(c, { damage: major, armor: true });
    expect(r.hp).toBe(1);
    expect(r.armorMarked).toBe(1);
    const text = applyDamage(c, r, 'physical')!;
    const report = parseDamageReport(text);
    expect(report).toMatchObject({ name: c.name, input: major, damage: major, type: 'physical', severity: 'Major → Minor', hp: 1 });
    expect(report!.lines).toEqual(['Used 1 Armor Slot']);
    expect(parseDamageReport('hello there')).toBeNull();
    expect(c.hp.on).toBe(1);
    expect(c.armorTrack.on).toBe(1);
  });

  it('applies passive armor features: Fortified, Physical and Painful', () => {
    const fortified = setup((c) => { c.activeArmor!.automation = rules('armor', 'Fortified'); });
    expect(run(fortified.c, { damage: fortified.severe, armor: true }).hp).toBe(1);
    const physical = setup((c) => { c.activeArmor!.automation = rules('armor', 'Physical'); });
    const blocked = run(physical.c, { damage: physical.major, type: 'magic', armor: true });
    expect(blocked.armorBlocked).toContain('Physical');
    expect(blocked.hp).toBe(2);
    expect(run(physical.c, { damage: physical.major, type: 'physical', armor: true }).hp).toBe(1);
    const painful = setup((c) => { c.activeArmor!.automation = rules('armor', 'Painful'); });
    const r = run(painful.c, { damage: painful.major, armor: true });
    expect(r.costs.stress).toBe(1);
    applyDamage(painful.c, r, 'physical');
    expect(painful.c.stress.on).toBe(1);
  });

  it('halves before flat reductions and keeps severity conditions on the reduced damage', () => {
    const { c, severe } = setup((x) => {
      x.heritage.push(feat('Increased Fortitude'), feat('Scales'));
      x.activeArmor!.automation = rules('armor', 'Warded');
    });
    const fort = 'feat:Increased Fortitude/fortitude';
    const r = run(c, { damage: severe * 2, selected: new Set([fort]) });
    expect(r.damage).toBe(severe);
    expect(r.costs.hope).toBe(3);
    const scales = 'feat:Scales/scales';
    expect(run(c, { damage: severe * 2, selected: new Set([fort, scales]) }).hp).toBe(2);
    const minor = run(c, { damage: 1, selected: new Set([scales]) });
    expect(minor.inactive[scales]).toMatch(/Severe/);
    const magic = run(c, { damage: severe, type: 'magic' });
    expect(magic.damage).toBe(severe - derivedStats(c).armorSlots);
  });

  it('replaces HP with a cost equal to the HP that would be marked (Splendor-Touched)', () => {
    const { c, major } = setup((x) => {
      x.vault = [];
      const card = (name: string, automation?: Automation) => ({ level: 1, domain: 'Splendor', type: 'Ability' as const, name, text: '', recall: 0, automation });
      x.loadout = [card('Splendor-Touched', rules('domain-cards', 'Splendor-Touched')), card('A'), card('B')];
    });
    const id = 'card:Splendor-Touched/stress';
    expect(run(c, { damage: major, selected: new Set([id]) }).inactive[id]).toMatch(/Requirement/);
    c.loadout.push({ ...c.loadout[1], name: 'C' });
    const r = run(c, { damage: major, selected: new Set([id]) });
    expect(r.hp).toBe(0);
    expect(r.costs.stress).toBe(2);
    applyDamage(c, r, 'physical');
    expect(c.stress.on).toBe(2);
    expect(c.hp.on).toBe(0);
    expect(run(c, { damage: major, selected: new Set([id]) }).inactive[id]).toMatch(/No uses/);
  });

  it('only allows last-HP replacements when the last HP would be marked', () => {
    const { c, major } = setup((x) => { x.activeArmor!.automation = rules('armor', 'Impenetrable'); });
    const id = `armor:${c.activeArmor!.name}/impenetrable`;
    expect(run(c, { damage: major, selected: new Set([id]) }).inactive[id]).toMatch(/last HP/);
    c.hp.on = c.hp.slots - 1;
    const r = run(c, { damage: major, selected: new Set([id]) });
    expect(r.hp).toBe(0);
    expect(r.costs.stress).toBe(1);
    applyDamage(c, r, 'physical');
    expect(c.hp.on).toBe(c.hp.slots - 1);
    expect(availableMitigations(c).length).toBe(1);
    expect(run(c, { damage: major, selected: new Set([id]) }).inactive[id]).toMatch(/No uses/);
  });

  it('rolls Thorn Skin tokens by quantity and spends them', () => {
    const { c, severe } = setup((x) => {
      x.loadout = [{ level: 1, domain: 'Sage', type: 'Spell', name: 'Thorn Skin', text: '', recall: 0, automation: rules('domain-cards', 'Thorn Skin') }];
      x.resources = { ...(x.resources ?? {}), thorns: 3 };
    });
    const id = 'card:Thorn Skin/thorns';
    const r = run(c, { damage: severe, selected: new Set([id]), quantities: { [id]: 2 } }, fixed(4, 5));
    expect(r.used[id]).toEqual([4, 5]);
    expect(r.damage).toBe(Math.max(0, severe - 9));
    expect(r.costs.thorns).toBe(2);
    applyDamage(c, r, 'physical');
    expect(c.resources!.thorns).toBe(1);
  });

  it('blocks combined costs that cannot be paid and consumes items', () => {
    const { c, major } = setup((x) => {
      x.inventory.push({ name: 'Mirror of Marigold', type: 'consumable', qty: 1, automation: rules('consumables', 'Mirror of Marigold') });
      x.hope.on = 0;
    });
    const id = 'item:Mirror of Marigold/mirror';
    expect(run(c, { damage: major, selected: new Set([id]) }).error).toMatch(/Hope/);
    c.hope.on = 1;
    const r = run(c, { damage: major, selected: new Set([id]) });
    expect(r.negated).toBe(true);
    expect(r.hp).toBe(0);
    applyDamage(c, r, 'physical');
    expect(c.inventory[0].qty).toBe(0);
    expect(c.hope.on).toBe(0);
  });

  it('lets a Resilient save the last Armor Slot on a 6', () => {
    const { c, major } = setup((x) => { x.activeArmor!.automation = rules('armor', 'Resilient'); x.armorTrack.on = x.armorTrack.slots - 1; });
    const saved = run(c, { damage: major, armor: true }, fixed(6));
    expect(saved.armorMarked).toBe(0);
    expect(saved.hp).toBe(1);
    expect(run(c, { damage: major, armor: true }, fixed(2)).armorMarked).toBe(1);
  });
});

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { availableRollOptions, derivedStats, rollBonus, runAction, type Automation } from './engine';
import { mockCharacter, type CharacterSheet } from './data';
import { applyDamage, availableMitigations, computeDamage, type DamageInput } from './damage';
import { availableMoves, resolveRest } from './rest';

const armor: { entries: Array<{ name: string; feature?: { name: string }; automation?: Automation }> } =
  JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/armor.json', import.meta.url), 'utf8'));
const rules = (feature: string): Automation => {
  const entry = armor.entries.find(e => e.feature?.name === feature);
  if (!entry?.automation) throw new Error(`Missing armor automation: ${feature}`);
  return structuredClone(entry.automation);
};
const fixed = (...faces: number[]) => { let i = 0; return () => faces[i++ % faces.length]; };

function setup(feature: string, mutate?: (c: CharacterSheet) => void) {
  const c = mockCharacter();
  c.heritage = []; c.features = []; c.loadout = []; c.inventory = []; c.activeWeapons = [];
  c.activeArmor = { ...c.activeArmor!, feature: { name: feature, text: '' }, automation: rules(feature) };
  c.hp.on = 0; c.stress.on = 0; c.hope.on = 2; c.armorTrack.on = 0; c.usage = {}; c.effects = [];
  mutate?.(c);
  const d = derivedStats(c);
  return { c, major: d.major, severe: d.severe };
}
const damage = (c: CharacterSheet, input: Partial<DamageInput>, roll = fixed(1)) =>
  computeDamage(c, availableMitigations(c), { damage: 0, type: 'physical', armor: false, selected: new Set(), ...input }, roll);
const optionId = (c: CharacterSheet, id: string) => availableMitigations(c).find(m => m.option.id === id)!.id;

describe('armor feature automation', () => {
  it('Reinforced raises thresholds by 2 only while its buff is active and every Armor Slot is marked', () => {
    const { c, major, severe } = setup('Reinforced');
    const buff = c.activeArmor!.automation!.armorFullHeldEffect!;
    c.armorTrack.on = c.armorTrack.slots;
    expect(derivedStats(c).major).toBe(major);
    c.effects.push({ ...structuredClone(buff), id: 'reinforced' } as CharacterSheet['effects'][number]);
    expect(derivedStats(c).major).toBe(major + 2);
    expect(derivedStats(c).severe).toBe(severe + 2);
    c.armorTrack.on -= 1;
    expect(derivedStats(c).major).toBe(major);
  });

  it('Quiet applies to action rolls but not weapon attack rolls', () => {
    const { c } = setup('Quiet');
    expect(availableRollOptions(c, ['action']).some(o => o.option.id === 'quiet')).toBe(true);
    expect(availableRollOptions(c, ['action', 'attack']).some(o => o.option.id === 'quiet')).toBe(false);
  });
  it('Last Stand adds +1 to rolls only while every Armor Slot is marked', () => {
    const { c } = setup('Last Stand');
    expect(rollBonus(c, ['action']).value).toBe(0);
    c.armorTrack.on = c.armorTrack.slots;
    expect(rollBonus(c, ['action']).value).toBe(1);
    expect(rollBonus(c, ['reaction']).value).toBe(1);
    expect(rollBonus(c, ['damage']).value).toBe(1);
  });

  it('Sharp only offers its damage die to Melee weapons', () => {
    const { c } = setup('Sharp');
    const weapon = (range: string) => ({ ...c.activeWeapons[0] ?? {}, name: 'Test', range } as CharacterSheet['activeWeapons'][number]);
    expect(availableRollOptions(c, ['damage'], weapon('Melee')).some(o => o.option.id === 'sharp')).toBe(true);
    expect(availableRollOptions(c, ['damage'], weapon('Far')).some(o => o.option.id === 'sharp')).toBe(false);
  });

  it('Glasslight and Flicker of Courage need armor to avoid every HP', () => {
    const glass = setup('Glasslight');
    const avoided = damage(glass.c, { damage: 1, armor: true });
    expect(avoided.hp).toBe(0);
    expect(avoided.applied.some(m => m.option.id === 'glasslight')).toBe(true);
    expect(applyDamage(glass.c, avoided, 'physical')).toContain('the attacker marks a Stress');
    const hit = setup('Glasslight');
    expect(damage(hit.c, { damage: hit.severe, armor: true }).applied.some(m => m.option.id === 'glasslight')).toBe(false);

    const flicker = setup('Flicker of Courage');
    const r = damage(flicker.c, { damage: 1, armor: true, selected: new Set([optionId(flicker.c, 'flicker')]) });
    expect(r.applied.map(m => m.option.id)).toContain('flicker');
    applyDamage(flicker.c, r, 'physical');
    expect(flicker.c.hope.on).toBe(3);
  });

  it('Capacitor reduces magic damage by a d8 and stores the result for magic damage', () => {
    const { c } = setup('Capacitor');
    const r = damage(c, { damage: 10, type: 'magic', selected: new Set([optionId(c, 'capacitor')]) }, fixed(5));
    expect(r.damage).toBe(5);
    applyDamage(c, r, 'magic');
    const stored = c.effects.find(e => e.name === 'Sparkleweave: Capacitor');
    expect(stored?.rollOptions?.[0].value).toBe(5);
  });

  it('Lined negates Minor damage for a Stress', () => {
    const { c } = setup('Lined');
    const r = damage(c, { damage: 1, selected: new Set([optionId(c, 'lined')]) });
    expect(r.negated).toBe(true);
    expect(r.costs.stress).toBe(1);
  });

  it('Bulky marks a Stress on Severe damage and overflows into HP when Stress is full', () => {
    const fresh = setup('Bulky');
    const r = damage(fresh.c, { damage: fresh.severe });
    expect(r.costs.stress).toBe(1);
    expect(r.hp).toBe(3);
    expect(r.error).toBeNull();
    const full = setup('Bulky', c => { c.stress.on = c.stress.slots; });
    const overflow = damage(full.c, { damage: full.severe });
    expect(overflow.costs.stress ?? 0).toBe(0);
    expect(overflow.hp).toBe(4);
    expect(overflow.error).toBeNull();
    expect(damage(setup('Bulky').c, { damage: 1 }).costs.stress ?? 0).toBe(0);
  });

  it('Divine gains a Hope when an Armor Slot is marked', () => {
    const { c, major } = setup('Divine');
    const r = damage(c, { damage: major, armor: true });
    applyDamage(c, r, 'physical');
    expect(c.hope.on).toBe(3);
  });

  it('Overflowing gains Hope when an Armor clear exceeds the marked slots', () => {
    const { c } = setup('Overflowing', c => { c.armorTrack.on = 1; });
    runAction(c, 'test', {}, { id: 'clear', label: 'Clear', steps: [{ op: 'clear', resource: 'armor', amount: 2 }] }, 'Overflowing');
    expect(c.armorTrack.on).toBe(0);
    expect(c.hope.on).toBe(3);
    const exact = setup('Overflowing', c => { c.armorTrack.on = 2; });
    runAction(exact.c, 'test', {}, { id: 'clear', label: 'Clear', steps: [{ op: 'clear', resource: 'armor', amount: 2 }] }, 'Overflowing');
    expect(exact.c.hope.on).toBe(2);
  });

  it('Runesleep clears a Stress only when the rest starts without marked Armor', () => {
    const { c } = setup('Runesleep', c => { c.stress.on = 2; });
    resolveRest(c, 'short', []);
    expect(c.stress.on).toBe(1);
    const marked = setup('Runesleep', c => { c.stress.on = 2; c.armorTrack.on = 1; });
    resolveRest(marked.c, 'short', []);
    expect(marked.c.stress.on).toBe(2);
  });

  it('Vitreous cracks on negation and is repaired by its downtime action', () => {
    const { c, severe } = setup('Vitreous');
    const id = availableMitigations(c).find(m => m.option.negate)!.id;
    const before = derivedStats(c).major;
    const r = damage(c, { damage: severe, selected: new Set([id]) });
    expect(r.negated).toBe(true);
    applyDamage(c, r, 'physical');
    expect(derivedStats(c).major).toBe(before - 5);
    const repair = availableMoves(c, 'short').find(m => m.label.endsWith('Repair Vitreous armor'))!;
    resolveRest(c, 'short', [{ move: repair.id }]);
    expect(c.effects.some(e => e.name === 'Vitreous Cracked')).toBe(false);
    expect(availableMoves(c, 'short').some(m => m.label.endsWith('Repair Vitreous armor'))).toBe(false);
  });

  it('Oathbound Break vow ends the vow and marks every free Armor Slot', () => {
    const { c } = setup('Oathbound', c => { c.armorTrack.on = 1; c.effects = [{ id: 'oath', name: 'Oathbound', kind: 'buff', until: 'manual', modifiers: [{ stat: 'evasion', value: 2 }] }]; });
    const action = c.activeArmor!.automation!.actions!.find(a => a.id === 'break')!;
    runAction(c, 'armor', c.activeArmor!.automation!, action, 'Oathbound');
    expect(c.armorTrack.on).toBe(c.armorTrack.slots);
    expect(c.effects.some(e => e.name === 'Oathbound')).toBe(false);
  });
});

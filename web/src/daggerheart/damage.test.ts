import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { applyRest, derivedStats, runAction, type Automation } from './engine';
import { mockCharacter, type CharacterSheet } from './data';
import { applyDamage, availableMitigations, computeDamage, severityOf, type DamageInput } from './damage';
import { formatDamageReport, parseDamageReport } from './damage-summary';

function rules(category: string, name: string): Automation {
  const file: { entries: Array<{ name: string; automation?: Automation; feature?: { name: string }; spells?: Array<{ name: string; automation?: Automation }>; features?: Array<{ name: string; automation?: Automation }> }> } =
    JSON.parse(readFileSync(new URL(`../../../dmmap-daggerheart/${category}.json`, import.meta.url), 'utf8'));
  type Source = { name: string; automation?: Automation; feature?: { name: string } };
  const source = file.entries.flatMap((e): Source[] => [e, ...(e.features ?? []), ...(e.spells ?? [])]).find((e) => e.name === name || e.feature?.name === name);
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
  it('adds Never Upstaged tokens only for confirmed actual HP marks and pays Stress once', () => {
    const { c, severe } = setup(c => {
      c.loadout = [{ name: 'Never Upstaged', domain: 'Grace', type: 'Ability', level: 6, recall: 2, text: '',
        automation: rules('domain-cards', 'Never Upstaged') }];
      c.resources = { upstaged: 2 };
      c.hp.on = c.hp.slots - 2;
    });
    const option = availableMitigations(c).find(m => m.option.gainTokens)!;
    const preview = run(c, { damage: severe, selected: new Set([option.id]) });
    expect(c.resources!.upstaged).toBe(2);
    expect(c.stress.on).toBe(0);
    expect(applyDamage(c, preview, 'physical')).toContain('gained 2 Never Upstaged tokens');
    expect(c.resources!.upstaged).toBe(4);
    expect(c.stress.on).toBe(1);
  });

  it('blocks a missing token pool before applying mitigation payments or HP', () => {
    const { c } = setup(c => {
      c.features = [{ source: 'test', name: 'Tokens', text: '', automation: {
        resources: [{ id: 'tokens', label: 'Tokens', initial: 0 }],
        mitigation: [{ id: 'gain', label: 'Gain tokens', cost: { stress: 1 }, gainTokens: { resource: 'tokens' } }],
      } }];
    });
    const option = availableMitigations(c)[0];
    const preview = run(c, { damage: 1, selected: new Set([option.id]) });
    c.features[0].automation!.resources = [];
    expect(applyDamage(c, preview, 'physical')).toBeNull();
    expect(c.hp.on).toBe(0);
    expect(c.stress.on).toBe(0);
  });

  const shield = (c: CharacterSheet) => {
    const a = rules('domain-cards', 'Shield Aura');
    runAction(c, 'card:Shield Aura', a, a.actions![0], 'Shield Aura');
  };

  it('expires Shield Aura only when its extra Armor reduction prevents the HP mark', () => {
    const { c, major } = setup();
    shield(c);
    const preview = run(c, { damage: major, armor: true });
    expect(preview.hp).toBe(0);
    expect(c.effects.some(effect => effect.name === 'Shield Aura')).toBe(true);
    applyDamage(c, preview, 'physical');
    expect(c.effects.some(effect => effect.name === 'Shield Aura')).toBe(false);
  });

  it('retains Shield Aura when ordinary Armor already prevents all HP or no Armor was used', () => {
    const { c } = setup();
    shield(c);
    const ordinaryArmor = run(c, { damage: 1, armor: true });
    expect(ordinaryArmor.hp).toBe(0);
    applyDamage(c, ordinaryArmor, 'physical');
    expect(c.effects.some(effect => effect.name === 'Shield Aura')).toBe(true);
    const unarmored = run(c, { damage: 1 });
    expect(unarmored.hp).toBe(1);
    applyDamage(c, unarmored, 'physical');
    expect(c.effects.some(effect => effect.name === 'Shield Aura')).toBe(true);
  });

  it('does not consume Shield Aura when the incoming attack is negated', () => {
    const { c, major } = setup();
    shield(c);
    c.effects.push({ id: 'negate', name: 'Negation', kind: 'buff',
      mitigation: [{ id: 'negate', label: 'Negate', passive: true, negate: true }] });
    const result = run(c, { damage: major, armor: true });
    expect(result.negated).toBe(true);
    applyDamage(c, result, 'physical');
    expect(c.effects.some(effect => effect.name === 'Shield Aura')).toBe(true);
  });

  it('uses only the chosen Vital Ward resistance and expires on actual two-HP damage', () => {
    const { c, major } = setup();
    const a = rules('domain-cards', 'Vital Ward');
    expect(availableMitigations(c).some(m => m.source === 'Vital Ward')).toBe(false);
    runAction(c, 'card:Vital Ward', a, a.conditionalActions![0], 'Vital Ward');
    c.hp.on = 0;
    expect(run(c, { damage: major, type: 'physical' }).damage).toBe(Math.floor(major / 2));
    expect(run(c, { damage: major, type: 'magic' }).damage).toBe(major);
    runAction(c, 'card:Vital Ward', a, a.conditionalActions![1], 'Vital Ward');
    c.hp.on = 0;
    expect(c.effects.filter(effect => effect.name === 'Vital Ward')).toHaveLength(1);
    expect(run(c, { damage: major, type: 'physical' }).damage).toBe(major);
    const result = run(c, { damage: major, type: 'physical' });
    expect(result.hp).toBe(2);
    expect(c.effects.some(effect => effect.name === 'Vital Ward')).toBe(true);
    applyDamage(c, result, 'physical');
    expect(c.effects.some(effect => effect.name === 'Vital Ward')).toBe(false);
  });

  it('does not expire Vital Ward when damage can actually mark only one remaining HP', () => {
    const { c, severe } = setup();
    const a = rules('domain-cards', 'Vital Ward');
    runAction(c, 'card:Vital Ward', a, a.conditionalActions![0], 'Vital Ward');
    c.hp.on = c.hp.slots - 1;
    const result = run(c, { damage: severe, type: 'magic' });
    applyDamage(c, result, 'magic');
    expect(c.effects.some(effect => effect.name === 'Vital Ward')).toBe(true);
  });

  it('conjures beetles and reduces the next damage, optionally retaining them for Hope', () => {
    const { c, major } = setup();
    const automation = rules('domain-cards', 'Tekaira Armored Beetles');
    c.loadout = [{ name: 'Conjure Swarm', domain: 'Sage', level: 2, type: 'Spell', recall: 1, text: '', automation }];
    expect(availableMitigations(c)).toHaveLength(0);
    runAction(c, 'card:Conjure Swarm/Tekaira Armored Beetles', automation, automation.actions![0], 'Tekaira Armored Beetles');
    expect(c.stress.on).toBe(1);
    const keep = availableMitigations(c).find(m => m.option.id === 'keep')!;
    const input = { damage: major, selected: new Set([keep.id]) };
    const preview = run(c, input);
    expect(preview.hp).toBe(1);
    expect(c.hope.on).toBe(3);
    expect(c.effects).toHaveLength(1);
    applyDamage(c, preview, 'physical');
    expect(c.hope.on).toBe(2);
    expect(c.effects).toHaveLength(1);
    c.hp.on = 0;
    const next = run(c, { damage: 1 });
    expect(next.hp).toBe(0);
    applyDamage(c, next, 'physical');
    expect(c.effects).toHaveLength(0);
    expect(availableMitigations(c)).toHaveLength(0);
  });

  it('does not spend Hope or dismiss beetles when damage is negated', () => {
    const { c, major } = setup();
    const automation = rules('domain-cards', 'Tekaira Armored Beetles');
    c.loadout = [{ name: 'Conjure Swarm', domain: 'Sage', level: 2, type: 'Spell', recall: 1, text: '', automation }];
    runAction(c, 'card:Conjure Swarm/Tekaira Armored Beetles', automation, automation.actions![0], 'Tekaira Armored Beetles');
    c.effects.push({ id: 'immune', name: 'immune', kind: 'buff', mitigation: [{ id: 'negate', label: 'Negate', passive: true, negate: true }] });
    const keep = availableMitigations(c).find(m => m.option.id === 'keep')!;
    const result = run(c, { damage: major, selected: new Set([keep.id]) });
    expect(result.negated).toBe(true);
    expect(result.costs.hope).toBeUndefined();
    applyDamage(c, result, 'physical');
    expect(c.hope.on).toBe(3);
    expect(c.effects.some(e => e.name === 'Tekaira Armored Beetles')).toBe(true);
  });

  it('negates magic damage only while Book of Yarrow Magic Immunity is active', () => {
    const { c, major } = setup();
    const book = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'))
      .entries.find((entry: { id: string }) => entry.id === 'book-of-yarrow');
    const automation: Automation = book.spells.find((spell: { name: string }) => spell.name === 'Magic Immunity').automation;
    c.hope.on = 6;
    runAction(c, 'card:Book of Yarrow/Magic Immunity', automation, automation.actions![0], 'Magic Immunity');
    expect(c.hope.on).toBe(1);
    expect(run(c, { damage: major, type: 'magic' }).negated).toBe(true);
    expect(run(c, { damage: major, type: 'physical' }).negated).toBe(false);
    applyRest(c, 'short');
    expect(run(c, { damage: major, type: 'magic' }).negated).toBe(false);
  });

  it('grants Specter of the Dark physical immunity until its effect is ended', () => {
    const { c, major } = setup();
    const automation = rules('domain-cards', 'Specter of the Dark');
    expect(run(c, { damage: major, type: 'physical' }).negated).toBe(false);
    runAction(c, 'card:Specter of the Dark', automation, automation.actions![0], 'Specter of the Dark');
    expect(c.stress.on).toBe(1);
    expect(run(c, { damage: major, type: 'physical' }).negated).toBe(true);
    expect(run(c, { damage: major, type: 'magic' }).negated).toBe(false);
    c.effects = c.effects.filter(effect => effect.name !== 'Specter of the Dark');
    expect(run(c, { damage: major, type: 'physical' }).negated).toBe(false);
  });

  it('applies Blood-Touched after mitigation and updates Evasion per three marked HP', () => {
    const { c, major } = setup();
    const card = (name: string) => ({ name, domain: 'Blood', level: 1, type: 'Ability' as const, recall: 0, text: '' });
    c.loadout = [{ ...card('Blood-Touched'), automation: rules('domain-cards', 'Blood-Touched') }, card('two'), card('three')];
    c.vault = [card('four')];
    const base = c.evasion;
    c.hp.on = 3;
    expect(derivedStats(c).evasion).toBe(base);
    c.loadout.push(c.vault.pop()!);
    expect(derivedStats(c).evasion).toBe(base + 1);
    c.hp.on = 0;
    const mitigated = run(c, { damage: major, armor: true });
    applyDamage(c, mitigated, 'physical');
    expect(c.hope.on).toBe(3);
    c.hp.on = 0;
    const damage = run(c, { damage: major });
    applyDamage(c, damage, 'physical');
    expect(c.hp.on).toBe(2);
    expect(c.hope.on).toBe(4);
    c.hp.on = 6;
    expect(derivedStats(c).evasion).toBe(base + 2);
  });

  it('applies Valor-Touched only when HP is marked without marking armor', () => {
    const { c, major } = setup();
    const card = (name: string) => ({ name, domain: 'Valor', level: 1, type: 'Ability' as const, recall: 0, text: '' });
    c.loadout = [{ ...card('Valor-Touched'), automation: rules('domain-cards', 'Valor-Touched') }, card('two'), card('three'), card('four')];
    c.armorTrack.on = 2;
    applyDamage(c, run(c, { damage: major }), 'physical');
    expect(c.armorTrack.on).toBe(1);
    c.hp.on = 0;
    c.armorTrack.on = 1;
    applyDamage(c, run(c, { damage: major, armor: true }), 'physical');
    expect(c.armorTrack.on).toBe(2);
    c.hp.on = 0;
    c.vault.push(c.loadout.pop()!);
    applyDamage(c, run(c, { damage: major }), 'physical');
    expect(c.armorTrack.on).toBe(2);
  });

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
    expect(run(c, { damage: major, selected: new Set([id]) }).inactive[id]).toMatch(/active loadout/);
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

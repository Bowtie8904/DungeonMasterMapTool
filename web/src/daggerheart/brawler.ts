import { CharacterSheet, TraitName, TRAIT_ORDER, tierOf } from './data';
import { ActionDef, Automation, DiceRoll, addEffect, automationSources, runActionDetailed, syncResources, traitTotal, weaponWithProfile } from './engine';
import { companionAvailable, companionWeapon } from './companion';

export function martialStances(c: CharacterSheet): NonNullable<Automation['stances']> {
  return automationSources(c).flatMap(a => a.stances ?? []);
}

export function missingStanceChoices(c: CharacterSheet): number {
  const foundationLevel = c.levelUps?.find(l => l.advancements.some(a => a.multiclass?.subclass === 'Brawler: Martial Artist'))?.level ?? 1;
  return Math.max(0, 2 + c.level - foundationLevel - (c.knownStances?.length ?? 0));
}

export function stanceProblems(catalog: NonNullable<Automation['stances']>, names: string[], count: number, tier: number, known: string[] = []): string[] {
  return names.length !== count || new Set(names).size !== count
    || names.some(name => known.includes(name) || !catalog.some(s => s.name === name && s.tier <= tier))
    ? [`Choose ${count} different, unknown stance${count === 1 ? '' : 's'} from tier ${tier} or below.`] : [];
}

export function syncBrawler(c: CharacterSheet): void {
  const spec = automationSources(c).find(a => a.unarmedWeapon)?.unarmedWeapon;
  c.carriedWeapons = c.carriedWeapons.filter(w => !w.generated);
  const real = c.activeWeapons.filter(w => !w.generated);
  if (!spec || real.length) {
    c.activeWeapons = real;
    c.effects = c.effects.filter(e => e.source !== 'Brawler unarmed weapon');
    return;
  }
  const trait = c.brawlerTrait ?? c.activeWeapons.find(w => w.generated)?.trait ?? TRAIT_ORDER.reduce((best, name) => traitTotal(c, name) > traitTotal(c, best) ? name : best);
  c.brawlerTrait = trait;
  c.activeWeapons = [{ ...structuredClone(spec), trait, generated: 'brawler' }];
  if (!c.effects.some(e => e.source === 'Brawler unarmed weapon')) addEffect(c, {
    name: 'I Am The Weapon', kind: 'buff', text: '+1 Evasion while Brawler\'s Strike is your only active weapon.',
    modifiers: [{ stat: 'evasion', value: 1 }],
  }, 'Brawler unarmed weapon');
}

export function shiftStance(c: CharacterSheet, key: string, automation: Automation, action: ActionDef, name: string, trait?: TraitName): string[] {
  const stance = martialStances(c).find(s => s.name === name && s.tier <= tierOf(c.level));
  if (!stance || !c.knownStances?.includes(name)) throw new Error('Choose a known stance from your tier or below.');
  if (stance.chooseTrait && !TRAIT_ORDER.includes(trait!)) throw new Error('Choose a trait for this stance.');
  const report = runActionDetailed(c, key, automation, action, 'Stance Fighter');
  if (!report) throw new Error('Cannot pay the stance shift cost.');
  c.effects = c.effects.filter(e => !e.stance);
  const effect = structuredClone(stance.effect);
  if (stance.chooseTrait) effect.modifiers = [...(effect.modifiers ?? []), { roll: 'damage', traitValue: trait }];
  addEffect(c, { ...effect, stance: true, until: 'scene' }, 'Stance Fighter');
  return [...report, `Shifted into ${name}${trait ? ` (${trait})` : ''}`];
}

export function refocus(c: CharacterSheet, key: string, automation: Automation, action: ActionDef, roll: DiceRoll): string[] {
  const count = Math.max(0, traitTotal(c, 'Instinct'));
  const report = runActionDetailed(c, key, automation, action, 'Refocus');
  if (!report) throw new Error('Refocus is unavailable until your next rest.');
  syncResources(c);
  const faces = Array.from({ length: count }, () => roll('1d6'));
  c.resources!.focus = Math.max(0, ...faces);
  return [...report, faces.length ? `Refocused: [${faces.join(', ')}]; ${c.resources!.focus} Focus` : 'Focus cleared to 0 (Instinct is 0 or lower; no dice rolled)'];
}

export function comboDie(c: CharacterSheet): number {
  const increases = (c.levelUps ?? []).flatMap(l => l.advancements).filter(a => a.type === 'combo').length;
  return [4, 6, 8, 10, 12][Math.min(4, increases)];
}

export function criticalMeleeBonus(c: CharacterSheet, weapon: string | undefined, critical: boolean, profile?: string): NonNullable<Automation['criticalMelee']> {
  const companion = companionAvailable(c) ? companionWeapon(c) : undefined;
  const weapons = c.activeWeapons.map(w => profile && w.name === weapon ? weaponWithProfile(w, profile) : w);
  const melee = [...weapons, ...(companion ? [companion] : [])].some(w => w.name === weapon && w.range.toLowerCase() === 'melee');
  return critical && melee ? automationSources(c).find(a => a.criticalMelee)?.criticalMelee ?? { hope: 0, stress: 0, proficiency: 0 }
    : { hope: 0, stress: 0, proficiency: 0 };
}

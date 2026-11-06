import type { CharacterSheet, CompanionChoices, CompanionTraining, Feature, RangerCompanion, Weapon } from './data';
import type { Automation, DiceRoll } from './engine';
import { hpHealingFloor } from './death';

export interface CompanionOption {
  id: string;
  name: string;
  text: string;
  max: number;
  choose?: 'experience' | 'attack';
  stat?: 'hope' | 'stress' | 'evasion';
  value?: number;
  automation?: Automation;
}

export interface CompanionRules {
  evasion: number;
  stress: number;
  maxStress: number;
  damage: string;
  range: string;
  dice: string[];
  ranges: string[];
  options: CompanionOption[];
}

export function companionRules(c: CharacterSheet): CompanionRules | undefined {
  return c.features.find(f => f.automation?.companion)?.automation?.companion;
}

export function emptyCompanionChoices(): CompanionChoices {
  return { name: '', animal: '', attack: '', damageType: 'phys', experiences: ['', ''] };
}

export function companionStartLevel(c: CharacterSheet): number {
  const source = c.features.find(f => f.automation?.companion)?.source ?? '';
  if (!source.startsWith('Multiclass ')) return 1;
  const record = c.levelUps?.find(r => r.advancements.some(a => a.multiclass && source.includes(a.multiclass.className)
    && source.includes(a.multiclass.subclass.replace(/^[^:]+:\s*/, ''))));
  return record?.level ?? c.level;
}

export function companionEntitlements(c: CharacterSheet): { training: number; experiences: number } {
  const start = companionStartLevel(c);
  const extra = c.features.reduce((n, f) => n + (f.automation?.companionTraining ?? 0), 0);
  const earned = Math.max(0, c.level - start + (start > 1 ? 1 : 0)) + extra;
  const milestones = [2, 5, 8].filter(level => level >= start && level <= c.level).length;
  return { training: Math.max(0, earned - (c.companion?.training.length ?? 0)), experiences: 2 + milestones };
}

export function companionChoiceProblems(ch: CompanionChoices, experiences = 2): string[] {
  const p: string[] = [];
  if (!ch.name.trim() || !ch.animal.trim()) p.push('Give your companion a name and animal type.');
  if (!ch.attack.trim()) p.push('Describe your companion\'s standard attack.');
  if (ch.damageType !== 'phys' && ch.damageType !== 'magic') p.push('Choose physical or magic damage.');
  if (ch.experiences.length !== experiences || ch.experiences.some(n => !n.trim())) p.push(`Write ${experiences} companion Experiences.`);
  return p;
}

export function buildCompanion(ch: CompanionChoices, rules: CompanionRules): RangerCompanion {
  return {
    ...structuredClone(ch), name: ch.name.trim(), animal: ch.animal.trim(), attack: ch.attack.trim(),
    experienceStats: ch.experiences.map(name => ({ name: name.trim(), mod: 2 })),
    training: [], stress: { slots: rules.stress, total: rules.maxStress, on: 0 },
    evasion: rules.evasion, damage: rules.damage, range: rules.range,
  };
}

export function companionFeatures(c: CharacterSheet): Feature[] {
  const rules = companionRules(c);
  return (rules?.options ?? []).filter(o => c.companion?.training.some(t => t.id === o.id)).map(o => ({
    source: `Companion - ${c.companion!.name}`, name: o.name, text: o.text, automation: o.automation,
  }));
}

export function hasCompanionTraining(c: CharacterSheet, id: string): boolean {
  return !!companionRules(c) && !!c.companion?.training.some(t => t.id === id);
}

export function companionAvailable(c: CharacterSheet): boolean {
  return !!companionRules(c) && !!c.companion && !c.companion.unavailable && c.companion.stress.on < c.companion.stress.slots;
}

export function companionTrainingProblems(rules: CompanionRules, companion: RangerCompanion, picks: CompanionTraining[], count: number): string[] {
  if (picks.length !== count) return [`Choose ${count} companion training option(s).`];
  const p: string[] = [];
  let damage = companion.damage;
  let range = companion.range;
  const selected = [...companion.training];
  for (const pick of picks) {
    if (!pick) { p.push('Choose an available companion training option.'); continue; }
    const option = rules.options.find(o => o.id === pick.id);
    if (!option || selected.filter(t => t.id === pick.id).length >= option.max) {
      p.push('Choose an available companion training option.');
      continue;
    }
    if (option.choose === 'experience' && (!Number.isInteger(pick.experience) || !companion.experienceStats[pick.experience!])) p.push('Choose a companion Experience to improve.');
    if (option.choose === 'attack') {
      if (pick.increase === 'damage' && rules.dice.indexOf(damage) >= 0 && rules.dice.indexOf(damage) < rules.dice.length - 1) damage = rules.dice[rules.dice.indexOf(damage) + 1];
      else if (pick.increase === 'range' && rules.ranges.indexOf(range) >= 0 && rules.ranges.indexOf(range) < rules.ranges.length - 1) range = rules.ranges[rules.ranges.indexOf(range) + 1];
      else p.push('Choose a damage die or range that can increase by one step.');
    }
    selected.push(pick);
  }
  return p;
}

export function applyCompanionTraining(c: CharacterSheet, picks: CompanionTraining[], count: number): void {
  const rules = companionRules(c);
  if (!rules || !c.companion) throw new Error('Create your companion before choosing training.');
  const problems = companionTrainingProblems(rules, c.companion, picks, count);
  if (problems.length) throw new Error(problems.join(' '));
  const pet = c.companion;
  for (const pick of picks) {
    const option = rules.options.find(o => o.id === pick.id)!;
    if (option.choose === 'experience') pet.experienceStats[pick.experience!].mod++;
    if (option.choose === 'attack') {
      if (pick.increase === 'damage') pet.damage = rules.dice[rules.dice.indexOf(pet.damage) + 1];
      else pet.range = rules.ranges[rules.ranges.indexOf(pet.range) + 1];
    }
    if (option.stat === 'stress') pet.stress.slots = Math.min(pet.stress.total, pet.stress.slots + (option.value ?? 1));
    if (option.stat === 'evasion') pet.evasion += option.value ?? 0;
    pet.training.push(structuredClone(pick));
  }
  syncCompanion(c);
}

export function syncCompanion(c: CharacterSheet): void {
  if (!companionRules(c) && !c.companion) return;
  const pet = c.companion;
  if (pet && pet.stress.on >= pet.stress.slots) pet.unavailable = true;
  if (pet && c.hp.on < c.hp.slots) pet.bondedAttempted = false;
  const extra = hasCompanionTraining(c, 'light-in-the-dark') ? 1 : 0;
  c.hope.total = 6 + extra;
  c.hope.slots = Math.max(0, c.hope.total - (c.scars ?? 0));
  c.hope.on = Math.min(c.hope.on, c.hope.slots);
}

export function companionWeapon(c: CharacterSheet): Weapon | undefined {
  if (!c.companion || !companionRules(c)) return undefined;
  const pet = c.companion;
  return {
    generated: 'companion', name: `${pet.name}: ${pet.attack}`, slot: 'Primary', hands: 1,
    damageType: pet.damageType, trait: c.traits.find(t => t.spellcast)?.name ?? 'Agility',
    range: pet.range, damage: pet.damage, tier: 1,
    feature: { name: 'Companion', text: "Commands use your Spellcast trait. Attacks inherit your applicable benefits, including Ranger's Focus." },
  };
}

export function markCompanionDamage(c: CharacterSheet, armored = false): string {
  if (!companionAvailable(c)) throw new Error('Your companion is unavailable until the next long rest.');
  if (armored) {
    if (!hasCompanionTraining(c, 'armored') || c.armorTrack.on >= c.armorTrack.slots) throw new Error('Armored requires an available owner Armor Slot.');
    c.armorTrack.on++;
    return 'Armored: marked one owner Armor Slot instead of companion Stress.';
  }
  const pet = c.companion!;
  pet.stress.on++;
  syncCompanion(c);
  return `${pet.name}: marked 1 Stress.${pet.unavailable ? ' Unavailable until the start of the next long rest.' : ''}`;
}

export function clearCompanionStress(c: CharacterSheet, amount: number): number {
  if (!companionAvailable(c)) return 0;
  const n = Math.min(c.companion!.stress.on, Math.max(0, amount));
  c.companion!.stress.on -= n;
  return n;
}

export function returnCompanion(c: CharacterSheet): string | undefined {
  if (!companionRules(c) || !c.companion?.unavailable) return undefined;
  c.companion.unavailable = false;
  c.companion.stress.on = Math.max(0, c.companion.stress.on - 1);
  return `${c.companion.name} returns and clears 1 Stress.`;
}

export function resolveBonded(c: CharacterSheet, roll: DiceRoll): string | undefined {
  const pet = c.companion;
  if (!pet || c.pendingDeathRoll || hpHealingFloor(c) >= c.hp.slots || !hasCompanionTraining(c, 'bonded') || !companionAvailable(c) || c.hp.on < c.hp.slots || pet.bondedAttempted) return undefined;
  pet.bondedAttempted = true;
  const count = pet.stress.slots - pet.stress.on;
  const dice = Array.from({ length: count }, () => roll('1d6'));
  pet.stress.on = pet.stress.slots;
  pet.unavailable = true;
  const rescued = dice.includes(6) && c.hp.on > hpHealingFloor(c);
  if (rescued) c.hp.on--;
  return `Bonded: ${pet.name} rolls ${dice.join(', ')}; marks ${count} Stress. ${rescued ? 'Cleared your last HP; return to the scene.' : 'No rescue.'} Companion unavailable until the next long rest.`;
}

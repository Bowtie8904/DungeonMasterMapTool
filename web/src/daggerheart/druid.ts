import { CharacterSheet, Feature, TraitName, TRAIT_ORDER, Weapon, tierOf } from './data';
import {
  ActionDef, Automation, BeastformDef, EffectDef, activeFeatures, addEffect, runActionDetailed,
  syncEffectDependencies,
} from './engine';

export interface BeastformChoice {
  form: string;
  base?: string;
  categories?: string[];
  advantages?: string[];
  features?: string[];
  trait?: TraitName;
}

export function beastformCatalog(c: CharacterSheet): BeastformDef[] {
  return c.features.flatMap(f => f.automation?.beastforms ?? []);
}

export function beastformParts(catalog: BeastformDef[], categories: string[]): { advantages: string[]; features: NonNullable<BeastformDef['features']> } {
  const forms = catalog.filter(f => categories.includes(f.name));
  return {
    advantages: [...new Set(forms.flatMap(f => f.advantages ?? []))],
    features: [...new Map(forms.flatMap(f => f.features ?? []).map(f => [f.name, f])).values()],
  };
}

export function buildBeastform(c: CharacterSheet, choice: BeastformChoice, evolution: boolean): { effect: EffectDef; stress: number } {
  const catalog = beastformCatalog(c);
  const form = catalog.find(f => f.name === choice.form && f.tier <= tierOf(c.level));
  if (!form) throw new Error('Choose a Beastform of your tier or lower.');
  if (evolution && !TRAIT_ORDER.includes(choice.trait!)) throw new Error('Choose the trait raised by Evolution.');
  let base = form;
  if (form.evolved) {
    const selected = catalog.find(f => f.name === choice.base && f.tier <= form.evolved!.maxTier && !f.evolved && !f.hybrid);
    if (!selected) throw new Error(`Choose an ordinary category of tier ${form.evolved.maxTier} or lower.`);
    base = selected;
  }
  let advantages = base.advantages ?? [];
  let features = base.features ?? [];
  if (form.hybrid) {
    const h = form.hybrid;
    const categories = choice.categories ?? [];
    if (categories.length !== h.count || new Set(categories).size !== h.count
      || categories.some(name => !catalog.some(f => f.name === name && f.tier <= h.maxTier && !f.evolved && !f.hybrid))) {
      throw new Error(`Choose ${h.count} different ordinary categories of tier ${h.maxTier} or lower.`);
    }
    const parts = beastformParts(catalog, categories);
    advantages = choice.advantages ?? [];
    const names = choice.features ?? [];
    if (advantages.length !== h.advantages || new Set(advantages).size !== h.advantages || advantages.some(a => !parts.advantages.includes(a))) {
      throw new Error(`Choose ${h.advantages} different advantages from the selected categories.`);
    }
    if (names.length !== h.features || new Set(names).size !== h.features || names.some(n => !parts.features.some(f => f.name === n))) {
      throw new Error(`Choose ${h.features} different features from the selected categories.`);
    }
    features = parts.features.filter(f => names.includes(f.name));
  }
  if (!base.trait || !base.damage) throw new Error('The selected Beastform has no attack statistics.');
  let damage = base.damage;
  if (form.evolved?.increaseDie) damage = damage.replace(/d(\d+)/, (_, sides: string) => {
    const sizes = [4, 6, 8, 10, 12, 20];
    return `d${sizes[Math.min(sizes.length - 1, sizes.indexOf(Number(sides)) + 1)]}`;
  });
  const damageBonus = form.evolved?.damage ?? 0;
  const name = `Beastform: ${form.name}${form.evolved ? ` (${base.name})` : ''}`;
  const weapon: Weapon = {
    name, generated: 'beastform', trait: base.trait, damage, damageType: 'phys', range: base.range ?? 'Melee',
    slot: 'Primary', hands: 2, tier: form.tier,
  };
  const temporary: Feature[] = features.map(f => ({ ...structuredClone(f), source: name }));
  temporary.unshift({
    source: name, name: 'Beastform Advantages', text: `Gain advantage on: ${advantages.join(', ')}.`,
    automation: { rollOptions: [{
      id: 'advantages', label: `${form.name}: ${advantages.join(', ')}`, description: `Only a roll related to ${advantages.join(', ')}.`,
      roll: 'duality', advantage: 'advantage',
    }] },
  });
  const effect: EffectDef = {
    name, kind: 'buff', group: 'beastform', beastform: true, weapon, features: temporary,
    text: `${form.name}${form.evolved ? ` - ${base.name}` : ''}. Gain advantage on: ${advantages.join(', ')}.`,
    modifiers: [
      { stat: `trait.${base.trait}`, value: (base.bonus ?? 0) + (form.evolved?.trait ?? 0) },
      { stat: 'evasion', value: (base.evasion ?? 0) + (form.evolved?.evasion ?? 0) },
      ...(damageBonus ? [{ roll: 'damage', value: damageBonus }] : []),
      ...(evolution ? [{ stat: `trait.${choice.trait}`, value: 1 }] : []),
    ],
    ...(features.some(f => f.name === 'Fragile') ? { endOnDamage: 2 } : {}),
  };
  return { effect, stress: form.hybrid?.stress ?? 0 };
}

export function transformBeastform(c: CharacterSheet, key: string, a: Automation, action: ActionDef, choice: BeastformChoice): string[] {
  const result = buildBeastform(c, choice, !!action.evolution);
  const costAction = { ...action, cost: { ...action.cost, stress: Number(action.cost?.stress ?? 0) + result.stress } };
  const log = runActionDetailed(c, key, a, costAction, action.label);
  if (!log) throw new Error('Cannot pay the transformation cost.');
  addEffect(c, result.effect, action.label);
  syncEffectDependencies(c);
  return [...log, `Transformed into ${result.effect.name.slice('Beastform: '.length)}${action.evolution ? `; Evolution: +1 ${choice.trait}` : ''}.`];
}

export function channelElement(c: CharacterSheet, key: string, a: Automation, action: ActionDef): string[] {
  const log = runActionDetailed(c, key, a, action, action.label);
  if (!log) throw new Error('Cannot channel this element.');
  const channel = c.effects.find(e => e.group === 'channel');
  if (!channel) throw new Error('Channeling configuration is missing its effect.');
  for (const f of activeFeatures(c)) {
    const benefit = f.automation?.channelBenefit;
    if (benefit && benefit.element === action.channel) addEffect(c, { ...structuredClone(benefit.effect), requiresEffect: channel.name }, f.name);
  }
  return log;
}

export function activateAura(c: CharacterSheet, key: string, a: Automation, action: ActionDef): string[] {
  const channel = c.effects.find(e => e.group === 'channel');
  const element = channel?.name.replace('Channeling: ', '');
  const feature = activeFeatures(c).find(f => f.name === `Elemental Aura: ${element}`);
  const effect = feature?.automation?.channelBenefit?.effect;
  if (!channel || !effect) throw new Error('Channel an element before activating its aura.');
  const log = runActionDetailed(c, key, a, action, action.label);
  if (!log) throw new Error('Elemental Aura is unavailable until your next rest.');
  addEffect(c, { ...structuredClone(effect), requiresEffect: channel.name, group: 'aura' }, 'Elemental Aura');
  return [...log, `Gained ${effect.name}.`];
}

export function canCastDomain(c: CharacterSheet): boolean {
  return !c.effects.some(e => e.beastform);
}

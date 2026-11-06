import type { CharacterSheet, TraitName } from './data';
import { TRAIT_ORDER } from './data';
import { actionBlocker, addEffect, runActionDetailed, type ActionDef, type Automation } from './engine';

export function channelRawPower(c: CharacterSheet, key: string, auto: Automation, action: ActionDef, cardName: string, benefit: 'hope' | 'damage'): string[] {
  const card = c.loadout.find(d => d.name === cardName);
  if (!action.channelRawPower || !card || !Number.isInteger(card.level) || card.level < 1)
    throw new Error('Choose a domain card from your active loadout.');
  const blocker = actionBlocker(c, key, auto, action);
  if (blocker) throw new Error(blocker);
  const lines = runActionDetailed(c, key, auto, action, 'Channel Raw Power')!;
  c.loadout.splice(c.loadout.indexOf(card), 1);
  c.vault.push(card);
  lines.push(`Moved ${card.name} (level ${card.level}) to the vault.`);
  if (benefit === 'hope') {
    const before = c.hope.on;
    c.hope.on = Math.min(c.hope.slots, before + card.level);
    lines.push(`Gained ${c.hope.on - before} Hope.`);
  } else {
    const effect = structuredClone(action.channelRawPower);
    effect.text = `${card.name} (level ${card.level}): +${card.level * 2} damage on one spell.`;
    effect.rollOptions = effect.rollOptions?.map(o => ({ ...o, value: card.level * 2 }));
    addEffect(c, effect, 'Channel Raw Power');
    lines.push(`Prepared +${card.level * 2} spell damage.`);
  }
  return lines;
}

export function chooseActionEffects(c: CharacterSheet, key: string, auto: Automation, action: ActionDef, selected: string[], trait?: TraitName): string[] {
  const config = action.chooseEffects;
  const options = config?.options.filter(o => selected.includes(o.id)) ?? [];
  if (!config || new Set(selected).size !== config.count || selected.length !== config.count || options.length !== config.count)
    throw new Error(`Choose exactly ${config?.count ?? 2} different benefits.`);
  if (options.some(o => o.chooseTrait) && (!trait || !TRAIT_ORDER.includes(trait))) throw new Error('Choose a trait to increase.');
  const blocker = actionBlocker(c, key, auto, action);
  if (blocker) throw new Error(blocker);
  const lines = runActionDetailed(c, key, auto, action, action.label)!;
  for (const option of options) {
    const effect = structuredClone(option.effect);
    if (option.chooseTrait) {
      effect.name += ` (${trait})`;
      effect.modifiers = effect.modifiers?.map(m => ({ ...m, stat: m.stat?.replace('@choice', trait!) }));
    }
    addEffect(c, effect, action.label);
    lines.push(`Gained ${effect.name}: ${option.label}${option.chooseTrait ? ` (${trait})` : ''}.`);
  }
  return lines;
}

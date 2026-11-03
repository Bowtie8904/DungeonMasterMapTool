// Subtle left-side overlay listing the player's active effects, grouped by character.

import { el } from './login';
import { rich } from './daggerheart/rich';
import type { CharacterSheet } from './daggerheart/data';
import type { ActiveEffect } from './daggerheart/engine';

export interface EffectGroup {
  actorId: string;
  name: string;
  character: CharacterSheet;
}

export interface EffectSource {
  title: string;
  text: string;
}

/** The action that started the effect, with its description when available. */
export function effectSource(c: CharacterSheet, e: ActiveEffect): EffectSource | null {
  const name = e.source?.trim().toLowerCase();
  let sourceText: string | undefined;
  if (name) {
    const same = (n: string) => n.trim().toLowerCase() === name;
    const feature = [...c.features, ...c.heritage].find((f) => same(f.name));
    if (feature?.text) sourceText = feature.text;
    const card = c.loadout.find((d) => same(d.name));
    if (card?.text) sourceText = card.text;
    for (const gear of [...c.activeWeapons, ...(c.activeArmor ? [c.activeArmor] : [])]) {
      if (gear.feature?.text && (same(gear.feature.name) || same(gear.name))) sourceText = gear.feature.text;
    }
    const item = c.inventory.find((i) => same(i.name));
    if (item?.note) sourceText = item.note;
  }
  const text = [e.text, sourceText].filter((value): value is string => !!value?.trim());
  if (!e.source?.trim() && !text.length) return null;
  return {
    title: e.source?.trim() ? `From ${e.source.trim()}` : e.name,
    text: [...new Set(text)].join('\n\n'),
  };
}

export function renderEffects(
  host: HTMLElement,
  groups: EffectGroup[],
  expanded: Set<string>,
  onRemove: (group: EffectGroup, effect: ActiveEffect) => void,
  onToggle: () => void,
): void {
  const blocks: HTMLElement[] = [];
  for (const g of groups) {
    const effects = g.character.effects ?? [];
    if (!effects.length) continue;
    const rows = effects.map((e) => {
      const key = `${g.actorId}:${e.id}`;
      const open = expanded.has(key);
      const label = el('button', { class: 'effect-name', type: 'button', title: open ? 'Hide source' : 'Show source' }, e.name);
      label.setAttribute('aria-expanded', String(open));
      label.addEventListener('click', () => {
        if (expanded.has(key)) expanded.delete(key); else expanded.add(key);
        onToggle();
      });
      const remove = el('button', { class: 'effect-remove', type: 'button', title: `Remove ${e.name}` }, '\u00D7');
      remove.setAttribute('aria-label', `Remove ${e.name}`);
      remove.addEventListener('click', () => { expanded.delete(key); onRemove(g, e); });
      const row = el('div', { class: `effect-row ${e.kind}${open ? ' open' : ''}` }, el('div', { class: 'effect-head' }, label, remove));
      if (open) {
        const src = effectSource(g.character, e);
        row.append(el('div', { class: 'effect-source' },
          ...(src ? [el('div', { class: 'effect-source-title' }, src.title),
            ...(src.text ? [rich(src.text)] : [el('span', { class: 'muted' }, 'No description.')])]
            : [el('span', { class: 'muted' }, 'No description.')])));
      }
      return row;
    });
    blocks.push(el('div', { class: 'effect-group' }, el('div', { class: 'effect-group-name' }, g.name), ...rows));
  }
  host.replaceChildren(...blocks);
}

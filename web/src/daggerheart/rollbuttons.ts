// Buttons shown under a roll in the chat, only to the player who rolled it:
// Damage on attacks, and the roll triggers (rerolls, Hope/Stress on matching dice) of the character's features.

import { el } from '../login';
import type { RollMessage, RollSource } from '../protocol';
import { rollSourceFor } from './roll-source';
import type { CharacterSheet } from './data';
import { actionBlocker, availableRollTriggers, costText, runAction } from './engine';
import type { AvailableRollTrigger, RollContext } from './engine';
import type { RollRequest, SheetBus } from './view';

export interface RollButtonSheet {
  character: CharacterSheet;
  bus: SheetBus;
  save: () => void;
}

export interface RollButtonHost {
  sheet(actorId: string): Promise<RollButtonSheet | undefined>;
  send(roll: RollRequest): boolean;
  log(text: string, rollSource?: RollSource): void;
  toast(message: string): void;
}

/** Context of a finished duality roll, or null when the message is not a duality roll. */
export function rollContext(m: RollMessage): RollContext | null {
  const values = Array.isArray(m.rolls) ? m.rolls.filter((v): v is number => typeof v === 'number') : [];
  if (m.kind !== 'duality' || values.length < 2) return null;
  return { source: m.source ?? '', hope: values[0], fear: values[1], total: m.total };
}

function newRef(): string {
  return Math.random().toString(36).slice(2, 12);
}

/** Request repeating the roll (optionally with advantage) or rolling only the Hope Die again. */
export function rerollRequest(m: RollMessage, t: AvailableRollTrigger, original?: RollRequest): RollRequest | null {
  const label = `${m.label ?? 'Roll'} - ${t.trigger.label}`.slice(0, 80);
  const base = { ref: newRef(), actorId: m.actorId, weapon: m.weapon, rollSource: m.rollSource ?? original?.rollSource };
  if (t.trigger.reroll === 'hope') return { ...base, label, expression: '1d12', source: 'reroll' };
  if (!original) return null;
  const advantage = t.trigger.advantage;
  return {
    ...original, ...base, label, source: m.source,
    expression: original.expression + (advantage ? '+1d6' : ''),
    contributions: [...(original.contributions ?? []), ...(advantage ? [{ label: `Advantage - ${t.source}`.slice(0, 120), expression: '1d6' }] : [])],
  };
}

export function rollButtons(m: RollMessage, host: RollButtonHost): HTMLElement | null {
  if (!m.actorId || !m.source) return null;
  const actorId = m.actorId;
  const ctx = rollContext(m);
  const isAttack = m.source.split(',').includes('attack');
  if (!(ctx || (isAttack && m.weapon))) return null;
  const box = el('div', { class: 'roll-actions' });
  const used = new Set<string>();

  const paint = (sheet: RollButtonSheet) => {
    const c = sheet.character;
    const buttons: HTMLElement[] = [];
    if (isAttack && m.weapon) {
      const damage = el('button', { class: 'small primary', type: 'button', title: `Roll damage for ${m.weapon}` }, 'Damage');
      damage.addEventListener('click', () => { if (!sheet.bus.damage?.(m.weapon!, !!ctx && ctx.hope === ctx.fear)) host.toast(`${m.weapon} is no longer equipped.`); });
      buttons.push(damage);
    }
    for (const t of ctx ? availableRollTriggers(c, ctx) : []) {
      const id = `${t.key}:${t.trigger.id}`;
      const cost = costText(t.trigger.cost, c);
      const b = el('button', { class: 'small', type: 'button' }, `${t.trigger.label}${cost ? ` (${cost})` : ''}`);
      const block = used.has(id) ? 'Already used on this roll' : actionBlocker(c, t.key, t.automation, t.trigger);
      b.title = block ?? t.trigger.description ?? t.source;
      b.disabled = !!block;
      b.addEventListener('click', () => {
        const current = actionBlocker(c, t.key, t.automation, t.trigger);
        if (current) { host.toast(current); paint(sheet); return; }
        const complete = () => {
          const message = runAction(c, t.key, t.automation, t.trigger, `${t.source} - ${t.trigger.label}`);
          if (message === null) return;
          if (t.trigger.endEffect) c.effects = c.effects.filter((e) => e.name !== t.trigger.endEffect);
          used.add(id);
          if (!t.trigger.damage) host.log(`${c.name}: ${message}`, rollSourceFor(c, t.key));
          sheet.bus.notify();
          sheet.save();
          paint(sheet);
        };
        if (t.trigger.damage) {
          if (!sheet.bus.spellDamage?.(t.source, t.trigger.damage, !!ctx && ctx.hope === ctx.fear, complete, rollSourceFor(c, t.key) ?? m.rollSource)) host.toast('The character sheet is not open.');
          return;
        }
        if (t.trigger.reroll) {
          const request = rerollRequest(m, t, m.ref ? sheet.bus.sent.get(m.ref) : undefined);
          if (!request) { host.toast('The original roll is no longer available to repeat.'); return; }
          if (!host.send(request)) { host.toast('Not connected. The reroll was not sent and nothing was spent.'); return; }
          sheet.bus.sent.set(request.ref!, request);
        }
        complete();
      });
      buttons.push(b);
    }
    box.replaceChildren(...buttons);
    box.hidden = buttons.length === 0;
  };

  box.hidden = true;
  void host.sheet(actorId).then((sheet) => { if (sheet) paint(sheet); }).catch(() => {});
  return box;
}

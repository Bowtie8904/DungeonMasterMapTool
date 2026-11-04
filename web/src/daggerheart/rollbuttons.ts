// Buttons shown under a roll in the chat, only to the player who rolled it:
// Damage on attacks, and the roll triggers (rerolls, Hope/Stress on matching dice) of the character's features.

import { el } from '../login';
import { claimRollReward, useFeature } from '../api';
import type { RollMessage, RollSource } from '../protocol';
import type { CharacterSheet } from './data';
import { availableRollTriggers, costText, usesLeft } from './engine';
import type { AvailableRollTrigger, RollContext } from './engine';
import type { RollRequest, SheetBus } from './view';

export interface RollButtonSheet {
  character: CharacterSheet;
  bus: SheetBus;
  save: (() => void) & { flush?: () => Promise<void> };
}

export interface RollButtonHost {
  sheet(actorId: string): Promise<RollButtonSheet | undefined>;
  actorIds?(): string[];
  send(roll: RollRequest): boolean;
  log(text: string, rollSource?: RollSource): void;
  toast(message: string): void;
  playerId?: string;
  supportsRollRewards?: () => boolean;
}

/** Context of a finished duality roll, or null when the message is not a duality roll. */
export function rollContext(m: RollMessage): RollContext | null {
  const values = Array.isArray(m.rolls) ? m.rolls.filter((v): v is number => typeof v === 'number') : [];
  if (m.kind !== 'duality' || values.length < 2) return null;
  return { source: m.source ?? '', hope: values[0], fear: values[1], total: m.total, outcome: m.outcome } as RollContext;
}

function newRef(): string {
  return Math.random().toString(36).slice(2, 12);
}

/** Request repeating the roll (optionally with advantage) or rolling only the Hope Die again. */
export function rerollRequest(m: RollMessage, t: AvailableRollTrigger, original?: RollRequest): RollRequest | null {
  const label = `${m.label ?? 'Roll'} - ${t.trigger.label}`.slice(0, 80);
  const base = { ref: newRef(), actorId: m.actorId, weapon: m.weapon, rollSource: m.rollSource ?? original?.rollSource };
  if (t.trigger.reroll === 'hope') return { ...base, label, expression: '1d12', source: 'reroll' };
  original ??= {
    label: m.label ?? 'Roll',
    expression: m.expression,
    kind: m.kind === 'duality' ? 'duality' : undefined,
    contributions: m.breakdown?.map(({ label: name, expression, notes }) => ({ label: name, expression, notes })),
    rollSource: m.rollSource,
    actorId: m.actorId,
    source: m.source,
    weapon: m.weapon,
  };
  const advantage = t.trigger.advantage;
  return {
    ...original, ...base, label, source: m.source,
    expression: original.expression + (advantage ? '+1d6' : ''),
    contributions: [...(original.contributions ?? []), ...(advantage ? [{ label: `Advantage - ${t.source}`.slice(0, 120), expression: '1d6' }] : [])],
  };
}

export function rollButtons(m: RollMessage, host: RollButtonHost): HTMLElement | null {
  const actorId = m.actorId;
  const ctx = rollContext(m);
  const isAttack = m.source?.split(',').includes('attack');
  if (!(ctx || (actorId && isAttack && m.weapon))) return null;
  const box = el('div', { class: 'roll-actions' });
  let claiming = false;

  const paint = (sheets: Array<{ actorId: string; sheet: RollButtonSheet }>) => {
    const buttons: HTMLElement[] = [];
    const rolledActor = actorId ? sheets.find((entry) => entry.actorId === actorId) : undefined;
    const critical = !!ctx && ctx.hope === ctx.fear;
    const withHope = !!ctx && (critical || (ctx.outcome ?? (ctx.hope > ctx.fear ? 'hope' : 'fear')) === 'hope');
    if (rolledActor && withHope && m.fromId === host.playerId && host.supportsRollRewards?.() && m.rewardClaimed === false) {
      const { character: c, save } = rolledActor.sheet;
      const reward = el('button', { class: 'small primary', type: 'button' },
        critical ? 'Gain 1 Hope / Clear 1 Stress' : 'Gain 1 Hope');
      reward.disabled = claiming || (c.hope.on >= c.hope.slots && (!critical || c.stress.on <= 0));
      reward.title = reward.disabled ? 'Already at maximum Hope with no available reward' : 'Claim this roll reward once';
      reward.addEventListener('click', async () => {
        if (claiming || m.rewardClaimed) return;
        claiming = true;
        reward.disabled = true;
        try {
          await save.flush?.();
          await claimRollReward(rolledActor.actorId, { id: m.id, ts: m.ts });
          m.rewardClaimed = true;
          await paintFor();
        } catch (error) {
          host.toast((error as Error).message);
        } finally {
          claiming = false;
          if (!m.rewardClaimed) void paintFor().catch((error: Error) => host.toast(error.message));
        }
      });
      buttons.push(reward);
    }
    if (rolledActor && isAttack && m.weapon) {
      const damage = el('button', { class: 'small primary', type: 'button', title: `Roll damage for ${m.weapon}` }, 'Damage');
      damage.addEventListener('click', () => { if (!rolledActor.sheet.bus.damage?.(m.weapon!, !!ctx && ctx.hope === ctx.fear)) host.toast(`${m.weapon} is no longer equipped.`); });
      buttons.push(damage);
    }

    for (const { actorId: featureActorId, sheet } of sheets) {
      const c = sheet.character;
      for (const t of ctx ? availableRollTriggers(c, ctx) : []) {
        if (usesLeft(c, t.key, t.automation) === 0) continue;
        const trigger = t.trigger as typeof t.trigger & {
          audience?: string; outcome?: 'hope' | 'fear'; target?: 'actor' | 'party';
        };
        const actualOutcome = ctx ? (ctx.hope === ctx.fear ? 'crit' : ctx.outcome ?? (ctx.hope > ctx.fear ? 'hope' : 'fear')) : undefined;
        if (trigger.outcome && (actualOutcome === 'crit' || actualOutcome === trigger.outcome)) continue;
        const audience = trigger.audience ?? 'self';
        if (audience === 'self' && featureActorId !== actorId) continue;
        if (audience === 'ally' && featureActorId === actorId) continue;
        if (m.featureUses?.includes(`${featureActorId}:${t.key}:${t.trigger.id}`)) continue;
        const cost = costText(t.trigger.cost, c);
        const b = el('button', { class: 'small', type: 'button' },
          `${t.trigger.label}${cost ? ` (${cost})` : ''}${featureActorId !== actorId ? ` · ${c.name}` : ''}`);
        b.title = t.trigger.description ?? t.source;
        b.addEventListener('click', async () => {
          b.disabled = true;
          try {
            await sheet.save.flush?.();
            await useFeature({
              actorId: featureActorId,
              key: t.key,
              actionId: t.trigger.id,
              roll: { id: m.id, ts: m.ts },
            });
            if (t.trigger.reroll) {
              const request = rerollRequest(m, t);
              if (!request) throw new Error('The original roll is no longer available to repeat.');
              if (!host.send(request)) throw new Error('Not connected. The reroll was not sent.');
            }
          } catch (error) {
            host.toast((error as Error).message);
            b.disabled = false;
          }
        });
        buttons.push(b);
      }
    }
    box.replaceChildren(...buttons);
    box.hidden = buttons.length === 0;
  };

  const paintFor = async () => {
    const ids = new Set([...(host.actorIds?.() ?? []), ...(actorId ? [actorId] : [])]);
    const values = await Promise.all([...ids].map(async (id) => {
      const sheet = await host.sheet(id);
      return sheet ? { actorId: id, sheet } : undefined;
    }));
    paint(values.filter((value): value is { actorId: string; sheet: RollButtonSheet } => !!value));
  };

  box.hidden = true;
  box.addEventListener('refresh-roll-actions', () => { void paintFor().catch((error: Error) => host.toast(error.message)); });
  void paintFor().catch((error: Error) => host.toast(error.message));
  return box;
}

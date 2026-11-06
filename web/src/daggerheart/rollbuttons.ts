// Buttons shown under a roll in the chat, only to the player who rolled it:
// Damage on attacks, and the roll triggers (rerolls, Hope/Stress on matching dice) of the character's features.

import { el } from '../login';
import { applyDeathRecovery, claimRollReward, useFeature } from '../api';
import { openDeathRecovery } from './death-view';
import type { ChatMessage, RollMessage, RollSource } from '../protocol';
import type { CharacterSheet } from './data';
import { actionBlocker, availableRollTriggers, costText, usesLeft } from './engine';
import type { AvailableRollTrigger, RollContext } from './engine';
import type { RollRequest, SheetBus } from './view';
import { criticalMeleeBonus } from './brawler';

export interface RollButtonSheet {
  character: CharacterSheet;
  bus: SheetBus;
  save: (() => void) & { flush?: () => Promise<void>; refresh?: () => Promise<void> };
}

export interface RollButtonHost {
  sheet(actorId: string): Promise<RollButtonSheet | undefined>;
  actorIds?(): string[];
  send(roll: RollRequest): boolean;
  log(text: string, rollSource?: RollSource): void;
  toast(message: string): void;
  playerId?: string;
  supportsRollRewards?: () => boolean;
  supportsPartyFeatures?: () => boolean;
  supportsGuardianRolls?: () => boolean;
  sessionId?: () => string;
  supportsDeathMoveOptions?: () => boolean;
}

/** Context of a finished duality or damage roll. */
export function rollContext(m: RollMessage): RollContext | null {
  if (m.deathMove) return null;
  if (m.kind !== 'duality' && m.source?.split(',').includes('damage')) {
    return { source: m.source, hope: 0, fear: 0, total: m.total, weapon: m.weapon, kind: 'damage' };
  }
  const values = Array.isArray(m.rolls) ? m.rolls.filter((v): v is number => typeof v === 'number') : [];
  if (m.kind !== 'duality' || values.length < 2) return null;
  return { source: m.source ?? '', hope: values[0], fear: values[1], total: m.total, outcome: m.outcome, weapon: m.weapon, difficulty: m.difficulty } as RollContext;
}

function newRef(): string {
  return Math.random().toString(36).slice(2, 12);
}

function openTokenGain(t: AvailableRollTrigger, cost: string, submit: (amount: number) => Promise<void>, onClose: () => void): void {
  const max = t.trigger.tokenGain!.max;
  const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: t.trigger.label });
  const amount = el('input', { type: 'number', min: '1', max: String(max), step: '1', value: '1', ariaLabel: 'HP marked by the target' });
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const go = el('button', { type: 'button', class: 'primary' }, cost ? `${t.trigger.label} (${cost})` : t.trigger.label);
  let pending = false;
  cancel.addEventListener('click', () => dialog.close());
  dialog.addEventListener('cancel', (event) => { if (pending) event.preventDefault(); });
  dialog.addEventListener('close', () => { dialog.remove(); onClose(); }, { once: true });
  go.addEventListener('click', async () => {
    if (pending) return;
    const n = amount.valueAsNumber;
    if (!Number.isInteger(n) || n < 1 || n > max) {
      error.textContent = `Enter a whole number from 1 to ${max}.`;
      return;
    }
    pending = true;
    go.disabled = cancel.disabled = amount.disabled = true;
    error.textContent = '';
    try { await submit(n); dialog.close(); }
    catch (e) { error.textContent = (e as Error).message; }
    finally { pending = false; go.disabled = cancel.disabled = amount.disabled = false; }
  });
  dialog.append(el('h4', {}, t.trigger.label),
    el('p', { class: 'muted' }, t.trigger.description ?? 'Enter the tokens gained.'),
    el('label', {}, `HP marked by the target (1-${max})`, amount), error, el('div', { class: 'acts' }, cancel, go));
  document.body.append(dialog);
  dialog.showModal();
  amount.focus();
  amount.select();
}

/** Request repeating the roll (optionally with advantage) or rolling only the Hope Die again. */
export function rerollRequest(m: RollMessage, t: AvailableRollTrigger, original?: RollRequest): RollRequest | null {
  const label = `${m.label ?? 'Roll'} - ${t.trigger.label}`.slice(0, 80);
  const base = { ref: newRef(), actorId: m.actorId, weapon: m.weapon, rollSource: m.rollSource ?? original?.rollSource };
  if (t.trigger.reroll === 'hope') return { ...base, label, expression: m.expression.startsWith('1d20+') ? '1d20' : '1d12',
    source: m.source?.split(',').includes('reaction') ? 'reroll,reaction' : 'reroll' };
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

function benefitButtons(m: ChatMessage, host: RollButtonHost): HTMLElement | null {
  const claims = m.actionBenefits;
  if (!claims || !host.supportsPartyFeatures?.() || !host.playerId || m.fromId === host.playerId && !claims.allowSourceOwner
      || claims.sessionId !== host.sessionId?.()) return null;
  const box = el('div', { class: 'roll-actions' });
  let pending = false;
  const paint = async () => {
    const buttons: HTMLButtonElement[] = [];
    if (!claims.claimedBy.includes(host.playerId!) && !(claims.options.some(o => o.exclusiveClaim) && claims.claimedBy.length)) {
      for (const actorId of host.actorIds?.() ?? []) {
        if (!claims.actorIds.includes(actorId)) continue;
        const sheet = await host.sheet(actorId);
        if (!sheet) continue;
        for (const benefit of claims.options) {
          const button = el('button', { type: 'button', class: 'small' }, `${benefit.label} · ${sheet.character.name}`);
          button.disabled = pending;
          button.addEventListener('click', async () => {
            if (pending) return;
            pending = true;
            buttons.forEach(b => { b.disabled = true; });
            try {
              await sheet.save.flush?.();
              await useFeature({ actorId, key: '', actionId: 'claim', benefitId: benefit.id, roll: { id: m.id, ts: m.ts } });
              if (!claims.claimedBy.includes(host.playerId!)) claims.claimedBy.push(host.playerId!);
              if (benefit.featureAction) {
                await sheet.save.refresh?.();
                if (!sheet.bus.featureAction?.(benefit.featureAction.name, benefit.featureAction.id))
                  host.toast('Cannonball is prepared. Open its effect on your sheet to make the throw.');
              }
            } catch (e) { host.toast((e as Error).message); }
            finally { pending = false; await paint(); }
          });
          buttons.push(button);
        }
      }
    }
    box.replaceChildren(...buttons);
    box.hidden = !buttons.length;
  };
  box.addEventListener('refresh-roll-actions', () => { void paint().catch((e: Error) => host.toast(e.message)); });
  void paint().catch((e: Error) => host.toast(e.message));
  return box;
}

function restButtons(m: ChatMessage, host: RollButtonHost): HTMLElement | null {
  if (!m.restDice?.length || !m.actorId) return null;
  const box = el('div', { class: 'roll-actions' });
  let claiming = false;
  const paint = async () => {
    const buttons: HTMLButtonElement[] = [];
    for (const actorId of host.actorIds?.() ?? []) {
      const sheet = await host.sheet(actorId);
      if (!sheet) continue;
      for (const feature of [...sheet.character.features, ...sheet.character.heritage].filter(f => f.automation?.restReroll)) {
        const key = `feat:${feature.name}`;
        if (usesLeft(sheet.character, key, feature.automation!) === 0) continue;
        m.restDice!.forEach((die, index) => {
          if (die.used) return;
          const resource = die.resource === 'hp' ? 'HP' : die.resource === 'stress' ? 'Stress' : die.resource === 'armor' ? 'Armor' : undefined;
          const button = el('button', { type: 'button', class: 'small' },
            `${feature.name}: reroll ${resource ?? `die ${index + 1}`} ${die.expression} · ${sheet.character.name}`);
          button.disabled = claiming;
          button.addEventListener('click', async () => {
            if (claiming) return;
            claiming = true;
            buttons.forEach(b => { b.disabled = true; });
            try {
              await sheet.save.flush?.();
              await useFeature({ actorId, key, actionId: 'reroll', roll: { id: m.id, ts: m.ts }, restDie: index });
              die.used = true;
            } catch (e) { host.toast((e as Error).message); }
            finally {
              claiming = false;
              await paint();
            }
          });
          buttons.push(button);
        });
      }
    }
    box.replaceChildren(...buttons);
    box.hidden = !buttons.length;
  };
  box.addEventListener('refresh-roll-actions', () => { void paint().catch((e: Error) => host.toast(e.message)); });
  void paint().catch((e: Error) => host.toast(e.message));
  return box;
}

function deathButtons(m: RollMessage, host: RollButtonHost): HTMLElement | null {
  if (m.deathMove !== 'risk' || !m.deathResult || !m.actorId || m.fromId !== host.playerId || !host.supportsDeathMoveOptions?.()) return null;
  const box = el('div', { class: 'roll-actions' });
  let pending = false;
  const paint = async () => {
    const sheet = await host.sheet(m.actorId!);
    box.replaceChildren();
    const result = m.deathResult!;
    if (!sheet || result.claimed || ((sheet.character.permanentHp ?? 0) >= sheet.character.hp.slots)) return;
    const ref = sheet.character.pendingDeathRoll;
    if (!ref || ref.id !== m.id || ref.ts !== m.ts) return;
    const failed = result.outcome === 'fear' && !result.converted;
    if (failed && !result.undying) return;
    const button = el('button', { type: 'button', class: 'small primary' }, failed ? "Won't Stay Dead" : result.outcome === 'crit' ? 'Clear all HP and Stress' : 'Apply recovery');
    button.disabled = pending;
    button.addEventListener('click', () => {
      if (pending || result.claimed) return;
      pending = true;
      button.disabled = true;
      openDeathRecovery(document.body, sheet.character, m, async recovery => {
        await sheet.save.flush?.();
        const current = sheet.character.pendingDeathRoll;
        if (!current || current.id !== m.id || current.ts !== m.ts) throw new Error('This death roll is no longer pending. Refresh the sheet and check chat for the resolved result.');
        await applyDeathRecovery(m.actorId!, { id: m.id, ts: m.ts }, recovery);
        if (recovery.undying) result.converted = true;
        else result.claimed = true;
        sheet.bus.notify();
      }, () => {
        pending = false;
        void paint().catch((e: Error) => host.toast(e.message));
      });
    });
    box.append(button);
  };
  box.addEventListener('refresh-roll-actions', () => { void paint().catch((e: Error) => host.toast(e.message)); });
  void paint().catch((e: Error) => host.toast(e.message));
  return box;
}

export function rollButtons(m: RollMessage | ChatMessage, host: RollButtonHost): HTMLElement | null {
  if ('text' in m) return benefitButtons(m, host) ?? restButtons(m, host);
  if (m.deathMove) return deathButtons(m, host);
  const actorId = m.actorId;
  const ctx = rollContext(m);
  const isAttack = m.source?.split(',').includes('attack');
  if (!(ctx || (actorId && isAttack && m.weapon))) return null;
  const box = el('div', { class: 'roll-actions' });
  let claiming = false;

  const paint = (sheets: Array<{ actorId: string; sheet: RollButtonSheet }>) => {
    const buttons: HTMLElement[] = [];
    const rolledActor = actorId ? sheets.find((entry) => entry.actorId === actorId) : undefined;
    const critical = !!ctx && ctx.kind !== 'damage' && ctx.hope === ctx.fear;
    const withHope = !!ctx && ctx.kind !== 'damage' && !ctx.source.split(',').includes('reaction')
      && (critical || (ctx.outcome ?? (ctx.hope > ctx.fear ? 'hope' : 'fear')) === 'hope');
    if (rolledActor && withHope && m.fromId === host.playerId && host.supportsRollRewards?.() && m.rewardClaimed === false) {
      const { character: c, save } = rolledActor.sheet;
      const extra = criticalMeleeBonus(c, m.weapon, critical);
      const reward = el('button', { class: 'small primary', type: 'button' },
        critical ? `Gain ${1 + extra.hope} Hope / Clear ${1 + extra.stress} Stress` : 'Gain 1 Hope');
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
      damage.addEventListener('click', () => {
        const proficiency = Number(m.source?.split(',').find(tag => /^proficiency:\d+$/.test(tag))?.split(':')[1] ?? 0);
        if (!rolledActor.sheet.bus.damage?.(m.weapon!, critical, ctx?.kind !== 'damage' ? ctx?.fear : undefined, proficiency)) host.toast(`${m.weapon} is no longer equipped.`);
      });
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
        const blocked = actionBlocker(c, t.key, t.automation, t.trigger);
        const unsupported = (t.trigger.advanceEffect || t.trigger.swapDuality) && !host.supportsGuardianRolls?.()
          ? 'Update the host to use Guardian roll actions.'
          : t.trigger.addDice && !host.supportsPartyFeatures?.() ? 'Update the host to add dice to finished rolls.' : undefined;
        b.disabled = !!blocked || !!unsupported;
        b.title = unsupported ?? blocked ?? t.trigger.description ?? t.source;
        b.addEventListener('click', async () => {
          if (b.disabled) return;
          b.disabled = true;
          if (t.trigger.damage && !t.trigger.damage.fixed) {
            const opened = sheet.bus.spellDamage?.(t.trigger.label, t.trigger.damage, critical, () => { sheet.save(); }, m.rollSource);
            if (!opened) host.toast('Open this character sheet to roll feature damage.');
            b.disabled = false;
            return;
          }
          if (t.trigger.tokenGain) {
            openTokenGain(t, cost, async (amount) => {
              await sheet.save.flush?.();
              await useFeature({ actorId: featureActorId, key: t.key, actionId: t.trigger.id, roll: { id: m.id, ts: m.ts }, amount });
            }, () => { b.disabled = false; });
            return;
          }
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

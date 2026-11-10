import { el } from '../login';
import { hostFetch, type FeatureRequest } from '../api';
import type { Actor } from '../protocol';
import type { CharacterSheet } from './data';
import type { ActionDef, RollContext } from './engine';
import { rich } from './rich';

export interface SplendorCandidates {
  allies: Array<{ actorId: string; actorName: string; hp: number }>;
  features: Array<{ actorId: string; actorName: string; key: string; name: string; text: string; used: number; max: number; per: string }>;
}

export type SplendorChoice = Pick<FeatureRequest, 'choiceId' | 'targetActorId' | 'amount' | 'allocations' | 'restoreActorId' | 'restoreKey'>;

export function healingBudget(c: CharacterSheet): number {
  return Math.max(0, c.stress.slots - c.stress.on);
}

export async function fetchSplendorCandidates(actorId: string): Promise<SplendorCandidates> {
  const response = await hostFetch(`/api/splendor/${encodeURIComponent(actorId)}`, { credentials: 'same-origin' });
  if (!response.ok) {
    const body = await response.json() as { error?: string };
    throw new Error(body.error ?? 'Could not load eligible Splendor targets.');
  }
  return response.json();
}

export async function openSplendorAction(c: CharacterSheet, actorId: string, action: ActionDef, actors: Actor[],
  submit: (choice: SplendorChoice) => Promise<void>, context?: RollContext): Promise<void> {
  const kind = action.splendorAction;
  if (kind === 'zone' || kind === 'resurrection') { await submit({}); return; }
  const candidates = kind === 'salvation' || kind === 'invigoration' ? await fetchSplendorCandidates(actorId) : undefined;
  await chooseSplendorAction(c, actorId, action, actors, submit, context, candidates);
}

export function chooseSplendorAction(c: CharacterSheet, actorId: string, action: ActionDef, actors: Actor[],
  submit: (choice: SplendorChoice) => Promise<void>, context?: RollContext, candidates?: SplendorCandidates): Promise<void> {
  return new Promise(resolve => {
    const dialog = el('dialog', { class: 'dh dh-rolldlg dh-copycat-dialog', ariaLabel: action.label });
    const fields = el('div', { class: 'dh-copycat-list' });
    const error = el('p', { class: 'error', role: 'alert' });
    const status = el('p', { class: 'muted', role: 'status', ariaLive: 'polite' });
    const cancel = el('button', { type: 'button' }, 'Cancel');
    const apply = el('button', { type: 'button', class: 'primary' }, 'Apply');
    let busy = false;
    let choice = (): SplendorChoice => ({});
    let valid = () => true;
    const update = () => { apply.disabled = busy || !valid(); };
    const kind = action.splendorAction;
    if (kind === 'healingHands') {
      const eligible = actors.filter(actor => !actor.disabled && actor.ownerId && actor.id !== actorId);
      const target = el('select', { ariaLabel: 'Healing target' }, ...eligible.map(actor => el('option', { value: actor.id }, actor.name)));
      target.value = eligible[0]?.id ?? '';
      fields.append(el('label', {}, 'Target within Melee range', target));
      choice = () => ({ targetActorId: target.value });
      valid = () => eligible.some(actor => actor.id === target.value);
      status.textContent = 'This target cannot receive Healing Hands again until they finish a long rest.';
    } else if (kind === 'secondWind') {
      const benefit = el('select', { ariaLabel: 'Healing benefit' },
        el('option', { value: 'hp' }, 'Clear 1 HP'),
        el('option', { value: 'stress' }, 'Clear 3 Stress'));
      benefit.value = 'hp';
      fields.append(el('label', {}, 'Benefit', benefit));
      const hope = context && (context.hope === context.fear || (context.outcome ?? (context.hope > context.fear ? 'hope' : 'fear')) === 'hope');
      const selectTarget = !!hope;
      const eligible = actors.filter(actor => !actor.disabled && actor.ownerId);
      const target = el('select', { ariaLabel: 'Healing target' }, ...eligible.map(actor => el('option', { value: actor.id }, actor.name)));
      target.value = eligible.some(actor => actor.id === actorId) ? actorId : eligible[0]?.id ?? '';
      if (selectTarget) fields.append(el('label', {}, 'Target', target));
      choice = () => ({ choiceId: benefit.value, targetActorId: selectTarget ? target.value : actorId });
      valid = () => !selectTarget || eligible.some(actor => actor.id === target.value);
      status.textContent = hope ? 'Choose yourself or an ally within Close range.' : 'Recover on yourself after a successful attack.';
    } else if (kind === 'sunlight') {
      const amount = el('input', { type: 'number', min: '1', max: String(c.hope.on), value: '1', step: '1', ariaLabel: 'Hope to spend' });
      fields.append(el('label', {}, 'Hope to spend (one per target)', amount));
      choice = () => ({ amount: amount.valueAsNumber });
      valid = () => Number.isInteger(amount.valueAsNumber) && amount.valueAsNumber > 0 && amount.valueAsNumber <= c.hope.on;
      amount.addEventListener('input', update);
      status.textContent = 'Roll both damage results: 3d20+3 for successful reactions, 4d20+5 and Stunned for failed reactions.';
      apply.textContent = 'Spend Hope and roll';
    } else if (kind === 'salvation') {
      const budget = healingBudget(c);
      const rows = (candidates?.allies ?? []).filter(actor => actor.actorId !== actorId && actor.hp > 0).map(actor => {
        const max = Math.min(actor.hp, budget);
        const input = el('input', { type: 'number', min: '0', max: String(max), value: '0', step: '1', ariaLabel: `${actor.actorName} HP healing` });
        const slider = el('input', { type: 'range', min: '0', max: String(max), value: '0', step: '1', ariaLabel: `${actor.actorName} HP healing slider` });
        fields.append(el('label', {}, `${actor.actorName} (${actor.hp} marked HP)`, input, slider));
        return { actor, input, slider, max };
      });
      const total = () => rows.reduce((sum, row) => sum + row.input.valueAsNumber, 0);
      valid = () => total() > 0 && total() <= budget && rows.every(row =>
        Number.isInteger(row.input.valueAsNumber) && row.input.valueAsNumber >= 0 && row.input.valueAsNumber <= row.max);
      const paint = () => {
        status.textContent = `Stress cost: ${total()} / ${budget} available slots`;
        for (const row of rows) row.slider.max = String(Math.max(0, Math.min(row.max, budget - total() + row.input.valueAsNumber)));
        update();
      };
      for (const row of rows) {
        row.input.addEventListener('input', () => { row.slider.value = row.input.value; paint(); });
        row.slider.addEventListener('input', () => { row.input.value = row.slider.value; paint(); });
      }
      choice = () => ({ allocations: rows.filter(row => row.input.valueAsNumber > 0)
        .map(row => ({ actorId: row.actor.actorId, amount: row.input.valueAsNumber })) });
      if (!rows.length) fields.append(el('p', { class: 'muted' }, 'No wounded allies are available.'));
      apply.textContent = 'Mark Stress and heal';
      paint();
    } else if (kind === 'invigoration') {
      const search = el('input', { type: 'search', placeholder: 'Search features or characters', ariaLabel: 'Search features or characters' });
      const rows: Array<{ node: HTMLElement; text: string }> = [];
      let selected: SplendorCandidates['features'][number] | undefined;
      const buttons: HTMLButtonElement[] = [];
      for (const feature of candidates?.features ?? []) {
        const pick = el('button', { type: 'button', class: 'small', ariaPressed: 'false' }, 'Select feature');
        buttons.push(pick);
        pick.addEventListener('click', () => {
          selected = feature;
          buttons.forEach(button => button.setAttribute('aria-pressed', String(button === pick)));
          status.textContent = `Restore one use of ${feature.name} for ${feature.actorName}.`;
          update();
        });
        const node = el('div', { class: 'dcard' }, el('span', { class: 'nm' }, feature.name),
          el('p', { class: 'muted' }, `${feature.actorName} - ${feature.used}/${feature.max} used per ${feature.per.replace(/([A-Z])/g, ' $1').toLowerCase()}`),
          rich(feature.text), pick);
        rows.push({ node, text: `${feature.actorName} ${feature.name} ${feature.text}`.toLowerCase() });
        fields.append(node);
      }
      const none = el('p', { class: 'muted', hidden: rows.length > 0, role: 'status' }, 'No exhausted features match your search.');
      fields.append(none);
      search.addEventListener('input', () => {
        const terms = search.value.toLowerCase().trim().split(/\s+/).filter(Boolean);
        rows.forEach(row => { row.node.hidden = !terms.every(term => row.text.includes(term)); });
        none.hidden = rows.some(row => !row.node.hidden);
      });
      dialog.append(search);
      choice = () => ({ restoreActorId: selected?.actorId, restoreKey: selected?.key });
      valid = () => !!selected;
      apply.textContent = 'Restore one use';
    }
    cancel.addEventListener('click', () => dialog.close());
    dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
    dialog.addEventListener('close', () => { dialog.remove(); resolve(); }, { once: true });
    apply.addEventListener('click', async () => {
      if (busy || !valid()) return;
      const selected = choice();
      busy = true;
      cancel.disabled = true;
      update();
      error.textContent = '';
      try { await submit(selected); dialog.close(); }
      catch (e) { error.textContent = (e as Error).message; }
      finally { busy = false; cancel.disabled = false; update(); }
    });
    dialog.prepend(el('h4', {}, action.label));
    dialog.append(fields, status, error, el('div', { class: 'acts' }, cancel, apply));
    update();
    document.body.append(dialog);
    dialog.showModal();
  });
}

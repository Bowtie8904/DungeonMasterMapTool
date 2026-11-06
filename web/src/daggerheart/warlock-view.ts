import { el } from '../login';
import type { CharacterSheet } from './data';
import type { ActionDef } from './engine';

export function chooseWarlockAmount(c: CharacterSheet, action: ActionDef, submit: (amount: number) => Promise<void>, closed?: () => void): void {
  const vengeance = action.patronDice === 'vengeance';
  const max = vengeance ? c.hp.slots : c.resources?.favor ?? 0;
  const label = vengeance ? 'HP marked from the attack' : 'Favor to spend / Patron Dice to roll';
  const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: action.label });
  const amount = el('input', { type: 'number', min: '1', max: String(max), step: '1', value: '1', ariaLabel: label });
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const go = el('button', { type: 'button', class: 'primary', disabled: max < 1 }, action.label);
  let busy = false;
  cancel.addEventListener('click', () => dialog.close());
  dialog.addEventListener('cancel', e => { if (busy) e.preventDefault(); });
  dialog.addEventListener('close', () => { dialog.remove(); closed?.(); }, { once: true });
  go.addEventListener('click', async () => {
    if (busy) return;
    const n = amount.valueAsNumber;
    if (!Number.isInteger(n) || n < 1 || n > max) {
      error.textContent = `Enter a whole number from 1 to ${max}.`;
      return;
    }
    busy = true;
    go.disabled = cancel.disabled = amount.disabled = true;
    error.textContent = '';
    try { await submit(n); dialog.close(); }
    catch (e) { error.textContent = (e as Error).message; }
    finally { busy = false; go.disabled = max < 1; cancel.disabled = amount.disabled = false; }
  });
  const description = vengeance ? 'Spend 1 Favor. Roll one Patron Die per HP marked; each 4+ makes the attacker mark 1 HP.'
    : action.patronDice === 'heal' ? 'Spend one Favor per Patron Die; each 4+ clears 1 HP.'
    : action.patronDice === 'ire' ? 'Spend one Favor per Patron Die. The highest result is the number of creatures within Close range that each mark 1 HP.'
    : 'After a successful action roll with Hope against a target, spend Favor to make that target mark an equal amount of Stress.';
  dialog.append(el('h4', {}, action.label), el('p', { class: 'muted' }, description),
    el('label', {}, action.spendFavor ? 'Favor to spend' : label, amount), error, el('div', { class: 'acts' }, cancel, go));
  document.body.append(dialog);
  dialog.showModal();
  amount.focus();
  amount.select();
}

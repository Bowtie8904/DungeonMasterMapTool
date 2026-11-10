import { el } from '../login';
import { amountAvailable, type ActionDef } from './engine';
import type { CharacterSheet } from './data';

/** Shared by card casts and result actions; cancellation never spends resources. */
export function chooseActionAmount(c: CharacterSheet, action: ActionDef): Promise<number | undefined> {
  const resource = action.selectAmount?.resource ?? action.castTokens?.resource ?? action.resourceRoll!.resource;
  const min = action.selectAmount?.min ?? 1;
  const label = action.selectAmount?.label ?? (action.castTokens ? 'Chaos tokens to spend'
    : resource === 'stress' ? 'Stress to mark' : 'Hope to spend');
  return new Promise(resolve => {
    const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: action.label });
    const max = action.selectAmount?.heal ? 2147483647 : amountAvailable(c, resource);
    const input = el('input', { type: 'number', min: String(min), ...(action.selectAmount?.heal ? {} : { max: String(max) }), step: '1', value: String(min), ariaLabel: label });
    const error = el('div', { class: 'error', role: 'alert' });
    const cancel = el('button', { type: 'button' }, 'Cancel');
    const go = el('button', { type: 'button', class: 'primary', disabled: max < min }, 'Continue');
    let selected: number | undefined;
    cancel.addEventListener('click', () => dialog.close());
    go.addEventListener('click', () => {
      const n = input.valueAsNumber, available = action.selectAmount?.heal ? max : amountAvailable(c, resource);
      if (!Number.isInteger(n) || n < min || n > available) {
        error.textContent = action.selectAmount?.heal ? 'Enter a nonnegative whole number of marked HP.' : `Enter a whole number from ${min} to ${available}.`;
        return;
      }
      selected = n;
      dialog.close();
    });
    dialog.addEventListener('close', () => { dialog.remove(); resolve(selected); }, { once: true });
    dialog.append(el('h4', {}, action.label), el('label', {}, label, input), error, el('div', { class: 'acts' }, cancel, go));
    document.body.append(dialog);
    dialog.showModal();
    input.focus();
    input.select();
  });
}

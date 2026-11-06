import { el } from '../login';
import type { CharacterSheet } from './data';

export function chooseTalismanHope(c: CharacterSheet, submit: (amount: number) => Promise<void>): void {
  const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: 'Enchanted Talisman' });
  const amount = el('input', { type: 'number', min: '1', max: String(c.hope.on), step: '1', value: '1', ariaLabel: 'Hope to spend' });
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const go = el('button', { type: 'button', class: 'primary' }, 'Imbue talisman');
  let busy = false;
  cancel.addEventListener('click', () => dialog.close());
  dialog.addEventListener('cancel', e => { if (busy) e.preventDefault(); });
  dialog.addEventListener('close', () => dialog.remove(), { once: true });
  go.addEventListener('click', async () => {
    if (busy) return;
    const n = amount.valueAsNumber;
    if (!Number.isInteger(n) || n < 1 || n > c.hope.on) {
      error.textContent = `Enter a whole number from 1 to ${c.hope.on}.`;
      return;
    }
    busy = true;
    go.disabled = cancel.disabled = amount.disabled = true;
    error.textContent = '';
    try { await submit(n); dialog.close(); }
    catch (e) { error.textContent = (e as Error).message; }
    finally { busy = false; go.disabled = cancel.disabled = amount.disabled = false; }
  });
  dialog.append(el('h4', {}, 'Enchanted Talisman'),
    el('p', { class: 'muted' }, 'Spend Hope to create an equal number of protective tokens. Give the talisman from Loot to another player to transfer its protection.'),
    el('label', {}, 'Hope to spend', amount), error, el('div', { class: 'acts' }, cancel, go));
  document.body.append(dialog);
  dialog.showModal();
  amount.focus();
  amount.select();
}

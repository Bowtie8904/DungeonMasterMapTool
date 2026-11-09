import { el } from '../login';
import type { CharacterSheet } from './data';
import type { ActionDef } from './engine';

export type VitalityBenefit = 'hp' | 'stress' | 'thresholds';
export interface SpecialCardChoice { amount?: number; choices?: VitalityBenefit[] }

export function chooseSpecialCard(c: CharacterSheet, action: ActionDef, fear: () => number,
  submit: (choice: SpecialCardChoice) => Promise<void>): void {
  const vitality = action.specialAction === 'vitality';
  const dialog = el('dialog', { class: 'dh dh-rolldlg dh-option-rolldlg', ariaLabel: action.label });
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const go = el('button', { type: 'button', class: 'primary' }, vitality ? 'Gain two permanent benefits' : 'Steal Fear and roll');
  const body = el('div', { class: 'dh-roll-options' });
  const choices = new Map<VitalityBenefit, HTMLInputElement>();
  const input = el('input', { type: 'number', min: '0', max: String(fear()), step: '1', value: '0', ariaLabel: 'Fear stolen (number of Horrified targets)' });
  if (vitality) {
    body.append(el('p', {}, `Choose two distinct benefits. ${c.testingCharacter
      ? 'Testing: this card stays in the fixed loadout, permanently used and unavailable after this choice.'
      : 'This card will remain permanently in your vault.'}`));
    for (const [id, label] of [['stress', 'One Stress slot'], ['hp', 'One Hit Point slot'], ['thresholds', '+2 to both damage thresholds']] as const) {
      const box = el('input', { type: 'checkbox' });
      choices.set(id, box);
      body.append(el('label', {}, box, label));
      box.addEventListener('change', () => { go.disabled = [...choices.values()].filter(b => b.checked).length !== 2; });
    }
    go.disabled = true;
  } else {
    body.append(el('p', {}, 'Resolve Reaction Rolls (16) at the table. Enter the number of Horrified targets, capped by the live GM Fear pool. Stolen Fear is discarded; damage applies to each Horrified target.'), el('label', {}, 'Fear stolen', input));
  }
  const pool = el('p', { class: 'muted', role: 'status' });
  const update = () => { input.max = String(fear()); pool.textContent = `Live GM Fear: ${fear()}`; };
  update();
  const timer = vitality ? undefined : window.setInterval(update, 250);
  cancel.addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => { if (timer !== undefined) clearInterval(timer); dialog.remove(); }, { once: true });
  go.addEventListener('click', async () => {
    const selected = [...choices].filter(([, box]) => box.checked).map(([id]) => id);
    if (vitality ? selected.length !== 2 : !Number.isInteger(input.valueAsNumber) || input.valueAsNumber < 0 || input.valueAsNumber > fear()) {
      error.textContent = vitality ? 'Choose exactly two distinct benefits.' : `Enter a whole number from 0 to ${fear()}.`;
      return;
    }
    go.disabled = cancel.disabled = true;
    try { await submit(vitality ? { choices: selected } : { amount: input.valueAsNumber }); dialog.close(); }
    catch (e) { error.textContent = (e as Error).message; go.disabled = cancel.disabled = false; }
  });
  dialog.append(el('h4', {}, action.label), body, ...(vitality ? [] : [pool]), error, el('div', { class: 'acts' }, cancel, go));
  document.body.append(dialog);
  dialog.showModal();
}

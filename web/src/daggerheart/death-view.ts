import { el } from '../login';
import type { CharacterSheet } from './data';
import { basicDeathOptions, canMakeDeathMove, recoverableHp, type DeathMove, type DeathMoveExtras, type DeathOptions } from './death';
import type { RollMessage } from '../protocol';

export function openDeathDialog(root: HTMLElement, c: CharacterSheet, take: (move: DeathMove, extras?: DeathMoveExtras) => Promise<void>, load?: () => Promise<DeathOptions>, initial?: string): void {
  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  const error = el('p', { class: 'error', role: 'alert' });
  const select = el('select', { ariaLabel: 'Death move' },
    el('option', { value: 'blaze' }, 'Blaze of Glory'),
    el('option', { value: 'avoid' }, 'Avoid Death'),
    el('option', { value: 'risk' }, 'Risk It All'));
  const descriptions: Record<DeathMove, string> = {
    blaze: 'Take one final action at the GM\'s discretion. It critically succeeds, then you cross through the veil of death.',
    avoid: 'Fall unconscious and work with the GM to worsen the situation. Movement is blocked, but actions are not enforced. Wake when an ally clears at least 1 marked HP or the party finishes a long rest. Roll a Hope d12; at or below your level, gain a permanent scar that crosses out one Hope slot.',
    risk: 'Roll Hope and Fear d12s. Adjusted Hope at least Fear: stay up and divide recovery between HP and Stress. Raw matching dice: clear all recoverable HP and Stress. Higher Fear: cross through the veil. Apply recovery manually on older hosts, or use the result in chat.',
    spectral: 'Clear 1 HP and enter Spectral Form: physical resistance and movement through walls and closed doors. Clearing further HP ends the form. Reaching full HP ends it and prevents choosing it for that trigger. Repositioning inside solid objects is GM-controlled.',
    egg: 'Consume one Cathartoid Egg and record your narrative transformation. This does not heal HP or change your build. Agree the transformation with your GM.',
    battleHardened: 'Spend 1 Hope to prevent death and clear 1 HP. Requires the active card and an unused long-rest use.',
    unbreakable: 'Prevent death, recover a host-rolled d6 HP and move Unbreakable to your vault.',
  };
  const description = el('p', {}, descriptions.blaze);
  const fields = el('div');
  let options = basicDeathOptions();
  let extras: () => DeathMoveExtras = () => ({});
  let validateExtras: () => string | undefined = () => undefined;
  const paint = () => {
    const move = select.value as DeathMove;
    description.textContent = descriptions[move] ?? '';
    fields.replaceChildren();
    const reason = options.options.find(o => o.move === move)?.disabled;
    if (reason) fields.append(el('p', { class: 'error' }, reason));
    const relevant = options.bonuses.filter(b => b.moves.includes(move));
    const spendBonus = relevant.find(b => b.kind === 'hopeSpend');
    const maskBonus = relevant.find(b => b.kind === 'mask');
    const spend = el('input', { type: 'number', min: '0', max: String(c.hope.on), step: '1', value: '0', ariaLabel: 'Blessed Hope spend' });
    const mask = el('input', { type: 'checkbox', ariaLabel: 'Spend a Mask token' });
    const wearing = el('input', { type: 'checkbox', ariaLabel: 'Wearing the death mask' });
    for (const bonus of relevant) {
      fields.append(el('p', { class: 'muted' }, `${bonus.label}${bonus.bonus !== undefined ? ` (+${bonus.bonus} Hope)` : ''}${bonus.disabled ? ` — ${bonus.disabled}` : ''}`));
    }
    if (spendBonus) { spend.disabled = !!spendBonus.disabled; fields.append(el('label', {}, 'Optional Hope spend (Blessed)', spend)); }
    if (maskBonus) {
      mask.disabled = wearing.disabled = !!maskBonus.disabled;
      fields.append(el('label', {}, 'Spend one Mask token (+1 Hope)', mask), el('label', {}, 'I confirm I am wearing the mask', wearing));
    }
    extras = () => ({
      ...(spendBonus && !spend.disabled && spend.valueAsNumber > 0 ? { blessedHope: spend.valueAsNumber } : {}),
      ...(maskBonus && !mask.disabled && mask.checked ? { mask: true, wearingMask: wearing.checked } : {}),
    });
    validateExtras = () => spendBonus && !spend.disabled && (!Number.isInteger(spend.valueAsNumber) || spend.valueAsNumber < 0 || spend.valueAsNumber > c.hope.on)
      ? 'Enter a whole Hope spend within your available Hope.' : undefined;
    confirm.disabled = !!reason || !options.options.some(o => o.move === move);
  };
  select.addEventListener('change', paint);
  const cancel = el('button', { type: 'button', class: 'small' }, 'Cancel');
  cancel.addEventListener('click', () => backdrop.remove());
  const confirm = el('button', { type: 'button', class: 'primary' }, 'Confirm death move');
  confirm.addEventListener('click', async () => {
    if (confirm.disabled) return;
    if (!canMakeDeathMove(c)) { error.textContent = 'A death move requires all HP to be marked and the character to be conscious.'; return; }
    const invalid = validateExtras();
    if (invalid) { error.textContent = invalid; return; }
    const values = extras();
    if (values.blessedHope !== undefined && (!Number.isInteger(values.blessedHope) || values.blessedHope > c.hope.on)) { error.textContent = 'Enter a whole Hope spend within your available Hope.'; return; }
    if (values.mask && !values.wearingMask) { error.textContent = 'Confirm you are wearing the mask before spending a token.'; return; }
    confirm.disabled = select.disabled = cancel.disabled = true;
    try {
      if (Object.keys(values).length) await take(select.value as DeathMove, values);
      else await take(select.value as DeathMove);
      backdrop.remove();
    } catch (e) {
      error.textContent = (e as Error).message;
      confirm.disabled = select.disabled = cancel.disabled = false;
    }
  });
  backdrop.append(el('div', { class: 'dh-rolldlg', role: 'dialog', ariaLabel: 'Death move', ariaModal: 'true' },
    el('h4', {}, `${c.name} - Death move`), select, description, fields,
    el('p', { class: 'muted' }, 'The result is shared publicly in chat. Discuss the choice with your GM before confirming.'),
    error, el('div', { class: 'acts' }, cancel, confirm)));
  root.append(backdrop);
  if (load) {
    confirm.disabled = select.disabled = true;
    error.textContent = 'Loading host death-move options...';
    void load().then(result => {
      if (!backdrop.isConnected) return;
      options = result;
      select.replaceChildren(...result.options.map(o => el('option', { value: o.move }, `${o.label}${o.disabled ? ` — ${o.disabled}` : ''}`)));
      select.value = result.options.some(o => o.move === initial) ? initial! : result.options[0]?.move ?? '';
      select.disabled = false;
      error.textContent = '';
      paint();
      if (initial && !result.options.some(o => o.move === initial)) {
        error.textContent = 'This host does not offer that expanded death move. Update the host or choose an available basic move.';
        confirm.disabled = true;
      }
    }).catch((e: Error) => { error.textContent = e.message; });
  } else if (initial) {
    if (options.options.some(o => o.move === initial)) { select.value = initial; paint(); }
    else { error.textContent = 'This host does not support expanded death moves. Update the host.'; confirm.disabled = true; }
  }
}

export function openDeathRecovery(root: HTMLElement, c: CharacterSheet, roll: RollMessage, submit: (recovery: { hp?: number; stress?: number; undying?: boolean }) => Promise<void>, onClose?: () => void): void {
  const result = roll.deathResult!;
  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  const error = el('p', { class: 'error', role: 'alert' });
  const controls = el('div');
  const hp = el('input', { type: 'number', min: '0', max: String(recoverableHp(c)), step: '1', value: '0', ariaLabel: 'HP recovery' });
  const stress = el('input', { type: 'number', min: '0', max: String(c.stress.on), step: '1', value: '0', ariaLabel: 'Stress recovery' });
  const total = el('p');
  const update = () => { total.textContent = `${hp.valueAsNumber + stress.valueAsNumber} / ${result.budget} recovery allocated`; };
  for (const [label, input] of [['HP', hp], ['Stress', stress]] as const) {
    const slider = el('input', { type: 'range', min: '0', max: input.max, step: '1', value: '0', ariaLabel: `${label} recovery slider` });
    input.addEventListener('input', () => { slider.value = input.value; update(); });
    slider.addEventListener('input', () => { input.value = slider.value; update(); });
    controls.append(el('label', {}, label, input, slider));
  }
  update();
  const failed = result.outcome === 'fear' && !result.converted;
  const critical = result.outcome === 'crit';
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const apply = el('button', { type: 'button', class: 'primary' }, failed ? "Confirm Won't Stay Dead" : critical ? 'Clear all HP and Stress' : 'Apply recovery');
  const close = () => { backdrop.remove(); onClose?.(); };
  cancel.addEventListener('click', close);
  apply.addEventListener('click', async () => {
    if (apply.disabled) return;
    if (result.claimed || (failed && (!result.undying || result.converted))) { error.textContent = 'This result is no longer available.'; return; }
    const h = critical ? recoverableHp(c) : hp.valueAsNumber;
    const s = critical ? c.stress.on : stress.valueAsNumber;
    if (!failed && (!Number.isInteger(h) || !Number.isInteger(s) || h < 0 || s < 0 || h > recoverableHp(c) || s > c.stress.on || (!critical && h + s > result.budget))) {
      error.textContent = 'Choose whole recovery amounts within your marked tracks and recovery budget.'; return;
    }
    apply.disabled = cancel.disabled = true;
    try { await submit(failed ? { undying: true } : { hp: h, stress: s }); close(); }
    catch (e) { error.textContent = (e as Error).message; apply.disabled = cancel.disabled = false; }
  });
  backdrop.append(el('div', { class: 'dh-rolldlg', role: 'dialog', ariaLabel: 'Death recovery', ariaModal: 'true' },
    el('h4', {}, failed ? "Won't Stay Dead" : 'Risk It All recovery'),
    failed ? el('p', {}, `Permanently mark one HP (${c.permanentHp ?? 0} → ${(c.permanentHp ?? 0) + 1}). This HP can never be healed or cleared by rest. If this is your last nonpermanent HP, you cannot recover. Confirm with your GM.`)
      : critical ? el('p', {}, 'Clear all recoverable marked HP and Stress. Permanent HP remain marked.') : el('div', {}, controls, total),
    error, el('div', { class: 'acts' }, cancel, apply)));
  root.append(backdrop);
}

export function openScarsDialog(root: HTMLElement, c: CharacterSheet, changed: () => void): void {
  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  const amount = el('input', { type: 'number', min: '0', max: String(c.hope.total), step: '1', value: String(c.scars ?? 0), ariaLabel: 'Scars' });
  const error = el('p', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button', class: 'small' }, 'Cancel');
  cancel.addEventListener('click', () => backdrop.remove());
  const save = el('button', { type: 'button', class: 'primary' }, 'Apply GM-approved change');
  save.addEventListener('click', () => {
    const n = amount.valueAsNumber;
    if (!Number.isInteger(n) || n < 0 || n > c.hope.total) { error.textContent = `Choose 0 to ${c.hope.total} scars.`; return; }
    c.scars = n;
    backdrop.remove();
    changed();
  });
  backdrop.append(el('div', { class: 'dh-rolldlg', role: 'dialog', ariaLabel: 'Scars', ariaModal: 'true' },
    el('h4', {}, 'Permanent scars'), el('p', {}, 'Each scar crosses out one Hope slot. Describe physical scars, painful memories or fears in Background.'),
    el('p', {}, 'Scars survive rests. Heal one only at the GM\'s discretion, such as a meaningful downtime project or healing quest.'),
    el('label', {}, 'Number of scars', amount), error, el('div', { class: 'acts' }, cancel, save)));
  root.append(backdrop);
}

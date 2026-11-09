import { el } from '../login';
import { keptRolls, rollDetails } from '../chat';
import type { RollMessage } from '../protocol';
import { TRAIT_ORDER, type CharacterSheet, type TraitName } from './data';
import type { ActionDef, RollTrigger } from './engine';
import { rich } from './rich';

function selectionDialog(title: string, closed?: () => void) {
  const dialog = el('dialog', { class: 'dh dh-rolldlg dh-sorcerer-dialog dh-option-rolldlg', ariaLabel: title });
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  cancel.addEventListener('click', () => dialog.close());
  let busy = false;
  dialog.addEventListener('cancel', e => { if (busy) e.preventDefault(); });
  dialog.addEventListener('close', () => { dialog.remove(); closed?.(); }, { once: true });
  const apply = async (fn: () => void | Promise<void>) => {
    if (busy) return;
    busy = true;
    const controls = [...dialog.querySelectorAll<HTMLButtonElement | HTMLInputElement | HTMLSelectElement>('button,input,select')];
    const disabled = controls.map(c => c.disabled);
    controls.forEach(c => { c.disabled = true; });
    error.textContent = '';
    try { await fn(); dialog.close(); }
    catch (e) { error.textContent = (e as Error).message; }
    finally { busy = false; controls.forEach((c, i) => { c.disabled = disabled[i]; }); }
  };
  dialog.append(el('h4', {}, title));
  const show = () => { document.body.append(dialog); dialog.showModal(); };
  return { dialog, error, cancel, apply, show };
}

export function chooseChannelCard(c: CharacterSheet, submit: (card: string, benefit: 'hope' | 'damage') => void): void {
  const ui = selectionDialog('Channel Raw Power');
  let selected = '';
  const hope = el('button', { type: 'button', class: 'primary', disabled: true }, 'Gain Hope');
  const damage = el('button', { type: 'button', class: 'primary', disabled: true }, 'Prepare damage bonus');
  const cards = el('div', { class: 'cards dh-roll-options' });
  const buttons: HTMLButtonElement[] = [];
  for (const card of c.loadout) {
    const button = el('button', { type: 'button', class: 'dcard', ariaPressed: 'false' },
      el('span', { class: 'hd' }, el('span', { class: 'lv' }, String(card.level)), el('span', { class: 'dm' }, card.domain)),
      el('span', { class: 'nm' }, card.name), rich(card.text));
    button.addEventListener('click', () => {
      selected = card.name;
      buttons.forEach(b => b.setAttribute('aria-pressed', String(b === button)));
      hope.disabled = damage.disabled = false;
      hope.textContent = `Gain ${card.level} Hope`;
      damage.textContent = `Prepare +${card.level * 2} damage`;
    });
    buttons.push(button);
    cards.append(button);
  }
  hope.addEventListener('click', () => { void ui.apply(() => submit(selected, 'hope')); });
  damage.addEventListener('click', () => { void ui.apply(() => submit(selected, 'damage')); });
  ui.dialog.append(el('p', { class: 'muted' }, 'Choose a card from your loadout to move into the vault, then choose its benefit.'),
    ...(c.loadout.length ? [cards] : [el('p', {}, 'No cards in your active loadout.')]), ui.error,
    el('div', { class: 'acts' }, ui.cancel, hope, damage));
  ui.show();
}

export function chooseFeatureEffects(action: ActionDef, submit: (ids: string[], trait?: TraitName) => void): void {
  const config = action.chooseEffects!;
  const ui = selectionDialog(action.label);
  const selected = new Set<string>();
  const options = el('div', { class: 'dh-roll-options' });
  const go = el('button', { type: 'button', class: 'primary', disabled: true }, 'Apply');
  const trait = el('select', { ariaLabel: 'Trait to increase' }, el('option', { value: '' }, 'Choose a trait'),
    ...TRAIT_ORDER.map(name => el('option', { value: name }, name)));
  const refresh = () => {
    const needsTrait = config.options.some(o => o.chooseTrait && selected.has(o.id));
    trait.hidden = !needsTrait;
    go.disabled = selected.size !== config.count || needsTrait && !trait.value;
  };
  for (const option of config.options) {
    const check = el('input', { type: 'checkbox' });
    check.addEventListener('change', () => {
      if (check.checked) selected.add(option.id); else selected.delete(option.id);
      refresh();
    });
    options.append(el('label', { class: 'dh-roll-option' }, check, option.label));
  }
  trait.addEventListener('change', refresh);
  go.addEventListener('click', () => { void ui.apply(() => submit([...selected], TRAIT_ORDER.find(t => t === trait.value))); });
  ui.dialog.append(el('p', { class: 'muted' }, `Choose exactly ${config.count} different benefits. They last until your next rest.`),
    options, trait, ui.error, el('div', { class: 'acts' }, ui.cancel, go));
  refresh();
  ui.show();
}

export interface DamageDieChoice { index: number; sides: number; value: number; label: string; kept: boolean }

export function damageDieChoices(m: RollMessage): DamageDieChoice[] {
  const out: DamageDieChoice[] = [];
  let index = 0;
  for (const detail of rollDetails(m)) {
    const kept = keptRolls(detail.expression, detail.rolls);
    const sides = [...detail.expression.matchAll(/([+-]?)(\d*)d(\d+)(?:kh\d+)?/gi)]
      .flatMap(match => Array.from({ length: Number(match[2] || 1) }, () => Number(match[3]) * (match[1] === '-' ? -1 : 1)));
    detail.rolls.forEach((value, i) => {
      const current = index++;
      if (sides[i] > 0 && !detail.label.startsWith('Combo Strike'))
        out.push({ index: current, sides: sides[i], value, label: detail.label, kept: kept[i] !== false });
    });
  }
  return out;
}

export function chooseRollModification(m: RollMessage, trigger: RollTrigger,
  submit: (choice: { choiceId?: string; dice?: number[]; expectedDice?: number[] }) => Promise<void>, closed: () => void): void {
  const ui = selectionDialog(trigger.label, closed);
  const expectedDice = Array.isArray(m.rolls) ? m.rolls.filter((v): v is number => typeof v === 'number') : [];
  const go = el('button', { type: 'button', class: 'primary', disabled: true }, 'Apply');
  let choiceId: string | undefined;
  const selected = new Set<number>();
  const options = el('div', { class: 'dh-roll-options' });
  if (trigger.damageDice) {
    const dice = el('div', { class: 'roll-dice' });
    const buttons: HTMLButtonElement[] = [];
    for (const die of damageDieChoices(m).filter(die => !trigger.damageDiceBelow || die.value > 0 && die.value < trigger.damageDiceBelow)) {
      const button = el('button', { type: 'button', class: `roll-die${die.kept ? '' : ' discarded'}`, ariaPressed: 'false',
        ariaLabel: `${die.label}, die ${die.index + 1}: d${die.sides}, rolled ${die.value}${die.kept ? '' : ', discarded'}` },
      el('span', { class: 'roll-die-name' }, `d${die.sides}`), el('strong', {}, String(die.value)));
      button.dataset.index = String(die.index);
      button.addEventListener('click', () => {
        const had = selected.has(die.index);
        if (trigger.damageDice === 'double') selected.clear();
        if (had) selected.delete(die.index); else selected.add(die.index);
        buttons.forEach(b => b.setAttribute('aria-pressed', String(selected.has(Number(b.dataset.index)))));
        go.disabled = selected.size === 0;
      });
      buttons.push(button);
      dice.append(button);
    }
    ui.dialog.append(el('p', { class: 'muted' }, trigger.damageDice === 'reroll'
      ? `Select any number of ${trigger.damageDiceBelow ? `damage dice below ${trigger.damageDiceBelow}` : 'damage dice'} to reroll. New results replace the selected dice, even if lower.`
      : 'Select one damage die to double its value.'));
    options.append(dice);
  } else {
    for (const choice of trigger.choices ?? []) {
      const radio = el('input', { type: 'radio', name: 'roll-modification', value: choice.id });
      radio.addEventListener('change', () => { choiceId = choice.id; go.disabled = false; });
      options.append(el('label', { class: 'dh-roll-option' }, radio, choice.label));
    }
  }
  go.addEventListener('click', () => { void ui.apply(() => submit(trigger.damageDice ? { dice: [...selected], expectedDice } : { choiceId })); });
  ui.dialog.append(options, ui.error, el('div', { class: 'acts' }, ui.cancel, go));
  ui.show();
}

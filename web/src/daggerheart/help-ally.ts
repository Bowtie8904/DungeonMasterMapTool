import { el } from '../login';
import type { Actor } from '../protocol';
import type { AdvantageDie, CharacterSheet } from './data';
import { actionBlocker, activeFeatures, activeWeapons, costText, type ActionDef, type Automation } from './engine';
import { activeCardSources } from './copycat';

export interface HelpAllyRequest { die?: AdvantageDie; description?: string }
export interface HelpAllyChoice { key: string; action: ActionDef; automation: Automation; name: string }

function helpAllySources(c: CharacterSheet) {
  return [
    ...activeFeatures(c).map(f => ({ key: `feat:${f.name}`, name: f.name, auto: f.automation })),
    ...c.heritage.map(f => ({ key: `feat:${f.name}`, name: f.name, auto: f.automation })),
    ...activeCardSources(c),
    ...activeWeapons(c).map(w => ({ key: `weapon:${w.name}`, name: w.name, auto: w.automation })),
    ...(c.activeArmor ? [{ key: `armor:${c.activeArmor.name}`, name: c.activeArmor.name, auto: c.activeArmor.automation }] : []),
    ...c.inventory.filter(i => i.qty > 0).map(i => ({ key: `item:${i.name}`, name: i.name, auto: i.automation })),
  ];
}

export function helpAllyChoices(c: CharacterSheet): HelpAllyChoice[] {
  return helpAllySources(c).flatMap(s => (s.auto?.actions ?? []).filter(a => a.helpAlly)
    .map(action => ({ key: s.key, name: s.name, action, automation: s.auto! })));
}

export function helpAllyDie(c: CharacterSheet, choice?: HelpAllyChoice): AdvantageDie {
  let die = choice?.action.helpAlly?.die ?? 6;
  for (const source of helpAllySources(c)) {
    const override = source.auto?.helpAllyDie;
    if (override && c.hp.on >= (override.markedHp ?? 0) && override.die > die) die = override.die;
  }
  return die;
}

export function openHelpAlly(host: HTMLElement, c: CharacterSheet, actors: () => Actor[],
  send: (targetActorId: string, help: HelpAllyRequest, choice?: HelpAllyChoice) => Promise<void>,
  initial?: HelpAllyChoice): void {
  const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: 'Help an Ally' });
  const choices = helpAllyChoices(c);
  const mode = el('select', { class: 'dh-help-feature', ariaLabel: 'Help feature' }, el('option', { value: '' }, 'Standard help (1 Hope)'),
    ...choices.map((choice, i) => el('option', { value: String(i) },
      `${choice.name}: ${choice.action.label}${costText(choice.action.cost, c) ? ` (${costText(choice.action.cost, c)})` : ' (no Hope cost)'}`)));
  if (initial) mode.value = String(choices.findIndex(choice => choice.key === initial.key && choice.action.id === initial.action.id));
  const target = el('select', { ariaLabel: 'Ally to help', required: true }, el('option', { value: '' }, 'Choose an ally'),
    ...actors().map(actor => el('option', { value: actor.id }, actor.name)));
  const guidance = el('p', { class: 'muted' });
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const go = el('button', { type: 'button', class: 'primary' }, 'Help ally');
  let pending = false;
  const selected = () => mode.value === '' ? undefined : choices[Number(mode.value)];
  const refresh = () => {
    const choice = selected();
    guidance.textContent = `Your ally receives a d${helpAllyDie(c, choice)} advantage die${choice?.action.helpAlly?.spellcastOnly ? ' for a Spellcast action' : ' for an action roll'}, determined by your features. Help cannot aid reaction rolls. Only the highest advantage die counts.`;
    const blocked = choice ? actionBlocker(c, choice.key, choice.automation, choice.action)
      : c.hope.on < 1 ? 'You need 1 Hope to help an ally.' : null;
    error.textContent = blocked ?? '';
    go.disabled = !!blocked || !actors().length;
  };
  mode.addEventListener('change', refresh);
  cancel.addEventListener('click', () => dialog.close());
  dialog.addEventListener('cancel', event => { if (pending) event.preventDefault(); });
  dialog.addEventListener('close', () => dialog.remove(), { once: true });
  go.addEventListener('click', async () => {
    if (pending) return;
    if (!actors().some(actor => actor.id === target.value)) { error.textContent = 'Choose an active ally.'; return; }
    pending = true;
    go.disabled = cancel.disabled = mode.disabled = target.disabled = true;
    try {
      await send(target.value, {}, selected());
      dialog.close();
    } catch (e) { error.textContent = (e as Error).message; }
    finally { pending = false; go.disabled = cancel.disabled = mode.disabled = target.disabled = false; }
  });
  dialog.append(el('h4', {}, 'Help an Ally'), el('label', {}, 'Ally', target), el('label', { class: 'dh-help-feature-field' }, 'Feature', mode),
    guidance, error, el('div', { class: 'acts' }, cancel, go));
  host.ownerDocument.body.append(dialog);
  dialog.showModal();
  refresh();
  target.focus();
}

import { el } from '../login';
import { CharacterSheet, TRAIT_ORDER, TraitName, tierOf } from './data';
import { martialStances, missingStanceChoices } from './brawler';

export function chooseStance(c: CharacterSheet, submit: (name: string, trait?: TraitName) => void, onLearn: () => void): void {
  const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: 'Shift martial stance' });
  const catalog = martialStances(c);
  const known = () => catalog.filter(s => c.knownStances?.includes(s.name) && s.tier <= tierOf(c.level));
  const select = el('select', { ariaLabel: 'Known stance' });
  const trait = el('select', { ariaLabel: 'Favored trait' }, ...TRAIT_ORDER.map(name => el('option', { value: name }, name)));
  const text = el('p', { class: 'muted' });
  const traitLabel = el('label', {}, 'Damage bonus trait', trait);
  const error = el('div', { class: 'error', role: 'alert' });
  const go = el('button', { type: 'button', class: 'primary' }, 'Shift stance');
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const update = () => {
    const stance = known().find(s => s.name === select.value);
    text.textContent = stance?.effect.text ?? 'Learn known stances below before shifting.';
    traitLabel.hidden = !stance?.chooseTrait;
    traitLabel.classList.toggle('hidden', !stance?.chooseTrait);
    go.disabled = !stance;
  };
  const refresh = () => { select.replaceChildren(...known().map(s => el('option', { value: s.name }, s.name))); update(); };
  select.addEventListener('change', update);
  cancel.addEventListener('click', () => dialog.close());
  go.addEventListener('click', () => {
    try { submit(select.value, catalog.find(s => s.name === select.value)?.chooseTrait ? trait.value as TraitName : undefined); dialog.close(); }
    catch (e) { error.textContent = (e as Error).message; }
  });
  dialog.addEventListener('close', () => dialog.remove(), { once: true });
  dialog.append(el('h4', {}, 'Shift into a known stance'), select, text, traitLabel, error);
  // Existing characters predate stance choices. Fill their missing choices without charging a shift.
  const missing = missingStanceChoices(c);
  if (missing && !c.testingCharacter) {
    const learned = new Set(c.knownStances ?? []);
    const learn = el('button', { type: 'button' }, `Save ${missing} missing stance choices`);
    const choices = catalog.filter(s => s.tier <= tierOf(c.level) && !learned.has(s.name));
    const checks = choices.map(s => {
      const box = el('input', { type: 'checkbox' });
      return { s, box, node: el('label', { class: 'dh-roll-option' }, box, `${s.name} (tier ${s.tier}): ${s.effect.text ?? ''}`) };
    });
    const field = el('fieldset', {}, el('legend', {}, 'Known stances for an existing character'), ...checks.map(x => x.node), learn);
    learn.addEventListener('click', () => {
      const picked = checks.filter(x => x.box.checked).map(x => x.s.name);
      if (picked.length !== missing) { error.textContent = `Choose exactly ${missing} stances.`; return; }
      if (catalog.filter(s => s.tier === 1 && (learned.has(s.name) || picked.includes(s.name))).length < 2) {
        error.textContent = 'At least two known stances must be from tier 1.'; return;
      }
      c.knownStances = [...learned, ...picked];
      onLearn();
      error.textContent = '';
      field.remove();
      refresh();
    });
    dialog.append(field);
  }
  dialog.append(el('div', { class: 'acts' }, cancel, go));
  document.body.append(dialog);
  refresh();
  dialog.showModal();
}

import { el } from '../login';
import { CompendiumEntry, fetchCompendiumCategory } from './compendium';
import { ancestryFeatureChoices } from './rules';
import { rich } from './rich';

export interface AncestryChoice { ancestry: string; feature: string }

export function ancestryPicker(entries: CompendiumEntry[], choice: AncestryChoice, change: () => void, allowAncestry = false, excludeFeature?: string): HTMLElement {
  const box = el('div', {});
  const draw = () => {
    const features = ancestryFeatureChoices(entries, choice.ancestry).filter((f) => f.name !== excludeFeature);
    if (!features.some((f) => f.name === choice.feature)) choice.feature = '';
    const select = el('select', { ariaLabel: 'Ancestry feature' }, el('option', { value: '' }, 'Choose one ancestry feature'),
      ...features.map((f) => el('option', { value: f.name }, f.name)));
    select.value = choice.feature;
    select.addEventListener('change', () => { choice.feature = select.value; draw(); change(); });
    const ancestry = el('select', { ariaLabel: 'Ancestry' }, el('option', { value: '' }, 'Choose an ancestry'),
      ...entries.map((e) => el('option', { value: e.name }, e.name)));
    ancestry.value = choice.ancestry;
    ancestry.addEventListener('change', () => { choice.ancestry = ancestry.value; choice.feature = ''; draw(); change(); });
    box.replaceChildren(...(allowAncestry ? [el('label', {}, 'Ancestry', ancestry)] : []),
      el('label', {}, 'Retained ancestry feature', select),
      ...features.filter((f) => f.name === choice.feature).map((f) => rich(f.text)));
  };
  draw();
  return box;
}

export async function chooseAncestryFeature(host: HTMLElement, ancestry: string, allowAncestry: boolean, currentFeature?: string): Promise<{ choice: AncestryChoice; entries: CompendiumEntry[] } | null> {
  const { entries } = await fetchCompendiumCategory('ancestries');
  return new Promise((resolve) => {
    const choice = { ancestry: allowAncestry ? '' : ancestry, feature: '' };
    const dialog = el('dialog', { class: 'dh dh-rolldlg dh-option-rolldlg' });
    const cancel = el('button', { type: 'button' }, 'Cancel');
    const go = el('button', { type: 'button', class: 'primary' }, 'Choose');
    go.disabled = true;
    cancel.addEventListener('click', () => dialog.close());
    go.addEventListener('click', () => {
      if (!ancestryFeatureChoices(entries, choice.ancestry).some((f) => f.name === choice.feature)) return;
      resolve({ choice, entries });
      dialog.close();
    });
    dialog.addEventListener('close', () => { resolve(null); dialog.remove(); }, { once: true });
    dialog.addEventListener('keydown', (event) => { if (event.key === 'Escape') event.stopPropagation(); });
    dialog.append(el('h4', {}, allowAncestry ? 'Change Shape - downtime move' : 'Only Skin Deep - downtime move'),
      el('div', { class: 'dh-roll-options' }, ancestryPicker(entries, choice, () => { go.disabled = !choice.feature; }, allowAncestry, allowAncestry ? undefined : currentFeature)), el('div', { class: 'acts' }, cancel, go));
    host.ownerDocument.body.append(dialog);
    dialog.showModal();
  });
}

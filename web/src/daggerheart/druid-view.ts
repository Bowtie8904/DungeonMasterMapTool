import { el } from '../login';
import { CharacterSheet, TRAIT_ORDER, TraitName, tierOf } from './data';
import { BeastformChoice, beastformCatalog, beastformParts } from './druid';

export function chooseBeastform(c: CharacterSheet, evolution: boolean, submit: (choice: BeastformChoice) => void): void {
  const dialog = el('dialog', { class: 'dh dh-rolldlg dh-option-rolldlg', ariaLabel: 'Choose Beastform' });
  const catalog = beastformCatalog(c);
  const select = el('select', { ariaLabel: 'Beastform category' }, ...catalog.filter(f => f.tier <= tierOf(c.level))
    .map(f => el('option', { value: f.name }, `Tier ${f.tier}: ${f.name}`)));
  const trait = el('select', { ariaLabel: 'Evolution trait' }, ...TRAIT_ORDER.map(name => el('option', { value: name }, name)));
  const details = el('div', { class: 'dh-roll-options' });
  const error = el('div', { class: 'error', role: 'alert' });
  const go = el('button', { type: 'button', class: 'primary' }, 'Transform');
  const cancel = el('button', { type: 'button' }, 'Cancel');
  let selection: () => BeastformChoice = () => ({ form: select.value });
  const update = () => {
    const form = catalog.find(f => f.name === select.value)!;
    details.replaceChildren(el('p', { class: 'muted' }, form.examples ?? form.name));
    const choice: BeastformChoice = { form: form.name };
    if (form.evolved) {
      const base = el('select', { ariaLabel: 'Base category' }, ...catalog.filter(f => f.tier <= form.evolved!.maxTier && !f.evolved && !f.hybrid)
        .map(f => el('option', { value: f.name }, f.name)));
      details.append(el('label', {}, 'Base category', base));
      selection = () => ({ ...choice, base: base.value });
    } else if (form.hybrid) {
      const h = form.hybrid;
      const parts = el('div');
      const categories = catalog.filter(f => f.tier <= h.maxTier && !f.evolved && !f.hybrid).map(f => {
        const box = el('input', { type: 'checkbox' });
        return { name: f.name, box, node: el('label', { class: 'dh-roll-option' }, box, f.name) };
      });
      let advantages: Array<{ name: string; box: HTMLInputElement }> = [];
      let features: Array<{ name: string; box: HTMLInputElement }> = [];
      const refresh = () => {
        const available = beastformParts(catalog, categories.filter(x => x.box.checked).map(x => x.name));
        const checked = new Set([...advantages, ...features].filter(x => x.box.checked).map(x => x.name));
        const advantageNodes = available.advantages.map(name => {
          const box = el('input', { type: 'checkbox', checked: checked.has(name) });
          return { name, box, node: el('label', { class: 'dh-roll-option' }, box, name) };
        });
        const featureNodes = available.features.map(f => {
          const box = el('input', { type: 'checkbox', checked: checked.has(f.name) });
          return { name: f.name, box, node: el('label', { class: 'dh-roll-option' }, box, `${f.name}: ${f.text}`) };
        });
        advantages = advantageNodes; features = featureNodes;
        parts.replaceChildren(el('h4', {}, `Choose ${h.advantages} advantages`), ...advantageNodes.map(x => x.node),
          el('h4', {}, `Choose ${h.features} features`), ...featureNodes.map(x => x.node));
      };
      categories.forEach(x => x.box.addEventListener('change', refresh));
      details.append(el('p', {}, `Choose ${h.count} categories; mark ${h.stress} additional Stress.`),
        ...categories.map(x => x.node), parts);
      refresh();
      selection = () => ({ ...choice, categories: categories.filter(x => x.box.checked).map(x => x.name),
        advantages: advantages.filter(x => x.box.checked).map(x => x.name), features: features.filter(x => x.box.checked).map(x => x.name) });
    } else {
      details.append(el('p', {}, `${form.trait} +${form.bonus} | Evasion +${form.evasion} | ${form.range ?? 'Melee'} ${form.damage} physical`),
        el('p', {}, `Advantages: ${(form.advantages ?? []).join(', ')}`),
        ...(form.features ?? []).map(f => el('p', {}, `${f.name}: ${f.text}`)));
      selection = () => choice;
    }
  };
  select.addEventListener('change', update);
  cancel.addEventListener('click', () => dialog.close());
  go.addEventListener('click', () => {
    try { submit({ ...selection(), ...(evolution ? { trait: trait.value as TraitName } : {}) }); dialog.close(); }
    catch (e) { error.textContent = (e as Error).message; }
  });
  dialog.addEventListener('close', () => dialog.remove(), { once: true });
  dialog.append(el('h4', {}, evolution ? 'Evolution' : 'Beastform'), select, details,
    ...(evolution ? [el('label', {}, 'Evolution: raise one trait by +1', trait)] : []),
    error, el('div', { class: 'acts' }, cancel, go));
  document.body.append(dialog);
  update();
  dialog.showModal();
}

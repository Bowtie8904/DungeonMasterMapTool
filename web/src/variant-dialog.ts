import { el } from './login';
import { createVariant, isCatalogObject, variantNameProblem, cloneValue, type CatalogObject } from './catalog-fields';
import {
  isDiceFormula, replaceFormula, variantFields, writeField, readPath, type VariantField,
} from './variant-fields';

type Request = (url: string, data?: CatalogObject, method?: string) => Promise<Response>;
interface Document { file: CatalogObject; revision: string }

/**
 * Flat "customize a copy" dialog: rename and tune the numbers, dice and descriptions of a compendium item.
 * Triggers, effect names and other plumbing stay hidden; unique effect names are generated on save.
 */
export async function openVariantDialog(request: Request, category: string, entryId: string,
                                        saved: (name: string) => Promise<void> | void): Promise<void> {
  const previous = document.activeElement;
  const dialog = el('dialog', { class: 'app-message-dialog variant-dialog', ariaLabel: 'Customize copy' });
  const status = el('p', { class: 'error', role: 'alert' });
  const body = el('div', { class: 'variant-body' }, el('p', { class: 'muted' }, 'Loading...'));
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const create = el('button', { type: 'submit', class: 'primary', disabled: true }, 'Create copy');
  const form = el('form', { method: 'dialog' }, body, status, el('div', { class: 'acts' }, cancel, create));
  dialog.append(el('h3', {}, 'Customize copy'), form);
  document.body.append(dialog);
  dialog.addEventListener('close', () => {
    dialog.remove();
    if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
  }, { once: true });
  cancel.addEventListener('click', () => dialog.close());
  dialog.showModal();

  let doc: Document;
  let base: CatalogObject;
  try {
    doc = await (await request(`/api/dm/catalog/${category}`)).json() as Document;
    const found = Array.isArray(doc.file.entries) ? doc.file.entries.find(entry => isCatalogObject(entry) && entry.id === entryId) : undefined;
    if (!isCatalogObject(found)) throw new Error('This entry is no longer in the catalog. Reload the compendium.');
    base = found;
  } catch (error) {
    body.replaceChildren();
    status.textContent = (error as Error).message;
    return;
  }

  const entries = (): CatalogObject[] => (Array.isArray(doc.file.entries) ? doc.file.entries.filter(isCatalogObject) : []);
  const working = cloneValue(base);
  delete working._editorOriginal;
  const fields = variantFields(working);
  const inputs = new Map<VariantField, HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>();
  const invalid = new Set<VariantField>();

  dialog.querySelector('h3')!.textContent = `Customize copy of ${String(base.name)}`;
  const nameInput = el('input', { type: 'text', ariaLabel: 'Name of the new item', maxLength: 100, value: `${String(base.name)} (custom)` });
  nameInput.select();
  body.replaceChildren(
    el('p', { class: 'muted' }, 'The copy is added to the compendium and works like the original, but is tracked, stacked and buffed separately.'),
    el('label', { class: 'variant-field' }, el('span', {}, 'Name'), nameInput));

  const sections = new Map<string, HTMLElement>();
  const sectionFor = (title: string): HTMLElement => {
    let section = sections.get(title);
    if (!section) {
      section = el('fieldset', { class: 'variant-section' }, el('legend', {}, title));
      sections.set(title, section);
      body.append(section);
    }
    return section;
  };

  const refresh = (): void => { create.disabled = invalid.size > 0; };
  const longFields = (): VariantField[] => fields.filter(field => field.kind === 'long');

  for (const field of fields) {
    let input: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;
    if (field.kind === 'long') {
      input = el('textarea', { rows: 3, ariaLabel: field.label, value: String(field.value) });
    } else if (field.kind === 'select') {
      input = el('select', { ariaLabel: field.label }, ...(field.options ?? []).map(option => el('option', { value: option }, option)));
      input.value = String(field.value);
    } else {
      input = el('input', { type: field.kind === 'number' ? 'number' : 'text', ariaLabel: field.label, value: String(field.value) });
      if (field.kind === 'number') (input as HTMLInputElement).step = 'any';
    }
    let last = String(field.value);
    input.addEventListener('input', () => {
      const raw = input.value;
      const ok = field.kind === 'dice' ? isDiceFormula(raw) && writeField(working, field, raw) : writeField(working, field, raw);
      input.classList.toggle('invalid', !ok);
      if (ok) invalid.delete(field); else invalid.add(field);
      if (ok && field.kind === 'dice' && last !== raw.trim()) {
        for (const text of longFields()) {
          const box = inputs.get(text);
          const updated = replaceFormula(String(readPath(working, text.path) ?? ''), last, raw);
          if (box && updated !== box.value) { box.value = updated; writeField(working, text, updated); }
        }
        last = raw.trim();
      }
      status.textContent = '';
      refresh();
    });
    inputs.set(field, input);
    sectionFor(field.section).append(el('label', { class: 'variant-field' }, el('span', {}, field.label), input));
  }
  refresh();

  form.addEventListener('submit', async event => {
    event.preventDefault();
    const problem = variantNameProblem(entries(), nameInput.value);
    status.textContent = problem;
    if (problem) { nameInput.focus(); return; }
    create.disabled = true;
    try {
      const variant = createVariant(working, nameInput.value, entries());
      await request(`/api/dm/catalog/${category}`, { revision: doc.revision, existingId: '', entry: variant }, 'PUT');
      const name = String(variant.name);
      dialog.close();
      await saved(name);
    } catch (error) {
      status.textContent = (error as Error).message;
      refresh();
    }
  });
  nameInput.focus();
}

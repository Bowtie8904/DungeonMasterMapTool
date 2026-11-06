import { el } from './login';
import { confirmDialog } from './dialogs';
import { markdownBlock } from './compendium';
import {
  CatalogShapes, CATALOG_LABELS, cloneValue, entryTemplate, entryFieldTab, fieldHelp, fieldLabel, isCatalogObject,
  ruleSection, ruleSummary, STEP_TEMPLATES, type EditorTab, type CatalogObject, type CatalogValue,
} from './catalog-fields';

interface Document { file: CatalogObject; revision: string }
interface Panel { value: CatalogObject | CatalogValue[]; path: string; title: string }
export type CatalogRequest = (url: string, data?: CatalogObject, method?: string) => Promise<Response>;

/** Host-only form editor. The draft is separate from the loaded file until an explicit successful save. */
export class CatalogEditor {
  readonly element = el('div', { class: 'catalog-editor' });
  private documents = new Map<string, Document>();
  private shapes = new CatalogShapes([]);
  private category = 'ancestries';
  private draft?: CatalogObject;
  private original = '';
  private existingId = '';
  private query = '';
  private busy = false;
  private message = '';
  private error = false;
  private listeners = new Set<() => void>();
  private tab: EditorTab = 'Overview';
  private panels: Panel[] = [];
  private sourcePath = '';
  private featurePath = '';
  private scrollPositions = new Map<string, number>();
  private renderedView = '';

  constructor(private readonly request: CatalogRequest, private readonly saved: () => Promise<void>) {
    this.render();
  }

  get dirty(): boolean { return !!this.draft && JSON.stringify(this.draft) !== this.original; }
  canClose(): boolean { return !this.busy && !this.dirty; }
  async requestClose(): Promise<boolean> {
    if (this.busy) return false;
    if (!this.dirty) return true;
    return await confirmDialog('Discard unsaved catalog changes?', 'Unsaved changes', 'Discard changes') && !this.busy;
  }
  discard(): void { this.draft = undefined; this.render(); }

  async open(): Promise<void> {
    if (this.documents.size) return;
    this.busy = true; this.render();
    try {
      await Promise.all(Object.keys(CATALOG_LABELS).map(async category => {
        const response = await this.request(`/api/dm/catalog/${category}`);
        this.documents.set(category, await response.json() as Document);
      }));
      this.shapes = new CatalogShapes([...this.documents.values()].map(document => document.file));
      this.message = 'Select an entry, or create a new one. Nothing is written until you click Save.';
      this.error = false;
    } catch (error) { this.fail(error); }
    finally { this.busy = false; this.render(); }
  }

  private fail(error: unknown): void { this.error = true; this.message = (error as Error).message; }
  private entries(): CatalogObject[] {
    const entries = this.documents.get(this.category)?.file.entries;
    return Array.isArray(entries) ? entries.filter(isCatalogObject) : [];
  }
  private async choose(entry?: CatalogObject, duplicate = false): Promise<void> {
    if (!this.canClose() && !await this.requestClose()) return;
    this.draft = cloneValue(entry ?? entryTemplate(this.category));
    delete this.draft._editorOriginal;
    this.existingId = entry && !duplicate ? String(entry.id) : '';
    if (duplicate) { this.draft.id = ''; this.draft.name = `${String(entry?.name ?? '')} (copy)`; }
    this.original = entry && !duplicate ? JSON.stringify(this.draft) : '';
    this.message = this.existingId ? 'Edit the fields below, then Save to update the JSON file.' : 'Give the new entry a unique stable ID and name, then Save.';
    this.error = false; this.tab = 'Overview'; this.panels = []; this.sourcePath = ''; this.featurePath = '';
    this.scrollPositions.clear(); this.renderedView = ''; this.render();
  }

  private async reload(): Promise<void> {
    if (!this.canClose() && !await this.requestClose()) return;
    this.busy = true; this.render();
    try {
      const document = await (await this.request(`/api/dm/catalog/${this.category}`)).json() as Document;
      this.documents.set(this.category, document);
      this.shapes = new CatalogShapes([...this.documents.values()].map(d => d.file));
      this.draft = undefined; this.message = 'Catalog reloaded. Select an entry to edit.'; this.error = false;
    } catch (error) { this.fail(error); }
    finally { this.busy = false; this.render(); }
  }

  private async save(): Promise<void> {
    const document = this.documents.get(this.category);
    if (!document || !this.draft || this.busy) return;
    this.busy = true; this.render();
    try {
      const result = await (await this.request(`/api/dm/catalog/${this.category}`, {
        revision: document.revision, existingId: this.existingId, entry: this.draft,
      }, 'PUT')).json() as Document;
      this.documents.set(this.category, result);
      this.existingId = String(this.draft.id);
      const savedEntry = Array.isArray(result.file.entries)
        ? result.file.entries.find(entry => isCatalogObject(entry) && entry.id === this.existingId) : undefined;
      if (isCatalogObject(savedEntry)) {
        this.draft = cloneValue(savedEntry);
        delete this.draft._editorOriginal;
      }
      this.original = JSON.stringify(this.draft);
      this.panels = [];
      this.shapes = new CatalogShapes([...this.documents.values()].map(d => d.file));
      this.message = `Saved to ${this.category}.json. Linked sheets and compendiums now use these rules.`;
      this.error = false;
      try { await this.saved(); }
      catch (error) { this.fail(new Error(`The JSON was saved, but the compendium could not refresh: ${(error as Error).message}`)); }
    } catch (error) { this.fail(error); }
    finally { this.busy = false; this.render(); }
  }

  private render(): void {
    const previousMain = this.element.querySelector<HTMLElement>('.catalog-main');
    if (previousMain && this.renderedView) this.scrollPositions.set(this.renderedView, previousMain.scrollTop);
    const navScroll = this.element.querySelector('.catalog-entry-list')?.scrollTop ?? 0;
    this.listeners.clear();
    const category = el('select', { disabled: this.busy, ariaLabel: 'Catalog category' },
      ...Object.entries(CATALOG_LABELS).map(([value, name]) => el('option', { value }, name)));
    category.value = this.category;
    category.onchange = async () => {
      if (!this.canClose() && !await this.requestClose()) { category.value = this.category; return; }
      this.category = category.value; this.draft = undefined; this.query = ''; this.message = '';
      this.panels = []; this.sourcePath = ''; this.scrollPositions.clear(); this.renderedView = ''; this.render();
    };
    const search = el('input', { type: 'search', placeholder: 'Search entry names...', value: this.query, ariaLabel: 'Search catalog entries' });
    const list = el('div', { class: 'catalog-entry-list' });
    const renderList = () => {
      list.replaceChildren();
      for (const entry of this.entries().filter(entry => String(entry.name).toLowerCase().includes(this.query.toLowerCase()))) {
        const stage = entry.stage ? ` (${String(entry.stage)})` : '';
        const button = el('button', { type: 'button', disabled: this.busy,
          class: this.existingId === entry.id ? 'active' : '' }, `${String(entry.name)}${stage}`);
        button.onclick = () => this.choose(entry); list.append(button);
      }
    };
    search.oninput = () => { this.query = search.value; renderList(); };
    renderList();
    const create = el('button', { type: 'button', disabled: this.busy }, 'New entry');
    create.onclick = () => this.choose();
    const reload = el('button', { type: 'button', disabled: this.busy }, 'Reload file');
    reload.onclick = () => { void this.reload(); };
    const side = el('aside', { class: 'catalog-nav' }, category, search, create, reload, list);
    const status = el('p', { class: this.error ? 'error' : 'muted', role: this.error ? 'alert' : 'status' },
      this.busy ? 'Working...' : this.message);
    const main = el('main', { class: 'catalog-main' });
    const workspace = el('section', { class: 'catalog-workspace' },
      el('h2', {}, this.draft ? String(this.draft.name || 'New entry') : 'Daggerheart catalog editor'), status);
    if (this.draft) {
      const form = el('form', { class: 'catalog-form', id: 'catalog-entry-form' });
      const save = el('button', { type: 'submit', class: 'primary', disabled: this.busy }, 'Save to JSON');
      save.setAttribute('form', 'catalog-entry-form');
      const duplicate = el('button', { type: 'button', disabled: this.busy }, 'Duplicate as new');
      duplicate.onclick = () => {
        if (this.draft) this.choose(this.draft, true);
      };
      const state = el('span', { class: 'muted', role: 'status' });
      const changed = () => { state.textContent = this.dirty ? 'Unsaved changes' : 'No unsaved changes'; };
      this.listeners.add(changed); changed();
      const controls = el('div', { class: 'catalog-save-bar' }, save, duplicate, state);
      const tabs = el('nav', { class: 'catalog-view-tabs', ariaLabel: 'Entry sections' });
      for (const name of ['Overview', 'Features', 'Automation', 'Advanced'] as EditorTab[]) {
        const button = el('button', { type: 'button', class: name === this.tab ? 'active' : '', disabled: this.busy }, name);
        button.onclick = () => { this.tab = name; this.panels = []; this.render(); };
        tabs.append(button);
      }
      workspace.append(controls, tabs);
      const current = this.panels.at(-1);
      if (current) {
        const crumbs = el('nav', { class: 'catalog-breadcrumbs', ariaLabel: 'Rule location' });
        const back = el('button', { type: 'button' }, this.tab);
        back.onclick = () => { this.panels = []; this.render(); };
        crumbs.append(back);
        this.panels.forEach((panel, index) => {
          const button = el('button', { type: 'button', disabled: index === this.panels.length - 1 }, panel.title);
          button.onclick = () => { this.panels = this.panels.slice(0, index + 1); this.render(); };
          crumbs.append(el('span', {}, '>'), button);
        });
        form.append(crumbs, el('h3', {}, current.title),
          Array.isArray(current.value) ? this.arrayFields(current.value, current.path) : this.objectFields(current.value, current.path));
      } else if (this.tab === 'Features') form.append(this.features());
      else if (this.tab === 'Automation') form.append(this.automation());
      else {
        form.append(el('p', { class: 'catalog-intro' }, this.tab === 'Overview'
          ? 'Shared game rules, not a character reset. Descriptions explain the rule; Automation controls its mechanics. Starting stats apply to new characters only.'
          : 'Uncommon fields and custom resource keys. Existing values are preserved; new field names only run if the host supports them.'),
          this.objectFields(this.draft, this.category, key => entryFieldTab(key) === this.tab));
        if (this.tab === 'Overview') {
          const preview = el('aside', { class: 'catalog-preview' });
          const update = () => {
            if (!this.draft) return;
            preview.replaceChildren(el('h3', {}, 'Entry preview'), el('strong', {}, String(this.draft.name || 'Unnamed entry')),
              markdownBlock(String(this.draft.text ?? this.draft.description ?? 'No description yet.')));
            for (const source of this.featureSources()) preview.append(el('strong', {}, source.title), markdownBlock(String(source.value.text ?? '')));
          };
          this.listeners.add(update); update(); form.append(preview);
        }
      }
      form.onsubmit = event => { event.preventDefault(); if (form.reportValidity()) void this.save(); };
      main.append(form);
    } else main.append(el('p', { class: 'muted' }, 'Select an entry on the left. New entry starts with a category-specific template; Duplicate as new copies an existing rule’s structure.'));
    workspace.append(main);
    this.element.replaceChildren(side, workspace);
    this.renderedView = `${this.tab}:${this.tab === 'Automation' ? this.sourcePath : this.tab === 'Features' ? this.featurePath : ''}:${this.panels.at(-1)?.path ?? ''}`;
    main.scrollTop = this.scrollPositions.get(this.renderedView) ?? 0;
    list.scrollTop = navScroll;
  }

  private notify(): void { for (const listener of this.listeners) listener(); }

  private navigate(value: CatalogObject | CatalogValue[], path: string, title: string): void {
    this.panels.push({ value, path, title }); this.render();
  }

  private featureSources(): { value: CatalogObject; path: string; title: string }[] {
    if (!this.draft) return [];
    const sources: { value: CatalogObject; path: string; title: string }[] = [];
    for (const key of ['feature', 'features', 'spells']) {
      const value = this.draft[key];
      if (isCatalogObject(value)) sources.push({ value, path: `${this.category}.${key}`, title: String(value.name || fieldLabel(key)) });
      else if (Array.isArray(value)) value.forEach((item, index) => {
        if (isCatalogObject(item)) sources.push({ value: item, path: `${this.category}.${key}[${index}]`,
          title: `${key === 'spells' ? 'Spell' : 'Feature'}: ${String(item.name || `Untitled ${index + 1}`)}` });
      });
    }
    return sources;
  }

  private features(): HTMLElement {
    const box = el('div', { class: 'catalog-feature-browser' },
      el('p', { class: 'catalog-intro' }, 'Choose a feature or spell to edit its description. Its mechanics are available in the Automation tab.'));
    const sources = this.featureSources();
    const selected = sources.find(source => source.path === this.featurePath) ?? sources[0];
    this.featurePath = selected?.path ?? '';
    const list = el('nav', { class: 'catalog-feature-list', ariaLabel: 'Features and spells' });
    if (this.draft) {
      const draft = this.draft;
      const available = this.shapes.fields(this.category);
      for (const key of ['features', 'spells']) {
        if (!Array.isArray(draft[key]) && (draft[key] !== undefined || !Array.isArray(available[key]))) continue;
        const add = el('button', { type: 'button', disabled: this.busy }, key === 'spells' ? 'Add spell' : 'Add feature');
        add.onclick = () => {
          const values = draft[key] ?? [];
          if (!Array.isArray(values)) return;
          values.push({ name: key === 'spells' ? 'New spell' : 'New feature', text: '' });
          draft[key] = values; this.featurePath = `${this.category}.${key}[${values.length - 1}]`; this.render();
        };
        list.append(add);
      }
    }
    for (const source of sources) {
      const button = el('button', { type: 'button', class: 'catalog-rule-card', disabled: this.busy },
        el('strong', {}, source.title), el('span', { class: 'muted' }, String(source.value.text ?? '').slice(0, 180)));
      button.classList.toggle('selected', selected === source);
      button.onclick = () => { this.featurePath = source.path; this.render(); };
      list.append(button);
    }
    const detail = el('section', { class: 'catalog-feature-detail' });
    if (selected) {
      const rules = el('button', { type: 'button', disabled: this.busy }, 'Edit feature automation');
      rules.onclick = () => { this.tab = 'Automation'; this.sourcePath = selected.path; this.panels = []; this.render(); };
      detail.append(el('h3', {}, selected.title), rules,
        this.objectFields(selected.value, selected.path, key => key !== 'automation'));
    } else detail.append(el('p', { class: 'muted' }, 'No named features or spells yet. Add a feature block or spell list below.'));
    box.append(el('div', { class: 'catalog-feature-layout' }, list, detail));
    if (this.draft) box.append(el('section', { class: 'catalog-feature-management' }, el('h3', {}, 'Add, remove or reorder feature blocks'),
      this.objectFields(this.draft, this.category, key => entryFieldTab(key) === 'Features')));
    return box;
  }

  private automation(): HTMLElement {
    const box = el('div', { class: 'catalog-automation' });
    if (!this.draft) return box;
    const sources = [{ value: this.draft, path: this.category, title: 'Entry-wide rules' }, ...this.featureSources()];
    const source = sources.find(item => item.path === this.sourcePath) ?? sources[0];
    this.sourcePath = source.path;
    const select = el('select', { ariaLabel: 'Automation source', disabled: this.busy },
      ...sources.map(item => el('option', { value: item.path }, item.title)));
    select.value = source.path;
    select.onchange = () => { this.sourcePath = select.value; this.panels = []; this.render(); };
    box.append(el('label', { class: 'catalog-source' }, el('strong', {}, 'Rules belonging to'), select),
      el('p', { class: 'catalog-intro' }, 'Choose a rule below. Actions run when activated; modifiers are passive; roll reactions run after matching rolls. Descriptions alone do not create mechanics.'));
    const automation = source.value.automation;
    if (isCatalogObject(automation)) box.append(this.objectFields(automation, `${source.path}.automation`));
    else if (automation !== undefined) box.append(this.field('automation', automation, `${source.path}.automation`,
      next => { source.value.automation = next; this.notify(); }));
    else {
      const add = el('button', { type: 'button', disabled: this.busy }, 'Add automation');
      add.onclick = () => { source.value.automation = {}; this.render(); };
      box.append(el('p', { class: 'muted' }, 'No automation configured for this source.'), add);
    }
    return box;
  }

  private objectFields(object: CatalogObject, path: string, include: (key: string) => boolean = () => true): HTMLElement {
    const box = el('div', { class: 'catalog-fields' });
    const sections = new Map<string, HTMLElement>();
    const grouped = /\.(actions|rollOptions|rollTriggers|mitigation|steps)\[\d+\]$/.test(path);
    const append = (key: string, field: HTMLElement) => {
      if (!grouped) { box.append(field); return; }
      const title = ruleSection(key);
      let section = sections.get(title);
      if (!section) {
        section = el('section', { class: 'catalog-rule-section' }, el('h4', {}, title));
        sections.set(title, section); box.append(section);
      }
      section.append(field);
    };
    for (const [key, value] of Object.entries(object)) {
      if (key.startsWith('_') || !include(key)) continue;
      const field = this.field(key, value, `${path}.${key}`, next => { object[key] = next; this.notify(); });
      if (!(path === this.category && ['id', 'name'].includes(key))) {
        const remove = el('button', { type: 'button', class: 'catalog-remove', title: `Remove optional field ${fieldLabel(key)}`, disabled: this.busy }, 'Remove field');
        remove.onclick = () => { delete object[key]; this.render(); };
        field.append(remove);
      }
      append(key, field);
    }
    if (grouped) {
      for (const title of ['When / activation', 'Conditions', 'Effects / results', 'Costs & limits']) {
        const section = sections.get(title);
        if (section) box.append(section);
      }
    }
    const optional = Object.entries(this.shapes.fields(path)).filter(([key]) => !Object.hasOwn(object, key) && include(key));
    const choices = el('select', { ariaLabel: 'Optional field to add', disabled: this.busy },
      el('option', { value: '' }, 'Choose an optional field...'),
      ...optional.map(([key]) => el('option', { value: key }, fieldLabel(key))));
    const help = el('p', { class: 'catalog-help' });
    choices.onchange = () => { help.textContent = choices.value ? fieldHelp(choices.value, `${path}.${choices.value}`) : ''; };
    const add = el('button', { type: 'button', disabled: this.busy }, 'Add field');
    add.onclick = () => {
      const sample = optional.find(([key]) => key === choices.value);
      if (!sample) return;
      object[sample[0]] = cloneValue(sample[1]);
      if (path === this.category) { this.tab = entryFieldTab(sample[0]); this.panels = []; }
      this.render();
    };
    const advanced = el('div', { class: 'catalog-custom' }, el('strong', {}, 'Custom pool/map key or advanced field'));
    const keyInput = el('input', { placeholder: 'Exact supported field or pool ID', ariaLabel: 'Custom field name', disabled: this.busy });
    const type = el('select', { ariaLabel: 'Custom field type', disabled: this.busy },
      ...['Text', 'Number', 'Boolean', 'Group', 'List'].map(name => el('option', { value: name }, name)));
    const custom = el('button', { type: 'button', disabled: this.busy }, 'Add named field');
    custom.onclick = () => {
      const key = keyInput.value.trim();
      if (!key || Object.hasOwn(object, key) || ['__proto__', 'prototype', 'constructor'].includes(key) || key.startsWith('_')) {
        keyInput.setCustomValidity('Choose a new, non-reserved field name.'); keyInput.reportValidity(); return;
      }
      object[key] = type.value === 'Number' ? 0 : type.value === 'Boolean' ? false : type.value === 'Group' ? {} : type.value === 'List' ? [] : '';
      if (path === this.category) { this.tab = entryFieldTab(key); this.panels = []; }
      this.render();
    };
    keyInput.oninput = () => keyInput.setCustomValidity('');
    advanced.append(el('p', { class: 'catalog-help' }, 'For costs and resource maps, use the custom pool’s stable ID as the key. Other new names only run if the host supports them; they do not create new game mechanics.'), keyInput, type, custom);
    const additions = el('div', { class: 'catalog-add-field' }, choices, add, help);
    if (path !== this.category || this.tab === 'Advanced') additions.append(advanced);
    box.append(additions);
    return box;
  }

  private field(key: string, value: CatalogValue, path: string, set: (value: CatalogValue) => void): HTMLElement {
    const title = fieldLabel(key);
    const help = el('p', { class: 'catalog-help' }, `${fieldHelp(key, path)} (${key})`);
    if (isCatalogObject(value) || Array.isArray(value)) {
      const card = el('div', { class: 'catalog-group' });
      card.dataset.field = path;
      const button = el('button', { type: 'button', class: 'catalog-rule-card', disabled: this.busy },
        el('strong', {}, title), el('span', { class: 'muted' }, ruleSummary(value)), el('span', {}, 'Edit >'));
      button.onclick = () => this.navigate(value, path, title);
      card.append(button, help);
      if (Array.isArray(value) && path.endsWith(`.automation.${key}`)) {
        value.forEach((item, index) => {
          if (!isCatalogObject(item)) return;
          const rule = el('button', { type: 'button', class: 'catalog-rule-card', disabled: this.busy },
            el('strong', {}, ruleSummary(item)), el('span', { class: 'muted' }, String(item.description ?? item.text ?? fieldHelp(key)).slice(0, 180)));
          rule.onclick = () => this.navigate(item, `${path}[${index}]`, ruleSummary(item));
          card.append(rule);
        });
      }
      return card;
    }
    const row = el('div', { class: 'catalog-field' });
    row.dataset.field = path;
    const label = el('label', {}, el('strong', {}, title));
    const disabled = this.busy || path === `${this.category}.id` && !!this.existingId;
    if (typeof value === 'boolean') {
      const input = el('input', { type: 'checkbox', checked: value, disabled });
      input.onchange = () => set(input.checked); label.append(input);
    } else if (typeof value === 'number') {
      const input = el('input', { type: 'number', step: 'any', value: String(value), disabled });
      input.oninput = () => {
        const number = input.valueAsNumber;
        input.setCustomValidity(Number.isFinite(number) ? '' : 'Enter a finite number.');
        if (Number.isFinite(number)) set(number);
      };
      label.append(input);
    } else {
      const long = ['text', 'description', 'manual', 'examples'].includes(key) || String(value ?? '').length > 100;
      const options = fieldChoices(key, path);
      if (options.length && !options.includes(String(value ?? ''))) options.unshift(String(value ?? ''));
      const input = options.length ? el('select', { value: String(value ?? ''), disabled },
        ...options.map(option => el('option', { value: option }, key === 'op' ? fieldLabel(option) : option || '(not set)')))
        : long ? el('textarea', { value: String(value ?? ''), rows: 3, disabled })
        : el('input', { type: 'text', value: String(value ?? ''), disabled });
      if (['id', 'name'].includes(key)) input.required = true;
      input.oninput = () => set(input.value);
      label.append(input);
    }
    // Values/amounts may be either formulas or numbers; never serialize a numeric cost as text accidentally.
    if (['value', 'amount', 'initial', 'max', 'bonus'].includes(key) || /\.(cost|usesOverrides|diceByLevel)\.[^.]+$/.test(path)) {
      const convert = el('button', { type: 'button', disabled }, typeof value === 'number' ? 'Use formula/text instead' : 'Use number instead');
      convert.onclick = () => { set(typeof value === 'number' ? String(value) : 0); this.render(); };
      row.append(convert);
    }
    row.prepend(label, help);
    return row;
  }

  private arrayFields(values: CatalogValue[], path: string): HTMLElement {
    const box = el('div', { class: 'catalog-list' });
    if (path.endsWith('.steps')) {
      const operations = el('select', { ariaLabel: 'Effect to add', disabled: this.busy },
        ...STEP_TEMPLATES.map((template, index) => el('option', { value: String(index) }, template.label)));
      const addEffect = el('button', { type: 'button', disabled: this.busy }, 'Add effect');
      addEffect.onclick = () => {
        const template = STEP_TEMPLATES[Number(operations.value)];
        if (!template) return;
        values.push(cloneValue(template.value)); this.render();
      };
      box.append(el('p', { class: 'catalog-intro' }, 'Effects run in order after costs are paid. Choose a result, then edit its amount, resource or duration.'),
        el('div', { class: 'catalog-list-bar' }, operations, addEffect));
    }
    values.forEach((value, index) => {
      const row = el('div', { class: 'catalog-list-item' });
      const set = (next: CatalogValue) => { values[index] = next; this.notify(); };
      const content = this.field(path.split('.').at(-1) ?? 'value', value, `${path}[${index}]`, set);
      const remove = el('button', { type: 'button', disabled: this.busy }, 'Remove item');
      remove.onclick = () => { values.splice(index, 1); this.render(); };
      const up = el('button', { type: 'button', disabled: this.busy || index === 0 }, 'Move up');
      up.onclick = () => { [values[index - 1], values[index]] = [values[index], values[index - 1]]; this.render(); };
      const down = el('button', { type: 'button', disabled: this.busy || index === values.length - 1 }, 'Move down');
      down.onclick = () => { [values[index + 1], values[index]] = [values[index], values[index + 1]]; this.render(); };
      row.append(el('div', { class: 'catalog-list-bar' }, el('strong', {}, `Item ${index + 1}`), up, down, remove), content);
      box.append(row);
    });
    const samples = this.shapes.samples(path);
    const select = el('select', { ariaLabel: 'List item template', disabled: this.busy },
      ...samples.map((sample, index) => el('option', { value: String(index) },
        isCatalogObject(sample) ? `Template: ${String(sample.label ?? sample.name ?? sample.op ?? sample.stat ?? `group ${index + 1}`)}` : `Template: ${String(sample) || 'text'}`)));
    const add = el('button', { type: 'button', disabled: this.busy }, 'Add item');
    add.onclick = () => {
      const item = cloneValue(samples[Number(select.value)] ?? '');
      if (isCatalogObject(item)) delete item._catalogFeatureId;
      values.push(item); this.render();
    };
    box.append(el('div', { class: 'catalog-list-bar' }, select, add));
    return box;
  }
}

function fieldChoices(key: string, path: string): string[] {
  const traits = ['Agility', 'Strength', 'Finesse', 'Instinct', 'Presence', 'Knowledge'];
  if (key === 'kind') return path === 'classes.kind' ? ['class', 'subclass']
    : path.includes('.deathMove.') ? ['hopeBonus', 'hopeSpend', 'mask', 'undying', 'spectral', 'egg', 'battleHardened', 'lifeWard', 'unbreakable']
      : path.includes('.effect.') ? ['buff', 'debuff'] : [];
  if (key === 'type' && path.includes('.weapon.damage.')) return ['physical', 'magic'];
  return ({
    slot: ['primary', 'secondary'], trait: traits, spellcastTrait: traits,
    range: ['Melee', 'Very Close', 'Close', 'Far', 'Very Far'],
    burden: ['One-Handed', 'Two-Handed'], stage: ['foundation', 'specialization', 'mastery'],
    cardType: ['Ability', 'Spell', 'Grimoire'], per: ['shortRest', 'longRest', 'rest', 'scene', 'session'],
    until: ['manual', 'rest', 'shortRest', 'longRest', 'scene', 'session'],
    resetOn: ['shortRest', 'longRest', 'rest'], op: ['clear', 'gain', 'mark', 'effect'],
    timing: ['action', 'downtime', 'rest'], target: ['actor', 'party', 'allActors'], audience: ['self', 'ally', 'any'],
  } as Record<string, string[]>)[key]?.slice() ?? [];
}

import { el } from './login';
import { iconButton } from './icons';
import { pickActor } from './actor-picker';
import { groupFeatures } from './daggerheart/feature-groups';
import {
  ADDABLE, CompendiumEntry, CompendiumFile, CompendiumIndexItem, fetchCompendiumCategory, fetchCompendiumIndex, invalidateCompendium,
  matchesSearch, type CompendiumFetch,
} from './daggerheart/compendium';
export type { CompendiumEntry };

/** Appends text with **bold** and *italic* markers as DOM nodes (never as HTML). */
export function inlineText(parent: HTMLElement, text: string): void {
  const re = /\*\*(.+?)\*\*|\*(.+?)\*/g;
  let last = 0;
  for (const m of text.matchAll(re)) {
    if (m.index! > last) parent.append(text.slice(last, m.index));
    parent.append(el(m[1] ? 'strong' : 'em', {}, m[1] ?? m[2]));
    last = m.index! + m[0].length;
  }
  if (last < text.length) parent.append(text.slice(last));
}

/** Renders the small markdown subset used by the data files: paragraphs, bullet lists, bold and italic. */
export function markdownBlock(text: string): HTMLElement {
  const box = el('div', { class: 'cmp-text' });
  for (const block of text.split(/\n{2,}/)) {
    const lines = block.split('\n').filter((l) => l.trim());
    if (!lines.length) continue;
    if (lines.every((l) => /^\s*[-*] /.test(l))) {
      const ul = el('ul', {});
      for (const l of lines) { const li = el('li', {}); inlineText(li, l.replace(/^\s*[-*] /, '')); ul.append(li); }
      box.append(ul);
    } else {
      const p = el('p', {});
      lines.forEach((l, i) => { if (i) p.append(el('br', {})); inlineText(p, l); });
      box.append(p);
    }
  }
  return box;
}

export interface CompendiumHost {
  /** Characters that can receive entries; empty hides the add button. */
  actors: () => Array<{ id: string; name: string }>;
  request?: CompendiumFetch;
  /** Puts the entry on the actor's sheet; returns a description of the target or null. */
  add: (actorId: string, category: string, entry: CompendiumEntry) => string | null | Promise<string | null>;
  toast: (text: string) => void;
  link: (category: string, entry: CompendiumEntry) => Promise<void>;
}

interface Column {
  label: string;
  value: (e: CompendiumEntry) => string;
  /** Long text that wraps over several lines. */
  wide?: boolean;
  numeric?: boolean;
}

interface Filter {
  label: string;
  /** One or several values the entry can be found under. */
  value: (e: CompendiumEntry) => string | string[] | undefined;
}

interface Layout {
  columns: Column[];
  filters: Filter[];
}

const damage = (e: CompendiumEntry): string => {
  const d = e.weapon?.damage;
  if (!d) return '';
  return `${d.dice}${d.bonus ? (d.bonus > 0 ? `+${d.bonus}` : `${d.bonus}`) : ''} ${d.type}`;
};
const featureText = (e: CompendiumEntry): string => e.feature ? `${e.feature.name}: ${e.feature.text}` : '';
const featureNames = (e: CompendiumEntry): string => groupFeatures(e.features ?? []).map(({ feature }) => feature.name).join(', ');
const tier = (e: CompendiumEntry): string | undefined => e.tier ? `Tier ${e.tier}` : undefined;
const starter = (e: CompendiumEntry): string | undefined => e.starter ? 'Starter gear' : undefined;
const plain = (text: string | undefined): string => (text ?? '').replace(/\*+/g, '').replace(/\s+/g, ' ').trim();
const kindOf = (e: CompendiumEntry): string => e.kind === 'subclass' ? 'Subclass' : 'Class';

const LAYOUTS: Record<string, Layout> = {
  armor: {
    columns: [
      { label: 'Tier', value: (e) => String(e.tier ?? ''), numeric: true },
      { label: 'Score', value: (e) => String(e.armor?.score ?? ''), numeric: true },
      { label: 'Thresholds', value: (e) => e.armor ? `${e.armor.major} / ${e.armor.severe}` : '' },
      { label: 'Feature', value: (e) => plain(featureText(e)), wide: true },
    ],
    filters: [{ label: 'Tier', value: tier }, { label: 'Gear', value: starter }],
  },
  weapons: {
    columns: [
      { label: 'Tier', value: (e) => String(e.tier ?? ''), numeric: true },
      { label: 'Slot', value: (e) => e.weapon?.slot ?? '' },
      { label: 'Trait', value: (e) => e.weapon?.trait ?? '' },
      { label: 'Range', value: (e) => e.weapon?.range ?? '' },
      { label: 'Damage', value: damage },
      { label: 'Burden', value: (e) => e.weapon?.burden ?? '' },
      { label: 'Feature', value: (e) => plain(featureText(e)), wide: true },
    ],
    filters: [
      { label: 'Tier', value: tier }, { label: 'Slot', value: (e) => e.weapon?.slot }, { label: 'Trait', value: (e) => e.weapon?.trait },
      { label: 'Range', value: (e) => e.weapon?.range }, { label: 'Damage type', value: (e) => e.weapon?.damage.type },
      { label: 'Burden', value: (e) => e.weapon?.burden }, { label: 'Gear', value: starter },
    ],
  },
  'domain-cards': {
    columns: [
      { label: 'Domain', value: (e) => e.domain ?? '' },
      { label: 'Level', value: (e) => String(e.level ?? ''), numeric: true },
      { label: 'Type', value: (e) => e.cardType ?? '' },
      { label: 'Recall', value: (e) => e.recall === undefined ? '' : String(e.recall), numeric: true },
      { label: 'Text', value: (e) => plain(e.text), wide: true },
    ],
    filters: [
      { label: 'Domain', value: (e) => e.domain }, { label: 'Level', value: (e) => e.level === undefined ? undefined : `Level ${e.level}` },
      { label: 'Type', value: (e) => e.cardType },
    ],
  },
  consumables: {
    columns: [{ label: 'Rarity', value: (e) => e.rarity ?? '' }, { label: 'Description', value: (e) => plain(e.text), wide: true }],
    filters: [{ label: 'Rarity', value: (e) => e.rarity }],
  },
  items: {
    columns: [{ label: 'Rarity', value: (e) => e.rarity ?? '' }, { label: 'Description', value: (e) => plain(e.text), wide: true }],
    filters: [{ label: 'Rarity', value: (e) => e.rarity }],
  },
  classes: {
    columns: [
      { label: 'Type', value: kindOf },
      { label: 'Class', value: (e) => e.parent ?? '' },
      { label: 'Stage', value: (e) => e.stage ?? '' },
      { label: 'Domains', value: (e) => (e.domains ?? []).join(' & ') },
      { label: 'Spellcast', value: (e) => e.spellcastTrait ?? '' },
      { label: 'HP / Evasion', value: (e) => e.startingHp === undefined ? '' : `${e.startingHp} / ${e.startingEvasion ?? ''}` },
      { label: 'Features', value: featureNames, wide: true },
    ],
    filters: [
      { label: 'Type', value: kindOf }, { label: 'Class', value: (e) => e.kind === 'subclass' ? e.parent : e.name },
      { label: 'Stage', value: (e) => e.stage }, { label: 'Domain', value: (e) => e.domains },
      { label: 'Spellcast trait', value: (e) => e.spellcastTrait },
    ],
  },
};
const HERITAGE: Layout = {
  columns: [{ label: 'Description', value: (e) => plain(e.text), wide: true }, { label: 'Features', value: featureNames, wide: true }],
  filters: [],
};
const GENERIC: Layout = { columns: [{ label: 'Description', value: (e) => plain(e.text || featureText(e)), wide: true }], filters: [] };

function layoutFor(category: string, entries: CompendiumEntry[]): Layout {
  return LAYOUTS[category] ?? (entries.some((e) => e.features?.length) ? HERITAGE : GENERIC);
}

const valuesOf = (f: Filter, e: CompendiumEntry): string[] => {
  const v = f.value(e);
  return v === undefined ? [] : Array.isArray(v) ? v : [v];
};

function optionsOf(entries: CompendiumEntry[], f: Filter): string[] {
  const seen = new Set<string>();
  for (const e of entries) for (const v of valuesOf(f, e)) if (v) seen.add(v);
  return [...seen].sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
}

/** Window content: category navigation on the left, a searchable and filterable table on the right. */
export class CompendiumPanel {
  readonly element = el('div', { class: 'compendium' });
  private index: CompendiumIndexItem[] | null = null;
  private files = new Map<string, CompendiumFile>();
  private category = '';
  private queries = new Map<string, string>();
  private filterValues = new Map<string, Map<string, string>>();
  private loading = false;
  private error = '';

  constructor(private readonly host: CompendiumHost) {}

  refreshActors(): void { this.render(); }

  async reload(): Promise<void> {
    invalidateCompendium();
    this.files.clear();
    this.error = '';
    try {
      this.index = await fetchCompendiumIndex(this.host.request);
      if (!this.index.some(item => item.category === this.category)) this.category = this.index[0]?.category ?? '';
      if (this.category) await this.load(this.category);
    } catch (error) { this.error = (error as Error).message; }
    this.render();
    if (this.error) throw new Error(this.error);
  }

  async revalidate(): Promise<void> {
    if (this.index) await this.reload();
  }

  /** Called when the window becomes visible; loads the category list once. */
  async open(): Promise<void> {
    if (this.index || this.loading) return;
    this.loading = true;
    this.render();
    try {
      this.index = await fetchCompendiumIndex(this.host.request);
      this.category = this.index[0]?.category ?? '';
      if (this.category) await this.load(this.category);
    } catch (e) {
      this.error = (e as Error).message;
    }
    this.loading = false;
    this.render();
  }

  private async load(category: string): Promise<void> {
    if (this.files.has(category)) return;
    this.files.set(category, await fetchCompendiumCategory(category, this.host.request));
  }

  private async select(category: string): Promise<void> {
    this.category = category;
    this.error = '';
    try { await this.load(category); } catch (e) { this.error = (e as Error).message; }
    this.render();
  }

  private render(): void {
    this.element.replaceChildren();
    if (this.loading) { this.element.append(el('p', { class: 'muted cmp-note' }, 'Loading compendium...')); return; }
    if (!this.index?.length) {
      this.element.append(el('p', { class: `${this.error ? 'error' : 'muted'} cmp-note` }, this.error || 'The DM has no compendium data configured.'));
      return;
    }
    const nav = el('nav', { class: 'cmp-nav' });
    nav.setAttribute('aria-label', 'Compendium categories');
    for (const item of this.index) {
      const b = el('button', { type: 'button', class: `cmp-cat${item.category === this.category ? ' active' : ''}` },
        el('span', {}, item.label), el('span', { class: 'cmp-count' }, String(item.count)));
      if (item.category === this.category) b.setAttribute('aria-current', 'true');
      b.addEventListener('click', () => { void this.select(item.category); });
      nav.append(b);
    }
    const main = el('div', { class: 'cmp-main' });
    this.element.append(nav, main);
    this.renderMain(main);
  }

  private renderMain(main: HTMLElement): void {
    if (this.error) main.append(el('p', { class: 'error cmp-note' }, this.error));
    const file = this.files.get(this.category);
    if (!file) return;
    const layout = layoutFor(this.category, file.entries);
    const values = this.filterValues.get(this.category) ?? new Map<string, string>();
    this.filterValues.set(this.category, values);

    const result = el('div', { class: 'cmp-table-wrap' });
    const search = el('input', { type: 'search', placeholder: `Search ${file.label.toLowerCase()}...`, value: this.queries.get(this.category) ?? '' });
    search.setAttribute('aria-label', 'Search compendium');
    search.addEventListener('input', () => { this.queries.set(this.category, search.value); this.renderTable(result, file, layout); });
    const bar = el('div', { class: 'cmp-toolbar' }, search);
    for (const f of layout.filters) {
      const options = optionsOf(file.entries, f);
      if (options.length < 2) continue;
      const select = el('select', {}, el('option', { value: '' }, `${f.label}: all`), ...options.map((o) => el('option', { value: o }, o)));
      select.setAttribute('aria-label', f.label);
      select.value = values.get(f.label) ?? '';
      select.addEventListener('change', () => { values.set(f.label, select.value); this.renderTable(result, file, layout); });
      bar.append(select);
    }
    main.append(bar, result);
    this.renderTable(result, file, layout);
  }

  private matches(e: CompendiumEntry, layout: Layout): boolean {
    const values = this.filterValues.get(this.category);
    for (const f of layout.filters) {
      const wanted = values?.get(f.label);
      if (wanted && !valuesOf(f, e).includes(wanted)) return false;
    }
    return matchesSearch(e, this.queries.get(this.category) ?? '');
  }

  private renderTable(wrap: HTMLElement, file: CompendiumFile, layout: Layout): void {
    wrap.replaceChildren();
    const hits = file.entries.filter((e) => this.matches(e, layout));
    const addable = !!ADDABLE[this.category];
    const head = el('tr', {}, el('th', { class: 'cmp-expander' }), el('th', {}, 'Name'),
      ...layout.columns.map((c) => el('th', { class: c.wide ? 'wide' : c.numeric ? 'num' : '' }, c.label)),
      el('th', { class: 'cmp-actions' }, 'Actions'));
    const body = el('tbody', {});
    for (const e of hits) this.appendRow(body, e, layout, addable);
    const table = el('table', { class: 'cmp-table' }, el('thead', {}, head), body);
    wrap.append(el('div', { class: 'cmp-summary muted' }, `${hits.length} of ${file.entries.length} entries`), table);
    if (!hits.length) wrap.append(el('p', { class: 'muted cmp-note' }, 'Nothing found.'));
  }

  private appendRow(body: HTMLElement, e: CompendiumEntry, layout: Layout, addable: boolean): void {
    const toggle = el('button', { type: 'button', class: 'cmp-toggle', title: 'Show details' }, '\u25B8');
    toggle.setAttribute('aria-expanded', 'false');
    toggle.setAttribute('aria-label', `Details of ${e.name}`);
    const row = el('tr', { class: 'cmp-row' }, el('td', { class: 'cmp-expander' }, toggle), el('td', { class: 'nm' }, e.name),
      ...layout.columns.map((c) => el('td', { class: c.wide ? 'wide' : c.numeric ? 'num' : '' }, c.wide ? el('div', { class: 'clamp' }, c.value(e)) : c.value(e))));
    const actions = el('div', { class: 'cmp-action-buttons' });
    const link = iconButton('chat', `Link ${e.name} in chat`, () => {
      link.disabled = true;
      void this.host.link(this.category, e)
        .catch((error: Error) => this.host.toast(error.message))
        .finally(() => { link.disabled = false; });
    });
    link.addEventListener('click', ev => ev.stopPropagation());
    actions.append(link);
    if (addable) {
      const label = ADDABLE[this.category];
      const add = iconButton('add', `${label}: ${e.name}`, () => {
        void this.addEntry(e).catch((error: Error) => this.host.toast(error.message));
      });
      add.classList.add('cmp-add-icon');
      add.addEventListener('click', ev => ev.stopPropagation());
      if (this.host.actors().length) actions.prepend(add);
    }
    row.append(el('td', { class: 'cmp-actions' }, actions));
    let detail: HTMLElement | null = null;
    const flip = () => {
      if (detail) {
        detail.remove();
        detail = null;
      } else {
        detail = el('tr', { class: 'cmp-detail-row' }, el('td', { colSpan: layout.columns.length + 3 }, this.detail(e)));
        row.after(detail);
      }
      const open = !!detail;
      row.classList.toggle('open', open);
      toggle.textContent = open ? '\u25BE' : '\u25B8';
      toggle.title = open ? 'Hide details' : 'Show details';
      toggle.setAttribute('aria-expanded', String(open));
    };
    row.addEventListener('click', flip);
    body.append(row);
  }

  private async addEntry(e: CompendiumEntry): Promise<void> {
    const actor = await pickActor(this.host.actors(), `Add ${e.name} to`, 'Add');
    if (!actor) return;
    const where = await this.host.add(actor.id, this.category, e);
    this.host.toast(where ? `${e.name} added to ${where}.` : 'This entry cannot be added to a sheet.');
  }

  private detail(e: CompendiumEntry): HTMLElement {
    const box = el('div', { class: 'cmp-detail' });
    const badges = el('div', { class: 'badges' });
    const tag = (t: string) => badges.append(el('span', { class: 'tag' }, t));
    if (e.tier) tag(`Tier ${e.tier}`);
    if (e.rarity) tag(e.rarity);
    if (e.cardType) tag(e.cardType);
    if (e.domain) tag(`${e.domain} - level ${e.level ?? 1}`);
    if (e.recall !== undefined && e.cardType) tag(`Recall ${e.recall}`);
    if (e.armor) tag(`Armor score ${e.armor.score}`), tag(`Thresholds ${e.armor.major} / ${e.armor.severe}`);
    if (e.weapon) {
      const w = e.weapon;
      tag(`${w.slot} - ${w.burden}`); tag(`${w.trait} - ${w.range}`);
      tag(damage(e));
    }
    if (e.kind === 'class') {
      if (e.domains?.length) tag(e.domains.join(' & '));
      if (e.startingEvasion !== undefined) tag(`Evasion ${e.startingEvasion}`);
      if (e.startingHp !== undefined) tag(`HP ${e.startingHp}`);
    }
    if (e.kind === 'subclass') {
      if (e.parent) tag(e.parent);
      if (e.stage) tag(e.stage);
      if (e.spellcastTrait) tag(`Spellcast: ${e.spellcastTrait}`);
    }
    if (badges.childElementCount) box.append(badges);
    if (e.text) box.append(markdownBlock(e.text));
    if (e.feature) box.append(el('div', { class: 'feat' }, el('div', { class: 'ft' }, e.feature.name), markdownBlock(e.feature.text)));
    for (const { feature: f, children } of groupFeatures(e.features ?? [])) {
      const card = el('div', { class: 'feat' }, el('div', { class: 'ft' }, f.name), markdownBlock(f.text),
        ...children.map(child => el('div', { class: 'cmp-feature-section' }, el('div', { class: 'ft' }, child.name), markdownBlock(child.text))));
      box.append(card);
    }
    return box;
  }
}

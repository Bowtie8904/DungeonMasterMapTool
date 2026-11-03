import { el } from './login';
import { DRAG_TYPE } from './daggerheart/window';
import {
  ADDABLE, CompendiumEntry, CompendiumFile, CompendiumIndexItem, describeAutomation, fetchCompendiumCategory, fetchCompendiumIndex,
  matchesSearch,
} from './daggerheart/compendium';
export type { CompendiumEntry };

const MAX_RESULTS = 80;

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
  /** Names of the player's actors that can receive entries; empty hides the add button. */
  actors: () => Array<{ id: string; name: string }>;
  /** Puts the entry on the actor's sheet; returns a description of the target or null. */
  add: (actorId: string, category: string, entry: CompendiumEntry) => string | null | Promise<string | null>;
  toast: (text: string) => void;
  /** A drag from the list started; lets the app reveal the sheet to drop onto. */
  dragStart?: () => void;
}

/** Sidebar panel: searchable, read-only browser over the host's Daggerheart compendium. */
export class CompendiumPanel {
  readonly element = el('div', { class: 'compendium' });
  private index: CompendiumIndexItem[] | null = null;
  private files = new Map<string, CompendiumFile>();
  private category = '';
  private query = '';
  private selected: CompendiumEntry | null = null;
  private loading = false;
  private error = '';

  constructor(private readonly host: CompendiumHost) {}

  /** Called when the section becomes visible; loads the category list once. */
  async open(): Promise<void> {
    if (this.index || this.loading) return;
    this.loading = true;
    this.render();
    try {
      this.index = await fetchCompendiumIndex();
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
    this.files.set(category, await fetchCompendiumCategory(category));
  }

  private async select(category: string): Promise<void> {
    this.category = category;
    this.selected = null;
    this.error = '';
    try { await this.load(category); } catch (e) { this.error = (e as Error).message; }
    this.render();
  }

  private render(): void {
    this.element.replaceChildren();
    if (this.loading) { this.element.append(el('p', { class: 'muted' }, 'Loading compendium...')); return; }
    if (!this.index?.length) {
      this.element.append(el('p', { class: this.error ? 'error' : 'muted' }, this.error || 'The DM has no compendium data configured.'));
      return;
    }
    if (this.selected) { this.element.append(this.detail(this.selected)); return; }

    const search = el('input', { type: 'search', placeholder: 'Search name, domain, text...', value: this.query });
    search.setAttribute('aria-label', 'Search compendium');
    search.addEventListener('input', () => { this.query = search.value; this.renderList(list); });
    const chips = el('div', { class: 'cmp-chips' });
    for (const item of this.index) {
      const b = el('button', { type: 'button', class: `cmp-chip${item.category === this.category ? ' active' : ''}` }, `${item.label} (${item.count})`);
      b.addEventListener('click', () => { void this.select(item.category); });
      chips.append(b);
    }
    const list = el('div', { class: 'cmp-list' });
    this.element.append(search, chips, ...(this.error ? [el('p', { class: 'error' }, this.error)] : []), list);
    this.renderList(list);
  }

  private renderList(list: HTMLElement): void {
    list.replaceChildren();
    const file = this.files.get(this.category);
    if (!file) return;
    const hits = file.entries.filter((e) => matchesSearch(e, this.query));
    for (const e of hits.slice(0, MAX_RESULTS)) {
      const row = el('button', { type: 'button', class: 'cmp-row' }, el('span', { class: 'nm' }, e.name), el('span', { class: 'muted' }, this.summary(e)));
      row.addEventListener('click', () => { this.selected = e; this.render(); });
      if (ADDABLE[this.category]) {
        row.draggable = true;
        row.title = 'Drag onto your sheet to add';
        row.addEventListener('dragstart', (ev) => {
          ev.dataTransfer?.setData(DRAG_TYPE, JSON.stringify({ category: this.category, entry: e }));
          if (ev.dataTransfer) ev.dataTransfer.effectAllowed = 'copy';
          this.host.dragStart?.();
        });
      }
      list.append(row);
    }
    if (!hits.length) list.append(el('p', { class: 'muted' }, 'Nothing found.'));
    else if (hits.length > MAX_RESULTS) list.append(el('p', { class: 'muted' }, `Showing ${MAX_RESULTS} of ${hits.length}. Refine the search to see more.`));
  }

  private summary(e: CompendiumEntry): string {
    const bits: string[] = [];
    if (e.domain) bits.push(`${e.domain} ${e.level ?? ''}`.trim());
    if (e.cardType) bits.push(e.cardType);
    if (e.tier) bits.push(`T${e.tier}`);
    if (e.rarity) bits.push(e.rarity);
    if (e.armor) bits.push(`Score ${e.armor.score}`);
    if (e.weapon) bits.push(`${e.weapon.trait} ${e.weapon.range}`);
    if (e.kind === 'subclass') bits.push(`${e.parent ?? ''} ${e.stage ?? ''}`.trim());
    return bits.join(' - ');
  }

  private detail(e: CompendiumEntry): HTMLElement {
    const back = el('button', { type: 'button', class: 'small' }, '\u2190 Back');
    back.addEventListener('click', () => { this.selected = null; this.render(); });
    const box = el('div', { class: 'cmp-detail' }, back, el('h3', {}, e.name));
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
      tag(`${w.damage.dice}${w.damage.bonus ? (w.damage.bonus > 0 ? `+${w.damage.bonus}` : w.damage.bonus) : ''} ${w.damage.type}`);
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
    for (const f of e.features ?? []) {
      const card = el('div', { class: 'feat' }, el('div', { class: 'ft' }, f.name), markdownBlock(f.text));
      const auto = describeAutomation(f.automation);
      if (auto.length) card.append(el('div', { class: 'muted cmp-auto' }, `Automated: ${auto.join('; ')}`));
      box.append(card);
    }
    const auto = describeAutomation(e.automation);
    if (auto.length) box.append(el('div', { class: 'muted cmp-auto' }, `Automated on your sheet: ${auto.join('; ')}`));

    const label = ADDABLE[this.category];
    const actors = this.host.actors();
    if (label && actors.length) {
      const pick = el('select', {}, ...actors.map((a) => el('option', { value: a.id }, a.name)));
      pick.setAttribute('aria-label', 'Actor');
      const add = el('button', { type: 'button', class: 'primary small' }, label);
      add.addEventListener('click', () => {
      void Promise.resolve(this.host.add(pick.value, this.category, e)).then((where) => {
        this.host.toast(where ? `${e.name} added to ${where}.` : 'This entry cannot be added to a sheet.');
      });
      });
      box.append(el('div', { class: 'row cmp-add' }, ...(actors.length > 1 ? [pick] : []), add));
    }
    return box;
  }
}


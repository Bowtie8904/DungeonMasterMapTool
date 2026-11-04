import { notesRequest } from './api';
import { bringToFront, makeDraggable, trackStacking } from './daggerheart/window';
import { el } from './login';
import { openPopout } from './popout';
import { folderSvg } from './handouts';

const noteIcon = 'M6 3h8l5 5v13H6Z M14 3v5h5 M9 13h7 M9 17h7';

function noteSvg(): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('class', 'handout-folder-icon');
  const path = document.createElementNS(svg.namespaceURI, 'path');
  path.setAttribute('d', noteIcon);
  svg.append(path);
  return svg;
}

function noteRow(classes: string, label: string, lead: SVGSVGElement, chevron = '', title?: string): HTMLButtonElement {
  return el('button', { type: 'button', class: `file-row ${classes}`, role: 'treeitem', ...(title ? { title } : {}) },
    el('span', { class: 'file-chevron', ariaHidden: 'true' }, chevron), lead, el('span', { class: 'file-name' }, label));
}

export interface NoteEntry { path: string; name: string; folder: boolean; shared: boolean }
export interface NoteContent { path: string; text: string; revision: string }

export function withinNote(path: string, parent: string): boolean {
  return path === parent || path.startsWith(`${parent}/`);
}

export function noteChildren(entries: NoteEntry[], parent: string): NoteEntry[] {
  return entries.filter(e => e.path.slice(0, e.path.lastIndexOf('/')) === parent)
    .sort((a, b) => Number(b.folder) - Number(a.folder) || a.name.localeCompare(b.name));
}

function ask(title: string, action: string, value?: string): Promise<string | null> {
  return new Promise(resolve => {
    const dialog = el('dialog', { class: 'inventory-picker notes-dialog', ariaLabel: title });
    const input = value === undefined ? null : el('input', { type: 'text', value, maxLength: 96, required: true, ariaLabel: 'Name' });
    const cancel = el('button', { type: 'button' }, 'Cancel');
    const ok = el('button', { type: 'submit', class: 'primary' }, action);
    const form = el('form', {}, el('h3', {}, title), ...(input ? [input] : []), el('div', { class: 'row' }, cancel, ok));
    let result: string | null = null;
    form.addEventListener('submit', event => { event.preventDefault(); result = input?.value.trim() ?? ''; dialog.close(); });
    cancel.addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => { dialog.remove(); resolve(result); }, { once: true });
    dialog.append(form);
    document.body.append(dialog);
    dialog.showModal();
    input?.select();
  });
}

class NoteEditor {
  readonly element: HTMLElement;
  readonly textarea = el('textarea', { class: 'note-text', spellcheck: true });
  private heading = el('h2');
  private status = el('span', { class: 'note-status' });
  private saveButton = el('button', { type: 'button', class: 'primary' }, 'Save');
  private reloadButton = el('button', { type: 'button' }, 'Reload');
  private original: string;
  private revision: string;
  private busy = false;
  private observer: ResizeObserver;
  private readonly sizeKey: string;

  constructor(public entry: NoteEntry, content: NoteContent, sizeKey: string, private report: (text: string) => void,
    private removed: () => void, standalone = false) {
    this.original = content.text;
    this.revision = content.revision;
    this.sizeKey = sizeKey;
    this.textarea.value = content.text;
    const close = el('button', { type: 'button', title: 'Close note', ariaLabel: 'Close note' }, '\u2715');
    const minimise = el('button', { type: 'button', title: 'Minimise note', ariaLabel: 'Minimise note' }, '\u2013');
    const pop = el('button', { type: 'button', title: 'Pop out to a new browser window', ariaLabel: 'Pop out to a new browser window' }, '\u29C9');
    const bar = el('div', { class: 'dh-win-title' }, this.heading, ...(standalone ? [] : [pop, minimise, close]));
    this.status.setAttribute('role', 'status');
    this.element = el('section', { class: 'dh-win note-win' }, bar,
      el('div', { class: 'dh-win-body' }, this.textarea,
        el('div', { class: 'note-actions' }, this.saveButton, this.reloadButton, this.status)));
    trackStacking(this.element);
    if (standalone) this.element.classList.add('note-popout');
    pop.addEventListener('click', () => { void this.popOut(); });
    this.rename(entry);
    if (!standalone) makeDraggable(this.element, bar);
    close.addEventListener('click', () => { void this.close(); });
    minimise.addEventListener('click', () => this.element.classList.toggle('minimised'));
    this.saveButton.addEventListener('click', () => { void this.save(); });
    this.reloadButton.addEventListener('click', () => { void this.reload(); });
    this.textarea.addEventListener('input', () => this.updateStatus());
    this.element.addEventListener('keydown', event => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault(); void this.save();
      }
    });
    try {
      const size: unknown = JSON.parse(localStorage.getItem(sizeKey) ?? 'null');
      if (Array.isArray(size) && size.length === 2 && size.every(n => typeof n === 'number' && Number.isFinite(n))) {
        this.element.style.width = `${Math.max(280, Math.min(window.innerWidth, size[0]))}px`;
        this.element.style.height = `${Math.max(200, Math.min(window.innerHeight, size[1]))}px`;
      }
    } catch (error) {
      report(`Could not restore note window size: ${(error as Error).message}`);
    }
    let sizeWarning = false;
    this.observer = new ResizeObserver(() => {
      if (this.element.classList.contains('minimised') || !this.element.offsetWidth) return;
      try {
        localStorage.setItem(this.sizeKey, JSON.stringify([this.element.offsetWidth, this.element.offsetHeight]));
      } catch (error) {
        if (!sizeWarning) report(`Could not remember note window size: ${(error as Error).message}`);
        sizeWarning = true;
      }
    });
    if (!standalone) this.observer.observe(this.element);
    this.updateStatus();
  }

  get dirty(): boolean { return this.textarea.value !== this.original; }
  get saving(): boolean { return this.busy; }

  rename(entry: NoteEntry): void {
    this.entry = entry;
    this.heading.textContent = entry.name;
    this.element?.setAttribute('aria-label', `${entry.name} note`);
    this.textarea.setAttribute('aria-label', `${entry.name} contents`);
  }

  focus(): void {
    this.element.classList.remove('minimised');
    bringToFront(this.element);
    this.textarea.focus();
  }

  private updateStatus(message?: string): void {
    this.saveButton.disabled = this.busy || !this.dirty;
    this.reloadButton.disabled = this.busy;
    this.status.textContent = message ?? (this.busy ? 'Saving...' : this.dirty ? 'Unsaved changes' : 'Saved');
  }

  private async save(): Promise<boolean> {
    if (this.busy) return false;
    if (!this.dirty) return true;
    this.busy = true;
    const draft = this.textarea.value;
    this.updateStatus('Saving...');
    try {
      const saved = await notesRequest<NoteContent>('', {
        action: 'save', path: this.entry.path, text: draft, revision: this.revision,
      });
      this.original = draft;
      this.revision = saved.revision;
      this.busy = false;
      this.updateStatus();
      return true;
    } catch (error) {
      this.busy = false;
      this.updateStatus((error as Error).message);
      return false;
    }
  }

  private async popOut(): Promise<void> {
    if (this.busy) { this.report('Wait for the note request to finish before popping out.'); return; }
    const popup = openPopout('', this.element.offsetWidth, this.element.offsetHeight);
    if (!popup) { this.report('The note pop-out was blocked. Allow pop-ups for this site and try again.'); return; }
    this.textarea.readOnly = true;
    try {
      if (!await this.save()) { popup.close(); return; }
      if (popup.closed) { this.report('The note pop-out closed before it could open. Your note is saved.'); return; }
      popup.location.replace(`/?popout=note&path=${encodeURIComponent(this.entry.path)}`);
      this.destroy();
      this.removed();
    } catch (error) {
      popup.close();
      this.report(`Could not open the note pop-out: ${(error as Error).message}`);
    } finally { this.textarea.readOnly = false; }
  }

  private async reload(): Promise<void> {
    if (this.busy || this.dirty && await ask('Discard your draft and reload this note?', 'Discard and reload') === null) return;
    this.busy = true;
    this.textarea.readOnly = true;
    this.updateStatus('Loading...');
    try {
      const content = await notesRequest<NoteContent>(`/content?path=${encodeURIComponent(this.entry.path)}`);
      this.original = content.text;
      this.revision = content.revision;
      this.textarea.value = content.text;
      this.busy = false;
      this.updateStatus();
    } catch (error) {
      this.busy = false;
      this.updateStatus((error as Error).message);
    } finally {
      this.textarea.readOnly = false;
    }
  }

  private async close(): Promise<void> {
    if (this.busy) { this.report('Wait for the note request to finish before closing.'); return; }
    if (this.dirty && await ask('Discard unsaved changes to this note?', 'Discard') === null) return;
    this.destroy();
    this.removed();
  }

  destroy(): void {
    this.observer.disconnect();
    this.element.remove();
  }
}

export class NotesPanel {
  readonly element = el('div', { class: 'notes-section' });
  private tree = el('div', { class: 'notes-tree', ariaLabel: 'Campaign notes' });
  private message = el('p', { class: 'tool-hint' }, 'Waiting for the host...');
  private entries: NoteEntry[] = [];
  private windows = new Set<NoteEditor>();
  private loading = new Set<string>();
  private collapsed = new Set<string>();
  private selected = 'private';
  private enabled = false;
  private key = '';
  private generation = 0;
  private menu?: HTMLElement;
  private readonly buttons: HTMLButtonElement[];
  private unload = (event: BeforeUnloadEvent) => {
    if ([...this.windows].some(win => win.dirty || win.saving)) { event.preventDefault(); event.returnValue = ''; }
  };
  private dismissMenu = () => { this.menu?.remove(); this.menu = undefined; };

  constructor(private mount: (element: HTMLElement) => void, private report: (text: string) => void) {
    const note = el('button', { type: 'button' }, 'New note');
    const folder = el('button', { type: 'button' }, 'New folder');
    const refresh = el('button', { type: 'button' }, 'Refresh');
    note.addEventListener('click', () => { void this.create(false); });
    folder.addEventListener('click', () => { void this.create(true); });
    refresh.addEventListener('click', () => { void this.open(); });
    this.buttons = [note, folder, refresh];
    this.buttons.forEach(b => b.disabled = true);
    this.element.append(el('div', { class: 'notes-toolbar' }, ...this.buttons), this.message, this.tree);
    this.tree.addEventListener('contextmenu', event => {
      if (event.target === this.tree && this.enabled) {
        event.preventDefault(); this.selected = 'private'; this.context(event, undefined);
      }
    });
    window.addEventListener('beforeunload', this.unload);
    document.addEventListener('pointerdown', this.dismissMenu);
    document.addEventListener('keydown', this.menuKey);
  }

  private menuKey = (event: KeyboardEvent) => { if (event.key === 'Escape') this.dismissMenu(); };

  setSession(supported: boolean, campaign: string, player: string): void {
    const key = `dmmt.notes.size.${campaign}.${player}`;
    if (this.key && this.key !== key) {
      this.windows.forEach(w => w.destroy()); this.windows.clear();
      this.entries = []; this.collapsed.clear(); this.tree.replaceChildren(); this.selected = 'private';
    }
    this.generation++;
    this.key = key;
    this.enabled = supported;
    this.buttons.forEach(b => b.disabled = !supported);
    this.message.textContent = supported ? 'Private notes are visible only to you. Group Notes is shared with every campaign player.'
      : 'This host does not support campaign notes. Update the host to enable them.';
    this.tree.classList.toggle('hidden', !supported);
  }

  async open(): Promise<void> {
    if (!this.enabled) return;
    const generation = this.generation;
    try {
      const entries = await notesRequest<NoteEntry[]>();
      if (generation !== this.generation) return;
      this.entries = entries;
      this.render();
    } catch (error) {
      if (generation === this.generation) this.report((error as Error).message);
    }
  }

  private parent(): string {
    const entry = this.entries.find(e => e.path === this.selected);
    return entry?.folder ? entry.path : entry && !entry.shared ? entry.path.slice(0, entry.path.lastIndexOf('/')) : 'private';
  }

  private render(): void {
    const branch = (parent: string): HTMLElement => {
      const list = el('ul', { class: 'file-tree', role: 'group' });
      for (const entry of noteChildren(this.entries, parent)) {
        const row = noteRow('note-row', entry.name, entry.folder ? folderSvg() : noteSvg(),
          entry.folder ? this.collapsed.has(entry.path) ? '\u25b8' : '\u25be' : '',
          entry.folder ? 'Click to expand; right-click for actions' : 'Double-click to edit; right-click for actions');
        row.classList.toggle('selected', this.selected === entry.path);
        if (entry.folder) row.setAttribute('aria-expanded', String(!this.collapsed.has(entry.path)));
        row.addEventListener('click', () => {
          this.selected = entry.path;
          if (entry.folder) {
            if (this.collapsed.has(entry.path)) this.collapsed.delete(entry.path); else this.collapsed.add(entry.path);
            this.render();
          } else {
            this.tree.querySelectorAll('.selected').forEach(e => e.classList.remove('selected'));
            row.classList.add('selected');
          }
        });
        row.addEventListener('dblclick', () => { if (!entry.folder) void this.edit(entry); });
        row.addEventListener('keydown', event => {
          if (!entry.folder && event.key === 'Enter') { event.preventDefault(); void this.edit(entry); }
          if (event.key === 'F2' && !entry.shared) { event.preventDefault(); void this.rename(entry); }
          if (event.key === 'Delete' && !entry.shared) { event.preventDefault(); void this.delete(entry); }
        });
        row.addEventListener('contextmenu', event => { event.preventDefault(); event.stopPropagation(); this.context(event, entry); });
        list.append(el('li', {}, row, ...(entry.folder && !this.collapsed.has(entry.path) ? [branch(entry.path)] : [])));
      }
      return list;
    };
    const group = this.entries.find(e => e.shared);
    const groupButton = noteRow('note-row note-group', 'Group Notes', noteSvg());
    groupButton.addEventListener('dblclick', () => { if (group) void this.edit(group); });
    groupButton.addEventListener('keydown', e => { if (e.key === 'Enter' && group) void this.edit(group); });
    groupButton.addEventListener('contextmenu', e => { e.preventDefault(); this.context(e, group); });
    const root = noteRow('note-row', 'My notes', folderSvg(), '\u25be');
    root.classList.toggle('selected', this.selected === 'private');
    root.addEventListener('click', () => { this.selected = 'private'; this.render(); });
    root.addEventListener('contextmenu', e => { e.preventDefault(); this.selected = 'private'; this.context(e, undefined); });
    const top = el('ul', { class: 'file-tree', role: 'tree' },
      el('li', {}, groupButton), el('li', {}, root, branch('private')));
    this.tree.replaceChildren(el('ul', { class: 'file-list' }, el('li', { class: 'file-root' }, top)));
  }

  private context(event: MouseEvent, entry?: NoteEntry): void {
    this.dismissMenu();
    if (entry) this.selected = entry.path;
    const menu = el('div', { class: 'notes-context' });
    menu.setAttribute('role', 'menu');
    const add = (label: string, action: () => Promise<void>) => {
      const button = el('button', { type: 'button' }, label);
      button.setAttribute('role', 'menuitem');
      button.addEventListener('click', () => { this.dismissMenu(); void action(); });
      menu.append(button);
    };
    if (entry && !entry.folder) add('Open', () => this.edit(entry));
    if (!entry?.shared) {
      add('New note', () => this.create(false));
      add('New folder', () => this.create(true));
      if (entry) { add('Rename', () => this.rename(entry)); add('Delete', () => this.delete(entry)); }
    }
    menu.addEventListener('pointerdown', e => e.stopPropagation());
    document.body.append(menu);
    menu.style.left = `${Math.max(0, Math.min(event.clientX, window.innerWidth - menu.offsetWidth))}px`;
    menu.style.top = `${Math.max(0, Math.min(event.clientY, window.innerHeight - menu.offsetHeight))}px`;
    this.menu = menu;
    menu.querySelector('button')?.focus();
  }

  private async create(folder: boolean): Promise<void> {
    const parent = this.parent();
    const name = await ask(folder ? 'New folder' : 'New note', 'Create', '');
    if (name === null) return;
    try {
      const entry = await notesRequest<NoteEntry>('', { action: 'create', path: parent, name, folder });
      this.selected = entry.path; this.collapsed.delete(parent);
      await this.open();
      if (!folder) await this.edit(entry);
    } catch (error) { this.report((error as Error).message); }
  }

  async edit(entry: NoteEntry, standalone = false): Promise<void> {
    const existing = [...this.windows].find(w => w.entry.path === entry.path);
    if (existing) { existing.focus(); return; }
    if (this.loading.has(entry.path)) return;
    this.loading.add(entry.path);
    const generation = this.generation;
    try {
      const content = await notesRequest<NoteContent>(`/content?path=${encodeURIComponent(entry.path)}`);
      if (generation !== this.generation) return;
      const editor = new NoteEditor(entry, content, this.key, this.report, () => this.windows.delete(editor), standalone);
      this.windows.add(editor);
      this.mount(editor.element);
      editor.focus();
    } catch (error) { this.report((error as Error).message); }
    finally { this.loading.delete(entry.path); }
  }

  async openStandalone(path: string): Promise<void> {
    const entries = await notesRequest<NoteEntry[]>();
    const entry = entries.find(e => e.path === path && !e.folder);
    if (!entry) throw new Error('This note is unavailable or belongs to another player.');
    document.title = `${entry.name} - Notes`;
    await this.edit(entry, true);
  }

  private async rename(entry: NoteEntry): Promise<void> {
    if ([...this.windows].some(w => withinNote(w.entry.path, entry.path) && w.saving)) {
      this.report('Wait for the note request to finish before renaming.'); return;
    }
    const name = await ask(`Rename ${entry.folder ? 'folder' : 'note'}`, 'Rename', entry.name);
    if (name === null) return;
    try {
      const renamed = await notesRequest<NoteEntry>('', { action: 'rename', path: entry.path, name });
      for (const win of this.windows) {
        if (!withinNote(win.entry.path, entry.path)) continue;
        const path = renamed.path + win.entry.path.slice(entry.path.length);
        win.rename({ ...win.entry, path, name: win.entry.path === entry.path ? renamed.name : win.entry.name });
      }
      this.selected = renamed.path;
      this.collapsed = new Set([...this.collapsed].map(p => withinNote(p, entry.path) ? renamed.path + p.slice(entry.path.length) : p));
      await this.open();
    } catch (error) { this.report((error as Error).message); }
  }

  private async delete(entry: NoteEntry): Promise<void> {
    const affected = [...this.windows].filter(w => withinNote(w.entry.path, entry.path));
    if (affected.some(w => w.saving)) { this.report('Wait for the note request to finish before deleting.'); return; }
    const warning = entry.folder ? `Delete "${entry.name}" and all notes and folders inside it?` : `Delete "${entry.name}"?`;
    if (await ask(warning + (affected.some(w => w.dirty) ? ' Unsaved drafts will also be lost.' : ''), 'Delete') === null) return;
    try {
      await notesRequest('', { action: 'delete', path: entry.path });
      affected.forEach(w => { w.destroy(); this.windows.delete(w); });
      this.selected = 'private';
      await this.open();
    } catch (error) { this.report((error as Error).message); }
  }

  async confirmLeave(): Promise<boolean> {
    if ([...this.windows].some(w => w.saving)) { this.report('Wait for the note request to finish before logging out.'); return false; }
    return ![...this.windows].some(w => w.dirty) || await ask('Log out and discard unsaved note changes?', 'Discard and log out') !== null;
  }

  dispose(): void {
    this.generation++;
    this.windows.forEach(w => w.destroy()); this.windows.clear();
    this.dismissMenu();
    window.removeEventListener('beforeunload', this.unload);
    document.removeEventListener('pointerdown', this.dismissMenu);
    document.removeEventListener('keydown', this.menuKey);
  }
}

import { makeDraggable } from './daggerheart/window';
import { el } from './login';
import type { HandoutFile } from './protocol';

export interface HandoutRow { kind: 'folder' | 'file'; name: string; path: string; file?: HandoutFile }

export function handoutPath(file: HandoutFile): string {
  return file.folder ? `${file.folder}/${file.name}` : file.name;
}

/** Folders first, then files, each sorted by name, for the folder at {@code folder} ('' is the root). */
export function handoutRows(folders: string[], files: HandoutFile[], folder: string): HandoutRow[] {
  const rows: HandoutRow[] = [];
  for (const path of folders) {
    const cut = path.lastIndexOf('/');
    if ((cut < 0 ? '' : path.slice(0, cut)) === folder) rows.push({ kind: 'folder', name: path.slice(cut + 1), path });
  }
  for (const file of files) if (file.folder === folder) rows.push({ kind: 'file', name: file.name, path: handoutPath(file), file });
  return rows.sort((a, b) => Number(b.kind === 'folder') - Number(a.kind === 'folder')
    || a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function handoutUrl(id: string): string {
  return `/api/handouts/${encodeURIComponent(id)}`;
}

const folderIcon = 'M3 6a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z';

function folderSvg(): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('class', 'handout-folder-icon');
  const path = document.createElementNS(svg.namespaceURI, 'path');
  path.setAttribute('d', folderIcon);
  svg.append(path);
  return svg;
}

/** Read-only list-style file browser for the handouts the DM shared with this player. */
export class HandoutsPanel {
  readonly element = el('div', { class: 'handouts-section' });
  private search = el('input', { type: 'search', class: 'file-search', placeholder: 'Search files', ariaLabel: 'Search files' });
  private list = el('ul', { class: 'file-list', ariaLabel: 'Handouts' });
  private folders: string[] = [];
  private files: HandoutFile[] = [];
  private expanded = new Set<string>(['']);
  private windows = new Map<string, HTMLElement>();

  constructor(private mount: (element: HTMLElement) => void) {
    this.search.addEventListener('input', () => this.render());
    this.element.append(this.search, this.list);
    this.render();
  }

  update(folders: string[], files: HandoutFile[]): void {
    this.folders = folders;
    this.files = files;
    for (const [id, win] of [...this.windows]) {
      const file = files.find(f => f.id === id);
      if (!file) { win.remove(); this.windows.delete(id); } else win.querySelector('h2')!.textContent = file.name;
    }
    this.render();
  }

  open(id: string, name: string): void {
    const known = this.files.find(f => f.id === id);
    const title = known?.name ?? name;
    const existing = this.windows.get(id);
    if (existing) {
      existing.classList.remove('minimised');
      this.mount(existing);
      return;
    }
    const mk = (text: string, label: string, fn: () => void) => {
      const b = el('button', { type: 'button', title: label, ariaLabel: label }, text);
      b.addEventListener('click', fn);
      return b;
    };
    const win = el('section', { class: 'dh-win handout-win', ariaLabel: title });
    const bar = el('div', { class: 'dh-win-title' }, el('h2', {}, title),
      mk('\u2013', 'Minimise', () => win.classList.toggle('minimised')),
      mk('\u2715', 'Close', () => { win.remove(); this.windows.delete(id); }));
    const image = el('img', { class: 'handout-image', src: handoutUrl(id), alt: title, draggable: false });
    image.addEventListener('dblclick', () => image.classList.toggle('actual'));
    const failed = el('p', { class: 'muted hidden' }, 'This image is no longer available.');
    image.addEventListener('error', () => { image.classList.add('hidden'); failed.classList.remove('hidden'); });
    win.append(bar, el('div', { class: 'dh-win-body handout-body' }, image, failed));
    win.addEventListener('pointerdown', () => { if (win.parentElement?.lastElementChild !== win) win.parentElement?.append(win); });
    makeDraggable(win, bar);
    const offset = this.windows.size * 24;
    win.style.left = `${40 + offset}px`;
    win.style.top = `${40 + offset}px`;
    this.windows.set(id, win);
    this.mount(win);
  }

  closeAll(): void {
    this.windows.forEach(w => w.remove());
    this.windows.clear();
    this.folders = []; this.files = []; this.expanded = new Set(['']);
    this.render();
  }

  private render(): void {
    const query = this.search.value.trim().toLowerCase();
    const matching = query ? this.files.filter(f => f.name.toLowerCase().includes(query)) : this.files;
    const visible = new Set<string>(['']);
    if (query) {
      for (const f of matching) {
        let path = f.folder;
        while (path) { visible.add(path); path = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : ''; }
      }
    }
    const root = el('li', { class: 'file-root' });
    const branch = this.branch('', query, visible);
    if (!branch.childElementCount) {
      this.list.replaceChildren(el('li', { class: 'file-empty muted' },
        query ? 'No matching files.' : 'Your DM has not shared any files yet.'));
      return;
    }
    root.append(branch);
    this.list.replaceChildren(root);
  }

  private branch(folder: string, query: string, visible: Set<string>): HTMLElement {
    const ul = el('ul', { class: 'file-tree', role: folder ? 'group' : 'tree' });
    for (const item of handoutRows(this.folders, this.files, folder)) {
      if (query && (item.kind === 'folder' ? !visible.has(item.path) : !item.name.toLowerCase().includes(query))) continue;
      ul.append(this.row(item, query));
    }
    return ul;
  }

  private row(item: HandoutRow, query: string): HTMLElement {
    const folder = item.kind === 'folder';
    const open = folder && (query !== '' || this.expanded.has(item.path));
    const chevron = el('span', { class: 'file-chevron', ariaHidden: 'true' }, folder ? (open ? '\u25be' : '\u25b8') : '');
    const lead = folder ? folderSvg()
      : el('img', { class: 'file-thumb', src: handoutUrl(item.file!.id), alt: '', loading: 'lazy', draggable: false });
    const label = el('span', { class: 'file-name' }, item.name);
    const button = el('button', { type: 'button', class: 'file-row', role: 'treeitem',
      title: folder ? 'Click to expand or collapse' : `${item.file!.ext.toUpperCase()} \u00b7 ${formatSize(item.file!.size)} \u2013 double-click to view` },
      chevron, lead, label);
    if (folder) button.setAttribute('aria-expanded', String(open));
    const toggle = () => {
      if (this.expanded.has(item.path)) this.expanded.delete(item.path); else this.expanded.add(item.path);
      this.render();
    };
    if (folder) button.addEventListener('click', toggle);
    else {
      const activate = () => this.open(item.file!.id, item.file!.name);
      button.addEventListener('dblclick', activate);
      button.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); activate(); } });
      button.addEventListener('click', () => {
        this.list.querySelectorAll('.selected').forEach(e => e.classList.remove('selected'));
        button.classList.add('selected');
      });
    }
    const li = el('li', {}, button);
    if (open) li.append(this.branch(item.path, query, this.visibleFor(query)));
    return li;
  }

  private visibleFor(query: string): Set<string> {
    const visible = new Set<string>(['']);
    if (!query) return visible;
    for (const f of this.files) {
      if (!f.name.toLowerCase().includes(query)) continue;
      let path = f.folder;
      while (path) { visible.add(path); path = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : ''; }
    }
    return visible;
  }
}

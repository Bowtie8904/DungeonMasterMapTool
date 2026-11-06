import { CompendiumPanel } from './compendium';
import { el } from './login';
import { CatalogEditor } from './catalog-editor';
import type { CatalogObject } from './catalog-fields';

declare global {
  interface Window { dmmtCanCloseCatalog?: () => boolean; dmmtRequestCloseCatalog?: () => Promise<boolean> }
}

/** Standalone content for the host's JavaFX WebView, not a player login or browser pop-out. */
export function startDmCompendium(root: HTMLElement): void {
  const token = location.hash.slice(1);
  history.replaceState(null, '', location.pathname + location.search);
  const status = el('div', { class: 'tool-popout-status', role: 'status' });
  const body = el('div', { class: 'tool-popout' }, status);
  root.replaceChildren(body);
  document.title = 'Compendium';
  let actors: Array<{ id: string; name: string }> = [];
  const request = async (url: string, data?: CatalogObject, method?: string): Promise<Response> => {
    const response = await fetch(url, {
      method: method ?? (data ? 'POST' : 'GET'),
      credentials: 'omit',
      headers: { Authorization: `Bearer ${token}`, ...(data ? { 'Content-Type': 'application/json' } : {}) },
      ...(data ? { body: JSON.stringify(data) } : {}),
    });
    if (!response.ok) {
      const error = await response.json() as { error?: string };
      throw new Error(error.error ?? `Compendium unavailable (${response.status}).`);
    }
    return response;
  };
  const panel = new CompendiumPanel({
    request,
    actors: () => actors,
    toast: text => { status.textContent = text; },
    add: async (actorId, category, entry) => {
      const result = await (await request('/api/dm/compendium/add', { actorId, category, entryId: entry.id })).json() as { target: string };
      return result.target;
    },
    link: async (category, entry) => {
      await request('/api/dm/compendium/link', { category, entryId: entry.id });
      status.textContent = `${entry.name} linked in chat.`;
    },
  });
  const editor = new CatalogEditor(request, () => panel.reload());
  const browse = el('button', { type: 'button', class: 'active' }, 'Browse compendium');
  const edit = el('button', { type: 'button' }, 'Edit JSON catalogs');
  const tabs = el('div', { class: 'catalog-tabs' }, browse, edit);
  let editing = false;
  browse.onclick = async () => {
    if (!await editor.requestClose()) return;
    editor.discard();
    editing = false;
    editor.element.remove();
    body.append(panel.element);
    browse.classList.add('active'); edit.classList.remove('active');
  };
  edit.onclick = () => {
    editing = true;
    panel.element.remove();
    body.append(editor.element);
    edit.classList.add('active'); browse.classList.remove('active');
    void editor.open();
  };
  window.dmmtCanCloseCatalog = () => editor.canClose();
  window.dmmtRequestCloseCatalog = () => editor.requestClose();
  body.append(tabs, panel.element);
  let revision = '';
  let refreshing = false;
  const refresh = async () => {
    if (refreshing) return;
    refreshing = true;
    try {
      const hadActors = actors.length > 0;
      actors = await (await request('/api/dm/compendium/actors')).json() as typeof actors;
      if (hadActors !== (actors.length > 0)) panel.refreshActors();
      const index = await (await request('/api/compendium')).json() as { revision?: string };
      if (revision && index.revision !== revision) await panel.reload();
      else if (!editing) await panel.open();
      revision = index.revision ?? '';
    } catch (error) {
      status.textContent = (error as Error).message;
    } finally { refreshing = false; }
  };
  void refresh();
  const timer = window.setInterval(() => { void refresh(); }, 3000);
  window.addEventListener('pagehide', () => {
    clearInterval(timer);
    delete window.dmmtCanCloseCatalog;
    delete window.dmmtRequestCloseCatalog;
  }, { once: true });
}

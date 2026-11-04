import { CompendiumPanel } from './compendium';
import { el } from './login';

/** Standalone content for the host's JavaFX WebView, not a player login or browser pop-out. */
export function startDmCompendium(root: HTMLElement): void {
  const token = location.hash.slice(1);
  history.replaceState(null, '', location.pathname + location.search);
  const status = el('div', { class: 'tool-popout-status', role: 'status' });
  const body = el('div', { class: 'tool-popout' }, status);
  root.replaceChildren(body);
  document.title = 'Compendium';
  let actors: Array<{ id: string; name: string }> = [];
  const request = async (url: string, data?: Record<string, string>): Promise<Response> => {
    const response = await fetch(url, {
      method: data ? 'POST' : 'GET',
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
  body.append(panel.element);
  const refresh = async () => {
    try {
      const hadActors = actors.length > 0;
      actors = await (await request('/api/dm/compendium/actors')).json() as typeof actors;
      if (hadActors !== (actors.length > 0)) panel.refreshActors();
      await panel.open();
    } catch (error) {
      status.textContent = (error as Error).message;
    }
  };
  void refresh();
  const timer = window.setInterval(() => { void refresh(); }, 3000);
  window.addEventListener('pagehide', () => clearInterval(timer), { once: true });
}

import { CompendiumPanel, type CompendiumEntry } from './compendium';
import { openPopout } from './popout';
import { el } from './login';
import { Connection } from './net';
import { NotesPanel } from './notes';
import { Store } from './store';
import { postCompendiumCard } from './shared-cards';

/** Applies additions in the receiving live sheet, never in a stale second copy of its saved JSON. */
export function addFromPopout(actorId: string, category: string, entry: CompendiumEntry): Promise<string | null> {
  const popup = openPopout('', 900, 900, `dmmt-compendium-sheet-${actorId}`);
  if (!popup) return Promise.reject(new Error('The character sheet pop-out was blocked. Allow pop-ups for this site and try again.'));
  if (popup.location.href === 'about:blank') popup.location.replace(`/sheet/${encodeURIComponent(actorId)}`);
  popup.focus();
  const id = crypto.randomUUID();
  return new Promise((resolve, reject) => {
    let timer: number;
    const cleanup = () => { clearInterval(timer); clearTimeout(timeout); window.removeEventListener('message', received); };
    const received = (event: MessageEvent<{ type?: string; id?: string; target?: string | null; error?: string }>) => {
      if (event.origin !== location.origin || event.source !== popup || event.data?.type !== 'dmmt-compendium-added' || event.data.id !== id) return;
      cleanup();
      if (event.data.error) reject(new Error(event.data.error));
      else resolve(event.data.target ?? null);
    };
    const send = () => {
      if (popup.closed) { cleanup(); reject(new Error('The receiving sheet closed before the addition was confirmed.')); return; }
      popup.postMessage({ type: 'dmmt-compendium-add', id, actorId, category, entry }, location.origin);
    };
    window.addEventListener('message', received);
    const timeout = window.setTimeout(() => {
      cleanup();
      reject(new Error('The sheet did not confirm the addition. Check the receiving sheet before trying again.'));
    }, 30_000);
    timer = window.setInterval(send, 400);
    send();
  });
}

export function startToolPopout(root: HTMLElement, tool: 'note' | 'compendium', path: string): void {
  const body = el('div', { class: 'tool-popout' });
  const status = el('div', { class: 'tool-popout-status', role: 'status' }, 'Connecting...');
  const report = (message: string) => { status.textContent = message; };
  root.replaceChildren(body);
  body.append(status);
  document.title = tool === 'note' ? 'Notes' : 'Compendium';
  const store = new Store();
  let playerId = '';
  let initialized = false;
  let compendiumChat = false;
  let notes: NotesPanel | undefined;
  const compendium = tool === 'compendium' ? new CompendiumPanel({
    actors: () => store.state?.actors.filter(a => a.ownerId === playerId).map(a => ({ id: a.id, name: a.name })) ?? [],
    add: addFromPopout,
    toast: report,
    link: async (category, entry) => {
      if (!compendiumChat) throw new Error('Update the host to support compendium chat links.');
      if (!conn.isOpen) throw new Error('Not connected. Your link was not posted.');
      await postCompendiumCard(category, entry.id);
      report(`${entry.name} linked in chat.`);
    },
  }) : undefined;
  const conn = new Connection({
    onOpen: () => {},
    onStatus: state => {
      if (state === 'unauthorized' || state === 'kicked') {
        report('Your session ended. Sign in from the map tab, then reload this window. Unsaved note text remains available to copy.');
      } else if (state === 'closed') report('Disconnected. Reconnecting... Unsaved text is kept in this window.');
      else if (state === 'open') report('Connected');
    },
    onMessage: message => {
      if (message.type === 'hello') {
        playerId = message.playerId;
        compendiumChat = message.compendiumChat === true;
        if (initialized) return;
        initialized = true;
        if (tool === 'note') {
          if (!message.notes) { report('This host does not support campaign notes.'); return; }
          notes = new NotesPanel(element => body.append(element), report);
          notes.setSession(true, message.campaignId ?? '', message.playerId);
          void notes.openStandalone(path).catch((error: Error) => report(error.message));
        }
      } else if (message.type === 'catalogChanged') {
        if (compendium) void compendium.reload().catch((error: Error) => report(error.message));
      } else if (message.type === 'snapshot' || message.type === 'delta') {
        if (message.type === 'snapshot') store.applySnapshot(message.seq, message.state);
        else if (store.applyDelta(message.seq, message.ops) === 'gap') conn.send({ type: 'resync', lastSeq: store.lastSeq });
        if (compendium) {
          if (!compendium.element.isConnected) body.append(compendium.element);
          void compendium.open();
        }
      } else if (message.type === 'kicked') {
        conn.stop();
      } else if (message.type === 'error') report(message.message);
    },
  }, () => store.lastSeq);
  window.addEventListener('pagehide', () => { conn.stop(); notes?.dispose(); }, { once: true });
  conn.start();
}

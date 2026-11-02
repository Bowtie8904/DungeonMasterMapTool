import { getSheet } from './api';
import { Connection } from './net';
import { el, showLogin } from './login';
import { Store } from './store';
import { CharacterSheet, emptyCharacter, normalizeSheet } from './daggerheart/data';
import { autosave } from './daggerheart/persist';
import { renderSheetView } from './daggerheart/view';

export async function startSheet(root: HTMLElement, actorId: string): Promise<void> {
  const store = new Store();
  let unauthorized = false;
  let shown = false;
  let allowed = false;
  let character: CharacterSheet | undefined;
  let changed: () => void = () => {};
  const refresh = () => {
    const actor = store.state?.actors.find((a) => a.id === actorId);
    if (shown || !allowed || !actor || !character) return;
    shown = true;
    const sheet = character;
    document.title = `${actor.name} - Daggerheart`;
    changed = autosave(actorId, sheet, (m) => { document.title = m; });
    const view = renderSheetView({
      character: sheet,
      artwork: actor.artwork,
      onChange: () => changed(),
      onLog: (text) => { conn.send({ type: 'chat', text, whisper: false }); },
      onRoll: (r) => conn.send({ type: 'roll', expression: r.expression, label: r.label, kind: r.kind, difficulty: r.difficulty }),
    });
    view.classList.add('dh-popout');
    root.replaceChildren(view);
  };
  root.replaceChildren(el('div', { class: 'center' }, el('p', { class: 'muted' }, 'Loading sheet...')));

  const conn = new Connection(
    {
      onOpen: () => {},
      onStatus: (s) => {
        if (s === 'unauthorized' && !unauthorized) {
          unauthorized = true;
          void showLogin(root, () => location.reload());
        }
      },
      onMessage: (m) => {
        if (m.type === 'snapshot') store.applySnapshot(m.seq, m.state);
        else if (m.type === 'delta') {
          if (store.applyDelta(m.seq, m.ops) === 'gap') conn.send({ type: 'resync', lastSeq: store.lastSeq });
        } else if (m.type === 'kicked') {
          conn.stop();
          root.replaceChildren(el('div', { class: 'center' }, el('p', { class: 'error' }, 'You were removed from the session.')));
          return;
        } else return;
        if (!unauthorized) refresh();
      },
    },
    () => store.lastSeq,
  );
  conn.start();
  try {
    const res = await getSheet(actorId);
    const name = store.state?.actors.find((a) => a.id === actorId)?.name ?? res.name ?? 'Character';
    character = res.sheet ? normalizeSheet(res.sheet, name) : emptyCharacter(name);
    allowed = true;
    if (!unauthorized) refresh();
  } catch (e) {
    if (!unauthorized) {
      root.replaceChildren(el('div', { class: 'center' }, el('p', { class: 'error' }, `Cannot load sheet: ${(e as Error).message}`)));
    }
  }
}

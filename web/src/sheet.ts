import { getSheet } from './api';
import { Connection } from './net';
import { el, showLogin } from './login';
import { Store } from './store';
import { mockCharacter } from './daggerheart/data';
import { renderSheetView } from './daggerheart/view';

export async function startSheet(root: HTMLElement, actorId: string): Promise<void> {
  const store = new Store();
  let unauthorized = false;
  let shown = false;
  let allowed = false;
  const character = mockCharacter();
  const refresh = () => {
    const actor = store.state?.actors.find((a) => a.id === actorId);
    if (shown || !allowed || !actor) return;
    shown = true;
    character.name = actor.name;
    document.title = `${actor.name} - Daggerheart`;
    const view = renderSheetView({
      character,
      artwork: actor.artwork,
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
    await getSheet(actorId);
    allowed = true;
    if (!unauthorized) refresh();
  } catch (e) {
    if (!unauthorized) {
      root.replaceChildren(el('div', { class: 'center' }, el('p', { class: 'error' }, `Cannot load sheet: ${(e as Error).message}`)));
    }
  }
}

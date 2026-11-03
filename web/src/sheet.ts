import { getSheet } from './api';
import { Connection } from './net';
import { el, showLogin } from './login';
import { Store } from './store';
import { CharacterSheet, emptyCharacter, normalizeSheet } from './daggerheart/data';
import { applyInventory, autosave, type SheetSave } from './daggerheart/persist';
import { refreshCharacterAutomation } from './daggerheart/persist';
import { renderSheetView, SheetBus } from './daggerheart/view';
import { setGmFear } from './daggerheart/engine';
import { postCard, transferable } from './shared-cards';

export async function startSheet(root: HTMLElement, actorId: string): Promise<void> {
  const store = new Store();
  let unauthorized = false;
  let shown = false;
  let allowed = false;
  let character: CharacterSheet | undefined;
  let changed: SheetSave | undefined;
  let chatCards = false;
  let transferOffers = false;
  let rulesWarning = '';
  const bus = new SheetBus();
  const report = (message: string) => {
    let error = root.querySelector<HTMLElement>('.sheet-error');
    if (!error) { error = el('div', { class: 'sheet-error error', role: 'alert' }); root.append(error); }
    error.textContent = message;
  };
  const refresh = () => {
    const actor = store.state?.actors.find((a) => a.id === actorId);
    if (shown || !allowed || !actor || !character) return;
    shown = true;
    const sheet = character;
    document.title = `${actor.name} - Daggerheart`;
    changed = autosave(actorId, sheet, report);
    document.addEventListener('dmmt-inventory-refreshed', (event) => {
      if ((event as CustomEvent<string>).detail === actorId) bus.notify();
    });
    const view = renderSheetView({
      character: sheet,
      artwork: actor.artwork,
      onChange: () => changed?.(),
      bus,
      actorId,
      onPrepareShare: () => changed!.flush(),
      onShare: (entry, quantity) => {
        const share = async () => {
          if (!chatCards) throw new Error('This host does not support shared chat cards. Update the host to share cards.');
          if (transferable(entry) && !transferOffers) throw new Error('Update the host to support safe inventory links and transfer offers.');
          if (!conn.isOpen) throw new Error('Not connected. Your card was not posted.');
          await changed!.flush();
          await postCard(actorId, entry, undefined, quantity);
        };
        void share().catch((e: Error) => report(e.message));
      },
      onLog: (text, rollSource) => { conn.send({ type: 'chat', text, rollSource, whisper: false }); },
      onRoll: (r) => conn.send({ type: 'roll', ...r }),
      supportsKeptDice: () => conn.keptDice,
    });
    view.classList.add('dh-popout');
    root.replaceChildren(view);
    if (rulesWarning) report(rulesWarning);
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
        if (m.type === 'hello') {
          chatCards = m.chatCards === true;
          transferOffers = m.transferOffers === true;
          if (changed) void changed.refresh().catch((e: Error) => report(e.message));
          return;
        }
        if (m.type === 'sheetInventory') {
          if (m.actorId === actorId && character && applyInventory(character, m.inventory)) bus.notify();
          return;
        }
        if (m.type === 'snapshot') store.applySnapshot(m.seq, m.state);
        else if (m.type === 'delta') {
          if (store.applyDelta(m.seq, m.ops) === 'gap') conn.send({ type: 'resync', lastSeq: store.lastSeq });
        } else if (m.type === 'kicked') {
          conn.stop();
          root.replaceChildren(el('div', { class: 'center' }, el('p', { class: 'error' }, 'You were removed from the session.')));
          return;
        } else return;
        setGmFear(store.state?.fear);
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
    if (res.sheet) {
      try { await refreshCharacterAutomation(character); }
      catch (e) { rulesWarning = `Could not refresh compendium rules; using saved rules: ${(e as Error).message}`; }
    }
    allowed = true;
    if (!unauthorized) refresh();
  } catch (e) {
    if (!unauthorized) {
      root.replaceChildren(el('div', { class: 'center' }, el('p', { class: 'error' }, `Cannot load sheet: ${(e as Error).message}`)));
    }
  }
}

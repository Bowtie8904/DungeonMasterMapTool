import { getSheet, useFeature, damageTaken } from './api';
import { Connection } from './net';
import { el, showLogin } from './login';
import { Store } from './store';
import { CharacterSheet, emptyCharacter, normalizeSheet } from './daggerheart/data';
import { applyFeatureState, applyInventory, autosave, featureTarget, type SheetSave } from './daggerheart/persist';
import { refreshCharacterAutomation } from './daggerheart/persist';
import { renderSheetView, SheetBus } from './daggerheart/view';
import { setGmFear } from './daggerheart/engine';
import { postCard, transferable } from './shared-cards';
import { addToSheet, type CompendiumEntry } from './daggerheart/compendium';
import { pickActor } from './actor-picker';

export async function startSheet(root: HTMLElement, actorId: string): Promise<void> {
  const store = new Store();
  let unauthorized = false;
  let shown = false;
  let allowed = false;
  let character: CharacterSheet | undefined;
  let changed: SheetSave | undefined;
  let chatCards = false;
  let transferOffers = false;
  const connectedPlayers = new Set<string>();
  let rulesWarning = '';
  const bus = new SheetBus();
  const additions = new Map<string, Promise<string | null>>();
  const report = (message: string) => {
    let error = root.querySelector<HTMLElement>('.sheet-error');
    if (!error) { error = el('div', { class: 'sheet-error error', role: 'alert' }); root.append(error); }
    error.textContent = message;
  };
  const receiveEntry = async (category: string, entry: CompendiumEntry): Promise<string | null> => {
    if (!character || !changed || !allowed || unauthorized) throw new Error('The receiving character sheet is not ready.');
    const target = addToSheet(character, category, entry);
    if (target) { bus.notify(); changed(); await changed.flush(); }
    return target ? `${character.name}'s ${target}` : null;
  };
  const receive = (event: MessageEvent<{ type?: string; id?: string; actorId?: string; category?: string; entry?: CompendiumEntry }>) => {
    const request = event.data;
    if (event.origin !== location.origin || request?.type !== 'dmmt-compendium-add' || request.actorId !== actorId
      || typeof request.id !== 'string' || typeof request.category !== 'string'
      || !request.entry || typeof request.entry.name !== 'string' || !event.source || !changed) return;
    let operation = additions.get(request.id);
    if (!operation) {
      operation = receiveEntry(request.category, request.entry);
      additions.set(request.id, operation);
      if (additions.size > 200) additions.delete(additions.keys().next().value!);
    }
    const source = event.source;
    const reply = (target: string | null, error?: string) => {
      if ('postMessage' in source && 'closed' in source)
        source.postMessage({ type: 'dmmt-compendium-added', id: request.id, target, error }, event.origin);
    };
    void operation.then(target => reply(target), (error: Error) => { report(error.message); reply(null, error.message); });
  };
  window.addEventListener('message', receive);
  window.addEventListener('pagehide', () => window.removeEventListener('message', receive), { once: true });
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
      onFeature: async (key, actionId, amount) => {
        await changed!.flush();
        let targetActorId: string | undefined;
        if (featureTarget(sheet, key, actionId) === 'actor') {
          const candidates = (store.state?.actors ?? []).filter(a => connectedPlayers.has(a.ownerId) && (amount === undefined || a.id !== actorId));
          const selected = await pickActor(candidates, 'Choose a character', 'Select target');
          if (!selected) return;
          targetActorId = selected.id;
        }
        await useFeature({ actorId, key, actionId, amount, ...(targetActorId ? { targetActorId } : {}) });
      },
      onDamageTaken: async (severity) => {
        await changed!.flush();
        await damageTaken(actorId, severity);
      },
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
      onLog: (text, rollSource, restDice) => {
        const send = () => { if (!conn.send({ type: 'chat', text, rollSource, restDice, actorId, whisper: false })) report('Not connected. The report was not posted.'); };
        if (restDice) { changed!(); void changed!.flush().then(send).catch((e: Error) => report(e.message)); }
        else send();
      },
      onRoll: (r) => conn.send({ type: 'roll', ...r }),
      supportsKeptDice: () => conn.keptDice,
      supportsRollMultipliers: () => conn.rollMultipliers,
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
        if (m.type === 'roster') {
          connectedPlayers.clear();
          for (const player of m.players) if (player.connections > 0) connectedPlayers.add(player.playerId);
          return;
        }
        if (m.type === 'sheetInventory') {
          if (m.actorId === actorId && character && applyInventory(character, m.inventory)) bus.notify();
          return;
        }
        if (m.type === 'sheetFeature') {
          if (m.actorId === actorId && character && applyFeatureState(character, m)) {
            bus.notify();
            changed?.();
          }
          return;
        }
        if (m.type === 'snapshot') store.applySnapshot(m.seq, m.state);
        else if (m.type === 'delta') {
          if (store.applyDelta(m.seq, m.ops) === 'gap') conn.send({ type: 'resync', lastSeq: store.lastSeq });
        } else if (m.type === 'kicked') {
          allowed = false;
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

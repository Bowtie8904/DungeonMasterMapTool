import { acceptSheetVersion, getSheet, useFeature, damageTaken, takeDeathMove, getDeathOptions, dmSheetAuthorization } from './api';
import { Connection } from './net';
import { el, showLogin } from './login';
import { Store } from './store';
import { CharacterSheet, emptyCharacter, normalizeSheet } from './daggerheart/data';
import { applyFeatureState, applyInventory, autosave, featureTarget, isLifeWard, type SheetSave } from './daggerheart/persist';
import { basicDeathOptions } from './daggerheart/death';
import { refreshCharacterAutomation } from './daggerheart/persist';
import { renderSheetView, SheetBus } from './daggerheart/view';
import { setGmFear } from './daggerheart/engine';
import { postCard, transferable } from './shared-cards';
import { addToSheet, type CompendiumEntry } from './daggerheart/compendium';
import { pickActor } from './actor-picker';
import { openCopycat } from './daggerheart/copycat-view';
import { setDisabledCopycatSources } from './daggerheart/copycat';
import { cardPreferences } from './daggerheart/card-preferences';

declare global {
  interface Window { dmmtFlushSheet?: () => Promise<void> }
}

export async function startSheet(root: HTMLElement, actorId: string): Promise<void> {
  const store = new Store();
  let unauthorized = false;
  let shown = false;
  let allowed = false;
  let character: CharacterSheet | undefined;
  let changed: SheetSave | undefined;
  let chatCards = false;
  let transferOffers = false;
  let goldTransfers = false;
  const connectedPlayers = new Set<string>();
  let rulesWarning = '';
  let actorName = 'Character';
  let disabled = false;
  let artwork: string | undefined;
  const bus = new SheetBus();
  const additions = new Map<string, Promise<string | null>>();
  const report = (message: string) => {
    let error = root.querySelector<HTMLElement>('.sheet-error');
    if (!error) { error = el('div', { class: 'sheet-error error', role: 'alert' }); root.append(error); }
    error.textContent = message;
  };
  const receiveEntry = async (category: string, entry: CompendiumEntry): Promise<string | null> => {
    if (!character || !changed || !allowed || unauthorized || disabled && !dmSheetAuthorization()) throw new Error('The receiving character sheet is not ready or is read-only.');
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
    if (shown || !allowed || !character) return;
    shown = true;
    const sheet = character;
    document.title = `${actor?.name ?? actorName} - Daggerheart`;
    const readOnly = () => disabled && !dmSheetAuthorization();
    changed = autosave(actorId, sheet, report, 700, readOnly);
    if (dmSheetAuthorization()) window.dmmtFlushSheet = () => changed!.flush();
    document.addEventListener('dmmt-inventory-refreshed', (event) => {
      if ((event as CustomEvent<string>).detail === actorId) {
        document.title = `${sheet.name} - Daggerheart`;
        bus.notify();
      }
    });
    document.addEventListener('dmmt-feature-refreshed', (event) => {
      if ((event as CustomEvent<string>).detail === actorId) bus.notify();
    });
    const view = renderSheetView({
      cardPreferences,
      character: sheet,
      supportsSpecialCardActions: () => conn.specialCardActions,
      gmFear: () => store.state?.fear ?? 0,
      onSpecialCard: async (key, actionId, choice) => {
        await changed!.flush();
        await useFeature({ actorId, key, actionId, ...choice });
        await changed!.refresh();
      },
      readOnly,
      supportsPartyFeatures: () => conn.partyFeatures,
      artwork: actor?.artwork ?? artwork,
      onChange: () => {
        document.title = `${sheet.name} - Daggerheart`;
        changed?.();
      },
      onFeature: async (key, actionId, amount) => {
        await changed!.flush();
        let targetActorId: string | undefined;
        const target = featureTarget(sheet, key, actionId);
        if (target === 'actor' || target === 'anyActor') {
          const ward = isLifeWard(sheet, key);
          if (ward && !conn.deathMoveOptions) throw new Error('This host does not support Life Ward. Update the host.');
          const candidates = (store.state?.actors ?? []).filter(a => !a.disabled && (ward ? a.id !== actorId : (target === 'anyActor' || connectedPlayers.has(a.ownerId)) && (amount === undefined || a.id !== actorId)));
          const selected = await pickActor(candidates, 'Choose a character', 'Select target', target === 'anyActor');
          if (!selected) return;
          targetActorId = selected.id;
        }
        await useFeature({ actorId, key, actionId, amount, ...(targetActorId ? { targetActorId } : {}), ...(isLifeWard(sheet, key) ? { confirmRange: true } : {}) });
        await changed!.refresh();
      },
      onCopycat: (key, actionId) => openCopycat(sheet, actorId, key, actionId, changed!),
      onDamageTaken: async (severity) => {
        await changed!.flush();
        await damageTaken(actorId, severity);
      },
      onDeathOptions: async () => {
        await changed!.flush();
        if (!conn.isOpen) throw new Error('Not connected. Cannot load death-move options.');
        return conn.deathMoveOptions ? getDeathOptions(actorId) : basicDeathOptions();
      },
      onDeathMove: async (move, extras) => {
        if (!conn.isOpen) throw new Error('Not connected. The death move was not made.');
        if (!conn.deathMoves) throw new Error('This host does not support death moves. Update the host.');
        if (!conn.deathMoveOptions && (extras || !['blaze', 'avoid', 'risk'].includes(move))) throw new Error('This host does not support expanded death moves. Update the host.');
        await changed!.flush();
        await takeDeathMove(actorId, move, extras);
      },
      bus,
      actorId,
      onPrepareShare: () => changed!.flush(),
      onShare: (entry, quantity) => {
        const share = async () => {
          if (!chatCards) throw new Error('This host does not support shared chat cards. Update the host to share cards.');
          if (transferable(entry) && !transferOffers) throw new Error('Update the host to support safe inventory links and transfer offers.');
          if (entry.category === 'gold' && !goldTransfers) throw new Error('Update the host to transfer gold.');
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
      supportsZeroKeptDice: () => conn.zeroKeptDice,
      supportsBrawlerRolls: () => conn.brawlerRolls,
      supportsDruidAutomation: () => conn.druidAutomation,
      supportsSeraphAutomation: () => conn.seraphAutomation,
      supportsSorcererAutomation: () => conn.sorcererAutomation,
      supportsWarlockAutomation: () => conn.warlockAutomation,
      supportsWitchAutomation: () => conn.witchAutomation,
      supportsDomainCardAutomation: () => conn.domainCardAutomation,
      supportsBloodAutomation: () => conn.bloodAutomation,
      supportsRollMultipliers: () => conn.rollMultipliers,
    });
    view.classList.add('dh-popout');
    root.replaceChildren(view);
    if (rulesWarning) report(rulesWarning);
    void changed.refresh().catch((e: Error) => report(e.message));
  };
  root.replaceChildren(el('div', { class: 'center' }, el('p', { class: 'muted' }, 'Loading sheet...')));

  const conn = new Connection(
    {
      onOpen: () => {},
      onStatus: (s) => {
        if (s === 'unauthorized' && !unauthorized) {
          unauthorized = true;
          if (dmSheetAuthorization()) report('The DM sheet session is unavailable. Close and reopen it while hosting.');
          else void showLogin(root, () => location.reload());
        }
      },
      onMessage: (m) => {
        if (m.type === 'hello') {
          chatCards = m.chatCards === true;
          transferOffers = m.transferOffers === true;
          goldTransfers = m.goldTransfers === true;
          if (changed) void changed.refresh().catch((e: Error) => report(e.message));
          return;
        }
        if (m.type === 'roster') {
          connectedPlayers.clear();
          for (const player of m.players) if (player.connections > 0) connectedPlayers.add(player.playerId);
          return;
        }
        if (m.type === 'catalogChanged') {
          if (changed) void changed.flush().then(() => changed!.refresh()).catch((error: Error) => report(error.message));
          return;
        }
        if (m.type === 'sheetChanged') {
          if (m.actorId === actorId && changed) void changed.refresh().catch((e: Error) => report(e.message));
          return;
        }
        if (m.type === 'error') { report(m.message); return; }
        if (m.type === 'sheetInventory') {
          if (conn.sheetSync) return;
          if (m.actorId === actorId && character && applyInventory(character, m.inventory)) bus.notify();
          return;
        }
        if (m.type === 'sheetFeature') {
          if (conn.sheetSync) return;
          if (m.actorId === actorId && character && applyFeatureState(character, m)) {
            bus.notify();
            changed?.();
          }
          return;
        }
        if (m.type === 'rollUpdate') {
          store.applyRollUpdate(m);
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
        const actorsChanged = m.type === 'snapshot' || store.changed.has('actors');
        if (actorsChanged) setDisabledCopycatSources((store.state?.actors ?? []).filter(a => a.disabled).map(a => a.id));
        const actor = store.state?.actors.find(a => a.id === actorId);
        if (actor) disabled = !!actor.disabled;
        if (shown && actorsChanged) {
          bus.artwork = actor?.artwork;
          bus.notify();
          if (changed) void changed.refresh().catch((e: Error) => report(e.message));
        }
        setGmFear(store.state?.fear);
        if (!unauthorized) refresh();
      },
    },
    () => store.lastSeq,
  );
  conn.start();
  try {
    const res = await getSheet(actorId);
    acceptSheetVersion(actorId, res.version);
    const name = store.state?.actors.find((a) => a.id === actorId)?.name ?? res.name ?? 'Character';
    actorName = name;
    disabled = store.state?.actors.find(a => a.id === actorId)?.disabled ?? res.disabled ?? false;
    artwork = res.artwork;
    character = res.sheet ? normalizeSheet(res.sheet, name) : emptyCharacter(name);
    character.testingCharacter = res.testingCharacter === true;
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
  window.addEventListener('pagehide', () => conn.stop(), { once: true });
}

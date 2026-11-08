import { cardRequest, uploadArtwork, logout, useFeature, damageTaken, takeDeathMove, getDeathOptions } from './api';
import { decodeBitset, encodeBitset } from './bitset';
import { Connection } from './net';
import { ChatMessage, Actor, GameState, Overlay, RollMessage, ServerMessage } from './protocol';
import { iconButton } from './icons';
import { LogEntry, renderLogEntry } from './chat';
import { applyFeatureState, applyInventory, autosave, featureTarget, isLifeWard, loadCharacter, type SheetSave } from './daggerheart/persist';
import { basicDeathOptions } from './daggerheart/death';
import { INVENTORY_DRAG, postCard, postCompendiumCard, transferable, waitForInventoryDrag, type GrabQuantity, type SharedCard, type SharedEntry } from './shared-cards';
import { SheetWindow, ToolWindow } from './daggerheart/window';
import { SheetBus } from './daggerheart/view';
import { renderEffects } from './effects';
import { renderCountdowns } from './countdowns';
import { setGmFear } from './daggerheart/engine';
import { rollButtons } from './daggerheart/rollbuttons';
import { addToSheet } from './daggerheart/compendium';
import { CompendiumPanel } from './compendium';
import { pickActor } from './actor-picker';
import { openCopycat } from './daggerheart/copycat-view';
import { setDisabledCopycatSources } from './daggerheart/copycat';
import type { CharacterSheet } from './daggerheart/data';
import { MapTool } from './tools';
import { MapView, MoveCommit } from './render';
import { el } from './login';
import { OnlineAudio } from './audio';
import { markAudioGesture, settings } from './settings';
import { Store } from './store';
import { NotesPanel } from './notes';
import { HandoutsPanel } from './handouts';
import { openPopout } from './popout';

const EXPLORED_MIN_INTERVAL_MS = 2000;

type SidebarSection = 'chat' | 'actors' | 'notes' | 'files' | 'settings';
type DrawingEdit = { action: 'create' | 'update' | 'delete'; id: string; shape?: Overlay };
type SheetHandle = { character: CharacterSheet; bus: SheetBus; save: SheetSave };

export class App {
  private store = new Store();
  private view: MapView;
  private conn: Connection;
  private playerId = '';
  private connectedPlayers = new Set<string>();
  private playerWhispers = false;
  private chatCards = false;
  private compendiumChat = false;
  private transferOffers = false;
  private goldTransfers = false;
  private grabQuantity = false;
  private drawingHistory: DrawingEdit[] = [];
  private pendingDrawings = new Map<string, { inverse: DrawingEdit; undo: boolean; after?: Overlay; record: boolean }>();
  private historyMap = '';
  private section: SidebarSection | null = 'chat';
  private sidebar = el('aside', { class: 'sidebar', id: 'player-sidebar' });
  private sectionTitle = el('h2', {}, 'Chat');
  private sections = new Map<string, HTMLElement>();
  private sectionButtons = new Map<string, HTMLButtonElement>();
  private toolButtons = new Map<MapTool, HTMLButtonElement>();
  private flyout = el('aside', { class: 'tool-flyout hidden', id: 'tool-flyout' });
  private drawingOptions = el('div', { class: 'tool-options' });
  private measuringOptions = el('div', { class: 'tool-options hidden' });
  private toolTitle = el('h2', {}, 'Drawing');
  private rosterEl = el('aside', { class: 'players-overlay' });
  private effectsEl = el('aside', { class: 'effects-overlay' });
  private expandedEffects = new Set<string>();
  private textureSelect = el('select', { id: 'effect-texture' });
  private colorInput = el('input', { type: 'color', id: 'drawing-color', value: '#55aa33' });
  private colorEditing = false;
  private colorSelection: string | null = null;
  private colorTimer: number | null = null;
  private lastColorSent = -Infinity;
  private sizeInput = el('input', { type: 'range', id: 'drawing-size', min: '0.1', max: '20', step: '0.1', value: '2' });
  private alphaInput = el('input', { type: 'range', id: 'drawing-opacity', min: '0', max: '1', step: '0.05', value: '1' });
  private borderInput = el('input', { type: 'checkbox' });
  private emitsInput = el('input', { type: 'checkbox' });
  private aboveInput = el('input', { type: 'checkbox', checked: true });
  private selectionHint = el('p', { class: 'tool-hint' }, 'Select controlled tokens, drawings or measurements by clicking or dragging a box.');
  private deleteButton = iconButton('delete', 'Delete selected drawing or measurement', () => this.view.deleteSelection());
  private audioGesture = () => {
    if (this.audioAvailable && !settings.muted) { this.audio.enable(); this.updateSoundUi(); }
  };
  private undoKey = (e: KeyboardEvent) => {
    const target = e.target;
    if (e.defaultPrevented || target instanceof HTMLElement &&
      (target.isContentEditable || target.closest('textarea, select')
        || target instanceof HTMLInputElement && !['range', 'color', 'checkbox', 'button'].includes(target.type))) return;
    if ((e.ctrlKey || e.metaKey) && !e.shiftKey && e.key.toLowerCase() === 'z') {
      e.preventDefault(); this.undoDrawing();
    }
  };
  private cmdCounter = 0;
  private pendingMoves = new Map<string, MoveCommit>();
  private log: LogEntry[] = [];
  private logEl = el('div', { class: 'log' });
  private logContent = el('div', { class: 'log-content' });
  private followChat = true;
  private chatScrollTop = 0;
  private chatResize?: ResizeObserver;
  private latestButton = el('button', { type: 'button', class: 'chat-latest hidden' }, 'Jump to latest');
  private chatRecipient = el('select', { id: 'chat-recipient' },
    el('option', { value: '' }, 'Everyone'), el('option', { value: 'dm' }, 'Gamemaster'));
  private actorsEl = el('div', { class: 'actors' });
  private notes = new NotesPanel(element => this.stageEl.append(element), text => this.toast(text));
  private handouts = new HandoutsPanel(element => this.stageEl.append(element));
  private sheetHandles = new Map<string, SheetHandle>();
  private myActors: Actor[] = [];
  private compendium = new CompendiumPanel({
    actors: () => this.myActors.filter(a => !a.disabled).map((a) => ({ id: a.id, name: a.name })),
    add: async (id, category, entry) => {
      const actor = this.myActors.find((a) => a.id === id && !a.disabled);
      if (!actor) return null;
      const handle = await this.ensureSheet(actor);
      const where = addToSheet(handle.character, category, entry);
      if (where) handle.bus.notify();
      return where ? `${actor.name}'s ${where}` : null;
    },
    toast: (t) => this.toast(t),
    link: async (category, entry) => {
      if (!this.compendiumChat) throw new Error('Update the host to support compendium chat links.');
      if (!this.conn.isOpen) throw new Error('Not connected. Your link was not posted.');
      if (this.chatRecipient.selectedOptions[0]?.disabled) throw new Error('That player is offline. Choose a recipient before sharing.');
      await postCompendiumCard(category, entry.id, this.chatRecipient.value || undefined);
      this.showSection('chat');
    },
  });
  private compendiumWindow = new ToolWindow('Compendium', this.compendium.element, (open) => {
    this.compendiumButton?.setAttribute('aria-pressed', String(open));
    if (open) void this.compendium.open();
  }, 'cmp-win', () => {
    if (openPopout('/?popout=compendium', 1180, 900)) this.compendiumWindow.setOpen(false);
    else this.toast('The Compendium pop-out was blocked. Allow pop-ups for this site and try again.');
  });
  private compendiumButton?: HTMLButtonElement;
  private banner = el(
    'div',
    { class: 'banner hidden', role: 'status' },
    el('span', { class: 'banner-icon' }, '\u23F8'),
    el(
      'span',
      { class: 'banner-text' },
      el('strong', {}, 'Game paused by the DM'),
      el('small', {}, 'Token movement is restricted.')
    ),
  );
  private statusEl = el('span', { class: 'status connecting', role: 'status', ariaLabel: 'Connection status' }, 'connecting');
  private rosterHeading = el('div', { class: 'roster-heading' }, el('span', {}, 'CONNECTED PLAYERS'), this.statusEl);
  private fearEl = el('aside', { class: 'fear-overlay hidden', role: 'status' });
  private countdownsEl = el('aside', { class: 'countdowns-overlay hidden', role: 'status', ariaLabel: 'Countdowns' });
  private toastEl = el('div', { class: 'toast hidden' });
  private overlay = el('div', { class: 'overlay hidden' });
  private exploredTimer: number | null = null;
  private lastExploredSent = 0;
  private fogLoad = 0;
  private audioAvailable = false;
  private sheetWindows = new Map<string, SheetWindow>();
  private stageEl = el('div', { class: 'stage' });
  private soundBtn = el('button', { class: 'primary hidden' }, 'Click to enable sound');
  private muteBtn = el('button', { class: 'icon hidden' });
  private volumeBox = el('span', { class: 'volumes hidden' });
  private audio = new OnlineAudio(
    () => ({ master: settings.volume, music: settings.musicVolume, effects: settings.effectsVolume, muted: settings.muted }),
    () => this.conn.clock.serverNow(performance.now()),
  );

  constructor(root: HTMLElement, private onLoggedOut: () => void) {
    const canvas = el('canvas', { class: 'map' });
    const right = this.buildSidebar();
    const left = this.buildTools();
    this.rosterEl.setAttribute('aria-label', 'Connected players and ping to host');
    this.rosterEl.append(this.rosterHeading);
    this.stageEl.append(canvas, left, this.flyout, this.rosterEl, this.fearEl, this.countdownsEl, right, this.compendiumWindow.element, this.banner, this.toastEl);
    root.replaceChildren(el('div', { class: 'app' }, this.stageEl, this.overlay));
    this.view = new MapView(canvas);
    this.view.maxParticles = settings.maxParticles;
    this.view.smoothMovement = settings.smoothTokens;
    this.updateDrawingMode('pen');
    this.view.onSelectTool = () => this.selectTool();
    this.view.onUndo = () => this.undoDrawing();
    this.view.onMove = (m) => this.commitMove(m);
    this.view.onPortal = id => {
      const state = this.store.state;
      if (state && !this.conn.send({ type: 'command', kind: 'togglePortal', id,
        cmdId: `portal-${++this.cmdCounter}`, mapId: state.mapId, levelId: state.levelId }))
        this.toast('Not connected. Your edit was not sent.');
    };
    this.view.onRotate = (tokenId, rotationDeg) => {
      const state = this.store.state;
      if (state) this.sendTool({ type: 'command',
        kind: 'rotateToken', tokenId, rotationDeg, mapId: state.mapId, levelId: state.levelId });
    };
    this.view.onExplored = () => this.scheduleExplored();
    this.view.onDrawing = (action, id, shape) => {
      this.sendDrawing({ action, id, shape });
    };
    this.view.onMeasurement = (m, remove) => {
      const state = this.store.state;
      if (state) this.sendTool({ ...m, type: 'measurement', action: remove ? 'delete' : 'upsert', mapKey: `${state.mapId}/${state.levelId}` });
    };
    this.view.onSelection = (shape, measurement, count) => this.selectionChanged(shape, measurement, count);
    document.addEventListener('keydown', this.undoKey);
    this.audio.onStatus = text => this.toast(text);
    this.conn = new Connection(
      {
        onMessage: (m) => this.handle(m),
        onOpen: () => {},
        onStatus: (s) => this.status(s),
      },
      () => this.store.lastSeq,
    );
    this.view.serverNow = () => this.conn.clock.serverNow(performance.now());
  }

  start(): void {
    this.conn.start();
  }

  private status(s: 'connecting' | 'open' | 'closed' | 'kicked' | 'unauthorized'): void {
    this.statusEl.textContent = s === 'open' ? 'online' : s === 'closed' ? 'reconnecting...' : s;
    this.statusEl.className = `status ${s}`;
    if (s === 'closed' && this.pendingDrawings.size) {
      this.pendingDrawings.clear();
      this.drawingHistory = [];
      this.toast('Connection lost before confirmation. Drawing undo history was reset.');
    }
    if (s === 'unauthorized') {
      this.dispose();
      this.onLoggedOut();
    }
  }

  private buildSettings(): HTMLElement {
    const slider = (title: string, get: () => number, set: (v: number) => void) => {
      const s = el('input', { type: 'range', min: '0', max: '1', step: '0.05', value: String(get()), title });
      s.addEventListener('input', () => {
        set(Number(s.value));
        this.audio.volumesChanged();
      });
      return s;
    };
    const volume = slider('Master volume', () => settings.volume, (v) => (settings.volume = v));
    const musicVol = slider('Music volume', () => settings.musicVolume, (v) => (settings.musicVolume = v));
    const effectsVol = slider('Sound effects volume', () => settings.effectsVolume, (v) => (settings.effectsVolume = v));
    this.volumeBox.replaceChildren(
      el('label', { class: 'vol' }, 'Master ', volume),
      el('label', { class: 'vol' }, 'Music ', musicVol),
      el('label', { class: 'vol' }, 'Effects ', effectsVol),
    );
    this.soundBtn.addEventListener('click', () => {
      markAudioGesture();
      this.audio.enable();
      this.updateSoundUi();
    });
    this.muteBtn.title = 'Mute: stops music and effects and uses no audio resources';
    const showMute = () => (this.muteBtn.textContent = settings.muted ? 'Sound off' : 'Sound on');
    showMute();
    this.muteBtn.addEventListener('click', () => {
      settings.muted = !settings.muted;
      showMute();
      markAudioGesture();
      this.audio.volumesChanged();
      this.updateSoundUi();
    });
    this.audio.onStateChange = () => this.updateSoundUi();
    // Browsers only allow sound after a gesture, so any first click or key press starts it.
    document.addEventListener('pointerdown', this.audioGesture, { capture: true });
    document.addEventListener('keydown', this.audioGesture, { capture: true });
    this.updateSoundUi();
    const out = el('button', { class: 'icon' }, 'Log out');
    out.addEventListener('click', async () => {
      if (!await this.notes.confirmLeave()) return;
      out.disabled = true;
      try {
        await logout();
        this.dispose();
        this.onLoggedOut();
      } catch (error) {
        this.toast(`Logout failed: ${(error as Error).message}`);
        out.disabled = false;
      }
    });
    const smooth = el('input', { type: 'checkbox', checked: settings.smoothTokens });
    smooth.addEventListener('change', () => {
      settings.smoothTokens = smooth.checked;
      this.view.smoothMovement = smooth.checked;
      this.view.invalidate();
    });
    return el('div', { class: 'settings-section' }, el('h3', {}, 'Your audio'), this.soundBtn, this.muteBtn, this.volumeBox,
      el('p', { class: 'tool-hint' }, 'Your volume is independent of the DM.'),
      el('h3', {}, 'Display'),
      el('label', { class: 'vol', title: 'Glide moving tokens between updates. Turn off on slow devices to redraw less.' },
        smooth, ' Smooth token movement'),
      out);
  }

  private updateSoundUi(): void {
    const needsClick = this.audioAvailable && !settings.muted && !this.audio.running;
    this.soundBtn.classList.toggle('hidden', !needsClick);
    this.volumeBox.classList.toggle('hidden', !this.audioAvailable || settings.muted);
    this.muteBtn.classList.toggle('hidden', !this.audioAvailable);
  }

  private buildChat(): HTMLElement {
    this.logEl.append(this.logContent);
    this.logEl.addEventListener('scroll', () => this.captureChatScroll());
    this.latestButton.addEventListener('click', () => {
      this.followChat = true;
      this.restoreChatScroll();
    });
    this.chatResize = new ResizeObserver(() => this.restoreChatScroll());
    this.chatResize.observe(this.logEl);
    this.chatResize.observe(this.logContent);
    this.chatRecipient.setAttribute('aria-label', 'Message recipient');
    const input = el('input', { type: 'text', placeholder: 'Message, /roll or /r 2d6+3', maxLength: 500 });
    const send = el('button', { class: 'primary' }, 'Send');
    const submit = () => {
      const text = input.value.trim();
      if (!text) return;
      if (this.chatRecipient.selectedOptions[0]?.disabled) {
        this.toast('That player is offline. Choose a recipient before sending.');
        return;
      }
      const toId = this.chatRecipient.value || undefined;
      if (toId && toId !== 'dm' && !this.playerWhispers) {
        this.toast('This host does not support player whispers. Choose Everyone or Gamemaster.');
        return;
      }
      const whisper = toId === 'dm';
      const roll = /^\/(?:roll|r)\s+(.+)$/i.exec(text);
      const sent = roll ? this.conn.send({ type: 'roll', expression: roll[1], toId, whisper })
        : this.conn.send({ type: 'chat', text, toId, whisper });
      if (sent) input.value = '';
      else this.toast('Not connected. Your message was not sent.');
    };
    send.addEventListener('click', submit);
    input.addEventListener('keydown', (e) => e.key === 'Enter' && submit());
    const section = el(
      'div',
      { class: 'chat-section' },
      el('div', { class: 'chat-history' }, this.logEl, this.latestButton),
      el('div', { class: 'composer' },
      el('label', { class: 'recipient' }, 'To', this.chatRecipient),
      el('div', { class: 'row' }, input, send)),
    );
    section.addEventListener('dragover', (e) => {
      if (!e.dataTransfer?.types.includes(INVENTORY_DRAG)) return;
      e.preventDefault();
      e.dataTransfer.dropEffect = 'copy';
      section.classList.add('inventory-drop-target');
    });
    section.addEventListener('dragleave', (e) => {
      if (!(e.relatedTarget instanceof Node) || !section.contains(e.relatedTarget)) section.classList.remove('inventory-drop-target');
    });
    section.addEventListener('drop', (e) => {
      section.classList.remove('inventory-drop-target');
      if (!e.dataTransfer?.types.includes(INVENTORY_DRAG)) return;
      e.preventDefault();
      const drop = async () => {
        const payload: { actorId: string; entry: SharedEntry; token: string } = JSON.parse(e.dataTransfer!.getData(INVENTORY_DRAG));
        await waitForInventoryDrag(payload.token);
        await this.shareEntry(payload.actorId, payload.entry);
      };
      void drop().catch((e: Error) => this.toast(e.message));
    });
    return section;
  }

  private async shareEntry(actorId: string, entry: SharedEntry, offerQuantity?: number): Promise<void> {
    if (!this.chatCards) throw new Error('This host does not support shared chat cards. Update the host to share cards.');
    if (transferable(entry) && !this.transferOffers) throw new Error('Update the host to support safe inventory links and transfer offers.');
    if (entry.category === 'gold' && !this.goldTransfers) throw new Error('Update the host to transfer gold.');
    if (!this.conn.isOpen) throw new Error('Not connected. Your card was not posted.');
    if (this.chatRecipient.selectedOptions[0]?.disabled) throw new Error('That player is offline. Choose a recipient before sharing.');
    const handle = this.sheetHandles.get(actorId);
    if (handle) await handle.save.flush();
    await postCard(actorId, entry, this.chatRecipient.value || undefined, offerQuantity);
    this.showSection('chat');
  }

  private async grabCard(card: SharedCard, quantity: GrabQuantity): Promise<void> {
    if (quantity !== 'all' && quantity !== 1 && !this.grabQuantity) throw new Error('Update the host to grab a chosen quantity.');
    const actors = this.myActors.filter(a => !a.disabled && a.id !== card.actorId);
    if (!actors.length) throw new Error(`You need a different owned character to receive this ${card.category === 'gold' ? 'gold' : 'item'}.`);
    const picked = await pickActor(actors, `Receive ${card.item.name}`, quantity === 'all' ? 'Grab all' : 'Grab');
    const actor = picked ? actors.find(a => a.id === picked.id) : null;
    if (!actor) return;
    const handle = await this.ensureSheet(actor);
    await handle.save.flush();
    try {
      await cardRequest(`/${encodeURIComponent(card.cardId)}/grab`,
        quantity === 'all' ? { actorId: actor.id, all: true } : { actorId: actor.id, all: false, quantity });
      await handle.save.refresh();
      handle.bus.notify();
      this.toast(`${card.item.name} transferred to ${actor.name}.`);
    } finally {
      this.refreshCardStock(card.actorId);
    }
  }

  private refreshCardStock(actorId?: string): void {
    for (const entry of this.log) {
      if (entry.kind !== 'chat' || !entry.m.sharedCard || actorId && entry.m.sharedCard.actorId !== actorId) continue;
      this.logCards.get(entry)?.querySelector('.shared-card')?.dispatchEvent(new Event('refresh-stock'));
    }
  }

  private buildSidebar(): HTMLElement {
    const rail = el('nav', { class: 'section-rail' });
    rail.setAttribute('aria-label', 'Sidebar sections');
    this.sections.set('chat', this.buildChat());
    this.sections.set('actors', this.actorsEl);
    this.sections.set('notes', this.notes.element);
    this.sections.set('files', this.handouts.element);
    this.sections.set('settings', this.buildSettings());
    for (const [id, label] of [['chat', 'Chat'], ['actors', 'My actors'], ['notes', 'Notes'], ['compendium', 'Compendium'], ['files', 'Files'], ['settings', 'Settings']] as const) {
      if (id === 'compendium') {
        const button = iconButton(id, label, () => this.compendiumWindow.toggle());
        button.setAttribute('aria-pressed', 'false');
        this.compendiumButton = button;
        rail.append(button);
        continue;
      }
      const button = iconButton(id, label, () => this.showSection(this.section === id ? null : id));
      button.setAttribute('aria-controls', 'player-sidebar');
      this.sectionButtons.set(id, button);
      rail.append(button);
      this.sections.get(id)!.classList.toggle('hidden', id !== 'chat');
    }
    this.sectionButtons.get('files')?.classList.add('hidden');
    this.sidebar.append(el('header', { class: 'panel-heading' }, this.sectionTitle,
      iconButton('close', 'Collapse sidebar', () => {
        const previous = this.section; this.showSection(null);
        if (previous) this.sectionButtons.get(previous)?.focus();
      })), ...this.sections.values());
    this.showSection('chat');
    return el('div', { class: 'right-sidebar' }, rail, this.sidebar);
  }

  private showSection(section: SidebarSection | null): void {
    if (this.section === 'chat' && this.logEl.clientHeight) this.chatScrollTop = this.logEl.scrollTop;
    this.section = section;
    this.sidebar.classList.toggle('hidden', section === null);
    this.stageEl.classList.toggle('sidebar-open', section !== null);
    for (const [id, button] of this.sectionButtons) {
      button.setAttribute('aria-pressed', String(id === section));
      button.setAttribute('aria-expanded', String(id === section));
      this.sections.get(id)!.classList.toggle('hidden', id !== section);
      if (id === section) this.sectionTitle.textContent = button.title;
    }
    if (section === 'chat') {
      this.setChatUnread(false);
      this.restoreChatScroll();
    }
    if (section === 'notes') void this.notes.open();
  }

  private setChatUnread(unread: boolean): void {
    const button = this.sectionButtons.get('chat');
    button?.classList.toggle('has-unread', unread);
    button?.setAttribute('aria-label', unread ? 'Chat (new messages)' : 'Chat');
  }

  private updateLatestButton(): void {
    this.latestButton.classList.toggle('hidden', this.followChat);
  }

  private captureChatScroll(): void {
    if (this.section !== 'chat' || !this.logEl.clientHeight || this.logEl.scrollTop === this.chatScrollTop) return;
    this.followChat = this.logEl.scrollHeight - this.logEl.scrollTop - this.logEl.clientHeight <= 8;
    this.chatScrollTop = this.logEl.scrollTop;
    this.updateLatestButton();
  }

  private restoreChatScroll(): void {
    if (this.section !== 'chat' || !this.logEl.clientHeight) return;
    this.logEl.scrollTop = this.followChat ? this.logEl.scrollHeight : this.chatScrollTop;
    this.chatScrollTop = this.logEl.scrollTop;
    this.updateLatestButton();
  }

  private buildTools(): HTMLElement {
    const rail = el('nav', { class: 'tool-rail' });
    rail.setAttribute('aria-label', 'Map tools');
    for (const [id, label] of [['navigate', 'Navigate / select'], ['draw', 'Drawing'], ['measure', 'Measure']] as const) {
      const button = iconButton(id, label, () => {
        if (id !== 'navigate' && this.view.tool === id && !this.flyout.classList.contains('hidden')) {
          this.closeTools(); return;
        }
        this.view.setTool(id);
        this.toolButtons.forEach((b, key) => {
          b.setAttribute('aria-pressed', String(key === id));
          if (id !== 'navigate' && key !== 'navigate') b.setAttribute('aria-expanded', String(key === id));
        });
        if (id !== 'navigate') {
          this.flyout.classList.remove('hidden');
          this.drawingOptions.classList.toggle('hidden', id !== 'draw');
          this.measuringOptions.classList.toggle('hidden', id !== 'measure');
          this.toolTitle.textContent = id === 'measure' ? 'Measure' : 'Drawing';
        }
      });
      button.setAttribute('aria-pressed', String(id === 'navigate'));
      if (id !== 'navigate') {
        button.disabled = true;
        button.setAttribute('aria-controls', 'tool-flyout');
        button.setAttribute('aria-expanded', 'false');
      }
      this.toolButtons.set(id, button); rail.append(button);
    }
    const modes = el('div', { class: 'mode-buttons' });
    for (const [id, icon, label] of [['pen', 'pen', 'Pen'], ['brush', 'draw', 'Freehand brush'], ['line', 'line', 'Line'],
      ['circle', 'circle', 'Circle'], ['rect', 'rect', 'Rectangle']] as const) {
      const button = iconButton(icon, label, () => {
        this.view.setTool('draw');
        this.toolButtons.forEach((b, key) => b.setAttribute('aria-pressed', String(key === 'draw')));
        this.view.drawMode = id;
        modes.querySelectorAll('button').forEach(b => b.setAttribute('aria-pressed', String(b === button)));
        this.updateDrawingMode(id);
      });
      button.setAttribute('aria-pressed', String(id === 'pen')); modes.append(button);
    }
    const field = (name: string, control: HTMLElement) => {
      if (control instanceof HTMLInputElement || control instanceof HTMLSelectElement) return el('label', { class: 'tool-field' }, name, control);
      control.setAttribute('role', 'group');
      control.setAttribute('aria-label', name);
      return el('div', { class: 'tool-field' }, el('span', {}, name), control);
    };
    const sizeValue = el('output', { id: 'drawing-size-value' }, '2 tiles');
    this.sizeInput.addEventListener('input', () => { sizeValue.textContent = `${this.sizeInput.value} tiles`; this.readStyle(); });
    this.alphaInput.addEventListener('input', () => this.readStyle());
    this.colorInput.addEventListener('focus', () => { this.colorEditing = true; });
    this.colorInput.addEventListener('pointerdown', () => { this.colorEditing = true; });
    this.colorInput.addEventListener('input', () => {
      this.colorEditing = true;
      this.readStyle();
      if (this.colorTimer === null) {
        const wait = Math.max(0, 100 - (performance.now() - this.lastColorSent));
        if (wait === 0) this.sendColorEdit();
        else this.colorTimer = window.setTimeout(() => this.sendColorEdit(), wait);
      }
    });
    const finishColor = () => {
      if (this.colorEditing) {
        this.readStyle();
        this.sendColorEdit();
        this.colorEditing = false;
      }
    };
    this.colorInput.addEventListener('change', () => {
      this.readStyle();
      this.sendColorEdit();
      this.colorEditing = false;
    });
    this.colorInput.addEventListener('blur', finishColor);
    for (const input of [this.sizeInput, this.alphaInput, this.borderInput, this.emitsInput, this.aboveInput]) {
      input.addEventListener('change', () => { this.readStyle(); this.view.editStyle(); });
    }
    this.textureSelect.addEventListener('change', () => {
      const texture = this.store.state?.effectTextures?.find(t => t.id === this.textureSelect.value);
      if (texture?.color) this.colorInput.value = texture.color;
      this.emitsInput.checked = texture?.emits ?? false;
      this.aboveInput.checked = texture?.above ?? true;
      this.readStyle(); this.view.editStyle();
      if (this.view.tool === 'navigate' && !this.toolButtons.get('draw')!.disabled) {
        this.view.setTool('draw');
        this.toolButtons.forEach((b, key) => b.setAttribute('aria-pressed', String(key === 'draw')));
        this.updateDrawingMode(this.view.drawMode);
      }
    });
    this.deleteButton.disabled = true;
    this.drawingOptions.append(field('Drawing tool', modes), field('Effect texture', this.textureSelect),
      field('Color', this.colorInput), field('Size / thickness', this.sizeInput), sizeValue,
      field('Opacity', this.alphaInput), el('label', { class: 'check' }, this.borderInput, ' Border'),
      el('label', { class: 'check' }, this.emitsInput, ' Emits light'),
      el('label', { class: 'check' }, this.aboveInput, ' Above tokens'));
    const measureModes = el('div', { class: 'mode-buttons' });
    const measureHint = el('p', { class: 'tool-hint' }, 'Drag to measure a distance in grid tiles.');
    for (const [id, icon, label] of [['distance', 'measure', 'Distance ruler'], ['radius', 'radius', 'Circle radius'],
      ['cone', 'cone', 'Cone (60 degrees)'], ['rect', 'rect', 'Rectangle']] as const) {
      const button = iconButton(icon, label, () => {
        this.view.setTool('measure');
        this.toolButtons.forEach((b, key) => b.setAttribute('aria-pressed', String(key === 'measure')));
        this.view.measureMode = id;
        measureModes.querySelectorAll('button').forEach(b => b.setAttribute('aria-pressed', String(b === button)));
        measureHint.textContent = `${label}: drag on the map. Units: grid tiles.`;
      });
      button.setAttribute('aria-pressed', String(id === 'distance')); measureModes.append(button);
    }
    this.measuringOptions.append(field('Measuring tool', measureModes), measureHint,
      el('p', { class: 'tool-hint' }, 'Shared with everyone. Remains after release; Navigate to move or delete your measurements. Cleared on map changes or restart.'));
    this.flyout.append(el('header', { class: 'panel-heading' }, this.toolTitle, iconButton('close', 'Close tool options', () => this.closeTools())),
      this.drawingOptions, this.measuringOptions);
    const selected = el('div', { class: 'selection-controls' }, this.selectionHint, this.deleteButton);
    this.effectsEl.setAttribute('aria-label', 'Active effects');
    return el('div', { class: 'left-tools' }, rail, selected, this.effectsEl);
  }

  private effectsTimer: number | null = null;

  private scheduleEffects(): void {
    if (this.effectsTimer !== null) return;
    this.effectsTimer = window.setTimeout(() => { this.effectsTimer = null; this.renderEffectsOverlay(); }, 0);
  }

  private renderEffectsOverlay(): void {
    const groups = this.myActors.filter(a => !a.disabled).flatMap((a) => {
      const handle = this.sheetHandles.get(a.id);
      return handle ? [{ actorId: a.id, name: a.name, character: handle.character }] : [];
    });
    renderEffects(this.effectsEl, groups, this.expandedEffects, (group, effect) => {
      const list = group.character.effects;
      const i = list.indexOf(effect);
      if (i >= 0) list.splice(i, 1);
      this.sheetHandles.get(group.actorId)?.save();
      this.sheetHandles.get(group.actorId)?.bus.notify();
    }, () => this.renderEffectsOverlay());
  }

  private updateDrawingMode(mode: string): void {
    const ink = mode === 'pen' || mode === 'line';
    for (const input of [this.textureSelect, this.borderInput, this.emitsInput, this.aboveInput]) input.disabled = ink;
    this.sizeInput.disabled = mode === 'pen';
  }

  private closeTools(): void {
    this.flyout.classList.add('hidden');
    this.toolButtons.forEach((button, id) => {
      if (id !== 'navigate') button.setAttribute('aria-expanded', 'false');
    });
  }

  private async useSheetFeature(actorId: string, character: CharacterSheet, save: SheetSave, key: string, actionId: string, amount?: number): Promise<void> {
    await save.flush();
    const target = featureTarget(character, key, actionId);
    let targetActorId: string | undefined;
    if (target === 'actor' || target === 'anyActor') {
      const ward = isLifeWard(character, key);
      if (ward && !this.conn.deathMoveOptions) throw new Error('This host does not support Life Ward. Update the host.');
      const candidates = (this.store.state?.actors ?? []).filter(a => !a.disabled && (ward ? a.id !== actorId : (target === 'anyActor' || this.connectedPlayers.has(a.ownerId)) && (amount === undefined || a.id !== actorId)));
      const selected = await pickActor(candidates, 'Choose a character', 'Select target', target === 'anyActor');
      if (!selected) return;
      targetActorId = selected.id;
    }
    await useFeature({ actorId, key, actionId, amount, ...(targetActorId ? { targetActorId } : {}), ...(isLifeWard(character, key) ? { confirmRange: true } : {}) });
    await save.refresh();
  }

  private selectTool(): void {
    this.toolButtons.forEach((b, id) => b.setAttribute('aria-pressed', String(id === 'navigate')));
  }

  private sendDrawing(edit: DrawingEdit, undo = false): void {
    const state = this.store.state;
    if (!state || this.view.paused) return;
    const previous = [...this.pendingDrawings.values()].reverse().find(p => p.inverse.id === edit.id);
    const before = previous ? previous.after : state.overlays?.find(o => o.id === edit.id);
    const inverse: DrawingEdit = edit.action === 'create' ? { action: 'delete', id: edit.id }
      : { action: edit.action === 'delete' ? 'create' : 'update', id: edit.id,
        shape: before ? { ...before, points: [...before.points] } : undefined };
    if (edit.action !== 'create' && !before) { this.toast('Drawing no longer exists.'); return; }
    const cmdId = `tool-${++this.cmdCounter}`;
    if (this.conn.send({ type: 'command', kind: 'drawing', ...edit, cmdId, mapId: state.mapId, levelId: state.levelId })) {
      this.pendingDrawings.set(cmdId, { inverse: undo ? edit : inverse, undo,
        after: edit.action === 'delete' ? undefined : edit.shape, record: true });
    } else {
      if (undo) this.drawingHistory.push(edit);
      this.toast('Not connected. Your edit was not sent.');
    }
  }

  private undoDrawing(): void {
    if (this.view.paused) { this.toast('The game is paused.'); return; }
    if (this.pendingDrawings.size) { this.toast('Waiting for the host to confirm your drawing edits.'); return; }
    const edit = this.drawingHistory.pop();
    if (edit) this.sendDrawing(edit, true);
  }

  private readStyle(): void {
    this.view.style = { color: this.colorInput.value, alpha: Number(this.alphaInput.value), size: Number(this.sizeInput.value),
      texture: this.textureSelect.value || 'none', border: this.borderInput.checked, emits: this.emitsInput.checked, above: this.aboveInput.checked };
    this.view.invalidate();
  }

  private sendColorEdit(): void {
    if (this.colorTimer !== null) clearTimeout(this.colorTimer);
    this.colorTimer = null;
    this.lastColorSent = performance.now();
    this.view.editStyle();
  }

  private selectionChanged(shape: Overlay | null, measurement: boolean, count = 0): void {
    if (this.colorSelection !== (shape?.id ?? null)) {
      if (this.colorTimer !== null) clearTimeout(this.colorTimer);
      this.colorTimer = null;
      this.colorEditing = false;
      this.colorSelection = shape?.id ?? null;
    }
    this.deleteButton.disabled = !shape && !measurement;
    this.selectionHint.textContent = count ? `${count} owned object${count === 1 ? '' : 's'} selected. Drag to move; arrows move tokens by half a tile.${this.view.canRotateTokens ? ' Drag a token handle to rotate.' : ''} Delete removes drawings and measurements, not tokens.`
      : shape ? 'Your drawing selected. Drag to move; corner handle to resize. Open Drawing to change style.'
      : measurement ? 'Your measurement selected. Drag to move or delete.' : 'Select your controlled tokens, drawings or measurements.';
    if (shape) {
      const pending = [...this.pendingDrawings.values()].reverse().find(p => p.inverse.id === shape.id);
      const color = pending?.after?.color ?? shape.color;
      if (!this.colorEditing) this.colorInput.value = color;
      this.alphaInput.value = String(shape.alpha);
      this.textureSelect.value = shape.texture; this.borderInput.checked = shape.border;
      this.emitsInput.checked = !!shape.emits; this.aboveInput.checked = shape.above !== false;
      this.sizeInput.value = String(shape.strokeWidth / (this.store.state?.grid.pixelsPerCell || 70));
      const sizeValue = document.getElementById('drawing-size-value');
      if (sizeValue) sizeValue.textContent = `${Number(this.sizeInput.value).toFixed(1)} tiles`;
      this.updateDrawingMode(shape.type);
      this.readStyle();
    }
  }

  private sendTool(command: Record<string, unknown>): void {
    if (!this.conn.send({ ...command, cmdId: `tool-${++this.cmdCounter}` })) this.toast('Not connected. Your edit was not sent.');
  }

  private dispose(): void {
    this.notes.dispose();
    this.chatResize?.disconnect();
    if (this.colorTimer !== null) clearTimeout(this.colorTimer);
    this.conn.stop();
    this.view.destroy();
    this.audio.dispose();
    document.removeEventListener('pointerdown', this.audioGesture, true);
    document.removeEventListener('keydown', this.audioGesture, true);
    document.removeEventListener('keydown', this.undoKey);
    if (this.exploredTimer !== null) clearTimeout(this.exploredTimer);
  }

  private toast(text: string): void {
    this.toastEl.textContent = text;
    this.toastEl.classList.remove('hidden');
    window.setTimeout(() => this.toastEl.classList.add('hidden'), 4000);
  }

  private handle(msg: ServerMessage): void {
    switch (msg.type) {
      case 'hello':
        this.playerId = msg.playerId;
        this.notes.setSession(msg.notes === true, msg.campaignId ?? '', msg.playerId);
        if (this.section === 'notes') void this.notes.open();
        this.handouts.closeAll();
        this.sectionButtons.get('files')?.classList.toggle('hidden', msg.handouts !== true);
        if (msg.handouts !== true && this.section === 'files') this.showSection('chat');
        this.playerWhispers = msg.playerWhispers === true;
        this.chatCards = msg.chatCards === true;
        this.compendiumChat = msg.compendiumChat === true;
        this.transferOffers = msg.transferOffers === true;
        this.goldTransfers = msg.goldTransfers === true;
        this.grabQuantity = msg.grabQuantity === true;
        for (const handle of this.sheetHandles.values()) {
          void handle.save.refresh().then(() => handle.bus.notify()).catch((e: Error) => this.toast(e.message));
        }
        this.view.playerId = msg.playerId;
        this.view.campaignId = msg.campaignId ?? '';
        this.view.canRotateTokens = msg.tokenRotation === true;
        this.view.canControlPortals = msg.portalControls === true;
        this.toolButtons.get('draw')!.disabled = !msg.drawingTools;
        this.toolButtons.get('measure')!.disabled = !msg.measurementTools;
        if (this.view.tool === 'draw' && !msg.drawingTools || this.view.tool === 'measure' && !msg.measurementTools) {
          this.view.setTool('navigate');
          this.selectTool();
          this.closeTools();
        }
        this.setPaused(msg.paused);
        break;
      case 'roster':
        {
          this.connectedPlayers = new Set(msg.players.filter(p => p.connections > 0).map(p => p.playerId));
        }
        this.updateRecipients(msg.players);
        this.rosterEl.replaceChildren(this.rosterHeading,
          el('div', { class: 'player-row' }, 'Dungeon Master', el('span', {}, 'Host')),
          ...msg.players.map(p => el('div', { class: 'player-row' },
            el('span', { class: 'player-name' }, `${p.playerName}${p.chatIdentity ? ` (${p.chatIdentity})` : ''}${p.playerId === this.playerId ? ' (you)' : ''}`),
            el('span', {}, p.pingMs < 0 ? '--' : `${p.pingMs} ms`))));
        break;
      case 'measurements':
        if (msg.mapKey === `${this.store.state?.mapId}/${this.store.state?.levelId}`) {
          this.view.setMeasurements(msg.items);
        }
        break;
      case 'snapshot':
        this.store.applySnapshot(msg.seq, msg.state);
        this.pendingMoves.clear();
        this.syncInFlight();
        this.afterState(true);
        if (this.store.state?.chatTail) {
          this.log = [];
          for (const c of this.store.state.chatTail) {
            this.log.push(c.type === 'roll' ? { kind: 'roll', m: c as RollMessage } : { kind: 'chat', m: c as ChatMessage });
          }
          this.renderLog();
        }
        break;
      case 'delta': {
        const r = this.store.applyDelta(msg.seq, msg.ops);
        if (r === 'gap') this.conn.send({ type: 'resync', lastSeq: this.store.lastSeq });
        else if (r === 'ok') this.afterState(false);
        break;
      }
      case 'tokenMoved': {
        const s = this.store.state;
        const token = s && msg.mapKey === `${s.mapId}/${s.levelId}` ? s.tokens.find((t) => t.id === msg.id) : undefined;
        if (token) this.view.tokenMoved(token, msg.x, msg.y);
        break;
      }
      case 'ack':
        {
          const edit = this.pendingDrawings.get(msg.cmdId);
          if (edit && !edit.undo && edit.record) {
            this.drawingHistory.push(edit.inverse);
            if (this.drawingHistory.length > 100) this.drawingHistory.shift();
          }
          this.pendingDrawings.delete(msg.cmdId);
        }
        this.pendingMoves.delete(msg.cmdId);
        this.syncInFlight();
        break;
      case 'reject':
        this.onReject(msg);
        break;
      case 'pause':
        this.setPaused(msg.paused);
        break;
      case 'audio':
        this.audioAvailable = msg.enabled;
        this.updateSoundUi();
        void this.audio.apply(msg);
        break;
      case 'chat':
        this.addLog({ kind: 'chat', m: msg });
        break;
      case 'chatUpdate': {
        const index = this.log.findIndex(e => e.kind === 'chat' && e.m.id === msg.message.id && e.m.ts === msg.message.ts);
        if (index >= 0) this.log[index] = { kind: 'chat', m: msg.message };
        if (this.store.state?.chatTail) {
          const tail = this.store.state.chatTail.findIndex(m => m.id === msg.message.id && m.ts === msg.message.ts);
          if (tail >= 0) this.store.state.chatTail[tail] = { type: 'chat', ...msg.message };
        }
        this.renderLog();
        break;
      }
      case 'chatDeleted':
        this.log = this.log.filter(entry => entry.m.id !== msg.id || entry.m.ts !== msg.ts);
        if (this.store.state?.chatTail) {
          this.store.state.chatTail = this.store.state.chatTail.filter(entry => entry.id !== msg.id || entry.ts !== msg.ts);
        }
        this.renderLog();
        break;
      case 'cardStock':
        this.refreshCardStock(msg.actorId);
        break;
      case 'catalogChanged':
        void this.compendium.reload().catch((error: Error) => this.toast(error.message));
        for (const handle of this.sheetHandles.values())
          void handle.save.flush().then(() => handle.save.refresh()).catch((error: Error) => this.toast(error.message));
        break;
      case 'sheetChanged': {
        const handle = this.sheetHandles.get(msg.actorId);
        if (handle) void handle.save.refresh().catch((error: Error) => this.toast(error.message));
        break;
      }
      case 'sheetInventory': {
        if (this.conn.sheetSync) break;
        const handle = this.sheetHandles.get(msg.actorId);
        if (handle && applyInventory(handle.character, msg.inventory)) handle.bus.notify();
        break;
      }
      case 'sheetFeature': {
        if (this.conn.sheetSync) break;
        const handle = this.sheetHandles.get(msg.actorId);
        if (handle && applyFeatureState(handle.character, msg)) {
          handle.bus.notify();
          handle.save();
          this.logContent.querySelectorAll('.roll-actions').forEach(box => box.dispatchEvent(new Event('refresh-roll-actions')));
        }
        break;
      }
      case 'rollUpdate': {
        this.store.applyRollUpdate(msg);
        const entry = this.log.find(e => e.kind === 'roll' && e.m.id === msg.id && e.m.ts === msg.ts);
        if (entry?.kind === 'roll') {
          const { type: _type, ...update } = msg;
          this.log[this.log.indexOf(entry)] = { kind: 'roll', m: { ...entry.m, ...update } };
          this.renderLog();
        }
        break;
      }
      case 'roll':
        this.addLog({ kind: 'roll', m: msg });
        break;
      case 'handouts':
        this.handouts.update(msg.folders, msg.files);
        break;
      case 'handoutShow':
        this.handouts.open(msg.id, msg.name);
        break;
      case 'kicked':
        this.dispose();
        this.showOverlay(`You were removed from the session${msg.reason ? ': ' + msg.reason : '.'}`);
        break;
      case 'error':
        this.toast(msg.message);
        break;
      default:
        break;
    }
  }

  /** Up to 12 filled/empty tokens; hidden when the host does not send a Fear count. */
  private renderFear(fear: number | undefined): void {
    if (fear === undefined) {
      this.fearEl.classList.add('hidden');
      return;
    }
    const value = Math.max(0, Math.min(12, Math.floor(fear)));
    this.fearEl.classList.remove('hidden');
    this.fearEl.setAttribute('aria-label', `GM Fear: ${value} of 12`);
    const tokens = Array.from({ length: 12 }, (_, i) => el('span', { class: i < value ? 'fear-token filled' : 'fear-token' }));
    this.fearEl.replaceChildren(
      el('div', { class: 'fear-heading' }, el('span', {}, 'FEAR'), el('strong', {}, String(value))),
      el('div', { class: 'fear-tokens' }, ...tokens),
    );
  }

  private setPaused(p: boolean): void {
    this.view.paused = p;
    if (this.store.state) this.store.state.paused = p;
    this.banner.classList.toggle('hidden', !p);
    this.view.invalidate();
  }

  private afterState(full: boolean): void {
    const s = this.store.state;
    if (!s) return;
    const map = `${s.mapId}/${s.levelId}`;
    if (map !== this.historyMap) {
      this.historyMap = map;
      this.drawingHistory = [];
      this.pendingDrawings.clear();
    }
    this.view.setState(s);
    setGmFear(s.fear);
    this.setPaused(s.paused);
    if (full || this.store.changed.has('fear')) this.renderFear(s.fear);
    if (full || this.store.changed.has('countdowns')) renderCountdowns(this.countdownsEl, s.countdowns);
    if (full || this.store.changed.has('dmFog') || this.store.changed.has('fogGrid') || this.store.changed.has('fogMemory')) {
      void this.loadFog(s);
    }
    if (full || this.store.changed.has('actors') || this.store.changed.has('tokens')) {
      if (full || this.store.changed.has('actors')) {
        setDisabledCopycatSources(s.actors.filter(a => a.disabled).map(a => a.id));
        for (const handle of this.sheetHandles.values()) {
          handle.bus.notify();
          void handle.save.refresh().catch((error: Error) => this.toast(error.message));
        }
      }
      this.renderActors(s);
      if (full || this.store.changed.has('actors'))
        this.logContent.querySelectorAll('.roll-actions').forEach(box => box.dispatchEvent(new Event('refresh-roll-actions')));
    }
    if (full || this.store.changed.has('effectTextures')) {
      const previous = this.textureSelect.value || 'none';
      this.textureSelect.replaceChildren(...(s.effectTextures ?? []).map(t => el('option', { value: t.id }, t.label)));
      this.textureSelect.value = previous;
    }
    if (full || this.store.changed.has('overlays')) this.view.refreshSelection();
  }

  private async loadFog(s: GameState): Promise<void> {
    const ticket = ++this.fogLoad;
    const g = s.fogGrid ?? s.dmFog;
    if (!g) {
      this.view.fog.configure(null);
      this.view.invalidateVision();
      return;
    }
    const grid = { originX: g.originX, originY: g.originY, cellSize: g.cellSize, cols: g.cols, rows: g.rows };
    this.view.fog.configure(grid);
    const n = grid.cols * grid.rows;
    const [revealed, memory] = await Promise.all([decodeBitset(s.dmFog?.revealed, n), decodeBitset(s.fogMemory?.cells, n)]);
    if (ticket !== this.fogLoad) return;
    this.view.fog.setDmRevealed(revealed);
    this.view.fog.setMemory(memory);
    this.view.invalidateVision();
  }

  private commitMove(m: MoveCommit): void {
    const cmdId = `${Date.now().toString(36)}-${++this.cmdCounter}`;
    this.pendingMoves.set(cmdId, m);
    this.syncInFlight();
    if (!this.conn.send({ type: 'command', cmdId, kind: 'moveToken', tokenId: m.tokenId, x: m.x, y: m.y })) {
      this.restore(m.tokenId, m.prevX, m.prevY);
      this.pendingMoves.delete(cmdId);
      this.syncInFlight();
    }
  }

  /** Vision from unconfirmed positions is only tentative and must not become explored fog memory. */
  private syncInFlight(): void {
    this.view.setMovesInFlight(this.pendingMoves.size);
  }

  private restore(tokenId: string, x: number, y: number): void {
    const t = this.store.state?.tokens.find((k) => k.id === tokenId);
    if (t) {
      t.x = x;
      t.y = y;
      this.view.corrected(tokenId, x, y);
      this.view.invalidateVision();
    }
  }

  private onReject(msg: Extract<ServerMessage, { type: 'reject' }>): void {
    const drawing = this.pendingDrawings.get(msg.cmdId);
    if (drawing?.undo) this.drawingHistory.push(drawing.inverse);
    if (drawing && !drawing.undo) {
      // Later edits may have used this unconfirmed edit as their inverse.
      for (const p of this.pendingDrawings.values()) {
        if (p.inverse.id === drawing.inverse.id) p.record = false;
      }
    }
    this.pendingDrawings.delete(msg.cmdId);
    const pending = this.pendingMoves.get(msg.cmdId);
    this.pendingMoves.delete(msg.cmdId);
    this.syncInFlight();
    if (msg.token) this.restore(msg.token.id, msg.token.x, msg.token.y);
    else if (pending) this.restore(pending.tokenId, pending.prevX, pending.prevY);
    if (msg.cmdId.startsWith('portal-') && (msg.reason === 'locked' || msg.reason === 'paused'
      || msg.reason === 'Map changed or game paused')) return;
    this.toast(msg.reason === 'paused' ? 'The game is paused.' : `Edit rejected: ${msg.reason}`);
  }

  private scheduleExplored(): void {
    if (this.exploredTimer !== null) return;
    const wait = Math.max(0, EXPLORED_MIN_INTERVAL_MS - (performance.now() - this.lastExploredSent));
    this.exploredTimer = window.setTimeout(() => {
      this.exploredTimer = null;
      void this.sendExplored();
    }, wait);
  }

  private async sendExplored(): Promise<void> {
    const s = this.store.state;
    const grid = this.view.fog.grid;
    const cells = this.view.fog.takePending();
    if (!s || !grid || !cells) return;
    const encoded = await encodeBitset(cells);
    const ok = this.conn.send({
      type: 'fogExplored',
      mapId: s.mapId,
      levelId: s.levelId,
      gridKey: `${grid.originX},${grid.originY},${grid.cellSize},${grid.cols},${grid.rows}`,
      cells: encoded,
    });
    this.lastExploredSent = performance.now();
    if (!ok) this.view.fog.restorePending(cells);
    if (this.view.fog.hasNewExplored) this.scheduleExplored();
  }

  private addLog(e: LogEntry): void {
    if (this.section !== 'chat') this.setChatUnread(true);
    this.log.push(e);
    if (this.log.length > 300) this.log.shift();
    this.renderLog();
  }

  private logCards = new WeakMap<LogEntry, HTMLElement>();

  private rollActions(m: RollMessage | import('./protocol').ChatMessage): HTMLElement | null {
    return rollButtons(m, {
      playerId: this.playerId,
      supportsRollRewards: () => this.conn.rollRewards,
      supportsPartyFeatures: () => this.conn.partyFeatures,
      supportsDualityDieBonus: () => this.conn.dualityDieBonus,
      supportsSeraphAutomation: () => this.conn.seraphAutomation,
      supportsSorcererAutomation: () => this.conn.sorcererAutomation,
      supportsWarlockAutomation: () => this.conn.warlockAutomation,
      supportsWitchAutomation: () => this.conn.witchAutomation,
      supportsDomainCardAutomation: () => this.conn.domainCardAutomation,
      supportsWarriorAutomation: () => this.conn.warriorAutomation,
      supportsGuardianRolls: () => this.conn.guardianRolls,
      sessionId: () => this.conn.sessionId,
      supportsDeathMoveOptions: () => this.conn.deathMoveOptions,
      actorIds: () => this.myActors.filter(a => !a.disabled).map(a => a.id),
      targetActors: () => (this.store.state?.actors ?? []).filter(a => !a.disabled),
      isActorActive: id => this.store.state?.actors.find(a => a.id === id)?.disabled !== true,
      sheet: async (id) => {
        const actor = this.myActors.find(a => a.id === id && !a.disabled);
        if (!actor) return undefined;
        await this.ensureSheet(actor);
        return this.sheetHandles.get(id);
      },
      send: (roll) => this.conn.send({ type: 'roll', ...roll }),
      log: (text, rollSource) => { this.conn.send({ type: 'chat', text, rollSource, whisper: false }); },
      toast: (message) => this.toast(message),
    });
  }

  private renderLog(): void {
    this.captureChatScroll();
    const anchor = Array.from(this.logContent.children).find(child =>
      child.getBoundingClientRect().bottom > this.logEl.getBoundingClientRect().top);
    const anchorTop = anchor?.getBoundingClientRect().top;
    const cards = this.log.map((e) => {
      let card = this.logCards.get(e);
      if (!card) {
        card = renderLogEntry(e, this.playerId, (card, quantity) => this.grabCard(card, quantity), (message) => this.toast(message), (m) => this.rollActions(m));
        this.logCards.set(e, card);
        card.querySelector('.shared-card')?.dispatchEvent(new Event('refresh-stock'));
      }
      return card;
    });
    const keep = new Set<Element>(cards);
    for (const child of Array.from(this.logContent.children)) if (!keep.has(child)) child.remove();
    for (const card of cards) if (card.parentElement !== this.logContent) this.logContent.append(card);
    if (!this.followChat && anchor?.isConnected && anchorTop !== undefined && this.section === 'chat') {
      this.chatScrollTop = this.logEl.scrollTop + anchor.getBoundingClientRect().top - anchorTop;
    }
    this.restoreChatScroll();
  }

  private updateRecipients(players: Array<{ playerId: string; playerName: string }>): void {
    const selected = this.chatRecipient.value;
    const previousLabel = this.chatRecipient.selectedOptions[0]?.textContent ?? selected;
    const options = [
      el('option', { value: '' }, 'Everyone'),
      el('option', { value: 'dm' }, 'Gamemaster'),
      ...players.filter(p => this.playerWhispers && p.playerId !== this.playerId)
        .sort((a, b) => a.playerName.localeCompare(b.playerName))
        .map(p => el('option', { value: p.playerId }, p.playerName)),
    ];
    if (!options.some(o => o.value === selected)) {
      options.push(el('option', { value: selected, disabled: true }, `${previousLabel.replace(/ \(offline\)$/, '')} (offline)`));
    }
    this.chatRecipient.replaceChildren(...options);
    this.chatRecipient.value = selected;
  }

  private sheetLoads = new Map<string, Promise<SheetHandle>>();

  private ensureSheet(a: Actor): Promise<SheetHandle> {
    let load = this.sheetLoads.get(a.id);
    if (!load) {
      load = loadCharacter(a.id, a.name, (message) => this.toast(message)).then((character) => {
        const bus = new SheetBus();
        const readOnly = () => this.store.state?.actors.find(actor => actor.id === a.id)?.disabled === true;
        const changed = autosave(a.id, character, (m) => this.toast(m), 700, readOnly);
        this.sheetHandles.set(a.id, { character, bus, save: changed });
        bus.subscribe(() => this.scheduleEffects());
        document.addEventListener('dmmt-inventory-refreshed', (event) => {
          if ((event as CustomEvent<string>).detail === a.id) {
            bus.notify();
            this.logContent.querySelectorAll('.roll-actions').forEach(box => box.dispatchEvent(new Event('refresh-roll-actions')));
          }
        });
        void changed.refresh().catch((error: Error) => this.toast(error.message));
        const win = new SheetWindow(
          {
            character,
            readOnly,
            bus,
            onChange: () => {
              changed();
              this.scheduleEffects();
              this.logContent.querySelectorAll('.roll-actions').forEach(box => box.dispatchEvent(new Event('refresh-roll-actions')));
            },
            onFeature: (key, actionId, amount) => this.useSheetFeature(a.id, character, changed, key, actionId, amount),
            onCopycat: (key, actionId) => openCopycat(character, a.id, key, actionId, changed),
            onDamageTaken: async (severity) => {
              await changed.flush();
              await damageTaken(a.id, severity);
            },
            onDeathOptions: async () => {
              await changed.flush();
              if (!this.conn.isOpen) throw new Error('Not connected. Cannot load death-move options.');
              return this.conn.deathMoveOptions ? getDeathOptions(a.id) : basicDeathOptions();
            },
            onDeathMove: async (move, extras) => {
              if (!this.conn.isOpen) throw new Error('Not connected. The death move was not made.');
              if (!this.conn.deathMoves) throw new Error('This host does not support death moves. Update the host.');
              if (!this.conn.deathMoveOptions && (extras || !['blaze', 'avoid', 'risk'].includes(move))) throw new Error('This host does not support expanded death moves. Update the host.');
              await changed.flush();
              await takeDeathMove(a.id, move, extras);
            },
            actorId: a.id,
            onShare: (entry, quantity) => { void this.shareEntry(a.id, entry, quantity).catch((e: Error) => this.toast(e.message)); },
            onPrepareShare: () => changed.flush(),
            onLog: (text, rollSource, restDice) => {
              const send = () => { if (!this.conn.send({ type: 'chat', text, rollSource, restDice, actorId: a.id, whisper: false })) this.toast('Not connected. The report was not posted.'); };
              if (restDice) { changed(); void changed.flush().then(send).catch((e: Error) => this.toast(e.message)); }
              else send();
            },
            artwork: a.artwork,
            supportsKeptDice: () => this.conn.keptDice,
            supportsBrawlerRolls: () => this.conn.brawlerRolls,
            supportsDruidAutomation: () => this.conn.druidAutomation,
            supportsSeraphAutomation: () => this.conn.seraphAutomation,
            supportsSorcererAutomation: () => this.conn.sorcererAutomation,
            supportsWarlockAutomation: () => this.conn.warlockAutomation,
            supportsWitchAutomation: () => this.conn.witchAutomation,
            supportsDomainCardAutomation: () => this.conn.domainCardAutomation,
            supportsRollMultipliers: () => this.conn.rollMultipliers,
            supportsPartyFeatures: () => this.conn.partyFeatures,
            onRoll: (r) => {
              const sent = this.conn.send({ type: 'roll', ...r });
              if (!sent) this.toast('Not connected. Your roll was not sent.');
              return sent;
            },
          },
          a.name,
          () => window.open(`/sheet/${encodeURIComponent(a.id)}`, '_blank', 'popup,width=900,height=900'),
          () => {},
        );
        this.sheetWindows.set(a.id, win);
        this.stageEl.append(win.element);
        return { character, bus, save: changed };
      });
      load.catch(() => this.sheetLoads.delete(a.id));
      this.sheetLoads.set(a.id, load);
    }
    return load;
  }

  private openSheet(a: Actor, toggle = false, show = true): void {
    this.ensureSheet(a).then(() => {
      const win = this.sheetWindows.get(a.id)!;
      if (toggle) win.toggle();
      else if (show) win.setOpen(true);
    }).catch((e: Error) => this.toast(`Cannot load the character sheet: ${e.message}`));
  }

  private renderActors(s: GameState): void {
    const mine = s.actors.filter((a) => a.ownerId === this.playerId);
    const changedStatus = mine.filter(actor => this.myActors.some(old => old.id === actor.id && !!old.disabled !== !!actor.disabled));
    this.myActors = mine;
    for (const actor of changedStatus) this.sheetHandles.get(actor.id)?.bus.notify();
    if (changedStatus.length) this.logContent.querySelectorAll('.roll-actions').forEach(box => box.dispatchEvent(new Event('refresh-roll-actions')));
    for (const a of mine) void this.ensureSheet(a).then(() => this.scheduleEffects()).catch(() => { /* reported when the sheet is opened */ });
    this.scheduleEffects();
    this.actorsEl.replaceChildren(
      ...(mine.length === 0 ? [el('p', { class: 'muted' }, 'No actors assigned to you.')] : []),
      ...mine.map((a) => {
        const img = el('img', { class: 'art', alt: '' });
        if (a.artwork) img.src = a.artwork;
        const err = el('div', { class: 'error small' });
        const file = el('input', { type: 'file', accept: 'image/*', class: 'hidden' });
        file.addEventListener('change', async () => {
          const f = file.files?.[0];
          if (!f) return;
          err.textContent = 'Uploading...';
          try {
            await uploadArtwork(a.id, f);
            err.textContent = '';
          } catch (e) {
            err.textContent = (e as Error).message;
          }
          file.value = '';
        });
        const up = el('button', { disabled: !!a.disabled }, 'Upload artwork');
        up.addEventListener('click', () => file.click());
        const open = el('button', { class: 'primary' }, 'Open sheet');
        open.addEventListener('click', () => this.openSheet(a));
        const pop = el('button', {}, 'Pop out sheet');
        pop.addEventListener('click', () => window.open(`/sheet/${encodeURIComponent(a.id)}`, '_blank', 'popup,width=900,height=900'));
        const find = s.tokens.find((t) => t.actorId === a.id);
        const name = el('span', { class: 'name' }, a.name, ...(a.disabled ? [' (DISABLED)'] : []), ...(a.testingCharacter ? [' (TESTING)'] : []));
        if (find) {
          name.classList.add('link');
          name.addEventListener('click', () => this.view.focus(find));
        }
        return el('div', { class: 'actor' }, img, el('div', { class: 'grow' }, name, el('div', { class: 'row' }, up), el('div', { class: 'row' }, open, pop), err, file));
      }),
    );
  }

  private showOverlay(text: string): void {
    this.overlay.textContent = text;
    this.overlay.classList.remove('hidden');
  }
}

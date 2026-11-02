import { uploadArtwork, logout } from './api';
import { decodeBitset, encodeBitset } from './bitset';
import { Connection } from './net';
import { ChatMessage, Actor, GameState, Overlay, RollMessage, ServerMessage } from './protocol';
import { iconButton } from './icons';
import { OUTCOME_LABEL, dualityResult, dualitySucceeds, mockCharacter, signed } from './daggerheart/data';
import { SheetWindow } from './daggerheart/window';
import { MapTool } from './tools';
import { MapView, MoveCommit } from './render';
import { el } from './login';
import { OnlineAudio } from './audio';
import { markAudioGesture, settings } from './settings';
import { Store } from './store';

const EXPLORED_MIN_INTERVAL_MS = 2000;

type LogEntry = { kind: 'chat'; m: ChatMessage } | { kind: 'roll'; m: RollMessage };
type SidebarSection = 'chat' | 'actors' | 'settings';
type DrawingEdit = { action: 'create' | 'update' | 'delete'; id: string; shape?: Overlay };

export class App {
  private store = new Store();
  private view: MapView;
  private conn: Connection;
  private playerId = '';
  private drawingHistory: DrawingEdit[] = [];
  private pendingDrawings = new Map<string, { inverse: DrawingEdit; undo: boolean; after?: Overlay; record: boolean }>();
  private historyMap = '';
  private section: SidebarSection | null = 'chat';
  private sidebar = el('aside', { class: 'sidebar', id: 'player-sidebar' });
  private sectionTitle = el('h2', {}, 'Chat & dice');
  private sections = new Map<string, HTMLElement>();
  private sectionButtons = new Map<string, HTMLButtonElement>();
  private toolButtons = new Map<MapTool, HTMLButtonElement>();
  private flyout = el('aside', { class: 'tool-flyout hidden', id: 'tool-flyout' });
  private drawingOptions = el('div', { class: 'tool-options' });
  private measuringOptions = el('div', { class: 'tool-options hidden' });
  private toolTitle = el('h2', {}, 'Drawing');
  private rosterEl = el('aside', { class: 'players-overlay' });
  private mapTitle = el('span', { class: 'map-name' });
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
  private selectionHint = el('p', { class: 'tool-hint' }, 'Navigate to select your drawings. Drag to move; corner handle to resize.');
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
  private actorsEl = el('div', { class: 'actors' });
  private banner = el(
    'div',
    { class: 'banner hidden', role: 'status' },
    el('span', { class: 'banner-icon' }, '\u23F8'),
    el(
      'span',
      { class: 'banner-text' },
      el('strong', {}, 'Game paused by the DM'),
      el('small', {}, 'Map edits are paused. Chat, dice and sheets still work.'),
    ),
  );
  private statusEl = el('span', { class: 'status' }, 'connecting');
  private titleEl = el('span', { class: 'title' });
  private toastEl = el('div', { class: 'toast hidden' });
  private overlay = el('div', { class: 'overlay hidden' });
  private exploredTimer: number | null = null;
  private lastExploredSent = 0;
  private fogLoad = 0;
  private audioAvailable = false;
  private sheetWindows = new Map<string, SheetWindow>();
  private sheetButton?: HTMLButtonElement;
  private firstActor?: Actor;
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
    const heading = el('div', { class: 'map-heading' }, this.titleEl, this.mapTitle, this.statusEl);
    this.rosterEl.setAttribute('aria-label', 'Connected players and ping to host');
    this.stageEl.append(canvas, heading, left, this.flyout, this.rosterEl, right, this.banner, this.toastEl);
    root.replaceChildren(el('div', { class: 'app' }, this.stageEl, this.overlay));
    this.view = new MapView(canvas);
    this.view.maxParticles = settings.maxParticles;
    this.updateDrawingMode('pen');
    this.view.onSelectTool = () => this.selectTool();
    this.view.onUndo = () => this.undoDrawing();
    this.view.onMove = (m) => this.commitMove(m);
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
    return el('div', { class: 'settings-section' }, el('h3', {}, 'Your audio'), this.soundBtn, this.muteBtn, this.volumeBox,
      el('p', { class: 'tool-hint' }, 'Your volume is independent of the DM.'), out);
  }

  private updateSoundUi(): void {
    const needsClick = this.audioAvailable && !settings.muted && !this.audio.running;
    this.soundBtn.classList.toggle('hidden', !needsClick);
    this.volumeBox.classList.toggle('hidden', !this.audioAvailable || settings.muted);
    this.muteBtn.classList.toggle('hidden', !this.audioAvailable);
  }

  private buildChat(): HTMLElement {
    const input = el('input', { type: 'text', placeholder: 'Message or /roll 2d6+3', maxLength: 500 });
    const whisper = el('input', { type: 'checkbox', id: 'whisper' });
    const send = el('button', { class: 'primary' }, 'Send');
    const dice = el('input', { type: 'text', placeholder: '2d6+3', value: '1d20' });
    const rollBtn = el('button', {}, 'Roll');
    const submit = () => {
      const text = input.value.trim();
      if (!text) return;
      const roll = /^\/roll\s+(.+)$/i.exec(text);
      const sent = roll ? this.conn.send({ type: 'roll', expression: roll[1] })
        : this.conn.send({ type: 'chat', text, whisper: whisper.checked });
      if (sent) input.value = '';
      else this.toast('Not connected. Your message was not sent.');
    };
    send.addEventListener('click', submit);
    input.addEventListener('keydown', (e) => e.key === 'Enter' && submit());
    rollBtn.addEventListener('click', () => {
      if (dice.value.trim() && !this.conn.send({ type: 'roll', expression: dice.value.trim() })) this.toast('Not connected. Your roll was not sent.');
    });
    dice.addEventListener('keydown', (e) => e.key === 'Enter' && rollBtn.click());
    return el(
      'div',
      { class: 'chat-section' },
      this.logEl,
      el('div', { class: 'composer' },
      el('div', { class: 'row' }, input, send),
      el('label', { class: 'check' }, whisper, ' Whisper to DM'),
      el('div', { class: 'row' }, dice, rollBtn)),
    );
  }

  private buildSidebar(): HTMLElement {
    const rail = el('nav', { class: 'section-rail' });
    rail.setAttribute('aria-label', 'Sidebar sections');
    this.sections.set('chat', this.buildChat());
    this.sections.set('actors', this.actorsEl);
    this.sections.set('settings', this.buildSettings());
    for (const [id, label] of [['chat', 'Chat & dice'], ['actors', 'My actors'], ['settings', 'Settings']] as const) {
      const button = iconButton(id, label, () => this.showSection(this.section === id ? null : id));
      button.setAttribute('aria-controls', 'player-sidebar');
      this.sectionButtons.set(id, button);
      rail.append(button);
      this.sections.get(id)!.classList.toggle('hidden', id !== 'chat');
    }
    this.sheetButton = iconButton('sheet', 'Character sheet', () => { if (this.firstActor) this.openSheet(this.firstActor, true); });
    this.sheetButton.classList.add('hidden');
    rail.append(this.sheetButton);
    this.sidebar.append(el('header', { class: 'panel-heading' }, this.sectionTitle,
      iconButton('close', 'Collapse sidebar', () => {
        const previous = this.section; this.showSection(null);
        if (previous) this.sectionButtons.get(previous)?.focus();
      })), ...this.sections.values());
    this.showSection('chat');
    return el('div', { class: 'right-sidebar' }, rail, this.sidebar);
  }

  private showSection(section: SidebarSection | null): void {
    this.section = section;
    this.sidebar.classList.toggle('hidden', section === null);
    for (const [id, button] of this.sectionButtons) {
      button.setAttribute('aria-pressed', String(id === section));
      button.setAttribute('aria-expanded', String(id === section));
      this.sections.get(id)!.classList.toggle('hidden', id !== section);
      if (id === section) this.sectionTitle.textContent = button.title;
    }
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
    return el('div', { class: 'left-tools' }, rail, selected);
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
    this.selectionHint.textContent = count ? `${count} owned objects selected. Drag a selected object to move the group. Delete removes drawings and measurements, not actors.`
      : shape ? 'Your drawing selected. Drag to move; corner handle to resize. Open Drawing to change style.'
      : measurement ? 'Your measurement selected. Drag to move or delete.' : 'Navigate to select your drawings or measurements.';
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
        this.view.playerId = msg.playerId;
        this.view.campaignId = msg.campaignId ?? '';
        this.toolButtons.get('draw')!.disabled = !msg.drawingTools;
        this.toolButtons.get('measure')!.disabled = !msg.measurementTools;
        if (this.view.tool === 'draw' && !msg.drawingTools || this.view.tool === 'measure' && !msg.measurementTools) {
          this.view.setTool('navigate');
          this.selectTool();
          this.closeTools();
        }
        this.titleEl.textContent = `${msg.sessionName ?? 'Online Play'}${msg.campaignName ? ' - ' + msg.campaignName : ''} (${msg.playerName})`;
        this.setPaused(msg.paused);
        break;
      case 'roster':
        this.rosterEl.replaceChildren(el('div', { class: 'roster-heading' }, 'CONNECTED / PING TO HOST'),
          el('div', { class: 'player-row' }, 'Dungeon Master', el('span', {}, 'Host')),
          ...msg.players.map(p => el('div', { class: 'player-row' }, `${p.playerName}${p.playerId === this.playerId ? ' (you)' : ''}`,
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
      case 'roll':
        this.addLog({ kind: 'roll', m: msg });
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
    this.setPaused(s.paused);
    if (full || this.store.changed.has('dmFog') || this.store.changed.has('fogGrid') || this.store.changed.has('fogMemory')) {
      void this.loadFog(s);
    }
    if (full || this.store.changed.has('actors') || this.store.changed.has('tokens')) this.renderActors(s);
    this.titleEl.dataset.map = s.mapName ?? '';
    this.mapTitle.textContent = s.mapName ?? '';
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
    this.log.push(e);
    if (this.log.length > 300) this.log.shift();
    this.renderLog();
  }

  private renderLog(): void {
    this.logEl.replaceChildren(
      ...this.log.map((e) => {
        const time = new Date(e.m.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        if (e.kind === 'roll') {
          if (e.m.kind === 'duality' && Array.isArray(e.m.rolls)) return this.dualityEntry(time, e.m);
          const rolls = Array.isArray(e.m.rolls) ? `[${e.m.rolls.join(', ')}]` : '';
          const label = e.m.label ? ` (${e.m.label})` : '';
          return el('div', { class: 'entry roll' }, `${time} ${e.m.from} rolled ${e.m.expression}${label} ${rolls} = `, el('b', {}, String(e.m.total)));
        }
        const whisper = e.m.toId !== undefined && e.m.toId !== null && e.m.toId !== '';
        return el('div', { class: `entry${whisper ? ' whisper' : ''}` }, `${time} `, el('b', {}, e.m.from), whisper ? ' (whisper)' : '', `: ${e.m.text}`);
      }),
    );
    this.logEl.scrollTop = this.logEl.scrollHeight;
  }

  private dualityEntry(time: string, m: RollMessage): HTMLElement {
    const r = dualityResult((m.rolls as unknown[]).map(Number), m.total);
    const die = (cls: string, v: number | string) => el('span', { class: `die ${cls}` }, String(v));
    const parts: Array<Node | string> = [`${time} ${m.from}: `, el('b', {}, m.label ?? 'Duality roll'), ' ', die('hope', r.hope), die('fear', r.fear)];
    if (r.advantage !== 0) parts.push(die('misc', r.advantage > 0 ? `+${r.advantage}` : String(r.advantage)));
    if (r.mod !== 0) parts.push(` ${signed(r.mod)}`);
    parts.push(' = ', el('b', {}, String(r.total)), ' ', el('span', { class: `outcome ${r.outcome}` }, OUTCOME_LABEL[r.outcome]));
    if (typeof m.difficulty === 'number') {
      parts.push(` vs ${m.difficulty}: `, el('b', {}, dualitySucceeds(r, m.difficulty) ? 'success' : 'failure'));
    }
    return el('div', { class: 'entry roll' }, ...parts);
  }

  private openSheet(a: Actor, toggle = false): void {
    let win = this.sheetWindows.get(a.id);
    if (!win) {
      const character = mockCharacter();
      character.name = a.name;
      win = new SheetWindow(
        {
          character,
          artwork: a.artwork,
          onRoll: (r) => {
            const sent = this.conn.send({ type: 'roll', expression: r.expression, label: r.label, kind: r.kind, difficulty: r.difficulty });
            if (!sent) this.toast('Not connected. Your roll was not sent.');
            return sent;
          },
        },
        a.name,
        () => window.open(`/sheet/${encodeURIComponent(a.id)}`, '_blank', 'popup,width=900,height=900'),
        () => this.sheetButton?.setAttribute('aria-pressed', String([...this.sheetWindows.values()].some((w) => w.isOpen))),
      );
      this.sheetWindows.set(a.id, win);
      this.stageEl.append(win.element);
    }
    if (toggle) win.toggle();
    else win.setOpen(true);
  }

  private renderActors(s: GameState): void {
    const mine = s.actors.filter((a) => a.ownerId === this.playerId);
    this.firstActor = mine[0];
    this.sheetButton?.classList.toggle('hidden', mine.length === 0);
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
        const up = el('button', {}, 'Upload artwork');
        up.addEventListener('click', () => file.click());
        const open = el('button', { class: 'primary' }, 'Open sheet');
        open.addEventListener('click', () => this.openSheet(a));
        const pop = el('button', {}, 'Pop out sheet');
        pop.addEventListener('click', () => window.open(`/sheet/${encodeURIComponent(a.id)}`, '_blank', 'popup,width=900,height=900'));
        const find = s.tokens.find((t) => t.actorId === a.id);
        const name = el('span', { class: 'name' }, a.name);
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

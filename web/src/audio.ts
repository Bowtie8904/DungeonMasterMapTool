/** Browser playback of the DM's music and sound effects (plan 3.41.5a). The host only syncs state; files are local. */

export interface AudioTrackInfo {
  id: string;
  name: string;
  url: string;
  /** Loudness-matching gain relative to the original file, in decibels. */
  gainDb: number;
  durationMs: number;
}

export interface MusicInfo extends AudioTrackInfo {
  playing: boolean;
  positionMs: number;
  loop: boolean;
}

export interface EffectInfo extends AudioTrackInfo {
  count: number;
  loop: boolean;
}

export interface AudioMessage {
  type: 'audio';
  enabled: boolean;
  serverTime?: number;
  panic?: boolean;
  effectsPaused?: boolean;
  music?: MusicInfo | null;
  effects?: EffectInfo[];
}

export interface PlayerVolumes {
  master: number;
  music: number;
  effects: number;
  muted: boolean;
}

const FADE_SECONDS = 1.5;
const CROSSFADE_SECONDS = 2;
const DRIFT_LIMIT_MS = 1500;
const MAX_GAIN_DB = 24;
const MIN_GAIN_DB = -48;

/** Decibels to a linear gain factor; invalid values mean unity gain. */
export function dbToGain(db: number): number {
  if (!Number.isFinite(db)) return 1;
  return 10 ** (Math.max(MIN_GAIN_DB, Math.min(MAX_GAIN_DB, db)) / 20);
}

/** The same loudness curve the host applies to its volume sliders. */
export function volumeCurve(x: number): number {
  const v = Math.max(0, Math.min(1, Number.isFinite(x) ? x : 0));
  return v ** 2.2;
}

export interface BusGains {
  master: number;
  music: number;
  effects: number;
}

/** Player levels only: the DM's own volume sliders and mute never affect browsers. Panic and paused effects still apply. */
export function busGains(msg: Pick<AudioMessage, 'panic' | 'effectsPaused'>, player: PlayerVolumes): BusGains {
  const silent = player.muted || msg.panic === true;
  return {
    master: silent ? 0 : volumeCurve(player.master),
    music: volumeCurve(player.music),
    effects: msg.effectsPaused ? 0 : volumeCurve(player.effects),
  };
}

/** Where the music should be now, in seconds, or null when a non-looping track already ended. */
export function musicPositionSeconds(music: MusicInfo, hostTime: number, hostNow: number): number | null {
  let ms = music.positionMs + (music.playing ? Math.max(0, hostNow - hostTime) : 0);
  if (music.durationMs > 0) {
    if (music.loop) ms %= music.durationMs;
    else if (ms >= music.durationMs) return null;
  }
  return Math.max(0, ms / 1000);
}

/** How many new instances of a one-shot effect to start, given the host's running count and what was seen before. */
export function oneShotsToStart(previous: number | undefined, count: number): number {
  return Math.max(0, count - (previous ?? count));
}

interface MusicVoice {
  id: string;
  el: HTMLAudioElement;
  fade: GainNode;
  nodes: AudioNode[];
}

interface LoopVoice {
  source: AudioBufferSourceNode;
  fade: GainNode;
}

export class OnlineAudio {
  private ctx: AudioContext | null = null;
  private master!: GainNode;
  private musicBus!: GainNode;
  private effectsBus!: GainNode;
  private music: MusicVoice | null = null;
  private loops = new Map<string, LoopVoice>();
  private oneShotCounts = new Map<string, number>();
  private buffers = new Map<string, Promise<AudioBuffer | null>>();
  private last: AudioMessage | null = null;
  private initialized = false;
  private resyncTimer: number | null = null;
  onStatus: (text: string) => void = () => {};
  onStateChange: () => void = () => {};

  constructor(private player: () => PlayerVolumes, private hostNow: () => number) {}

  /** Creates the audio graph if needed and tries to resume it; browsers only allow sound after a user gesture. */
  enable(): void {
    if (this.ctx) {
      void this.ctx.resume();
      return;
    }
    const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctor) return;
    const ctx = new Ctor();
    this.ctx = ctx;
    ctx.onstatechange = () => this.onStateChange();
    this.master = ctx.createGain();
    this.musicBus = ctx.createGain();
    this.effectsBus = ctx.createGain();
    // Loudness matching can amplify quiet tracks, so a limiter near full scale prevents clipping.
    const limiter = ctx.createDynamicsCompressor();
    limiter.threshold.value = -2;
    limiter.knee.value = 0;
    limiter.ratio.value = 20;
    limiter.attack.value = 0.003;
    limiter.release.value = 0.1;
    this.musicBus.connect(this.master);
    this.effectsBus.connect(this.master);
    this.master.connect(limiter);
    limiter.connect(ctx.destination);
    void ctx.resume();
    this.resyncTimer = window.setInterval(() => this.keepInSync(), 2000);
  }

  get running(): boolean {
    return this.ctx !== null && this.ctx.state === 'running';
  }

  get active(): boolean {
    return this.last?.enabled === true && !this.player().muted;
  }

  /** Re-applies the player's own sliders; muting releases everything, unmuting rejoins the DM's current playback. */
  volumesChanged(): void {
    if (this.last) void this.apply(this.last);
  }

  async apply(msg: AudioMessage): Promise<void> {
    this.last = msg;
    if (!msg.enabled || this.player().muted) {
      this.release();
      return;
    }
    this.enable();
    if (!this.ctx) return;
    this.applyBuses(msg);
    this.applyMusic(msg);
    await this.applyEffects(msg);
    this.initialized = true;
  }

  dispose(): void {
    this.release();
  }

  /** Stops all voices, drops decoded audio and closes the audio context so a muted player uses no resources. */
  private release(): void {
    if (!this.ctx) return;
    this.stopAll();
    this.buffers.clear();
    if (this.resyncTimer !== null) clearInterval(this.resyncTimer);
    this.resyncTimer = null;
    const ctx = this.ctx;
    this.ctx = null;
    ctx.onstatechange = null;
    window.setTimeout(() => void ctx.close(), 3000);
    this.onStateChange();
  }

  private applyBuses(msg: AudioMessage): void {
    const ctx = this.ctx!;
    const g = busGains(msg, this.player());
    const t = ctx.currentTime;
    this.master.gain.setTargetAtTime(g.master, t, msg.panic ? 0.3 : 0.05);
    this.musicBus.gain.setTargetAtTime(g.music, t, 0.05);
    this.effectsBus.gain.setTargetAtTime(g.effects, t, 0.05);
  }

  private applyMusic(msg: AudioMessage): void {
    const info = msg.music;
    if (!info) {
      this.releaseMusic();
      return;
    }
    if (!this.music || this.music.id !== info.id) {
      this.startMusic(info, msg);
    }
    const voice = this.music;
    if (!voice) return;
    voice.el.loop = info.loop;
    this.syncPosition(voice, info, msg, voice.el.paused);
    if (info.playing && voice.el.paused) {
      void voice.el.play().catch(() => this.onStatus('Click the page to enable sound'));
    } else if (!info.playing && !voice.el.paused) {
      voice.el.pause();
    }
  }

  private startMusic(info: MusicInfo, msg: AudioMessage): void {
    const ctx = this.ctx!;
    const previous = this.music;
    if (previous) this.fadeOutMusic(previous, CROSSFADE_SECONDS);
    const el = new Audio();
    el.preload = 'auto';
    el.src = info.url;
    const source = ctx.createMediaElementSource(el);
    const trackGain = ctx.createGain();
    trackGain.gain.value = dbToGain(info.gainDb);
    const fade = ctx.createGain();
    fade.gain.value = 0;
    source.connect(trackGain);
    trackGain.connect(fade);
    fade.connect(this.musicBus);
    fade.gain.setTargetAtTime(1, ctx.currentTime, CROSSFADE_SECONDS / 4);
    this.music = { id: info.id, el, fade, nodes: [source, trackGain] };
    this.syncPosition(this.music, info, msg, true);
  }

  private syncPosition(voice: MusicVoice, info: MusicInfo, msg: AudioMessage, force: boolean): void {
    if (msg.serverTime === undefined) return;
    const target = musicPositionSeconds(info, msg.serverTime, this.hostNow());
    if (target === null) return;
    const drift = Math.abs(voice.el.currentTime - target) * 1000;
    if (force || drift > DRIFT_LIMIT_MS) {
      try {
        voice.el.currentTime = target;
      } catch {
        /* metadata not loaded yet; the periodic sync retries */
      }
    }
  }

  private fadeOutMusic(voice: MusicVoice, seconds: number): void {
    const ctx = this.ctx!;
    voice.fade.gain.setTargetAtTime(0, ctx.currentTime, Math.max(0.05, seconds / 4));
    window.setTimeout(() => {
      voice.el.pause();
      voice.el.removeAttribute('src');
      voice.el.load();
      voice.nodes.forEach((n) => n.disconnect());
      voice.fade.disconnect();
    }, seconds * 1000 + 300);
  }

  private releaseMusic(): void {
    if (this.music) {
      this.fadeOutMusic(this.music, CROSSFADE_SECONDS);
      this.music = null;
    }
  }

  private keepInSync(): void {
    const msg = this.last;
    if (!msg?.enabled || !msg.music || !this.music || msg.music.id !== this.music.id) return;
    if (msg.music.playing && !this.music.el.paused) this.syncPosition(this.music, msg.music, msg, false);
  }

  private async applyEffects(msg: AudioMessage): Promise<void> {
    const effects = msg.effects ?? [];
    const wantedLoops = new Set(effects.filter((e) => e.loop).map((e) => e.id));
    for (const [id, voice] of [...this.loops]) {
      if (!wantedLoops.has(id)) {
        this.stopLoop(voice);
        this.loops.delete(id);
      }
    }
    const seen = new Set<string>();
    for (const effect of effects) {
      seen.add(effect.id);
      if (effect.loop) {
        if (!this.loops.has(effect.id)) await this.startLoop(effect);
        continue;
      }
      const start = this.initialized ? oneShotsToStart(this.oneShotCounts.get(effect.id), effect.count) : 0;
      this.oneShotCounts.set(effect.id, effect.count);
      for (let i = 0; i < start; i++) void this.playOneShot(effect);
    }
    for (const id of [...this.oneShotCounts.keys()]) if (!seen.has(id)) this.oneShotCounts.delete(id);
  }

  private loadBuffer(url: string): Promise<AudioBuffer | null> {
    let p = this.buffers.get(url);
    if (!p) {
      p = fetch(url)
        .then((r) => (r.ok ? r.arrayBuffer() : Promise.reject(new Error(String(r.status)))))
        .then((data) => this.ctx!.decodeAudioData(data))
        .catch(() => null);
      this.buffers.set(url, p);
    }
    return p;
  }

  private async startLoop(effect: EffectInfo): Promise<void> {
    const buffer = await this.loadBuffer(effect.url);
    const ctx = this.ctx;
    if (!buffer || !ctx || this.loops.has(effect.id)) return;
    const source = ctx.createBufferSource();
    source.buffer = buffer;
    source.loop = true;
    const trackGain = ctx.createGain();
    trackGain.gain.value = dbToGain(effect.gainDb);
    const fade = ctx.createGain();
    fade.gain.value = 0;
    source.connect(trackGain);
    trackGain.connect(fade);
    fade.connect(this.effectsBus);
    fade.gain.setTargetAtTime(1, ctx.currentTime, FADE_SECONDS / 4);
    source.start();
    this.loops.set(effect.id, { source, fade });
  }

  private stopLoop(voice: LoopVoice): void {
    const ctx = this.ctx;
    if (!ctx) return;
    voice.fade.gain.setTargetAtTime(0, ctx.currentTime, FADE_SECONDS / 4);
    try {
      voice.source.stop(ctx.currentTime + FADE_SECONDS + 0.3);
    } catch {
      /* already stopped */
    }
  }

  private async playOneShot(effect: EffectInfo): Promise<void> {
    const buffer = await this.loadBuffer(effect.url);
    const ctx = this.ctx;
    if (!buffer || !ctx) return;
    const source = ctx.createBufferSource();
    source.buffer = buffer;
    const trackGain = ctx.createGain();
    trackGain.gain.value = dbToGain(effect.gainDb);
    source.connect(trackGain);
    trackGain.connect(this.effectsBus);
    source.onended = () => {
      source.disconnect();
      trackGain.disconnect();
    };
    source.start();
  }

  private stopAll(): void {
    this.releaseMusic();
    for (const voice of this.loops.values()) this.stopLoop(voice);
    this.loops.clear();
    this.oneShotCounts.clear();
    this.initialized = false;
  }
}

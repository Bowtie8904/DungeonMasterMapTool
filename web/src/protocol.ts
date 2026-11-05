export interface ImageLayer {
  id: string;
  hash?: string;
  url: string;
  x: number;
  y: number;
  width: number;
  height: number;
  rotationDeg: number;
  zIndex: number;
  /** Present for large images: full-resolution tiles from the host's tile pyramid. */
  pyramid?: import('./pyramid').PyramidInfo;
}

export interface Door {
  id: string;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  open: boolean;
  kind?: string;
}

export interface Light {
  id: string;
  x: number;
  y: number;
  range: number;
  brightness: number;
  revealMode?: 'PERSISTENT' | 'WHILE_LIT' | 'NONE';
  color?: string;
  on: boolean;
  /** Present only when the light flickers. */
  flicker?: { strength: number; speed: number };
}

export interface Overlay {
  id: string;
  ownerId?: string | null;
  ownerCampaignId?: string | null;
  type: string;
  x: number;
  y: number;
  width: number;
  height: number;
  radius: number;
  strokeWidth: number;
  color: string;
  alpha: number;
  texture: string;
  border: boolean;
  points: number[];
  /** Procedural texture tile (hashed asset) and its scrolling layers [vx, vy, scale, alpha, pulse, pulseHz]. */
  tile?: string;
  soft?: boolean;
  tileCells?: number;
  featherCells?: number;
  featherPasses?: number;
  layers?: number[][];
  emits?: boolean;
  /** False = drawn on the floor below tokens and affected by light; otherwise above tokens, unaffected. */
  above?: boolean;
  lightStrength?: number;
  /** Light reach beyond the shape edge, in grid cells. */
  lightRange?: number;
  flicker?: number;
  flickerSpeed?: number;
}

export interface EffectTexture {
  id: string;
  label: string;
  color: string | null;
  emits: boolean;
  above: boolean;
}

export interface Measurement {
  id: string;
  ownerId: string;
  kind: 'distance' | 'radius' | 'cone' | 'rect';
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

export interface Ping {
  x: number;
  y: number;
  startMs: number;
  durationMs: number;
}

export interface IndoorData {
  originX: number;
  originY: number;
  cellSize: number;
  cols: number;
  rows: number;
  /** java.util.BitSet bytes, base64. */
  inside: string;
  /** One byte (0-255) per cell, base64; absent when daylight through openings is off. */
  daylight?: string;
  nightDarkness: number;
  night: number[];
  /** False when indoor ambient/lights do not apply (night or indoor darkness off); the grid still drives weather. */
  lighting?: boolean;
}

export interface TextBoxState {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  background: string;
  border: string;
  runs: Array<{ text: string; size: number; color: string }>;
}

export interface Lighting {
  ambient: number;
  /** Peak strength (0..1) of the coloured highlight at the centre of each light. */
  brightCore?: number;
  brightCoreRadius?: number;
  lightTint?: number;
  /** DM darkness scaling for colour tint only; player ambient darkness is unchanged. */
  tintDarknessFactor?: number;
  /** Whether lights may flicker (global setting, off in performance mode). */
  flicker?: boolean;
  /** Time-of-day multiply tint, each channel 0..1. */
  tint?: [number, number, number];
  timeOfDay?: number | string;
}

export interface Weather {
  type: string;
  intensity: number;
  lightningIntervalSeconds: number;
  seed: number;
  /** Also drawn inside enclosed rooms (weather.<type>.indoors). */
  indoors?: boolean;
  particles?: number;
  color?: string;
  opacity?: number;
  flash?: { duration: number; opacity: number; brightness: number; color: string };
}

export interface Token {
  id: string;
  x: number;
  y: number;
  sizeTiles: number;
  rotationDeg?: number;
  name?: string;
  image?: string;
  color?: string;
  shape?: 'circle' | 'square';
  border?: boolean;
  zoom?: number;
  centerX?: number;
  centerY?: number;
  actorId?: string;
  controlledBy?: string[];
}

export interface FogGridInfo {
  originX: number;
  originY: number;
  cellSize: number;
  cols: number;
  rows: number;
}

export interface DmFog extends FogGridInfo {
  revealed?: string;
}

export interface Actor {
  id: string;
  name: string;
  ownerId: string;
  artwork?: string;
  testingCharacter?: boolean;
}

export interface ChatMessage {
  id: string | number;
  ts: number;
  fromId: string;
  from: string;
  toId?: string;
  to?: string;
  text: string;
  sharedCard?: import('./shared-cards').SharedCard;
  actorId?: string;
  restDice?: import('./daggerheart/rest').RestDie[];
  rollSource?: RollSource;
}

/** Description at roll time, independent of mechanics and the current sheet. */
export interface RollSource {
  name: string;
  text: string;
  source: string;
}

export interface RollContribution {
  label: string;
  expression: string;
  notes?: Array<{ label: string; impact: string }>;
}

export interface RollDetail extends RollContribution {
  rolls: number[];
  total: number;
}

export interface RollMessage {
  id: string | number;
  ts: number;
  fromId: string;
  from: string;
  toId?: string;
  to?: string;
  expression: string;
  rolls: unknown;
  total: number;
  label?: string;
  kind?: string;
  difficulty?: number;
  breakdown?: RollDetail[];
  rollSource?: RollSource;
  secret?: boolean;
  /** Hints echoed from the roller's sheet; only the roller's client acts on them. */
  ref?: string;
  actorId?: string;
  /** Comma separated roll targets, for example "attack,trait:Agility,action" or "damage". */
  source?: string;
  weapon?: string;
  /** Host-authoritative Hope/Fear override; dice and total remain unchanged. */
  outcome?: 'hope' | 'fear';
  /** Additional result rendered on a feature-check roll card. */
  featureRoll?: { name: string; result: number; consequence: string };
  deathMove?: 'avoid' | 'risk';
  deathResult?: { outcome: 'hope' | 'fear' | 'crit'; hope: number; fear: number; adjustedHope: number; bonus: number; budget: number; claimed: boolean; undying: boolean; converted?: boolean };
  /** Feature actions already applied to this roll, keyed by actor and action id. */
  featureUses?: string[];
  /** Present only on host-supported rolls; persisted and updated after the roller claims the reward. */
  rewardClaimed?: boolean;
}

export interface FeatureSheetMessage {
  type: 'sheetFeature';
  actorId: string;
  featureRevision: number;
  hp?: import('./daggerheart/data').Track;
  stress?: import('./daggerheart/data').Track;
  hope?: import('./daggerheart/data').Track;
  scars?: number;
  permanentHp?: number;
  longRestRevision?: number;
  spectralBlocked?: boolean;
  deathNarrative?: string;
  pendingDeathRoll?: { id: number; ts: number } | null;
  inventoryRevision?: number;
  compendiumRevision?: number;
  inventory?: import('./daggerheart/data').CharacterSheet['inventory'];
  loadout?: import('./daggerheart/data').CharacterSheet['loadout'];
  vault?: import('./daggerheart/data').CharacterSheet['vault'];
  armorTrack?: import('./daggerheart/data').Track;
  effects?: import('./daggerheart/engine').ActiveEffect[];
  usage?: import('./daggerheart/data').CharacterSheet['usage'];
  resources?: Record<string, number>;
}

export interface Countdown {
  id: string;
  name: string;
  initialValue: number;
  value: number;
}

export interface GameState {
  effectTextures?: EffectTexture[];
  penWidthCells?: number;
  /** Fear tokens the GM holds (0-12); absent when the host predates the Fear counter. */
  fear?: number;
  /** Read-only campaign countdowns; omitted by older hosts. */
  countdowns?: Countdown[];
  mapId: string;
  levelId: string;
  mapName?: string;
  grid: { pixelsPerCell: number };
  view?: {
    gridOpacity: number;
    /** Mirrors the DM's player-grid toggle; the grid is hidden unless this is true. */
    showGrid?: boolean;
    fogSoftness?: number;
    fogFade?: boolean;
    fogEnabled?: boolean;
    fogRevealSeconds?: number;
    fogHideSeconds?: number;
    ping?: { color: string; startRadius: number; grow: number };
  };
  pings?: Ping[];
  indoor?: IndoorData | null;
  overlays?: Overlay[];
  texts?: TextBoxState[];
  images: ImageLayer[];
  walls: number[];
  doors: Door[];
  lights: Light[];
  lighting: Lighting;
  weather: Weather;
  tokens: Token[];
  dmFog: DmFog | null;
  fogGrid?: FogGridInfo | null;
  fogMemory: { cells: string } | null;
  actors: Actor[];
  paused: boolean;
  visionTiles: number;
  chatTail?: Array<(ChatMessage & { type?: 'chat' }) | (RollMessage & { type: 'roll' })>;
}

export type DeltaOp =
  | { op: 'set'; path: string; value: unknown }
  | { op: 'tokens'; upsert?: Token[]; remove?: string[] };

export interface Hello {
  type: 'hello';
  deathMoves?: boolean;
  deathMoveOptions?: boolean;
  protocol: number;
  playerId: string;
  playerName: string;
  serverTime: number;
  sessionName?: string;
  campaignName?: string;
  paused: boolean;
  campaignId?: string;
  drawingTools?: boolean;
  measurementTools?: boolean;
  playerWhispers?: boolean;
  chatCards?: boolean;
  compendiumChat?: boolean;
  transferOffers?: boolean;
  keptDice?: boolean;
  rollMultipliers?: boolean;
  rollRewards?: boolean;
  chatIdentity?: boolean;
  notes?: boolean;
  handouts?: boolean;
}

export interface HandoutFile { id: string; name: string; folder: string; ext: string; size: number }

import type { AudioMessage } from './audio';

export type ServerMessage =
  | Hello
  | { type: 'cardStock'; actorId: string }
  | { type: 'sheetInventory'; actorId: string; inventory: Partial<import('./daggerheart/data').CharacterSheet> }
  | FeatureSheetMessage
  | { type: 'roster'; players: Array<{ playerId: string; playerName: string; chatIdentity?: string; connections: number; pingMs: number }> }
  | { type: 'measurements'; mapKey: string; items: Measurement[] }
  | { type: 'snapshot'; seq: number; state: GameState }
  | { type: 'delta'; seq: number; ops: DeltaOp[] }
  | { type: 'ack'; cmdId: string; seq?: number }
  | { type: 'reject'; cmdId: string; reason: string; token?: { id: string; x: number; y: number } }
  | { type: 'clockSync'; id: number; clientSent: number; serverTime: number }
  | { type: 'ping'; id: number }
  | { type: 'pause'; paused: boolean }
  | ({ type: 'chat' } & ChatMessage)
  | { type: 'chatDeleted'; id: number; ts: number }
  | { type: 'chatUpdate'; message: ChatMessage }
  | { type: 'handouts'; folders: string[]; files: HandoutFile[] }
  | { type: 'handoutShow'; id: string; name: string }
  | ({ type: 'roll' } & RollMessage)
  | ({ type: 'rollUpdate'; id: number; ts: number } & Partial<Pick<RollMessage, 'outcome' | 'featureUses' | 'rewardClaimed' | 'deathResult' | 'featureRoll'>>)
  | AudioMessage
  | { type: 'kicked'; reason?: string }
  | { type: 'error'; message: string };

export interface SessionInfo {
  protocol: number;
  sessionName: string;
  campaignName: string;
  paused: boolean;
  maxPlayers: number;
  players: Array<{ id: string; name: string }>;
}

export function defaultState(): GameState {
  return {
    mapId: '',
    levelId: '',
    grid: { pixelsPerCell: 70 },
    images: [],
    walls: [],
    doors: [],
    lights: [],
    lighting: { ambient: 1 },
    weather: { type: 'none', intensity: 0.4, lightningIntervalSeconds: 7, seed: 0 },
    tokens: [],
    dmFog: null,
    fogMemory: null,
    actors: [],
    paused: false,
    visionTiles: 8,
  };
}

/** Fills defaults for fields an older or partial host may omit. */
export function normalizeState(raw: Partial<GameState>): GameState {
  const base = defaultState();
  const s = { ...base, ...raw } as GameState;
  s.images ??= [];
  s.walls ??= [];
  s.doors ??= [];
  s.lights ??= [];
  s.tokens ??= [];
  s.actors ??= [];
  s.overlays ??= [];
  s.texts ??= [];
  s.pings ??= [];
  s.lighting = { ...base.lighting, ...(raw.lighting ?? {}) };
  s.weather = { ...base.weather, ...(raw.weather ?? {}) };
  s.grid = { ...base.grid, ...(raw.grid ?? {}) };
  s.dmFog ??= null;
  s.fogMemory ??= null;
  s.visionTiles = typeof s.visionTiles === 'number' ? s.visionTiles : 8;
  return s;
}

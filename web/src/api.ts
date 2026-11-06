import { SessionInfo } from './protocol';
import type { CharacterSheet } from './daggerheart/data';

let dmSheetToken: string | undefined;
export function configureDmSheet(token: string): void { dmSheetToken = token; }
export function dmSheetAuthorization(): string | undefined { return dmSheetToken; }
export function hostFetch(url: string, options: RequestInit = {}): Promise<Response> {
  if (!dmSheetToken) return fetch(url, options);
  const headers = new Headers(options.headers);
  headers.set('Authorization', `Bearer ${dmSheetToken}`);
  return fetch(url, { ...options, credentials: 'omit', headers });
}

const sheetVersions = new Map<string, string>();
export function acceptSheetVersion(actorId: string, version?: string): void {
  if (version) sheetVersions.set(actorId, version);
}

export class ApiError extends Error {
  constructor(message: string, public status: number, public retryAfterSeconds?: number) {
    super(message);
  }
}

export class InventoryConflict extends ApiError {
  constructor(public sheet: Partial<CharacterSheet>, public version?: string) {
    super(version ? 'The character sheet changed in another window. Latest sheet loaded; please reapply your edit.'
      : 'Inventory changed in chat; inventory refreshed. Please review your changes.', 409);
  }
}

async function readError(res: Response): Promise<ApiError> {
  let body: { error?: string; retryAfterSeconds?: number } = {};
  try {
    body = await res.json();
  } catch {
    /* no body */
  }
  let message = body.error ?? `Request failed (${res.status})`;
  if (res.status === 429 && body.retryAfterSeconds !== undefined) {
    message = `${body.error ?? 'Too many attempts'} Try again in ${body.retryAfterSeconds} s.`;
  }
  return new ApiError(message, res.status, body.retryAfterSeconds);
}

export async function getSession(): Promise<SessionInfo> {
  const res = await fetch('/api/session', { credentials: 'same-origin' });
  if (!res.ok) throw await readError(res);
  return (await res.json()) as SessionInfo;
}

export async function login(playerId: string, password: string): Promise<void> {
  const res = await fetch('/api/login', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ playerId, password }),
  });
  if (!res.ok) throw await readError(res);
}

export async function logout(): Promise<void> {
  const res = await fetch('/api/logout', { method: 'POST', credentials: 'same-origin' });
  if (!res.ok) throw await readError(res);
}

export async function uploadArtwork(actorId: string, file: File): Promise<void> {
  const res = await fetch(`/api/actors/${encodeURIComponent(actorId)}/artwork`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': file.type || 'application/octet-stream' },
    body: file,
  });
  if (!res.ok) throw await readError(res);
}

export interface SheetData {
  version?: string;
  testingCharacter?: boolean;
  id?: string;
  name?: string;
  artwork?: string;
  /** The stored character sheet; null until the player has created one. */
  sheet?: Partial<CharacterSheet> | null;
  [key: string]: unknown;
}

export async function getSheet(actorId: string): Promise<SheetData> {
  const res = await hostFetch(`/api/sheet/${encodeURIComponent(actorId)}`, { credentials: 'same-origin' });
  if (!res.ok) throw await readError(res);
  const data = (await res.json()) as SheetData;
  if (!sheetVersions.has(actorId)) acceptSheetVersion(actorId, data.version);
  return data;
}

export async function saveSheet(actorId: string, sheet: CharacterSheet): Promise<SheetData | void> {
  const version = sheetVersions.get(actorId);
  const res = await hostFetch(`/api/sheet/${encodeURIComponent(actorId)}`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', ...(version ? { 'If-Match': version } : {}) },
    body: JSON.stringify(sheet),
  });
  if (res.status === 409) {
    const body: { error?: string; sheet?: Partial<CharacterSheet>; version?: string } = await res.json();
    if (body.sheet && typeof body.sheet === 'object' && !Array.isArray(body.sheet)) throw new InventoryConflict(body.sheet, body.version);
    throw new ApiError(body.error ?? 'The character sheet changed. Reload before saving.', 409);
  }
  if (!res.ok) throw await readError(res);
  const data = await res.json() as SheetData;
  acceptSheetVersion(actorId, data.version);
  return data;
}

export interface FeatureRequest {
  actorId: string;
  key: string;
  actionId: string;
  targetActorId?: string;
  confirmRange?: boolean;
  amount?: number;
  restDie?: number;
  benefitId?: string;
  roll?: { id: number | string; ts: number };
}

export async function useFeature(request: FeatureRequest): Promise<void> {
  const res = await hostFetch('/api/feature', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  if (!res.ok) throw await readError(res);
}

export async function getDeathOptions(actorId: string): Promise<import('./daggerheart/death').DeathOptions> {
  const res = await hostFetch(`/api/death-move/${encodeURIComponent(actorId)}`, { credentials: 'same-origin' });
  if (!res.ok) throw await readError(res);
  return res.json();
}

export async function applyDeathRecovery(actorId: string, roll: { id: number | string; ts: number }, recovery: { hp?: number; stress?: number; undying?: boolean }): Promise<void> {
  const res = await hostFetch('/api/death-recovery', { method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ actorId, roll, ...recovery }) });
  if (!res.ok) throw await readError(res);
}

export async function takeDeathMove(actorId: string, move: import('./daggerheart/death').DeathMove, extras?: import('./daggerheart/death').DeathMoveExtras): Promise<void> {
  const res = await hostFetch('/api/death-move', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ actorId, move, ...extras }),
  });
  if (!res.ok) throw await readError(res);
}

export async function claimRollReward(actorId: string, roll: { id: number | string; ts: number }, reward?: 'removeFear'): Promise<void> {
  const res = await hostFetch('/api/roll-reward', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ actorId, roll, ...(reward ? { reward } : {}) }),
  });
  if (!res.ok) throw await readError(res);
}

export async function damageTaken(actorId: string, severity: number): Promise<void> {
  if (severity < 3) return;
  const res = await hostFetch('/api/damage-taken', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ actorId, severity }),
  });
  if (!res.ok) throw await readError(res);
}

export async function cardRequest<T>(path: string, body?: unknown): Promise<T> {
  const res = await hostFetch(`/api/chat-cards${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!res.ok) throw await readError(res);
  return await res.json() as T;
}

export async function notesRequest<T>(path = '', body?: unknown): Promise<T> {
  const res = await fetch(`/api/notes${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!res.ok) throw await readError(res);
  return await res.json() as T;
}

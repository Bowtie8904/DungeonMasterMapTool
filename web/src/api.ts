import { SessionInfo } from './protocol';
import type { CharacterSheet } from './daggerheart/data';

export class ApiError extends Error {
  constructor(message: string, public status: number, public retryAfterSeconds?: number) {
    super(message);
  }
}

export class InventoryConflict extends ApiError {
  constructor(public sheet: Partial<CharacterSheet>) {
    super('Inventory changed in chat; inventory refreshed. Please review your changes.', 409);
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
  testingCharacter?: boolean;
  id?: string;
  name?: string;
  artwork?: string;
  /** The stored character sheet; null until the player has created one. */
  sheet?: Partial<CharacterSheet> | null;
  [key: string]: unknown;
}

export async function getSheet(actorId: string): Promise<SheetData> {
  const res = await fetch(`/api/sheet/${encodeURIComponent(actorId)}`, { credentials: 'same-origin' });
  if (!res.ok) throw await readError(res);
  return (await res.json()) as SheetData;
}

export async function saveSheet(actorId: string, sheet: CharacterSheet): Promise<void> {
  const res = await fetch(`/api/sheet/${encodeURIComponent(actorId)}`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(sheet),
  });
  if (res.status === 409) {
    const body: { error?: string; sheet?: Partial<CharacterSheet> } = await res.json();
    if (body.sheet && typeof body.sheet === 'object' && !Array.isArray(body.sheet)) throw new InventoryConflict(body.sheet);
    throw new ApiError(body.error ?? 'The character sheet changed. Reload before saving.', 409);
  }
  if (!res.ok) throw await readError(res);
}

export interface FeatureRequest {
  actorId: string;
  key: string;
  actionId: string;
  targetActorId?: string;
  roll?: { id: number | string; ts: number };
}

export async function useFeature(request: FeatureRequest): Promise<void> {
  const res = await fetch('/api/feature', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  if (!res.ok) throw await readError(res);
}

export async function claimRollReward(actorId: string, roll: { id: number | string; ts: number }): Promise<void> {
  const res = await fetch('/api/roll-reward', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ actorId, roll }),
  });
  if (!res.ok) throw await readError(res);
}

export async function damageTaken(actorId: string, severity: number): Promise<void> {
  if (severity < 3) return;
  const res = await fetch('/api/damage-taken', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ actorId, severity }),
  });
  if (!res.ok) throw await readError(res);
}

export async function cardRequest<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`/api/chat-cards${path}`, {
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

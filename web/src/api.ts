import { SessionInfo } from './protocol';

export class ApiError extends Error {
  constructor(message: string, public status: number, public retryAfterSeconds?: number) {
    super(message);
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
  id?: string;
  name?: string;
  artwork?: string;
  [key: string]: unknown;
}

export async function getSheet(actorId: string): Promise<SheetData> {
  const res = await fetch(`/api/sheet/${encodeURIComponent(actorId)}`, { credentials: 'same-origin' });
  if (!res.ok) throw await readError(res);
  return (await res.json()) as SheetData;
}

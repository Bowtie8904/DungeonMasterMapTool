import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, notesRequest } from './api';
import { noteChildren, withinNote, type NoteEntry } from './notes';

afterEach(() => vi.unstubAllGlobals());

describe('campaign notes', () => {
  it('lists immediate children with folders before named notes', () => {
    const entry = (path: string, name: string, folder = false): NoteEntry => ({ path, name, folder, shared: false });
    const entries = [
      entry('private/Z folder', 'Z folder', true), entry('private/Apple.txt', 'Apple'),
      entry('private/Apple 2.txt', 'Apple 2'), entry('private/Z folder/Child.txt', 'Child'),
      { ...entry('group.txt', 'Group Notes'), shared: true },
    ];
    expect(noteChildren(entries, 'private').map(e => e.name)).toEqual(['Z folder', 'Apple', 'Apple 2']);
    expect(noteChildren(entries, 'private/Z folder').map(e => e.name)).toEqual(['Child']);
  });

  it('matches exact folder boundaries when renaming/deleting open editors', () => {
    expect(withinNote('private/A/One.txt', 'private/A')).toBe(true);
    expect(withinNote('private/A', 'private/A')).toBe(true);
    expect(withinNote('private/AB/One.txt', 'private/A')).toBe(false);
    expect(withinNote('group.txt', 'private')).toBe(false);
  });

  it('authenticates reads, bypasses caches and sends exact text and revision on save', async () => {
    const content = { path: 'group.txt', text: 'Line 1\nLine 2', revision: 'new' };
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify(content))));
    vi.stubGlobal('fetch', fetch);
    expect(await notesRequest('/content?path=group.txt')).toEqual(content);
    expect(fetch).toHaveBeenLastCalledWith('/api/notes/content?path=group.txt', expect.objectContaining({
      method: 'GET', credentials: 'same-origin', cache: 'no-store',
    }));
    const request = { action: 'save', path: 'group.txt', text: content.text, revision: 'old' };
    await notesRequest('', request);
    expect(fetch).toHaveBeenLastCalledWith('/api/notes', expect.objectContaining({
      method: 'POST', credentials: 'same-origin', body: JSON.stringify(request),
    }));
  });

  it('surfaces conflicts and storage failures instead of reporting success', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'Note changed. Reload first.' }), { status: 409 }));
    vi.stubGlobal('fetch', fetch);
    await expect(notesRequest('', {})).rejects.toMatchObject({ status: 409, message: 'Note changed. Reload first.' });
    fetch.mockResolvedValue(new Response(JSON.stringify({ error: 'Could not save notes.' }), { status: 500 }));
    await expect(notesRequest('', {})).rejects.toBeInstanceOf(ApiError);
  });
});

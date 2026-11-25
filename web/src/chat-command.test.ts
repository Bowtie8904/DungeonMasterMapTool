import { describe, expect, it } from 'vitest';
import { emoteCommand } from './chat-command';
import type { Actor } from './protocol';

const actors: Actor[] = [
  { id: 'one', name: 'Old', ownerId: 'player', disabled: true },
  { id: 'two', name: 'Mara', ownerId: 'player' },
  { id: 'three', name: 'Rook', ownerId: 'player' },
];

describe('/em', () => {
  it('uses the first enabled character and sends readable text to older hosts', () => {
    expect(emoteCommand('/EM   says hello to the dogs.', actors)).toEqual({
      text: 'Mara says hello to the dogs.', emoteText: 'says hello to the dogs.',
    });
  });
  it('requires an action and an active character without treating other commands as emotes', () => {
    expect(emoteCommand('/em', actors)).toEqual({ error: 'Use /em followed by an action.' });
    expect(emoteCommand('/em waves', [])).toEqual({ error: 'An active character is required to use /em.' });
    expect(emoteCommand('/emote waves', actors)).toBeNull();
    expect(emoteCommand('hello', actors)).toBeNull();
  });
});

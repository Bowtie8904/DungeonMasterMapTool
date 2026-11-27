import { expect, it } from 'vitest';
import { rosterPlayerName } from './roster';

it('formats actor pronouns after the character and supports older roster messages', () => {
  expect(rosterPlayerName({ playerName: 'Alice', chatIdentity: 'Aria', pronouns: 'they/them' }))
    .toBe('Alice (Aria)[they/them]');
  expect(rosterPlayerName({ playerName: 'Alice', chatIdentity: 'Aria' })).toBe('Alice (Aria)');
  expect(rosterPlayerName({ playerName: 'Alice', chatIdentity: 'Aria', pronouns: '' })).toBe('Alice (Aria)');
  expect(rosterPlayerName({ playerName: 'Alice', chatIdentity: 'Aria', pronouns: '  ' })).toBe('Alice (Aria)');
  expect(rosterPlayerName({ playerName: 'Alice', pronouns: 'they/them' })).toBe('Alice');
  expect(rosterPlayerName({ playerName: 'Alice', chatIdentity: '', pronouns: 'they/them' })).toBe('Alice');
});

import type { Actor } from './protocol';

export function emoteCommand(text: string, actors: Actor[]):
    { text: string; emoteText: string } | { error: string } | null {
  const command = /^\/em(?:\s+([\s\S]*))?$/i.exec(text);
  if (!command) return null;
  const emoteText = (command[1] ?? '').trim();
  if (!emoteText) return { error: 'Use /em followed by an action.' };
  const actor = actors.find(candidate => !candidate.disabled && candidate.name.trim());
  if (!actor) return { error: 'An active character is required to use /em.' };
  return { text: `${actor.name} ${emoteText}`, emoteText };
}

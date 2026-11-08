export function rosterPlayerName(player: { playerName: string; chatIdentity?: string; pronouns?: string }): string {
  if (!player.chatIdentity) return player.playerName;
  const pronouns = player.pronouns?.trim() ? ` [${player.pronouns}]` : '';
  return `${player.playerName} (${player.chatIdentity}${pronouns})`;
}

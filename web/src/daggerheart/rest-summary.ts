// Format of the automated rest report posted to the chat. It is plain chat text (readable on any client);
// clients that know the format render it as a distinct report card instead of a message a player typed.
export interface RestReport { kind: 'short' | 'long'; subtitle: string; lines: string[] }

// Older clients prefixed the header with a campfire emoji; still accept it so earlier chat history renders as a card.
const HEAD = /^(?:\u{1F3D5}\uFE0F )?(SHORT|LONG) REST$/u;

export function formatRestReport(r: RestReport): string {
  const out = [`${r.kind.toUpperCase()} REST`, r.subtitle];
  let used = out.join('\n').length;
  for (const line of r.lines) {
    if (used + line.length + 3 > 990) { out.push('• ...'); break; }
    out.push(`• ${line}`);
    used += line.length + 3;
  }
  return out.join('\n');
}

export function parseRestReport(text: string): RestReport | null {
  const lines = text.split('\n');
  const head = HEAD.exec(lines[0] ?? '');
  if (!head || lines.length < 2) return null;
  return {
    kind: head[1] === 'LONG' ? 'long' : 'short',
    subtitle: lines[1],
    lines: lines.slice(2).map((l) => l.replace(/^• ?/, '')),
  };
}

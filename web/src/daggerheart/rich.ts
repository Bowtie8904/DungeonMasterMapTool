// Renders the light markdown used by the compendium texts (**bold**, *italic*, _italic_, "- " lists, line breaks) as DOM nodes.

import { el } from '../login';

const INLINE = /\*\*\*(.+?)\*\*\*|\*\*(.+?)\*\*|\*(.+?)\*|(?<![\w])_(.+?)_(?![\w])/g;

/** Inline markup of a single line as text and <b>/<i> nodes. */
export function inline(line: string): Array<Node | string> {
  const out: Array<Node | string> = [];
  let last = 0;
  for (const m of line.matchAll(INLINE)) {
    if (m.index > last) out.push(line.slice(last, m.index));
    if (m[1] !== undefined) out.push(el('b', {}, el('i', {}, m[1])));
    else out.push(m[2] !== undefined ? el('b', {}, ...inline(m[2])) : el('i', {}, m[3] ?? m[4]));
    last = m.index + m[0].length;
  }
  if (last < line.length) out.push(line.slice(last));
  return out;
}

/** A block with one paragraph per line and bullet lists for lines starting with "- " or "* ". */
export function rich(text: string | undefined, cls = ''): HTMLElement {
  const box = el('div', { class: `rich ${cls}`.trim() });
  let list: HTMLElement | null = null;
  for (const raw of (text ?? '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) { list = null; continue; }
    const bullet = /^[-*]\s+(.*)$/.exec(line);
    if (bullet) {
      if (!list) { list = el('ul', {}); box.append(list); }
      list.append(el('li', {}, ...inline(bullet[1])));
    } else {
      list = null;
      box.append(el('p', {}, ...inline(line)));
    }
  }
  return box;
}

/** The text without markup, for short previews. */
export const plain = (text: string | undefined): string => (text ?? '').replace(/\*\*|\*|(?<![\w])_|_(?![\w])/g, '').replace(/\s*\n\s*/g, ' ');

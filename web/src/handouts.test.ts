import { describe, expect, it } from 'vitest';
import { formatSize, handoutRows, handoutUrl } from './handouts';

const file = (id: string, name: string, folder: string) => ({ id, name, folder, ext: 'png', size: 10 });

describe('handout browser helpers', () => {
  it('lists folders first, then files, for one folder level', () => {
    const rows = handoutRows(['Maps', 'Maps/Caves', 'Clues'], [file('1', 'b.png', ''), file('2', 'a.png', ''), file('3', 'x.png', 'Maps')], '');
    expect(rows.map(r => r.name)).toEqual(['Clues', 'Maps', 'a.png', 'b.png']);
    expect(handoutRows(['Maps', 'Maps/Caves'], [file('3', 'x.png', 'Maps')], 'Maps').map(r => r.path)).toEqual(['Maps/Caves', 'Maps/x.png']);
  });

  it('formats sizes and urls', () => {
    expect(formatSize(512)).toBe('512 B');
    expect(formatSize(2048)).toBe('2 KB');
    expect(formatSize(3 * 1024 * 1024)).toBe('3.0 MB');
    expect(handoutUrl('a b')).toBe('/api/handouts/a%20b');
  });
});

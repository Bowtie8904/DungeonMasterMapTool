import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { actionBlocker, routeDeathAction, sheetActions, type Automation } from './engine';
import { emptyCharacter } from './data';
import { pips } from './view';

afterEach(() => vi.unstubAllGlobals());

describe('death action routing and permanent pips', () => {
  it('routes every catalog equipment/card death action to the shared host dialog instead of local automation', () => {
    const seen = new Set<string>();
    for (const category of ['armor', 'domain-cards', 'consumables', 'classes']) {
      const data = JSON.parse(readFileSync(new URL(`../../../dmmap-daggerheart/${category}.json`, import.meta.url), 'utf8'));
      const sources = data.entries.flatMap((entry: { automation?: Automation; features?: Array<{ automation?: Automation }> }) => [entry, ...(entry.features ?? [])]);
      for (const source of sources as Array<{ automation?: Automation }>) {
        const actions = sheetActions(source.automation);
        for (const action of actions.filter(action => action.deathMove)) {
          const open = vi.fn();
          expect(routeDeathAction(action, open)).toBe(true);
          expect(open).toHaveBeenCalledWith(action.deathMove);
          seen.add(action.deathMove!);
          const c = emptyCharacter('Aria');
          c.hp.slots = c.hp.on = 6;
          c.hope.on = 6;
          expect(actionBlocker(c, 'source', source.automation!, action)).toBeNull();
          c.hp.on = 5;
          expect(actionBlocker(c, 'source', source.automation!, action)).toContain('Requires all HP');
        }
      }
    }
    expect([...seen].sort()).toEqual(['battleHardened', 'egg', 'risk', 'spectral', 'unbreakable']);
    const open = vi.fn();
    expect(routeDeathAction({ id: 'normal', label: 'Normal' }, open)).toBe(false);
    expect(open).not.toHaveBeenCalled();
  });

  it('locks distinct permanent marks and permits only nonpermanent HP pip recovery', () => {
    class Element {
      disabled = false;
      className = '';
      title = '';
      children: Element[] = [];
      dataset = {};
      style = { setProperty: () => {} };
      classList = { toggle: () => {} };
      listeners = new Map<string, () => void>();
      append(child: Element): void { this.children.push(child); }
      setAttribute(): void {}
      addEventListener(event: string, handler: () => void): void { this.listeners.set(event, handler); }
    }
    vi.stubGlobal('document', { createElement: () => new Element() });
    const track = { on: 6, slots: 6, total: 12 };
    const changed = vi.fn();
    const box = pips(track, 'hp', 'red', false, changed, 0, 2) as unknown as Element;
    for (const permanent of box.children.slice(0, 2)) {
      expect(permanent.disabled).toBe(true);
      expect(permanent.className).toContain('permanent-hp');
      expect(permanent.title).toContain('cannot be healed');
      permanent.listeners.get('click')!();
      expect(track.on).toBe(6);
    }
    box.children[2].listeners.get('click')!();
    expect(track.on).toBe(3);
    box.children[2].listeners.get('click')!();
    expect(track.on).toBe(2);
    box.children[0].listeners.get('click')!();
    expect(track.on).toBe(2);
    expect(changed).toHaveBeenCalledTimes(2);
  });
});

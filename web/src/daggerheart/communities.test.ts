import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { dualityExpression, emptyCharacter } from './data';
import { describeAutomation } from './compendium';
import { openRollDialog, type RollRequest } from './view';
import {
  type Automation, applyRest, availableRollOptions, availableRollTriggers, commitRollOptions, configuredRollOption,
  rollBonus, runAction, selectedRollBonus, selectionBlocker, syncDerived, usesLeft,
} from './engine';

const catalog: { entries: Array<{ name: string; features: Array<{ name: string; text: string; automation: Automation }> }> } =
  JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/communities.json', import.meta.url), 'utf8'));

function community(name: string) {
  const c = emptyCharacter(name);
  const entry = catalog.entries.find(e => e.name === name)!;
  c.heritage = entry.features.map(f => ({ ...structuredClone(f), source: `Community - ${name}` }));
  c.hope.on = 5;
  syncDerived(c);
  return c;
}

describe('community catalog automation', () => {
  it.each(['Wanderborne', 'Warborne'])('%s spends Hope and shares its session-use counter', name => {
    const c = community(name);
    const f = c.heritage[0];
    const a = f.automation!;
    expect(runAction(c, `feat:${f.name}`, a, a.actions![0], f.name)).not.toBeNull();
    expect(c.hope.on).toBe(4);
    expect(usesLeft(c, `feat:${f.name}`, a)).toBe(0);
    expect(runAction(c, `feat:${f.name}`, a, a.actions![0], f.name)).toBeNull();
    expect(c.hope.on).toBe(4);
    c.usage = {};
    c.hope.on = 0;
    expect(runAction(c, `feat:${f.name}`, a, a.actions![0], f.name)).toBeNull();
    expect(usesLeft(c, `feat:${f.name}`, a)).toBe(1);
  });

  class DialogElement {
    className = '';
    value = '';
    type = '';
    checked = false;
    disabled = false;
    textContent = '';
    removed = false;
    children: DialogElement[] = [];
    listeners = new Map<string, Array<() => void>>();
    classList = { toggle: (_name: string, _on?: boolean) => undefined };
    constructor(readonly tag: string) {}
    get ownerDocument(): Document { return document; }
    get valueAsNumber(): number { return this.value === '' ? NaN : Number(this.value); }
    append(...nodes: Array<DialogElement | string>): void {
      for (const node of nodes) {
        if (typeof node === 'string') { const text = new DialogElement('text'); text.textContent = node; this.children.push(text); }
        else this.children.push(node);
      }
    }
    addEventListener(type: string, callback: () => void): void {
      this.listeners.set(type, [...(this.listeners.get(type) ?? []), callback]);
    }
    fire(type: string): void { if (!this.disabled) this.listeners.get(type)?.forEach(callback => callback()); }
    focus(): void {}
    remove(): void { this.removed = true; }
    get text(): string { return this.textContent + this.children.map(child => child.text).join(''); }
    find(predicate: (element: DialogElement) => boolean): DialogElement {
      if (predicate(this)) return this;
      for (const child of this.children) {
        const result = child.findOptional(predicate);
        if (result) return result;
      }
      throw new Error('Dialog control not found');
    }
    private findOptional(predicate: (element: DialogElement) => boolean): DialogElement | undefined {
      return predicate(this) ? this : this.children.map(child => child.findOptional(predicate)).find(Boolean);
    }
  }

  afterEach(() => vi.unstubAllGlobals());

  function rollDialog(name: string, sent = true, tokens?: number) {
    const c = community(name);
    if (tokens !== undefined) { c.level = 5; c.resources!.tide = tokens; }
    const body = new DialogElement('body');
    vi.stubGlobal('document', { body, createElement: (tag: string) => new DialogElement(tag), addEventListener: () => undefined, removeEventListener: () => undefined });
    const send = vi.fn((_request: RollRequest) => sent);
    openRollDialog(body as unknown as HTMLElement, c, 'Action', [], rollBonus(c, ['action']), send, () => undefined, ['action']);
    const checkbox = body.find(element => element.tag === 'input' && element.type === 'checkbox');
    checkbox.checked = true;
    checkbox.fire('change');
    const go = body.find(element => element.tag === 'button' && element.text === 'Roll to chat');
    return { c, body, send, go };
  }

  describe('community roll dialog wiring', () => {
    it('sends Dedicated with a real d20 Hope die and consumes its rest use', () => {
      const { c, send, go } = rollDialog('Orderborne');
      go.fire('click');
      expect(send.mock.calls[0][0]).toMatchObject({
        kind: 'duality', expression: '1d20+1d12',
        contributions: expect.arrayContaining([{ label: 'Hope die', expression: '1d20' }, { label: 'Fear die', expression: '1d12' }]),
      });
      expect(c.usage['feat:Dedicated'].used).toBe(1);
    });

    it('sends Found Family with the default or edited bonus and pays exactly one Hope', () => {
      for (const amount of [2, 4]) {
        const { c, body, send, go } = rollDialog('Reborne');
        const input = body.find(element => element.tag === 'input' && element.type === 'number');
        input.value = String(amount);
        input.fire('input');
        go.fire('click');
        expect(send.mock.calls[0][0]).toMatchObject({ expression: `2d12+${amount}` });
        expect(c.hope.on).toBe(4);
        expect(c.usage['feat:Found Family'].used).toBe(1);
      }
    });

    it('blocks unavailable Tide quantities and spends exactly the quantity sent', () => {
      const { c, body, send, go } = rollDialog('Seaborne', true, 3);
      const input = body.find(element => element.tag === 'input' && element.type === 'number');
      input.value = '4';
      input.fire('input');
      expect(go.disabled).toBe(true);
      go.fire('click');
      expect(send).not.toHaveBeenCalled();
      expect(c.resources!.tide).toBe(3);
      input.value = '2';
      input.fire('input');
      expect(go.disabled).toBe(false);
      go.fire('click');
      expect(send.mock.calls[0][0]).toMatchObject({ expression: '2d12+2' });
      expect(c.resources!.tide).toBe(1);
    });

    it('does not spend Hope, tokens or uses on cancellation or a failed send', () => {
      const family = rollDialog('Reborne', false);
      family.go.fire('click');
      expect(family.c.hope.on).toBe(5);
      expect(family.c.usage['feat:Found Family']).toBeUndefined();
      const tide = rollDialog('Seaborne', false, 3);
      tide.go.fire('click');
      expect(tide.c.resources!.tide).toBe(3);
      const dedicated = rollDialog('Orderborne');
      dedicated.body.find(element => element.tag === 'button' && element.text === 'Cancel').fire('click');
      expect(dedicated.send).not.toHaveBeenCalled();
      expect(dedicated.c.usage['feat:Dedicated']).toBeUndefined();
    });
  });

  it('Unbound exposes a sheet button and uses the same limit as the Fear reaction', () => {
    const c = community('Freeborne');
    const f = c.heritage[0];
    const a = f.automation!;
    expect(a.actions?.[0].label).toBe('Unbound');
    expect(availableRollTriggers(c, { source: 'action', hope: 1, fear: 12, total: 13 })).toHaveLength(1);
    runAction(c, `feat:${f.name}`, a, a.actions![0], f.name);
    expect(usesLeft(c, `feat:${f.name}`, a)).toBe(0);
  });

  it('Dedicated replaces the Hope die and consumes its source rest limit', () => {
    const c = community('Orderborne');
    const options = availableRollOptions(c, ['action']);
    expect(options[0].option.hopeDie).toBe(20);
    expect(dualityExpression(2, 1, 20)).toBe('1d20+1d12+1d6+2');
    expect(dualityExpression(2, 1)).toBe('2d12+1d6+2');
    expect(availableRollOptions(c, ['damage'])).toHaveLength(0);
    commitRollOptions(c, options);
    expect(selectionBlocker(c, options)).toBeTruthy();
    applyRest(c, 'short');
    expect(selectionBlocker(c, options)).toBeNull();
  });

  it('Found Family accepts an ally Experience bonus, defaults to +2 and spends 1 Hope once', () => {
    const c = community('Reborne');
    const option = availableRollOptions(c, ['action'])[0];
    const defaultOption = configuredRollOption(c, option);
    expect(selectedRollBonus(c, rollBonus(c, ['action']), [defaultOption]).value).toBe(2);
    const selected = configuredRollOption(c, option, 4);
    expect(selectedRollBonus(c, rollBonus(c, ['action']), [selected]).value).toBe(4);
    expect(() => configuredRollOption(c, option, 1.5)).toThrow();
    commitRollOptions(c, [selected]);
    expect(c.hope.on).toBe(4);
    expect(selectionBlocker(c, [selected])).toBeTruthy();
  });

  it('Tide has a session-only level-capped counter and spends exactly the selected tokens', () => {
    const c = community('Seaborne');
    c.level = 5;
    c.resources!.tide = 4;
    const a = c.heritage[0].automation!;
    expect(a.resources?.[0]).toMatchObject({ initial: 0, max: '@level', session: true });
    expect(a.actions).toBeUndefined();
    expect(availableRollTriggers(c, { source: 'action', hope: 2, fear: 8, total: 10 })).toHaveLength(1);
    expect(availableRollTriggers(c, { source: 'action', hope: 8, fear: 2, total: 10 })).toHaveLength(0);
    const option = availableRollOptions(c, ['action'])[0];
    const selected = configuredRollOption(c, option, 3);
    expect(selectedRollBonus(c, rollBonus(c, ['action']), [selected]).value).toBe(3);
    expect(() => configuredRollOption(c, option, 5)).toThrow();
    commitRollOptions(c, [selected]);
    expect(c.resources!.tide).toBe(1);
  });

  it('Oasis opts into explicit rest reactions and Close-Knit opts into targeted Hope transfer', () => {
    expect(community('Duneborne').heritage[0].automation).toEqual({ restReroll: true, uses: { max: 1, per: 'rest' } });
    expect(describeAutomation(community('Duneborne').heritage[0].automation)).toContain('Chat: reroll a short-rest downtime die and keep the higher result');
    expect(community('Hearthborne').heritage[0].automation?.actions?.[0]).toMatchObject({ target: 'actor', transferHope: true });
  });
});

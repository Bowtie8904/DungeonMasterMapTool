import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter } from './data';
import { openRollDialog, type RollRequest } from './view';
import {
  availableRollOptions, availableRollTriggers, commitRollOptions, derivedStats, experienceModifier,
  rollBonus, rollExperienceRefund, rollOptionDice, selectionBlocker, statBreakdown, syncDerived,
} from './engine';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));

function wizard(school = 'knowledge', stage = 'mastery') {
  const c = emptyCharacter('Wizard');
  const stages = ['foundation', 'specialization', 'mastery'];
  c.className = 'Wizard';
  c.level = 8;
  c.features = catalog.entries.filter(e => e.id === 'wizard'
    || e.id?.startsWith(`wizard-school-of-${school}-`) && stages.indexOf(e.stage ?? '') <= stages.indexOf(stage))
    .flatMap(e => (e.features ?? []).map(f => ({ ...structuredClone(f), source: e.name })));
  c.hope.on = 3;
  c.evasion = 11;
  c.proficiency.on = 3;
  syncDerived(c);
  return c;
}

describe('Wizard catalog automation', () => {
  it('requires an Experience for Adept, doubles its modifier, and replaces Hope with Stress', () => {
    const c = wizard();
    const adept = availableRollOptions(c, ['action', 'trait:Knowledge']).filter(o => o.source === 'Adept');
    expect(adept).toHaveLength(1);
    expect(selectionBlocker(c, adept)).toMatch(/Select an Experience/);
    expect(() => commitRollOptions(c, adept)).toThrow(/Select an Experience/);
    expect(experienceModifier(3, adept)).toBe(6);
    expect(experienceModifier(-1, adept)).toBe(-2);
    expect(experienceModifier(3, [])).toBe(3);
    c.hope.on = 0;
    expect(selectionBlocker(c, adept, true)).toBeNull();
    commitRollOptions(c, adept, true);
    expect(c.hope.on).toBe(0);
    expect(c.stress.on).toBe(1);
    expect(availableRollOptions(c, ['reaction', 'trait:Knowledge']).some(o => o.source === 'Adept')).toBe(true);
    expect(availableRollOptions(c, ['attack', 'action']).some(o => o.source === 'Adept')).toBe(true);
    expect(availableRollOptions(c, ['damage']).some(o => o.source === 'Adept')).toBe(false);
  });

  class DialogElement {
    className = '';
    hidden = false;
    value = '';
    type = '';
    checked = false;
    disabled = false;
    textContent = '';
    children: DialogElement[] = [];
    listeners = new Map<string, () => void>();
    classList = { toggle: () => undefined };
    constructor(readonly tag: string) {}
    get ownerDocument(): Document { return document; }
    append(...nodes: Array<DialogElement | string>): void {
      for (const node of nodes) {
        if (typeof node === 'string') { const text = new DialogElement('text'); text.textContent = node; this.children.push(text); }
        else this.children.push(node);
      }
    }
    replaceChildren(...nodes: DialogElement[]): void { this.children = nodes; }
    setAttribute(): void {}
    addEventListener(type: string, callback: () => void): void { this.listeners.set(type, callback); }
    fire(type: string): void { if (!this.disabled) this.listeners.get(type)?.(); }
    focus(): void {}
    remove(): void {}
    get text(): string { return this.textContent + this.children.map(child => child.text).join(''); }
    all(): DialogElement[] { return [this, ...this.children.flatMap(child => child.all())]; }
    find(predicate: (element: DialogElement) => boolean): DialogElement {
      const result = this.all().find(predicate);
      if (!result) throw new Error('Dialog control not found');
      return result;
    }
  }

  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

  describe('Wizard roll dialog', () => {
    function dialog(sent = true) {
      const c = wizard();
      c.experiences = [{ name: 'Arcane Scholar', mod: 3 }];
      const body = new DialogElement('body');
      vi.stubGlobal('document', { body, createElement: (tag: string) => new DialogElement(tag),
        addEventListener: () => undefined, removeEventListener: () => undefined });
      const send = vi.fn((_request: RollRequest) => sent);
      const onSent = vi.fn();
      openRollDialog(body as unknown as HTMLElement, c, 'Knowledge', [], rollBonus(c, ['action']), send, onSent, ['action']);
      const exp = body.find(e => e.tag === 'select');
      const adept = body.find(e => e.tag === 'label' && e.text.includes('Adept') && e.all().some(n => n.type === 'checkbox'))
        .find(e => e.type === 'checkbox');
      const go = body.find(e => e.tag === 'button' && e.text === 'Roll to chat');
      return { c, body, send, onSent, exp, adept, go };
    }

    it.each([true, false])('blocks Adept without an Experience and refunds its Stress only on a sent roll (%s)', sent => {
      const { c, body, send, onSent, exp, adept, go } = dialog(sent);
      adept.checked = true;
      adept.fire('change');
      expect(go.disabled).toBe(true);
      expect(body.text).toContain('Select an Experience');
      exp.value = '0';
      exp.fire('change');
      expect(go.disabled).toBe(false);
      expect(body.text).toContain('Modifier +6');
      const random = vi.spyOn(Math, 'random').mockReturnValue(0.9);
      go.fire('click');
      expect(random).toHaveBeenCalledOnce();
      expect(send.mock.calls[0][0]).toMatchObject({
        kind: 'duality', expression: '2d12+6',
        contributions: expect.arrayContaining([
          { label: 'Experience - Arcane Scholar (Adept: doubled)', expression: '6' },
          { label: 'Honed Expertise: 1d6 = 6', expression: '0',
            notes: [{ label: 'Experience cost', impact: '1 Stress refunded' }] },
        ]),
      });
      expect(c.hope.on).toBe(3);
      expect(c.stress.on).toBe(0);
      expect(onSent).toHaveBeenCalledTimes(sent ? 1 : 0);
      if (!sent) expect(body.text).toContain('no costs were paid');
    });

    it.each([true, false])('pays Adept Stress on a low d6 only when the send succeeds (%s)', sent => {
      const { c, send, exp, adept, go } = dialog(sent);
      exp.value = '0';
      exp.fire('change');
      adept.checked = true;
      adept.fire('change');
      vi.spyOn(Math, 'random').mockReturnValue(0);
      go.fire('click');
      expect(send.mock.calls[0][0].contributions?.at(-1)?.label).toBe('Honed Expertise: 1d6 = 1');
      expect(c.hope.on).toBe(3);
      expect(c.stress.on).toBe(sent ? 1 : 0);
    });

    it('does not roll the refund die on cancellation or when no Experience is used', () => {
      const { c, body, go, send } = dialog();
      const random = vi.spyOn(Math, 'random');
      body.find(e => e.tag === 'button' && e.text === 'Cancel').fire('click');
      expect(random).not.toHaveBeenCalled();
      expect(send).not.toHaveBeenCalled();
      expect(c.hope.on).toBe(3);
      const other = dialog();
      other.go.fire('click');
      expect(other.send.mock.calls[0][0].contributions?.some(p => p.label.includes('Honed Expertise'))).toBe(false);
      expect(random).not.toHaveBeenCalled();
      expect(go.disabled).toBe(false);
    });
  });

  it('checks combined costs without spending them during selection', () => {
    const c = wizard();
    const adept = availableRollOptions(c, ['action']).filter(o => o.source === 'Adept');
    c.stress.on = c.stress.slots - 1;
    expect(selectionBlocker(c, adept, true)).toBeNull();
    expect(c.stress.on).toBe(c.stress.slots - 1);
    const extra = { key: 'stress-option', source: 'Other', automation: {},
      option: { id: 'other', label: 'Other', roll: 'action', cost: { stress: 1 } } };
    expect(selectionBlocker(c, [...adept, extra], true)).toMatch(/STRESS/);
    c.stress.on++;
    expect(selectionBlocker(c, adept, true)).toMatch(/STRESS/);
    expect(() => commitRollOptions(c, adept, true)).toThrow(/STRESS/);
    c.hope.on = 0;
    expect(selectionBlocker(c, [], true)).toBe('Not enough Hope');
  });

  it.each([1, 4, 5, 6])('automatically refunds Hope or Adept Stress on a Honed Expertise result of %i', face => {
    for (const adept of [false, true]) {
      const c = wizard();
      const selected = adept ? availableRollOptions(c, ['action']).filter(o => o.source === 'Adept') : [];
      const result = rollExperienceRefund(c, selected, dice => {
        expect(dice).toBe('1d6');
        return face;
      })!;
      expect(result.refunded).toBe(face >= 5);
      expect(result.contribution.expression).toBe('0');
      expect(result.contribution.label).toContain(`1d6 = ${face}`);
      expect(result.contribution.notes?.[0].impact).toBe(`1 ${adept ? 'Stress' : 'Hope'} ${face >= 5 ? 'refunded' : 'spent'}`);
      commitRollOptions(c, selected, true, [], result.refunded);
      expect(c.hope.on).toBe(adept || face >= 5 ? 3 : 2);
      expect(c.stress.on).toBe(adept && face < 5 ? 1 : 0);
    }
  });

  it('does not roll Honed Expertise without the mastery feature', () => {
    expect(rollExperienceRefund(wizard('knowledge', 'foundation'), [], () => { throw new Error('Unexpected die'); })).toBeUndefined();
  });

  it.each([0, 1, 2, 6])('applies Conjure Shield and its breakdown only at 2+ Hope (Hope %i)', hope => {
    const c = wizard('war');
    c.hope.on = hope;
    expect(derivedStats(c).evasion).toBe(hope >= 2 ? 14 : 11);
    expect(statBreakdown(c).evasion.some(s => s.label === 'Conjure Shield')).toBe(hope >= 2);
    c.proficiency.on = 4;
    expect(derivedStats(c).evasion).toBe(hope >= 2 ? 15 : 11);
    c.hope.on = 2;
    commitRollOptions(c, [], true);
    expect(derivedStats(c).evasion).toBe(11);
  });

  it.each([['foundation', '1d10'], ['specialization', '2d10'], ['mastery', '3d10']])('upgrades Face Your Fear at %s', (stage, dice) => {
    const c = wizard('war', stage);
    const option = availableRollOptions(c, ['damage']).find(o => o.source === 'Face Your Fear')!;
    expect(rollOptionDice(c, option.option)).toBe(dice);
  });

  it('offers Thrive in Chaos only on damage messages, with its Stress cost and target report', () => {
    const c = wizard('war');
    const attack = { source: 'attack,action', hope: 9, fear: 2, total: 11 };
    expect(availableRollTriggers(c, attack).some(t => t.source === 'Thrive in Chaos')).toBe(false);
    const trigger = availableRollTriggers(c, { ...attack, source: 'damage', kind: 'damage' }).find(t => t.source === 'Thrive in Chaos')!;
    expect(trigger.trigger.cost).toEqual({ stress: 1 });
    expect(trigger.trigger.description).toContain('target marks an additional Hit Point');
    expect(availableRollTriggers(wizard('war', 'specialization'), { ...attack, source: 'damage', kind: 'damage' })
      .some(t => t.source === 'Thrive in Chaos')).toBe(false);
  });
});

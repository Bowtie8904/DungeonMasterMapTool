import { readFileSync } from 'node:fs';
import { afterEach, expect, it, vi } from 'vitest';
import { emptyCharacter } from './data';
import { availableRollOptions, availableRollTriggers, rollBonus, syncEffectDependencies, type Automation, type EffectDef } from './engine';
import { openRollDialog, type RollRequest } from './view';

const catalog = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
const card = catalog.entries.find((entry: { id: string }) => entry.id === 'invisibility');
const automation: Automation = card.automation;
const invisible = (): EffectDef & { id: string; source: string } => ({
  ...structuredClone(automation.rollTriggers![0].steps![0].effect!),
  id: 'f12345678-1234-1234-1234-123456789012', source: 'Invisibility', sourceActorId: 'caster', sourceKey: 'card:Invisibility',
});

class Element {
  textContent = '';
  className = '';
  value = '';
  checked = false;
  disabled = false;
  hidden = false;
  style = {};
  classList = { toggle: () => {} };
  children: Array<Element | string> = [];
  ownerDocument = { body: this };
  listeners = new Map<string, () => unknown>();
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  addEventListener(event: string, handler: () => unknown): void { this.listeners.set(event, handler); }
  setAttribute(): void {}
  focus(): void {}
  remove(): void {}
  querySelectorAll(tag: string): Element[] {
    return this.children.flatMap(child => typeof child === 'string' ? [] :
      [...(child.tag === tag ? [child] : []), ...child.querySelectorAll(tag)]);
  }
  click(): void { if (!this.disabled) this.listeners.get('click')?.(); }
  text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text()).join(''); }
}

afterEach(() => vi.unstubAllGlobals());

it('offers only a successful Difficulty 10 targeted cast and card token tracker', () => {
  expect(automation.actions ?? []).toEqual([]);
  expect(automation.spellcastDifficulty).toBe(10);
  expect(automation.resources?.map(resource => resource.id)).toEqual(['invisibility']);
  const caster = emptyCharacter('Caster');
  caster.loadout.push({ name: card.name, level: card.level, domain: card.domain, type: 'Spell', recall: card.recall, text: card.text, automation });
  const context = { source: 'action,spellcast,card:Invisibility', kind: 'duality' as const, hope: 7, fear: 3, total: 12, difficulty: 10 };
  const trigger = availableRollTriggers(caster, context)[0].trigger;
  expect(trigger.target).toBe('anyActor');
  expect(trigger.cost).toEqual({ stress: 1 });
  expect(availableRollTriggers(caster, { ...context, total: 9 })).toHaveLength(0);
});

it('keeps a recipient buff without local tokens and offers it only on action rolls', () => {
  const recipient = emptyCharacter('Recipient');
  recipient.effects.push(invisible());
  recipient.resources = { invisibility: 0 };
  syncEffectDependencies(recipient);
  expect(recipient.effects).toHaveLength(1);
  const options = availableRollOptions(recipient, ['trait:Agility', 'action']);
  expect(options).toHaveLength(1);
  expect(options[0].option.label).toBe('Invisibility: spend token');
  expect(options[0].option.defaultOn).toBe(true);
  expect(availableRollOptions(recipient, ['reaction', 'trait:Agility'])).toHaveLength(0);
  expect(availableRollOptions(recipient, ['damage'])).toHaveLength(0);
});

it('preselects a validated host-spend hint without spending locally; cancellation and failed sends are inert', () => {
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag),
    addEventListener: () => {}, removeEventListener: () => {} });
  const recipient = emptyCharacter('Recipient');
  recipient.effects.push(invisible());
  const host = new Element('host');
  const requests: RollRequest[] = [];
  let connected = false;
  const sent = vi.fn();
  const open = (support: boolean) => openRollDialog(host as unknown as HTMLElement, recipient, 'Agility', [], rollBonus(recipient, ['action']),
    request => { requests.push(request); return connected; }, sent, ['trait:Agility', 'action'],
    undefined, undefined, undefined, false, undefined, undefined, undefined, undefined, 0, undefined, undefined, () => support);
  open(true);
  const checkbox = host.querySelectorAll('input').find(input => input.checked)!;
  expect(checkbox).toBeDefined();
  host.querySelectorAll('button').find(button => button.text() === 'Cancel')!.click();
  expect(requests).toHaveLength(0);
  expect(recipient.effects).toHaveLength(1);
  open(true);
  const go = host.querySelectorAll('button').filter(button => button.text() === 'Roll to chat').at(-1)!;
  go.click();
  expect(requests[0].source).toContain(`option:effect:${recipient.effects[0].id}:spend-token`);
  expect(sent).not.toHaveBeenCalled();
  expect(recipient.effects).toHaveLength(1);
  connected = true;
  go.click();
  expect(sent).toHaveBeenCalledOnce();
  expect(recipient.effects).toHaveLength(1);
  expect(recipient.resources?.invisibility).toBeUndefined();
  const oldHost = new Element('host');
  openRollDialog(oldHost as unknown as HTMLElement, recipient, 'Agility', [], rollBonus(recipient, ['action']),
    () => true, () => {}, ['action']);
  expect(oldHost.querySelectorAll('input').some(input => input.checked)).toBe(false);
});

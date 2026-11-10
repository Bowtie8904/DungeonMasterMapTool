import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { weaponFrom, type CompendiumFile } from './compendium';
import { emptyCharacter, type CharacterSheet, type Weapon } from './data';
import {
  addEffect, availableRollOptions, availableRollTriggers, commitRollOptions, damageContributions, evalValue, rollBonus,
  selectionBlocker, weaponAttackBlocker, weaponDamageRules, weaponProfiles, weaponWithProfile,
} from './engine';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import { attackDamageContext, rerollRequest } from './rollbuttons';
import { criticalMeleeBonus } from './brawler';
import { openRollDialog, type RollRequest } from './view';
import type { RollMessage } from '../protocol';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/weapons.json', import.meta.url), 'utf8'));
const entry = (id: string) => catalog.entries.find(e => e.id === id)!;
const weapon = (id: string): Weapon => weaponFrom(entry(id));
const featured = (name: string) => catalog.entries.filter(e => e.feature?.name === name);

function hero(...ids: string[]): CharacterSheet {
  const c = emptyCharacter('Hero');
  c.activeWeapons = ids.map(weapon);
  c.armorTrack = { slots: 4, total: 12, on: 1 };
  c.hp = { slots: 6, total: 12, on: 0 };
  return c;
}

class Element {
  className = '';
  private content = '';
  get textContent(): string { return this.content; }
  set textContent(value: string) { this.content = value; this.children = []; }
  value = '';
  get valueAsNumber(): number { return Number(this.value); }
  checked = false;
  disabled = false;
  hidden = false;
  ariaLabel = '';
  title = '';
  style = { cssText: '', setProperty: () => {} };
  classList = { toggle: () => {}, add: () => {}, contains: () => false };
  dataset: Record<string, string> = {};
  children: Array<Element | string> = [];
  listeners = new Map<string, () => unknown>();
  constructor(readonly tag: string) {}
  get ownerDocument(): Document { return document; }
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  addEventListener(event: string, handler: () => unknown): void { this.listeners.set(event, handler); }
  setAttribute(): void {}
  remove(): void {}
  focus(): void {}
  querySelector(): null { return null; }
  querySelectorAll(selector: string): Element[] {
    return this.children.flatMap(child => typeof child === 'string' ? [] : [...(child.tag === selector ? [child] : []), ...child.querySelectorAll(selector)]);
  }
  text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text()).join(''); }
  async click(): Promise<void> { if (!this.disabled) await this.listeners.get('click')?.(); }
}

function stubDocument(): Element {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {}, removeEventListener: () => {} });
  return body;
}

afterEach(() => vi.unstubAllGlobals());

describe('weapon catalog automation', () => {
  it('moves costed after-hit features onto their attack results instead of sheet buttons', () => {
    for (const name of ['Concussive', 'Grappling', 'Restraining', 'Entangling', 'Deforming', 'Magnetic', 'Draining', 'Catalytic', 'Lucky']) {
      for (const e of featured(name)) {
        expect(e.automation?.actions, `${e.id} ${name}`).toBeUndefined();
        expect(e.automation?.rollTriggers?.[0].on, `${e.id} ${name}`).toBe('attack');
      }
    }
    for (const name of ['Quick', 'Ricochet', 'Volleyed', 'Aimed', 'Devastating', 'Charged', 'Accelerator']) {
      for (const e of featured(name)) {
        expect(e.automation?.actions, `${e.id} ${name}`).toBeUndefined();
        expect(e.automation?.rollOptions?.every(o => o.roll === 'attack'), `${e.id} ${name}`).toBe(true);
      }
    }
    expect(entry('blitz-hammer').automation).toMatchObject({ uses: { max: 1, per: 'scene' }, rollOptions: [{ cost: { stress: 1 }, proficiency: 1 }] });
  });

  it('declares alternate profiles for every Versatile and Stockpiled weapon', () => {
    const entries = [...featured('Versatile'), ...featured('Stockpiled')];
    expect(entries.length).toBe(22);
    for (const e of entries) {
      const [profile] = weaponProfiles(weaponFrom(e));
      expect(profile, e.id).toBeDefined();
      expect(profile.damage, e.id).toMatch(/^1d\d+(\+\d+)?$/);
    }
    expect(weaponWithProfile(weapon('improved-scepter'), 'Versatile')).toMatchObject({ trait: 'Presence', range: 'Melee', damage: '1d8+3', damageType: 'magic', profile: 'Versatile' });
    expect(weaponWithProfile(weapon('gunblade'), 'Versatile').damageType).toBe('phys');
    expect(weaponWithProfile(weapon('javelins'), 'Stockpiled (thrown)')).toMatchObject({ trait: 'Agility', range: 'Far' });
  });

  it('marks host damage rules, Reloading and Pompous explicitly', () => {
    expect(featured('Reloading').every(e => e.automation?.reloading?.fail === 1)).toBe(true);
    const c = hero('knuckle-blades');
    expect(weaponDamageRules(c, c.activeWeapons[0])).toEqual({ explode: 'once', label: 'Brutal' });
    expect(weaponDamageRules(hero('m-bius-orb'), weapon('m-bius-orb'))).toMatchObject({ explode: 'repeat' });
    expect(weaponDamageRules(hero('curved-dagger'), weapon('curved-dagger'))).toEqual({ onesAs: 8, label: 'Serrated' });
    expect(weaponDamageRules(hero('gilded-bow'), weapon('gilded-bow'))).toEqual({ onesAs: 6, label: 'Self-Correcting' });
    expect(damageContributions(c, c.activeWeapons[0], rollBonus(c, ['damage'], c.activeWeapons[0]))[0].notes
      ?.some(note => note.impact.includes('extra'))).toBe(false);
  });
});

describe('weapon automation rules', () => {
  it('applies weapon roll modifiers only to their own weapon, or to the slot they name', () => {
    const c = hero('flickerfly-blade', 'tinkers-hammer');
    c.traits.find(t => t.name === 'Agility')!.mod = 2;
    const [blade, hammer] = c.activeWeapons;
    expect(rollBonus(c, ['damage'], blade).value).toBe(2);
    expect(rollBonus(c, ['damage'], hammer).value).toBe(0);
    expect(rollBonus(c, ['damage']).value).toBe(0);
    expect(rollBonus(c, ['attack'], blade).value).toBe(1);
    expect(rollBonus(c, ['attack'], hammer).value).toBe(0);
  });

  it('offers weapon triggers on their own weapon, any weapon, or primary attacks as declared', () => {
    const c = hero('axe-of-fortunis', 'vorpal-shard');
    const triggers = (name: string) => availableRollTriggers(c, { source: 'attack,trait:Strength,action', hope: 3, fear: 8, total: 9, weapon: name })
      .map(t => t.trigger.id).sort();
    expect(triggers('Axe of Fortunis')).toEqual(['lucky', 'targeted']);
    expect(triggers('Vorpal Shard')).toEqual(['targeted']);
    const entangle = hero('broadsword', 'razor-wire');
    const ids = (name: string) => availableRollTriggers(entangle, { source: 'attack,action', hope: 8, fear: 3, total: 12, weapon: name }).map(t => t.trigger.id);
    expect(ids('Broadsword')).toEqual(['entangling']);
    expect(ids('Razor Wire')).toEqual([]);
  });

  it('keeps primary-weapon options for alternate profiles and offers stored Channeling only to the Claws', () => {
    const c = hero('scepter', 'shortsword');
    const profile = weaponWithProfile(c.activeWeapons[0], 'Versatile');
    expect(availableRollOptions(c, ['damage'], profile).map(o => o.option.label)).toContain('Paired');
    expect(damageContributions(c, profile, rollBonus(c, ['damage'], profile))[0].notes).toContainEqual({ label: 'Versatile', impact: 'Alternate statistics: Presence, Melee, 1d8' });
    c.features = [{ name: 'Melee crit', source: 'Custom', text: '', automation: { criticalMelee: { hope: 1, stress: 1, proficiency: 1 } } }];
    expect(criticalMeleeBonus(c, 'Scepter', true).proficiency).toBe(0);
    expect(criticalMeleeBonus(c, 'Scepter', true, 'Versatile').proficiency).toBe(1);

    const claws = hero('claws-of-yifang');
    const channeling = availableMitigations(claws).find(m => m.option.id === 'channeling')!;
    for (const damage of [9, 5]) {
      const result = computeDamage(claws, [channeling], { damage, type: 'magic', armor: false, selected: new Set([channeling.id]) });
      expect(applyDamage(claws, result, 'magic')).toContain('Claws of Yifang: Channeling: stored');
    }
    expect(claws.stress.on).toBe(2);
    expect(claws.effects).toHaveLength(1);
    expect(claws.effects[0]).toMatchObject({ name: 'Claws of Yifang: Channeling', until: 'rest' });
    const stored = availableRollOptions(claws, ['damage'], claws.activeWeapons[0]).find(o => o.option.id === 'stored')!;
    expect(stored.option).toMatchObject({ value: 6, defaultOn: true });
    expect(availableRollOptions(claws, ['damage'], weapon('broadsword')).some(o => o.option.id === 'stored')).toBe(false);
    commitRollOptions(claws, [stored]);
    expect(claws.effects).toHaveLength(0);
  });

  it('blocks Pompous and unloaded weapons and pays Greedy with ten gold coins', () => {
    const ego = hero('ego-blade');
    expect(weaponAttackBlocker(ego, ego.activeWeapons[0])).toBeNull();
    ego.traits.find(t => t.name === 'Presence')!.mod = 1;
    expect(weaponAttackBlocker(ego, ego.activeWeapons[0])).toContain('Presence +0 or lower');
    const gun = hero('blunderbuss');
    addEffect(gun, { name: 'Blunderbuss: Unloaded', kind: 'debuff', unloadedWeapon: 'Blunderbuss' });
    expect(weaponAttackBlocker(gun, gun.activeWeapons[0])).toContain('unloaded');

    const midas = hero('midas-scythe');
    const greedy = availableRollOptions(midas, ['damage'], midas.activeWeapons[0]).find(o => o.option.id === 'greedy')!;
    midas.gold = 9;
    expect(selectionBlocker(midas, [greedy])).toContain('Not enough gold');
    midas.gold = 25;
    commitRollOptions(midas, [greedy]);
    expect(midas.gold).toBe(15);
  });

  it('evaluates armor placeholders before marking the Deflecting slot', () => {
    const c = hero('buckler');
    expect(evalValue('@armor_available', c)).toBe(3);
    expect(evalValue('@armor_score', c)).toBe(0);
  });

  it('carries attack-linked profiles and dice to damage, and tags rerolls', () => {
    expect(attackDamageContext({ source: 'attack,weaponProfile:Versatile,damageDie:20,action' })).toEqual({ profile: 'Versatile', damageDie: 20 });
    expect(attackDamageContext({ source: 'attack,action' })).toBeUndefined();
    const message = { type: 'roll', id: 1, ts: 2, from: 'A', fromId: 'p', expression: '2d12+2', rolls: [3, 5], total: 10, kind: 'duality',
      source: 'attack,action', weapon: 'Bladed Star', label: 'Attack' } as RollMessage;
    const request = rerollRequest(message, { key: 'weapon:Bladed Star', source: 'Bladed Star', automation: {},
      trigger: entry('bladed-star').automation!.rollTriggers![0] })!;
    expect(request.expression).toBe('2d12+2-1d6');
    expect(request.source).toBe('attack,action,reroll');
    expect(request.contributions?.at(-1)).toEqual({ label: 'Disadvantage - Bladed Star', expression: '-1d6' });
  });
});

describe('weapon roll dialogs', () => {
  it('requires Painful while payable and sends Brutal rules only to supporting hosts', async () => {
    const body = stubDocument();
    const c = hero('bloodstaff');
    const staff = c.activeWeapons[0];
    const rolls: RollRequest[] = [];
    openRollDialog(body as unknown as HTMLElement, c, 'Bloodstaff damage', [], rollBonus(c, ['damage'], staff),
      request => { rolls.push(request); return true; }, () => {}, ['damage'], staff);
    const painful = body.querySelectorAll('label').find(label => label.text().includes('Painful'))!.querySelectorAll('input')[0];
    expect(painful.checked).toBe(true);
    expect(painful.disabled).toBe(true);
    await body.querySelectorAll('button').find(button => button.text() === 'Roll to chat')!.click();
    expect(rolls).toHaveLength(1);
    expect(c.stress.on).toBe(1);

    const blades = hero('knuckle-blades');
    const blade = blades.activeWeapons[0];
    const brutal: RollRequest[] = [];
    const open = (supported: boolean) => {
      const dialog = stubDocument();
      openRollDialog(dialog as unknown as HTMLElement, blades, 'Knuckle Blades damage', [], rollBonus(blades, ['damage'], blade),
        request => { brutal.push(request); return true; }, () => {}, ['damage'], blade, () => true, undefined, false, undefined, undefined,
        undefined, () => true, 0, undefined, undefined, undefined, undefined, false, undefined, () => supported);
      return dialog;
    };
    const old = open(false);
    expect(old.querySelectorAll('div').some(div => div.text().includes('Update the host to roll Brutal damage.'))).toBe(true);
    await open(true).querySelectorAll('button').find(button => button.text() === 'Roll to chat')!.click();
    expect(brutal).toHaveLength(1);
    expect(brutal[0].damageRules).toEqual({ weaponRules: { explode: 'once', label: 'Brutal' } });
  });

  it('tags a Devastating attack so its Damage button rolls d20s', async () => {
    const body = stubDocument();
    const c = hero('hammer-of-wrath');
    const hammer = c.activeWeapons[0];
    const rolls: RollRequest[] = [];
    openRollDialog(body as unknown as HTMLElement, c, 'Attack', [], rollBonus(c, ['attack', 'action'], hammer, false),
      request => { rolls.push(request); return true; }, () => {}, ['attack', 'action'], undefined, undefined, hammer);
    const option = body.querySelectorAll('label').find(label => label.text().includes('Devastating'))!.querySelectorAll('input')[0];
    option.checked = true;
    option.listeners.get('change')?.();
    await body.querySelectorAll('button').find(button => button.text() === 'Roll to chat')!.click();
    expect(rolls[0].source?.split(',')).toContain('damageDie:20');
    expect(c.stress.on).toBe(1);
  });
});

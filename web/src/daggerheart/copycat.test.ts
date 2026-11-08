import { describe, expect, it } from 'vitest';
import { activeCardSources, clearBorrowedCard, copycatCost, prepareBorrowedCard, uniqueCopycatCandidates } from './copycat';
import { emptyCharacter, type DomainCard } from './data';
import { actionBlocker, applyRest, availableRollOptions, availableRollTriggers, derivedStats, resourceDefinitions, runAction, syncResources } from './engine';
import { availableMitigations } from './damage';
import { rollSourceFor } from './roll-source';
import { restPassives, restSlots } from './rest';

const card = (name: string, level = 3): DomainCard => ({ name, level, domain: 'Grace', type: 'Ability', recall: 1, text: '' });
const borrow = (copied: DomainCard) => {
  const c = emptyCharacter('Borrower');
  c.loadout = [{ ...card('Copycat', 8), borrowedCard: { sourceActorId: 'other', sourceActorName: 'Other', card: prepareBorrowedCard(copied) } }];
  return c;
};

describe('Copycat', () => {
  it('namespaces Safe Haven casting and prevents a second cast while its borrowed rest buff is active', () => {
    const copied = card('Safe Haven', 8);
    copied.automation = {
      actions: [{ id: 'use', label: 'Cast', steps: [{ op: 'effect', effect: {
        name: 'Safe Haven', kind: 'buff', until: 'rest', rest: { extraMoves: 1 },
      } }] }],
      rest: { passives: [{ id: 'shelter', label: 'Cast Safe Haven', defaultOn: false, extraMoves: 1,
        castBeforeRest: 'use', excludesEffect: 'Safe Haven' }] },
    };
    const c = borrow(copied);
    const source = activeCardSources(c).find(source => source.name === 'Safe Haven')!;
    expect(source.auto!.rest!.passives![0].excludesEffect).toBe('Copycat/Safe Haven: Safe Haven');
    expect(restPassives(c, 'short').map(passive => passive.id)).toContain('card:Copycat/Safe Haven|shelter');
    runAction(c, source.key, source.auto!, source.auto!.actions![0], source.name);
    expect(restSlots(c, 'short')).toHaveLength(3);
    expect(restPassives(c, 'short').map(passive => passive.id)).not.toContain('card:Copycat/Safe Haven|shelter');
  });

  it('rounds half printed level up and offers unique other-actor cards in ascending level', () => {
    expect(Array.from({ length: 8 }, (_, i) => copycatCost(i + 1))).toEqual([1, 1, 2, 2, 3, 3, 4, 4]);
    const candidate = (actorId: string, name: string, level: number) => ({ actorId, actorName: actorId, card: card(name, level) });
    const choices = uniqueCopycatCandidates([
      candidate('other', 'Eight', 8), candidate('self', 'Mine', 1), candidate('other', 'Five', 5),
      candidate('third', 'Five', 5), candidate('other', 'One', 1), candidate('other', 'Nine', 9),
      candidate('other', 'Copycat', 8),
    ], 'self');
    expect(choices.map(choice => choice.card.name)).toEqual(['One', 'Five', 'Eight']);
    expect(choices[1].actorId).toBe('other');
  });

  it('applies borrowed passive stats without occupying a slot or counting toward Touched', () => {
    const copied = card('Passive');
    copied.automation = { modifiers: [{ stat: 'evasion', value: 2 }],
      conditionalModifiers: [{ stat: 'evasion', value: 9, loadoutDomain: { domain: 'Grace', min: 2 } }] };
    const c = borrow(copied);
    expect(c.loadout).toHaveLength(1);
    expect(derivedStats(c).evasion).toBe(2);
    expect(rollSourceFor(c, 'card:Copycat/Passive')).toMatchObject({ name: 'Passive', source: expect.stringContaining('Copycat') });
    c.vault.push(c.loadout.pop()!);
    expect(derivedStats(c).evasion).toBe(0);
  });

  it('namespaces pools, effects, cost keys and finished-roll sources while retaining actions', () => {
    const copied = card('Tokens');
    copied.automation = {
      resources: [{ id: 'tokens', label: 'Tokens', initial: 3, max: 5 }],
      actions: [{ id: 'spend', label: 'Spend token', cost: { tokens: 1 }, steps: [{ op: 'effect', effect: { name: 'Token bonus', kind: 'buff', until: 'manual', modifiers: [{ stat: 'evasion', value: 1 }] } }] }],
      rollOptions: [{ id: 'bonus', label: 'Bonus', roll: 'action', cost: { tokens: 1 }, value: 2 }],
      rollTriggers: [{ id: 'done', label: 'Done', on: 'featureAction:card:Tokens:spend', when: 'success' }],
      mitigation: [{ id: 'avoid', label: 'Avoid', negate: true, cost: { tokens: 1 } }],
    };
    const c = borrow(copied);
    syncResources(c);
    expect(resourceDefinitions(c)[0].id).toBe('Copycat/Tokens/tokens');
    expect(c.resources!['Copycat/Tokens/tokens']).toBe(3);
    const source = activeCardSources(c).find(source => source.key === 'card:Copycat/Tokens')!;
    expect(runAction(c, source.key, source.auto!, source.auto!.actions![0], source.name)).not.toBeNull();
    expect(c.resources!['Copycat/Tokens/tokens']).toBe(2);
    expect(c.effects[0].name).toBe('Copycat/Tokens: Token bonus');
    expect(availableRollOptions(c, ['action'])[0].key).toBe('card:Copycat/Tokens');
    expect(availableMitigations(c).some(mitigation => mitigation.key === 'card:Copycat/Tokens')).toBe(true);
    expect(availableRollTriggers(c, { kind: 'duality', source: 'featureAction:card:Copycat/Tokens:spend', outcome: 'hope', hope: 8, fear: 2, total: 10, difficulty: 10 })
      .some(trigger => trigger.key === 'card:Copycat/Tokens')).toBe(true);
    clearBorrowedCard(c, c.loadout[0]);
    expect(c.effects).toHaveLength(0);
    expect(c.resources).toEqual({});
    expect(activeCardSources(c)).toHaveLength(1);
  });

  it('expands grimoire spells with canonical nested keys, independent uses and rest passives', () => {
    const copied = { ...card('Book'), type: 'Grimoire' as const, spells: [{ name: 'Spell', text: '', automation: {
      uses: { max: 1, per: 'rest' as const },
      actions: [{ id: 'cast', label: 'Cast', steps: [] }],
    } }] };
    copied.automation = { rest: { passives: [{ id: 'rest', label: 'Extra rest move', defaultOn: true, extraMoves: 1 }] } };
    const c = borrow(copied);
    const spell = activeCardSources(c).find(source => source.key === 'card:Copycat/Book/Spell')!;
    expect(rollSourceFor(c, spell.key)?.name).toBe('Spell');
    expect(runAction(c, spell.key, spell.auto!, spell.auto!.actions![0], spell.name)).not.toBeNull();
    expect(actionBlocker(c, spell.key, spell.auto!, spell.auto!.actions![0])).toContain('uses');
    expect(restPassives(c, 'short').some(passive => passive.id === 'card:Copycat/Book|rest')).toBe(true);
  });

  it('rewrites nested featureAction and option tags and links borrowed Invisibility to its own pool', () => {
    const copied: DomainCard = { ...card('Book'), spells: [{ name: 'Spell', text: '', automation: {
      rollTriggers: [{ id: 'cast', label: 'Cast', on: 'featureAction:card:Book/Spell:cast' }, { id: 'option', label: 'Option', on: 'option:card:Book/Spell:bonus' }],
    } }] };
    expect(prepareBorrowedCard(copied).spells![0].automation!.rollTriggers![0].on).toBe('featureAction:card:Copycat/Book/Spell:cast');
    expect(prepareBorrowedCard(copied).spells![0].automation!.rollTriggers![1].on).toBe('option:card:Copycat/Book/Spell:bonus');
    const invisibility = card('Invisibility');
    invisibility.automation = { resources: [{ id: 'invisibility', label: 'Tokens', initial: 0 }],
      rollTriggers: [{ id: 'cast', label: 'Cast', on: 'card:Invisibility', steps: [{
        op: 'effect', effect: { name: 'Invisibility', kind: 'buff', linkedTokenResource: 'invisibility',
          rollOptions: [{ id: 'spend-token', label: 'Spend', roll: 'action' }] },
      }] }] };
    const borrowed = prepareBorrowedCard(invisibility);
    expect(borrowed.automation!.rollTriggers![0].steps![0].effect!.linkedTokenResource).toBe('Copycat/Invisibility/invisibility');
    expect(borrowed.automation!.rollTriggers![0].on).toBe('card:Copycat/Invisibility');
  });

  it.each(['short', 'long'] as const)('removes copied automation, pools, effects and uses on %s rest, even vaulted', kind => {
    const copied = card('Tokens');
    copied.automation = { resources: [{ id: 'tokens', label: 'Tokens', initial: 3, max: 5 }] };
    const c = borrow(copied);
    syncResources(c);
    c.effects.push({ id: 'copy', name: 'Copycat/Tokens: Bonus', kind: 'buff', until: 'manual' });
    c.usage['card:Copycat/Tokens'] = { used: 1, per: 'longRest' };
    c.usage['card:Copycat'] = { used: 1, per: 'longRest' };
    c.vault.push(c.loadout.pop()!);
    applyRest(c, kind);
    expect(c.vault[0].borrowedCard).toBeUndefined();
    expect(c.effects).toHaveLength(0);
    expect(c.resources).toEqual({});
    expect(c.usage).toEqual(kind === 'short' ? { 'card:Copycat': { used: 1, per: 'longRest' } } : {});
  });
});

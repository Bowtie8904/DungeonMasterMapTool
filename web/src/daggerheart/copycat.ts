import { hostFetch } from '../api';
import type { CharacterSheet, DomainCard, Spell } from './data';

export interface CopycatCandidate {
  actorId: string;
  actorName: string;
  card: DomainCard;
}

export const copycatCost = (level: number): number => Math.ceil(level / 2);

let disabledActors = new Set<string>();
export function setDisabledCopycatSources(ids: Iterable<string>): void { disabledActors = new Set(ids); }
export function actorAutomationAvailable(id?: string): boolean { return !id || !disabledActors.has(id); }
export function copiedCardAvailable(card: DomainCard): boolean {
  return !!card.borrowedCard && !disabledActors.has(card.borrowedCard.sourceActorId);
}

/** Borrowed cards contribute rules, but never occupy a slot or count toward Touched. */
export function activeCards(c: CharacterSheet): DomainCard[] {
  return c.loadout.flatMap(card => card.name === 'Copycat' && card.borrowedCard && copiedCardAvailable(card)
    ? [card, card.borrowedCard.card] : [card]);
}

export function activeCardSources(c: CharacterSheet): Array<{ key: string; name: string; auto?: DomainCard['automation'] }> {
  return c.loadout.flatMap(card => {
    const sources = [{ key: `card:${card.name}`, name: card.name, auto: card.automation },
      ...(card.spells ?? []).map(spell => ({ key: `card:${card.name}/${spell.name}`, name: spell.name, auto: spell.automation }))];
    if (card.name === 'Copycat' && card.borrowedCard && copiedCardAvailable(card)) {
      const borrowed = card.borrowedCard.card;
      sources.push({ key: `card:Copycat/${borrowed.name}`, name: borrowed.name, auto: borrowed.automation },
        ...(borrowed.spells ?? []).map(spell => ({ key: `card:Copycat/${borrowed.name}/${spell.name}`, name: spell.name, auto: spell.automation })));
    }
    return sources;
  });
}

/** Keep the borrowed card's pools, effects and roll tags separate from owned cards. */
export function prepareBorrowedCard(card: DomainCard): DomainCard {
  const borrowed = structuredClone(card);
  delete borrowed.borrowedCard;
  delete borrowed.catalog;
  const prefix = `Copycat/${card.name}`;
  const replacements = new Map<string, string>();
  replacements.set(`card:${card.name}`, `card:${prefix}`);
  for (const spell of card.spells ?? []) {
    replacements.set(`card:${spell.name}`, `card:${prefix}/${spell.name}`);
    replacements.set(`card:${card.name}/${spell.name}`, `card:${prefix}/${spell.name}`);
  }
  const discover = (value: unknown): void => {
    if (Array.isArray(value)) { value.forEach(discover); return; }
    if (!value || typeof value !== 'object') return;
    const obj = value as Record<string, unknown>;
    for (const resource of (obj.resources as Array<{ id: string }> | undefined) ?? []) replacements.set(resource.id, `${prefix}/${resource.id}`);
    if (obj.effect && typeof obj.effect === 'object') {
      const effect = obj.effect as { name?: string; group?: string };
      if (effect.name) replacements.set(effect.name, `${prefix}: ${effect.name}`);
      if (effect.group) replacements.set(effect.group, `${prefix}/${effect.group}`);
    }
    if (obj.dicePool && typeof obj.dicePool === 'object') {
      const pool = obj.dicePool as { id?: string };
      if (pool.id) replacements.set(pool.id, `${prefix}/${pool.id}`);
    }
    Object.values(obj).forEach(discover);
  };
  discover(borrowed.automation);
  borrowed.spells?.forEach(spell => discover(spell.automation));
  const rewrite = (value: unknown): unknown => {
    if (typeof value === 'string') {
      for (const [from, to] of replacements) if (from.startsWith('card:')) {
        for (const kind of ['featureAction', 'option']) if (value.startsWith(`${kind}:${from}:`))
          return value.replace(`${kind}:${from}:`, `${kind}:${to}:`);
      }
      return replacements.get(value) ?? value;
    }
    if (Array.isArray(value)) return value.map(rewrite);
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, entry]) => [replacements.get(key) ?? key, rewrite(entry)]));
    return value;
  };
  borrowed.automation = rewrite(borrowed.automation) as DomainCard['automation'];
  for (const spell of borrowed.spells ?? []) spell.automation = rewrite(spell.automation) as Spell['automation'];
  return borrowed;
}

export function clearBorrowedCard(c: CharacterSheet, card: DomainCard): void {
  const borrowed = card.borrowedCard;
  if (!borrowed) return;
  const prefix = `Copycat/${borrowed.card.name}`;
  const key = `card:${prefix}`;
  c.effects = c.effects.filter(effect => !effect.name.startsWith(`${prefix}: `)
    && !effect.source?.startsWith(prefix) && !effect.sourceKey?.startsWith(key));
  for (const resource of Object.keys(c.resources ?? {})) if (resource.startsWith(`${prefix}/`)) delete c.resources![resource];
  for (const usage of Object.keys(c.usage)) if (usage === key || usage.startsWith(`${key}/`) || usage.startsWith(`${key}:`)) delete c.usage[usage];
  delete card.borrowedCard;
}

export function uniqueCopycatCandidates(candidates: CopycatCandidate[], actorId: string): CopycatCandidate[] {
  const cards = new Map<string, CopycatCandidate>();
  for (const candidate of candidates) {
    if (candidate.actorId === actorId || candidate.card.name === 'Copycat'
        || !Number.isInteger(candidate.card.level) || candidate.card.level < 1 || candidate.card.level > 8) continue;
    if (!cards.has(candidate.card.name)) cards.set(candidate.card.name, candidate);
  }
  return [...cards.values()].sort((a, b) => a.card.level - b.card.level || a.card.name.localeCompare(b.card.name));
}

export async function fetchCopycatCandidates(actorId: string): Promise<CopycatCandidate[]> {
  const response = await hostFetch(`/api/copycat/${encodeURIComponent(actorId)}`, { credentials: 'same-origin' });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error ?? 'Could not load Copycat cards.');
  return uniqueCopycatCandidates(body.cards ?? [], actorId);
}

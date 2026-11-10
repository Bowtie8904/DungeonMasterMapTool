import type { CharacterSheet, Feature } from './data';
import { activeWeapons } from './engine';
import { characterFeatureGroups } from './feature-groups';

export type FavoriteCategory = 'weapon' | 'domain' | 'feature' | 'item';

export interface FavoriteIdentity {
  name: string;
  source?: string;
  tier?: number;
  slot?: string;
  type?: string;
  custom?: boolean;
  note?: string;
}

export function favoriteKey(category: FavoriteCategory, item: FavoriteIdentity): string {
  const variant = category === 'feature' ? [item.source ?? '']
    : category === 'weapon' ? [item.tier ?? 0, item.slot ?? '']
      : category === 'item' ? [item.type ?? '', item.custom ? item.note ?? '' : ''] : [];
  return JSON.stringify([category, item.name, ...variant]);
}

export function isFavorite(c: CharacterSheet, category: FavoriteCategory, item: FavoriteIdentity): boolean {
  return c.quickAccessFavorites?.includes(favoriteKey(category, item)) === true;
}

export function quickAccessEntries(c: CharacterSheet) {
  const features: Array<{ feature: Feature; children: Feature[] }> = [];
  for (const group of characterFeatureGroups(c.features, c.heritage)) {
    if (isFavorite(c, 'feature', group.feature)) features.push(group);
    else for (const child of group.children) {
      if (isFavorite(c, 'feature', child)) features.push({ feature: child, children: [] });
    }
  }
  const slotRank = (slot: string) => slot === 'Primary' ? 0 : 1;
  return {
    weapons: activeWeapons(c).filter(w => isFavorite(c, 'weapon', w)).sort((a, b) => slotRank(a.slot) - slotRank(b.slot)),
    cards: c.loadout.filter(d => !d.permanentlyVaulted && isFavorite(c, 'domain', d)).sort((a, b) => a.level - b.level),
    features,
    items: c.inventory.filter(i => isFavorite(c, 'item', i))
      .sort((a, b) => a.name.localeCompare(b.name, 'en', { sensitivity: 'base' })),
  };
}

/** Viewport coordinates; prefer above unless below offers more useful space. */
export function quickAccessPlacement(anchor: { top: number; bottom: number; left: number }, width: number, height: number) {
  const gap = 8;
  const panelWidth = Math.max(0, Math.min(400, width - gap * 2));
  if (width <= 680) return { left: gap, width: Math.max(0, width - gap * 2), top: undefined, bottom: gap, maxHeight: Math.max(0, height - gap * 2) };
  const above = Math.max(0, anchor.top - gap * 2);
  const below = Math.max(0, height - anchor.bottom - gap * 2);
  const upward = above >= Math.min(460, below);
  return {
    left: Math.max(gap, Math.min(width - panelWidth - gap, anchor.left)),
    width: panelWidth,
    top: upward ? undefined : Math.max(gap, anchor.bottom + gap),
    bottom: upward ? Math.max(gap, height - anchor.top + gap) : undefined,
    maxHeight: Math.min(600, upward ? above : below),
  };
}

/** Places the tray right of a side-rail trigger, bottom-aligned with it and growing upward. */
export function quickAccessSidePlacement(anchor: { right: number; bottom: number }, width: number, height: number) {
  const gap = 8;
  const panelWidth = Math.max(0, Math.min(400, width - gap * 2));
  if (width <= 680) return { left: gap, width: Math.max(0, width - gap * 2), top: undefined, bottom: gap, maxHeight: Math.max(0, height - gap * 2) };
  const bottomEdge = Math.min(height - gap, Math.max(anchor.bottom, Math.min(height - gap, 460 + gap)));
  return {
    left: Math.max(gap, Math.min(width - panelWidth - gap, anchor.right + gap)),
    width: panelWidth,
    top: undefined,
    bottom: Math.max(gap, height - bottomEdge),
    maxHeight: Math.min(600, Math.max(0, bottomEdge - gap)),
  };
}

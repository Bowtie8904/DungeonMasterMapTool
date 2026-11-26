import { itemName, type ItemArtworkSubject } from '../item-artwork';
import { el } from '../login';

export interface InventoryGrade {
  level: 1 | 2 | 3 | 4;
  label: string;
}

export function tierGrade(tier: number): InventoryGrade | undefined {
  if (tier !== 1 && tier !== 2 && tier !== 3 && tier !== 4) return undefined;
  return { level: tier, label: `Tier ${tier}` };
}

export function rarityGrade(rarity: unknown): InventoryGrade {
  switch (typeof rarity === 'string' ? rarity.trim().toLowerCase() : '') {
    case 'common': return { level: 1, label: 'Common rarity' };
    case 'uncommon': return { level: 2, label: 'Uncommon rarity' };
    case 'rare': return { level: 3, label: 'Rare rarity' };
    case 'legendary': return { level: 4, label: 'Legendary rarity' };
    default: return { level: 1, label: 'Unclassified rarity' };
  }
}

export function gradedItemName(item: ItemArtworkSubject, grade?: InventoryGrade): HTMLElement {
  const name = itemName(item);
  if (grade) {
    name.classList.add('inventory-grade', `inventory-grade-${grade.level}`);
    name.title = grade.label;
    name.append(el('span', { class: 'inventory-grade-label' }, ` (${grade.label})`));
  }
  return name;
}

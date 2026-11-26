import { el } from './login';
import type { InventoryItem } from './daggerheart/data';

export interface ItemArtworkSubject {
  name: string;
  image?: string;
  witchTalisman?: { sourceActorId: string; activationId: string };
}

const ENCHANTED_ITEM_ICONS = new Map([
  ['Rune Ward', '/item-icons/lorc/rune-stone.svg'],
  ['Lifeblood Talisman', '/item-icons/lorc/gem-pendant.svg'],
  ['Enchanted Talisman', '/item-icons/lorc/gem-pendant.svg'],
]);

export const INVENTORY_ITEM_ICONS: Record<InventoryItem['type'], string> = {
  loot: '/item-icons/delapouite/chest.svg',
  gear: '/item-icons/delapouite/backpack.svg',
  consumable: '/item-icons/lorc/standing-potion.svg',
  'class item': '/item-icons/lorc/gem-pendant.svg',
};

export function itemImagePath(image: unknown): string | null {
  return typeof image === 'string' && /^\/item-icons\/[a-z0-9-]+\/[a-z0-9-]+\.svg$/.test(image) ? image : null;
}

/** Artwork is decorative: the adjacent name remains the accessible label. */
export function itemName(item: ItemArtworkSubject, fallbackImage?: string): HTMLElement {
  const name = el('span', { class: 'item-name' });
  const imagePath = item.image || (item.witchTalisman ? ENCHANTED_ITEM_ICONS.get(item.name) : undefined) || fallbackImage;
  const path = itemImagePath(imagePath);
  const unavailable = () => el('span', { class: 'item-artwork-error', title: `Item image unavailable: ${imagePath}`, role: 'img', ariaLabel: 'Item image unavailable' }, '!');
  if (path) {
    const image = el('img', { class: 'item-artwork', src: path, alt: '', width: 28, height: 28, draggable: false });
    image.addEventListener('error', () => {
      image.replaceWith(unavailable());
    }, { once: true });
    name.append(image);
  } else if (imagePath) name.append(unavailable());
  name.append(el('span', { class: 'item-name-text' }, item.name));
  return name;
}

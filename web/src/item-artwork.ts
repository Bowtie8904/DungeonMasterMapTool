import { el } from './login';

export function itemImagePath(image: unknown): string | null {
  return typeof image === 'string' && /^\/item-icons\/[a-z0-9-]+\/[a-z0-9-]+\.svg$/.test(image) ? image : null;
}

/** Artwork is decorative: the adjacent name remains the accessible label. */
export function itemName(item: { name: string; image?: string }): HTMLElement {
  const name = el('span', { class: 'item-name' });
  const path = itemImagePath(item.image);
  const unavailable = () => el('span', { class: 'item-artwork-error', title: `Item image unavailable: ${item.image}`, role: 'img', ariaLabel: 'Item image unavailable' }, '!');
  if (path) {
    const image = el('img', { class: 'item-artwork', src: path, alt: '', width: 28, height: 28, draggable: false });
    image.addEventListener('error', () => {
      image.replaceWith(unavailable());
    }, { once: true });
    name.append(image);
  } else if (item.image) name.append(unavailable());
  name.append(el('span', { class: 'item-name-text' }, item.name));
  return name;
}

import { cardRequest } from './api';
import type { Armor, DomainCard, Feature, InventoryItem, Weapon } from './daggerheart/data';
import { rich } from './daggerheart/rich';
import { iconButton } from './icons';
import { el } from './login';
import { drawingId } from './tools';

export type SharedEntry =
  | { category: 'feature'; item: Feature }
  | { category: 'domain'; item: DomainCard }
  | { category: 'weapon'; item: Weapon }
  | { category: 'armor'; item: Armor }
  | { category: 'item'; item: InventoryItem };
export type SharedCard = SharedEntry & { cardId: string; actorId: string; actorName: string; available: number };
export const INVENTORY_DRAG = 'application/x-dmmt-inventory';

export function transferable(entry: SharedEntry): boolean {
  return entry.category === 'weapon' || entry.category === 'armor' || entry.category === 'item';
}

export async function postCard(actorId: string, entry: SharedEntry, toId?: string): Promise<void> {
  await cardRequest('', { actorId, ...entry, toId });
}

// Drag data is fixed synchronously; the receiver waits for the source window's save before posting.
const preparations = new Map<string, Promise<string | null>>();
const waiters = new Map<string, (error: string | null) => void>();
let channel: BroadcastChannel | undefined;
function dragChannel(): BroadcastChannel {
  if (channel) return channel;
  channel = new BroadcastChannel('dmmt-inventory-drag');
  channel.onmessage = (e: MessageEvent<{ type: string; token: string; error?: string | null }>) => {
    const { type, token, error } = e.data;
    if (type === 'wait') {
      const pending = preparations.get(token);
      if (pending) void pending.then(error => channel!.postMessage({ type: 'ready', token, error }));
    } else if (type === 'ready') waiters.get(token)?.(error ?? null);
  };
  return channel;
}

export function prepareInventoryDrag(save: () => Promise<void>): string {
  dragChannel();
  const token = drawingId();
  preparations.set(token, save().then(() => null, (e: Error) => e.message));
  window.setTimeout(() => preparations.delete(token), 60_000);
  return token;
}

export async function waitForInventoryDrag(token: string): Promise<void> {
  let error: string | null;
  const pending = preparations.get(token);
  if (pending) error = await pending;
  else error = await new Promise<string | null>((resolve) => {
    const timer = window.setTimeout(() => {
      waiters.delete(token);
      resolve('The source sheet did not finish saving. Try dragging again.');
    }, 10_000);
    waiters.set(token, error => { window.clearTimeout(timer); waiters.delete(token); resolve(error); });
    dragChannel().postMessage({ type: 'wait', token });
  });
  if (error) throw new Error(error);
}

export function renderSharedCard(card: SharedCard, onGrab?: (card: SharedCard) => Promise<void>,
                                 onError?: (message: string) => void): HTMLElement {
  const body = el('div', { class: 'shared-description' });
  const badges: string[] = [];
  switch (card.category) {
    case 'feature':
      badges.push(card.item.source);
      body.append(rich(card.item.text));
      break;
    case 'domain':
      badges.push(card.item.domain, `Level ${card.item.level}`, card.item.type, `Recall ${card.item.recall}`);
      body.append(rich(card.item.text));
      break;
    case 'weapon':
      badges.push(card.item.slot, card.item.damageType, card.item.trait, card.item.range,
        card.item.damage, `${card.item.hands}H`, `Tier ${card.item.tier}`);
      if (card.item.feature) body.append(el('strong', {}, card.item.feature.name), rich(card.item.feature.text));
      break;
    case 'armor':
      badges.push(`Armor score ${card.item.score}`, `Tier ${card.item.tier}`);
      if (card.item.major !== undefined && card.item.severe !== undefined) badges.push(`${card.item.major} / ${card.item.severe}`);
      if (card.item.feature) body.append(el('strong', {}, card.item.feature.name), rich(card.item.feature.text));
      break;
    case 'item':
      badges.push(card.item.type);
      if (card.item.note) body.append(rich(card.item.note));
      break;
  }
  const root = el('section', { class: `shared-card shared-${card.category}` },
    el('div', { class: 'shared-source' }, card.actorName),
    el('h3', {}, card.item.name),
    el('div', { class: 'shared-badges' }, ...badges.map(text => el('span', {}, text))), body);
  if (transferable(card)) {
    const count = el('span', { class: 'muted' }, `${card.available} remaining`);
    let busy = false;
    let known = true;
    const paint = () => { grab.disabled = busy || !known || !onGrab || card.available <= 0; };
    const grab = iconButton('grab', 'Grab one into your inventory', () => {
      busy = true;
      paint();
      void onGrab!(card).catch(e => onError?.((e as Error).message)).finally(async () => {
        await refresh();
        busy = false;
        paint();
      });
    });
    grab.classList.add('share-icon');
    grab.disabled = !onGrab || card.available <= 0;
    root.append(el('div', { class: 'shared-stock' }, count, grab));
    let request = 0;
    const refresh = async () => {
      const current = ++request;
      known = false;
      paint();
      try {
        const stock = await cardRequest<{ available: number }>(`/${encodeURIComponent(card.cardId)}`);
        if (request !== current) return;
        card.available = stock.available;
        known = true;
        count.textContent = `${card.available} remaining`;
        paint();
      } catch (e) {
        if (request !== current) return;
        paint();
        count.textContent = 'Availability unavailable';
        count.title = (e as Error).message;
      }
    };
    root.addEventListener('refresh-stock', () => { void refresh(); });
  }
  return root;
}

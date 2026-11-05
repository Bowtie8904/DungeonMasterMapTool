import { cardRequest } from './api';
import type { Armor, DomainCard, Feature, InventoryItem, Weapon } from './daggerheart/data';
import { rich } from './daggerheart/rich';
import { el } from './login';
import { drawingId } from './tools';

export type SharedEntry =
  | { category: 'feature'; item: Feature }
  | { category: 'domain'; item: DomainCard }
  | { category: 'weapon'; item: Weapon }
  | { category: 'armor'; item: Armor }
  | { category: 'item'; item: InventoryItem }
  | { category: 'gold'; item: { name: string } };
export type SharedCard = SharedEntry & { cardId: string; actorId: string; actorName: string; available: number; offerQuantity?: number; fromDm?: boolean; claimed?: number; badges?: string[]; sections?: Array<{ name?: string; text: string }> };
export const INVENTORY_DRAG = 'application/x-dmmt-inventory';

export function transferable(entry: SharedEntry): boolean {
  return (entry.category === 'weapon' && !entry.item.generated) || entry.category === 'armor' || entry.category === 'item' || entry.category === 'gold';
}

export async function postCard(actorId: string, entry: SharedEntry, toId?: string, offerQuantity?: number): Promise<void> {
  await cardRequest('', { actorId, ...entry, toId, offerQuantity });
}

export async function postCompendiumCard(category: string, entryId: string, toId?: string): Promise<void> {
  await cardRequest('', { compendium: { category, entryId }, toId });
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

export type GrabQuantity = number | 'all';

/** Asks how many units to grab; resolves null when cancelled. */
export function pickGrabQuantity(card: SharedCard): Promise<number | null> {
  if (card.available <= 1) return Promise.resolve(1);
  return new Promise(resolve => {
    const dialog = el('dialog', { class: 'inventory-picker grab-picker' });
    const input = el('input', { type: 'number', min: '1', max: String(card.available), step: '1', value: '1', required: true, ariaLabel: 'Quantity to grab' });
    const cancel = el('button', { type: 'button' }, 'Cancel');
    const submit = el('button', { type: 'submit', class: 'primary' }, 'Grab');
    const form = el('form', {}, el('h3', {}, `Grab ${card.item.name}`),
      el('label', {}, `Quantity (up to ${card.available})`, input), el('div', { class: 'row' }, cancel, submit));
    let result: number | null = null;
    cancel.addEventListener('click', () => dialog.close());
    form.addEventListener('submit', e => {
      e.preventDefault();
      if (!form.reportValidity()) return;
      result = input.valueAsNumber;
      dialog.close();
    });
    dialog.addEventListener('close', () => { dialog.remove(); resolve(result); }, { once: true });
    dialog.append(form);
    document.body.append(dialog);
    dialog.showModal();
    input.focus();
    input.select();
  });
}

export function renderSharedCard(card: SharedCard, onGrab?: (card: SharedCard, quantity: GrabQuantity) => Promise<void>,
                                 onError?: (message: string) => void): HTMLElement {
  const body = el('div', { class: 'shared-description' });
  const featureBlock = (name: string, text: string) => el('div', { class: 'shared-feature-block' },
    el('div', { class: 'shared-feature-name' }, name), rich(text));
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
      if (card.item.feature) body.append(featureBlock(card.item.feature.name, card.item.feature.text));
      break;
    case 'armor':
      badges.push(`Armor score ${card.item.score}`, `Tier ${card.item.tier}`);
      if (card.item.major !== undefined && card.item.severe !== undefined) badges.push(`${card.item.major} / ${card.item.severe}`);
      if (card.item.feature) body.append(featureBlock(card.item.feature.name, card.item.feature.text));
      break;
    case 'item':
      badges.push(card.item.type);
      if (card.item.note) body.append(rich(card.item.note));
      break;
    case 'gold':
      badges.push('coins');
      break;
  }
  if (card.sections) {
    body.replaceChildren(...card.sections.map(section =>
      section.name ? featureBlock(section.name, section.text) : rich(section.text)));
  }
  const displayBadges = card.badges ?? badges;
  const offer = transferable(card) && (card.offerQuantity ?? 0) > 0;
  const root = el('section', { class: `shared-card shared-${card.category}${offer ? ' transfer-offer' : ''}` },
    el('div', { class: 'shared-source' }, offer ? `Transfer offer from ${card.actorName}` : card.actorName),
    el('h3', {}, card.item.name),
    el('div', { class: 'shared-badges' }, ...displayBadges.map(text => el('span', {}, text))), body);
  if (offer) {
    const amount = el('b', { class: 'shared-qty-n' }, String(card.available));
    const label = el('span', { class: 'shared-qty-label' }, ' remaining');
    const count = el('div', { class: 'shared-qty', title: `${card.offerQuantity} offered in total` }, amount,
      el('span', { class: 'shared-qty-text' }, label, el('span', { class: 'shared-qty-offered' }, ` of ${card.offerQuantity} offered`)));
    count.classList.toggle('empty', card.available <= 0);
    let busy = false;
    let known = true;
    const buttons: HTMLButtonElement[] = [];
    const paint = () => { buttons.forEach(b => { b.disabled = busy || !known || !onGrab || card.available <= 0; }); };
    for (const all of [false, true]) {
      const grab = el('button', { type: 'button', class: 'inventory-action', title: all ? 'Transfer all remaining units into your inventory' : 'Choose how many units to transfer into your inventory' }, all ? 'Grab all' : 'Grab');
      grab.addEventListener('click', () => {
        if (!onGrab || busy) return;
        busy = true;
        paint();
        const run = async () => {
          const quantity = all ? 'all' : await pickGrabQuantity(card);
          if (quantity !== null) await onGrab(card, quantity);
        };
        void run().catch(e => onError?.((e as Error).message)).finally(async () => {
          await refresh();
          busy = false;
          paint();
        });
      });
      buttons.push(grab);
    }
    paint();
    root.append(count, el('div', { class: 'shared-stock' }, ...buttons));
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
        amount.textContent = String(card.available);
        label.textContent = ' remaining';
        count.classList.toggle('empty', card.available <= 0);
        paint();
      } catch (e) {
        if (request !== current) return;
        paint();
        amount.textContent = '?';
        label.textContent = ' availability unavailable';
        count.title = (e as Error).message;
      }
    };
    root.addEventListener('refresh-stock', () => { void refresh(); });
  }
  return root;
}

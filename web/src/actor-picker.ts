import { el } from './login';

export interface ActorChoice { id: string; name: string }

/** Asks which of the player's actors should receive something. A single candidate is chosen without asking. */
export async function pickActor(actors: ActorChoice[], title: string, confirm: string, alwaysAsk = false): Promise<ActorChoice | null> {
  if (actors.length === 1 && !alwaysAsk) return actors[0];
  if (!actors.length) return null;
  return new Promise<ActorChoice | null>((resolve) => {
    const dialog = el('dialog', { class: 'inventory-picker' });
    const select = el('select', {}, ...actors.map((a) => el('option', { value: a.id }, a.name)));
    select.setAttribute('aria-label', 'Receiving character');
    const cancel = el('button', { type: 'button' }, 'Cancel');
    const ok = el('button', { type: 'button', class: 'primary' }, confirm);
    let selected: ActorChoice | null = null;
    cancel.addEventListener('click', () => dialog.close());
    ok.addEventListener('click', () => { selected = actors.find((a) => a.id === select.value) ?? null; dialog.close(); });
    dialog.addEventListener('close', () => { dialog.remove(); resolve(selected); }, { once: true });
    dialog.append(el('h3', {}, title), select, el('div', { class: 'row' }, cancel, ok));
    document.body.append(dialog);
    dialog.showModal();
  });
}

import { el } from '../login';
import type { CharacterSheet } from './data';
import { copycatCost, fetchCopycatCandidates, type CopycatCandidate } from './copycat';
import { rich } from './rich';
import { useSheetAction, type SheetSave } from './persist';

export async function openCopycat(c: CharacterSheet, actorId: string, key: string, actionId: string,
  save: { flush: () => Promise<void>; refresh: () => Promise<void>; action?: SheetSave['action'] }): Promise<void> {
  await save.flush();
  const candidates = await fetchCopycatCandidates(actorId);
  await chooseCopycatCard(c, candidates, async candidate => {
    await save.flush();
    await useSheetAction(save, { actorId, key, actionId, copyActorId: candidate.actorId, copyCardName: candidate.card.name });
  });
}

export function chooseCopycatCard(c: CharacterSheet, candidates: CopycatCandidate[], submit: (candidate: CopycatCandidate) => Promise<void>): Promise<void> {
  return new Promise(resolve => {
    const dialog = el('dialog', { class: 'dh dh-rolldlg dh-copycat-dialog', ariaLabel: 'Copycat — choose a domain card' });
    const error = el('div', { class: 'error', role: 'alert' });
    const list = el('div', { class: 'list dh-copycat-list' });
    const search = el('input', { type: 'search', placeholder: 'Search cards', ariaLabel: 'Search Copycat cards' });
    const rows: Array<{ node: HTMLElement; text: string }> = [];
    const none = el('p', { class: 'muted', hidden: true, role: 'status' }, 'No cards match your search.');
    search.addEventListener('input', () => {
      const terms = search.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
      rows.forEach(row => { row.node.hidden = !terms.every(term => row.text.includes(term)); });
      none.hidden = !candidates.length || rows.some(row => !row.node.hidden);
    });
    const cancel = el('button', { type: 'button' }, 'Cancel');
    let busy = false;
    const buttons: HTMLButtonElement[] = [];
    const paint = () => buttons.forEach((button, index) => {
      const cost = copycatCost(candidates[index].card.level);
      button.disabled = busy || cost > c.hope.on;
      button.title = cost > c.hope.on ? `Requires ${cost} Hope; you have ${c.hope.on}.` : '';
    });
    for (const candidate of candidates) {
      const card = candidate.card;
      const button = el('button', { type: 'button', class: 'small primary' }, `Copy (${copycatCost(card.level)} Hope)`);
      buttons.push(button);
      button.addEventListener('click', async () => {
        if (busy || copycatCost(card.level) > c.hope.on) { paint(); return; }
        busy = true; cancel.disabled = true; paint(); error.textContent = '';
        try { await submit(candidate); dialog.close(); }
        catch (e) { error.textContent = (e as Error).message; }
        finally { busy = false; cancel.disabled = false; paint(); }
      });
      const node = el('div', { class: 'dcard' }, el('div', { class: 'hd' }, `Level ${card.level} · ${card.domain} · ${card.type}`),
        el('span', { class: 'nm' }, card.name), el('p', { class: 'muted' }, `From ${candidate.actorName}`), rich(card.text), button);
      rows.push({ node, text: `${card.name} ${card.domain} ${card.type} ${card.level} ${card.text} ${candidate.actorName} ${(card.spells ?? []).map(spell => `${spell.name} ${spell.text}`).join(' ')}`.toLowerCase() });
      list.append(node);
    }
    list.append(none);
    if (!candidates.length) list.append(el('p', { class: 'muted' }, 'No eligible domain cards in other actors’ active loadouts.'));
    cancel.addEventListener('click', () => dialog.close());
    dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
    dialog.addEventListener('close', () => { dialog.remove(); resolve(); }, { once: true });
    dialog.append(el('h4', {}, 'Copycat'), el('p', { class: 'muted' }, 'Copy a level 8 or lower card. Spend half its printed level in Hope, rounded up, and your once-per-long-rest use. Ends on any rest or when its source vaults the card.'),
      search, list, error, el('div', { class: 'acts' }, cancel));
    paint();
    document.body.append(dialog);
    dialog.showModal();
  });
}

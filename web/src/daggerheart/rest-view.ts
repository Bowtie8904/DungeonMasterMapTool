import { el } from '../login';
import type { CharacterSheet } from './data';
import type { RestKind } from './engine';
import { chooseAncestryFeature } from './ancestry-choice';
import {
  RestOptions, RestPick, RestResult, canTakeLongMovesInShortRest, moveBlocker, passiveOn, resolveRest, restPassives, restSlots, restSummary,
  shortRestBlocker,
} from './rest';

/** Downtime dialog: pick moves, then rest. Cancelling leaves the sheet untouched. */
export function openRestDialog(host: HTMLElement, c: CharacterSheet, kind: RestKind, onDone: (summary: string, result: RestResult) => void): void {
  const o: RestOptions = {};
  const picks: RestPick[] = [];
  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  const body = el('div', { class: 'dh-rest-body' });
  const error = el('div', { class: 'error', role: 'alert' });
  const go = el('button', { class: 'primary', type: 'button' }, kind === 'long' ? 'Take long rest' : 'Take short rest');
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const close = () => { backdrop.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };

  const check = (label: string, get: () => boolean | undefined, set: (v: boolean) => void, disabled = false): HTMLElement => {
    const input = el('input', { type: 'checkbox' });
    input.checked = !!get();
    input.disabled = disabled;
    input.addEventListener('change', () => { set(input.checked); draw(); });
    return el('label', { class: 'dh-roll-option' }, input, el('span', {}, label));
  };

  function draw(): void {
    const slots = restSlots(c, kind, o);
    picks.length = slots.length;
    const blocker = shortRestBlocker(c, kind);
    const rows = slots.map((slot, i) => {
      const pick = picks[i] ??= { move: '' };
      if (pick.move && !slot.moves.some((m) => m.id === pick.move)) pick.move = '';
      const options = slot.moves.map((m) => {
        const why = moveBlocker(kind, picks, i, m.id);
        const opt = el('option', { value: m.id }, why ? `${m.label} (${why})` : m.label);
        opt.disabled = !!why && m.id !== pick.move;
        return opt;
      });
      const select = el('select', {}, el('option', { value: '' }, 'None'), ...options);
      select.value = pick.move;
      select.addEventListener('change', () => {
        pick.move = select.value; pick.ally = false; delete pick.ancestry;
        const move = slot.moves.find((m) => m.id === pick.move);
        if (move?.ancestryChoice) {
          const ancestry = picks.slice(0, i).reduce((name, p) => p.ancestry?.ancestry ?? name, c.ancestry);
          const feature = picks.slice(0, i).reduce((name, p) => p.ancestry?.feature ?? name, c.ancestryFeature);
          go.disabled = true;
          void chooseAncestryFeature(host, ancestry, move.ancestryChoice === 'ancestry', feature).then((picked) => {
            if (picked) { pick.ancestry = picked.choice; o.ancestries = picked.entries; }
            else pick.move = '';
            draw();
          }).catch((e: Error) => { pick.move = ''; draw(); error.textContent = e.message; });
        } else draw();
      });
      const move = slot.moves.find((m) => m.id === pick.move);
      const ally = move?.ally ? (() => {
        const t = el('select', {}, el('option', { value: 'self' }, 'Self'), el('option', { value: 'ally' }, 'An ally (chat only)'));
        t.value = pick.ally ? 'ally' : 'self';
        t.addEventListener('change', () => { pick.ally = t.value === 'ally'; draw(); });
        return t;
      })() : null;
      return el('div', { class: 'dh-rest-slot' }, el('span', { class: 'muted' }, slot.label), select, ...(ally ? [ally] : []),
        ...(pick.ancestry ? [el('span', { class: 'muted' }, `${pick.ancestry.ancestry} - ${pick.ancestry.feature}`)] : []));
    });
    const longInShort = kind === 'short' && canTakeLongMovesInShortRest(c);
    const passives = restPassives(c, kind);
    const invalid = picks.some((p, i) => p.move && moveBlocker(kind, picks, i, p.move));
    body.replaceChildren(
      el('div', { class: 'muted' }, kind === 'long'
        ? 'Each character gets 2 downtime moves; the same move cannot be chosen twice. Long rest: all rest abilities refresh.'
        : 'Each character gets 2 downtime moves; the same move cannot be chosen twice. Short rest: short-rest abilities refresh.'),
      ...(longInShort ? [el('div', { class: 'muted' }, 'You may choose one long-rest move during this short rest.')] : []),
      ...(blocker ? [el('div', { class: 'error' }, blocker)] : []),
      ...rows,
      ...(passives.length ? [el('div', { class: 'dh-rest-passives' }, ...passives.map((p) =>
        check(p.label, () => passiveOn(c, kind, o, p.id), (v) => { (o.passives ??= {})[p.id] = v; })))] : []),
    );
    go.disabled = !!blocker || invalid;    error.textContent = '';
  }

  go.addEventListener('click', () => {
    try {
      const chosen = picks.map((p) => ({ ...p }));
      const result = resolveRest(c, kind, chosen, o);
      close();
      onDone(restSummary(c, kind, chosen, result), result);
    } catch (e) {
      error.textContent = e instanceof Error ? e.message : 'Resting failed.';
    }
  });
  cancel.addEventListener('click', close);
  backdrop.addEventListener('click', (ev) => { if (ev.target === backdrop) close(); });
  backdrop.append(el('div', { class: 'dh-rolldlg dh-restdlg' }, el('h4', {}, kind === 'long' ? 'Long rest - downtime moves' : 'Short rest - downtime moves'), body, error, el('div', { class: 'acts' }, cancel, go)));
  document.addEventListener('keydown', onKey);
  host.ownerDocument.body.append(backdrop);
  draw();
  go.focus();
}

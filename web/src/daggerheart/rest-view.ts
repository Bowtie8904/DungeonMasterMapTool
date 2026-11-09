import { el } from '../login';
import { CharacterSheet, TRAIT_ORDER } from './data';
import type { RestKind } from './engine';
import { activeCardSources } from './copycat';
import type { Actor } from '../protocol';
import type { FeatureRequest } from '../api';
import { chooseAncestryFeature } from './ancestry-choice';
import {
  RestOptions, RestPick, RestResult, canTakeLongMovesInShortRest, moveBlocker, passiveOn, resolveRest, restPassives, restSlots, restSummary,
  shortRestBlocker, mutagenChoices,
} from './rest';

/** Downtime choices do not change the sheet until confirmation, including any host-backed casting. */
export function openRestDialog(host: HTMLElement, c: CharacterSheet, kind: RestKind, onDone: (summary: string, result: RestResult) => void,
  castBeforeRest?: (key: string, actionId: string) => Promise<void>, actors?: () => Actor[],
  commit?: (rest: NonNullable<FeatureRequest['rest']>) => Promise<void>): void {
  const o: RestOptions = {};
  const picks: RestPick[] = [];
  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  const body = el('div', { class: 'dh-rest-body dh-roll-options' });
  const error = el('div', { class: 'error', role: 'alert' });
  const go = el('button', { class: 'primary', type: 'button' }, kind === 'long' ? 'Take long rest' : 'Take short rest');
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const close = () => { backdrop.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !pending) close(); };

  const check = (label: string, get: () => boolean | undefined, set: (v: boolean) => void, disabled = false, description?: string): HTMLElement => {
    const input = el('input', { type: 'checkbox' });
    input.checked = !!get();
    input.disabled = disabled;
    input.addEventListener('change', () => { set(input.checked); draw(); });
    return el('label', { class: 'dh-roll-option' }, input, el('span', {}, label,
      ...(description ? [el('small', { class: 'muted' }, description)] : [])));
  };

  function draw(): void {
    const preview = structuredClone(c);
    const slots = restSlots(preview, kind, o);
    picks.length = slots.length;
    const blocker = shortRestBlocker(preview, kind);
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
        pick.move = select.value; pick.ally = false; delete pick.ancestry; delete pick.mutagens;
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
      const mutagens: HTMLElement[] = [];
      if (move?.mutagens) {
        const choices = mutagenChoices(preview, move.mutagens);
        const selection = pick.mutagens ??= { increase: 'Agility', decrease: 'Strength', benefits: [] };
        for (const [field, label] of [['increase', `Increase (+${choices.traitBonus})`], ['decrease', `Decrease (-${choices.traitBonus})`]] as const) {
          const traits = el('select', { ariaLabel: label }, ...TRAIT_ORDER.map(name => el('option', { value: name }, name)));
          traits.value = selection[field];
          traits.addEventListener('change', () => {
            selection[field] = TRAIT_ORDER.find(name => name === traits.value)!;
            draw();
          });
          mutagens.push(el('label', {}, label, traits));
        }
        mutagens.push(el('div', { class: 'muted' }, `Choose up to ${choices.maxBenefits} benefit${choices.maxBenefits > 1 ? 's (two costs 1 locked HP until your next rest)' : ''}.`));
        for (const benefit of choices.benefits) {
          mutagens.push(check(benefit.effect.name, () => selection.benefits.includes(benefit.id), selected => {
            selection.benefits = selected ? [...selection.benefits, benefit.id] : selection.benefits.filter(id => id !== benefit.id);
          }, !selection.benefits.includes(benefit.id) && selection.benefits.length >= choices.maxBenefits, benefit.effect.text));
        }
      }
      const ally = move?.ally ? (() => {
        const t = el('select', {}, el('option', { value: 'self' }, 'Self'), el('option', { value: 'ally' }, 'An ally (chat only)'));
        t.value = pick.ally ? 'ally' : 'self';
        t.addEventListener('change', () => { pick.ally = t.value === 'ally'; draw(); });
        return t;
      })() : null;
      return el('div', { class: 'dh-rest-slot' }, el('span', { class: 'muted' }, slot.label), select, ...(ally ? [ally] : []),
        ...(pick.ancestry ? [el('span', { class: 'muted' }, `${pick.ancestry.ancestry} - ${pick.ancestry.feature}`)] : []),
        ...(mutagens.length ? [el('div', { class: 'dh-mutagen-choices' }, ...mutagens)] : []));
    });
    const longInShort = kind === 'short' && canTakeLongMovesInShortRest(preview);
    const passives = restPassives(preview, kind);
    const invalid = picks.some((p, i) => p.move && (moveBlocker(kind, picks, i, p.move)
      || slots[i].moves.find(m => m.id === p.move)?.mutagens
        && (!p.mutagens?.benefits.length || p.mutagens.increase === p.mutagens.decrease)));
    body.replaceChildren(
      el('div', { class: 'muted' }, kind === 'long'
        ? 'Each character gets 2 downtime moves; the same move cannot be chosen twice. Long rest: all rest abilities refresh.'
        : 'Each character gets 2 downtime moves; the same move cannot be chosen twice. Short rest: short-rest abilities refresh.'),
      ...(longInShort ? [el('div', { class: 'muted' }, 'You may choose one long-rest move during this short rest.')] : []),
      ...(blocker ? [el('div', { class: 'error' }, blocker)] : []),
      ...rows,
      ...(passives.length ? [el('div', { class: 'dh-rest-passives' }, ...passives.map((p) => {
        const source = activeCardSources(c).find(s => p.id.startsWith(`${s.key}|`));
        const benefit = source?.auto?.rest?.passives?.find(b => p.id.endsWith(`|${b.id}`));
        const toggle = check(p.label, () => passiveOn(preview, kind, o, p.id), (v) => { (o.passives ??= {})[p.id] = v; });
        if (!benefit?.chooseAlly || !passiveOn(preview, kind, o, p.id)) return toggle;
        const target = el('select', { ariaLabel: 'Recovery ally' }, el('option', { value: '' }, 'Choose an ally'),
          ...(actors?.() ?? []).map(actor => el('option', { value: actor.id }, actor.name)));
        target.value = o.allies?.[p.id] ?? '';
        target.addEventListener('change', () => { (o.allies ??= {})[p.id] = target.value; });
        return el('div', {}, toggle, target);
      }))] : []),
    );
    go.disabled = !!blocker || invalid;    error.textContent = '';
  }

  let pending = false;
  go.addEventListener('click', async () => {
    if (pending) return;
    pending = true;
    go.disabled = cancel.disabled = true;
    try {
      for (const source of activeCardSources(c)) for (const benefit of source.auto?.rest?.passives ?? []) {
        const id = `${source.key}|${benefit.id}`;
        if (!benefit.castBeforeRest || !passiveOn(c, kind, o, id)) continue;
        if (!castBeforeRest) throw new Error('Update the host to cast this spell during downtime.');
        await castBeforeRest(source.key, benefit.castBeforeRest);
        if (!c.effects.some(e => e.name === benefit.excludesEffect)) throw new Error(`${source.name} was not granted. Reopen downtime after the sheet refreshes.`);
        delete o.passives?.[id];
      }
      const chosen = picks.map((p) => ({ ...p }));
      const next = structuredClone(c);
      const result = resolveRest(next, kind, chosen, o);
      const summary = restSummary(next, kind, chosen, result);
      const hostRest = Object.values(o.passives ?? {}).some(Boolean) && Object.keys(o.allies ?? {}).length > 0
        || c.effects.some(e => e.consumeLongRestMove)
        || activeCardSources(c).some(s => s.auto?.rest?.modifiers?.some(m => m.repairAllies && chosen.some(p => !p.ally && p.move === m.move)));
      if (commit && hostRest) await commit({ kind, picks: chosen, options: o, sheet: next, result, report: summary });
      else if (!commit && hostRest) throw new Error('Update the host to resolve this rest benefit.');
      else Object.assign(c, next);
      close();
      if (!commit || !hostRest) onDone(summary, result);
    } catch (e) {
      error.textContent = e instanceof Error ? e.message : 'Resting failed.';
    } finally {
      pending = false;
      go.disabled = cancel.disabled = false;
    }
  });
  cancel.addEventListener('click', () => { if (!pending) close(); });
  backdrop.addEventListener('click', (ev) => { if (!pending && ev.target === backdrop) close(); });
  backdrop.append(el('div', { class: 'dh-rolldlg dh-restdlg dh-option-rolldlg' }, el('h4', {}, kind === 'long' ? 'Long rest - downtime moves' : 'Short rest - downtime moves'), body, error, el('div', { class: 'acts' }, cancel, go)));
  document.addEventListener('keydown', onKey);
  host.ownerDocument.body.append(backdrop);
  draw();
  go.focus();
}

import { el } from '../login';
import { confirmDialog } from '../dialogs';
import type { CharacterSheet } from './data';
import type { FeatureRequest } from '../api';
import { costText, usesLeft } from './engine';
import {
  AvailableMitigation, DamageInput, DamageType, SEVERITY_LABELS, applyDamage, availableMitigations, computeDamage, mitigationBlocker, quantityMax,
} from './damage';

/** Incoming-damage dialog: enter damage, pick mitigation, preview the HP to mark, then apply. */
export function openDamageDialog(host: HTMLElement, c: CharacterSheet, onDone: (summary: string, severity: number) => void, initial?: { damage: number; type: DamageType; excludeCompanionIntercept?: boolean; guard?: () => string | null },
  commit?: (damage: NonNullable<FeatureRequest['incomingDamage']>) => Promise<void>,
  reportRoll?: (label: string, value: number) => void): void {
  const options = availableMitigations(c).filter(m => !initial?.excludeCompanionIntercept || !m.option.companionIntercept);
  const input: DamageInput = {
    damage: initial?.damage ?? 0, type: initial?.type ?? 'physical', armor: false, armorCount: 1, selected: new Set(options.filter((m) => m.option.defaultOn).map((m) => m.id)),
    quantities: {}, rolls: {},
  };
  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  const dialog = el('div', { class: 'dh-rolldlg dh-damage-dlg dh-option-rolldlg', role: 'dialog' });
  dialog.setAttribute('aria-label', 'Take damage');
  const close = () => { backdrop.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };

  const amount = el('input', { type: 'number', min: '0', step: '1', value: initial ? String(initial.damage) : '' }) as HTMLInputElement;
  amount.addEventListener('input', () => { input.damage = Math.max(0, Math.floor(Number(amount.value) || 0)); draw(); });
  const typeButtons = (['physical', 'magic'] as DamageType[]).map((t) => {
    const b = el('button', { type: 'button', class: 'dh-adv-btn norm' }, t === 'physical' ? 'Physical' : 'Magic');
    b.addEventListener('click', () => { input.type = t; draw(); });
    return [t, b] as const;
  });
  const typeRow = el('div', { class: 'dh-dmg-type' }, ...typeButtons.map(([, b]) => b));
  const recoveryControls = el('div');
  const body = el('div', { class: 'dh-dmg-body dh-roll-options' });
  const preview = el('div', { class: 'dh-dmg-preview' });
  preview.setAttribute('aria-live', 'polite');
  const error = el('div', { class: 'error', role: 'alert' });
  const apply = el('button', { class: 'primary', type: 'button' }, 'Apply') as HTMLButtonElement;
  const cancel = el('button', { type: 'button' }, 'Cancel');
  cancel.addEventListener('click', close);

  const meta = (m: AvailableMitigation): string => {
    const bits: string[] = [];
    const cost = costText(m.option.cost ?? {}, c);
    if (cost) bits.push(m.option.quantity ? `${cost} each` : cost);
    const armorCost = costText(m.option.armorCost ?? {}, c);
    if (armorCost) bits.push(`${armorCost} per Armor Slot marked`);
    const uses = m.option.uses === 'source' ? m.automation.uses : m.option.uses;
    if (uses) {
      const key = m.option.uses === 'source' ? m.key : `${m.key}:mitigation:${m.option.id}`;
      bits.push(`${usesLeft(c, key, { uses }) ?? 0}/${uses.max} left`);
    }
    if (m.option.consumeItem && m.item) bits.push(`${m.item.qty} owned`);
    return bits.join(' - ');
  };

  function draw(): void {
    for (const [t, b] of typeButtons) b.classList.toggle('on', input.type === t);
    const r = computeDamage(c, options, input);
    const free = Math.max(0, c.armorTrack.slots - c.armorTrack.on);
    body.replaceChildren();
    recoveryControls.replaceChildren();
    recoveryControls.hidden = !r.applied.some(m => m.option.recoveryChoice);
    if (r.applied.some(m => m.option.recoveryChoice)) {
      const recovery = el('select', { ariaLabel: 'Not Done Yet recovery' },
        el('option', { value: 'hope' }, 'Gain 1 Hope'), el('option', { value: 'stress' }, 'Clear 1 Stress'));
      recovery.value = r.recovery ?? 'hope';
      recovery.addEventListener('change', () => { input.recovery = recovery.value === 'stress' ? 'stress' : 'hope'; draw(); });
      recoveryControls.append(el('label', { class: 'dh-roll-field' }, 'Not Done Yet (automatic on Severe damage)', recovery));
    }

    // Base Armor Slot.
    const armorBox = el('input', { type: 'checkbox' }) as HTMLInputElement;
    armorBox.checked = input.armor;
    const armorWhy = r.armorBlocked ?? (free <= 0 ? 'No free Armor Slots' : null);
    armorBox.disabled = !!armorWhy && !input.armor;
    armorBox.addEventListener('change', () => { input.armor = armorBox.checked; draw(); });
    const armorText = el('span', {}, r.anyArmor ? 'Mark Armor Slots' : 'Mark an Armor Slot', el('small', { class: 'muted' }, `Reduces severity by one per slot (${free}/${c.armorTrack.slots} free)`));
    if (armorWhy) armorText.append(el('small', { class: 'error' }, armorWhy));
    const armorRow = el('div', { class: 'dh-roll-option-row' },
      el('label', { class: 'dh-roll-option' }, armorBox, armorText));
    if (r.anyArmor && input.armor) {
      const count = el('input', { type: 'number', min: '1', max: String(Math.max(1, r.armorAllowed)), value: String(r.armorUsed || 1) }) as HTMLInputElement;
      count.addEventListener('change', () => { input.armorCount = Math.max(1, Math.floor(Number(count.value) || 1)); draw(); });
      armorRow.append(el('label', { class: 'dh-roll-option-control' }, el('span', {}, 'Armor Slots'), count));
    }
    body.append(armorRow);

    const choices = options.filter((m) => !m.option.passive);
    const passives = options.filter((m) => m.option.passive);
    if (choices.length) {
      const set = el('fieldset', {}, el('legend', {}, 'Mitigation (select all that apply)'));
      for (const m of choices) {
        const box = el('input', { type: 'checkbox' }) as HTMLInputElement;
        const staticWhy = mitigationBlocker(c, m, input.type);
        box.checked = input.selected.has(m.id);
        box.disabled = !!staticWhy && !box.checked;
        box.addEventListener('change', () => {
          if (box.checked) {
            if (m.option.companionIntercept) for (const other of choices.filter(o => o.option.companionIntercept)) input.selected.delete(other.id);
            input.selected.add(m.id);
          } else input.selected.delete(m.id);
          draw();
        });
        const info = meta(m);
        const text = el('span', {}, el('b', {}, m.option.label), el('span', { class: 'muted' }, ` - ${m.source}${info ? ` (${info})` : ''}`));
        if (m.option.description) text.append(el('small', { class: 'muted' }, m.option.description));
        const faces = r.used[m.id];
        if (box.checked && faces?.length && !m.option.hiddenRoll) text.append(el('small', {}, `Rolled ${faces.join(', ')}`));
        const why = box.checked ? r.inactive[m.id] : staticWhy;
        if (why) text.append(el('small', { class: 'error' }, why));
        const row = el('div', { class: 'dh-roll-option-row' },
          el('label', { class: 'dh-roll-option' }, box, text));
        if (m.option.quantity && box.checked) {
          const max = Math.max(1, quantityMax(c, m.option));
          const q = el('input', { type: 'number', min: '1', max: String(max), value: String(input.quantities![m.id] ?? 1) }) as HTMLInputElement;
          q.title = 'How many to spend';
          q.addEventListener('change', () => { input.quantities![m.id] = Math.min(max, Math.max(1, Math.floor(Number(q.value) || 1))); draw(); });
          row.append(el('label', { class: 'dh-roll-option-control' }, el('span', {}, 'How many to spend'), q));
        }
        set.append(row);
      }
      body.append(set);
    }
    if (passives.length) {
      const list = el('div', { class: 'dh-dmg-passive' }, el('div', { class: 'muted' }, 'Applied automatically'));
      for (const m of passives) {
        const on = r.applied.some((a) => a.id === m.id);
        const why = r.inactive[m.id];
        const info = meta(m);
        const faces = r.used[m.id];
        list.append(el(
          'div', { class: on ? 'on' : 'muted' },
          `${on ? '\u2713' : '\u2013'} ${m.option.label} (${m.source})${info ? ` - ${info}` : ''}${on && faces?.length && !m.option.hiddenRoll ? ` - rolled ${faces.join(', ')}` : ''}${!on && why ? ` - ${why}` : ''}`,
        ));
      }
      body.append(list);
    }

    // Live preview.
    const severity = r.negated ? 'Negated' : r.incoming === r.final ? SEVERITY_LABELS[r.incoming] : `${SEVERITY_LABELS[r.incoming]} \u2192 ${SEVERITY_LABELS[r.final]}`;
    const extra: string[] = [];
    if (r.armorUsed) extra.push(`${r.armorMarked} Armor Slot${r.armorMarked === 1 ? '' : 's'} marked${r.armorMarked !== r.armorUsed ? ` (${r.armorUsed - r.armorMarked} saved)` : ''}`);
    const cost = costText(r.costs, c);
    if (cost) extra.push(`pay ${cost}`);
    if (r.hpBeforeReplace !== r.hp && r.hp === 0) extra.push(`instead of ${r.hpBeforeReplace} HP`);
    preview.replaceChildren(
      el('div', { class: 'muted' }, input.damage ? `${r.input} damage${r.damage !== r.input ? ` \u2192 ${r.damage}` : ''} \u00b7 ${severity}` : 'Enter the damage you took'),
      el('div', { class: 'dh-dmg-hp' }, `Mark ${r.hp} HP`),
      ...(extra.length ? [el('div', { class: 'muted' }, extra.join(' \u00b7 '))] : []),
    );
    error.textContent = r.error ?? '';
    apply.disabled = !!r.error || input.damage <= 0;
    apply.onclick = async () => {
      const guard = initial?.guard?.();
      if (guard) { error.textContent = guard; return; }
      if (r.applied.some(m => m.option.companionIntercept)
        && !await confirmDialog('Confirm this is attack damage and you are within Close range of your available companion. They take the damage instead, using the selected Stress or Armored option.')) return;
      const currentGuard = initial?.guard?.();
      if (currentGuard) { error.textContent = currentGuard; return; }
      apply.disabled = cancel.disabled = true;
      try {
        const next = structuredClone(c);
        const resolved = computeDamage(next, availableMitigations(next), input);
        const summary = applyDamage(next, resolved, input.type);
        if (!summary) throw new Error('Could not apply damage');
        const hostDamage = resolved.applied.some(m => m.option.giveFear || m.option.hiddenRoll || m.option.clearStress);
        if (commit && hostDamage) await commit({ sheet: next, input: { ...input, selected: [...input.selected] },
          result: { armorUsed: resolved.armorUsed, armorMarked: resolved.armorMarked, hp: resolved.hp, hpBeforeReplace: resolved.hpBeforeReplace, final: resolved.final,
            applied: resolved.applied.map(m => ({ key: m.key, id: m.option.id })) }, report: summary });
        else if (resolved.applied.some(m => m.option.giveFear)) throw new Error('Update the host to grant GM Fear with this armor option.');
        else { Object.assign(c, next); onDone(summary, resolved.negated ? 0 : resolved.final); }
        for (const m of resolved.applied.filter(m => m.option.reportRoll))
          reportRoll?.(m.option.label, (resolved.used[m.id] ?? []).reduce((sum, value) => sum + value, 0));
        close();
      } catch (e) { error.textContent = (e as Error).message; }
      finally { apply.disabled = cancel.disabled = false; }
    };
  }

  const d = computeDamage(c, options, input);
  dialog.append(
    el('h4', {}, 'Take damage'),
    el('div', { class: 'muted' }, `Thresholds: Minor < ${d.major} \u2264 Major < ${d.severe} \u2264 Severe; ${d.severe * 2}+ marks 4 HP`),
    el('label', {}, 'Damage taken', amount),
    typeRow,
    recoveryControls,
    body,
    preview,
    error,
    el('div', { class: 'acts' }, cancel, apply),
  );
  backdrop.append(dialog);
  backdrop.addEventListener('click', (e) => { if (e.target === backdrop) close(); });
  document.addEventListener('keydown', onKey);
  host.append(backdrop);
  draw();
  amount.focus();
}

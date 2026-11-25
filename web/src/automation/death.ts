import { ApiError, hostFetch } from '../api';
import { el } from '../login';
import { drawingId } from '../tools';
import { costLabel, inputFields, targetFields, type RollSelection, type RuleDescriptor, type RuleIntent } from './controls';
import { automationUrl, CONTROL_TEXT, errorDetails, executeDeathOption, executeDeathRoll } from './panel';

interface DeathOffer {
  revision: number;
  actions: RuleDescriptor[];
  rolls?: Record<string, { label: string; description?: string; options: RuleDescriptor[] }>;
}

type Move = { kind: 'roll'; move: string; label: string; description?: string; options: RuleDescriptor[] }
  | { kind: 'action'; action: RuleDescriptor };

const optionMeta = (option: RuleDescriptor) => [option.cost ?? '',
  option.uses ? `${option.uses.left}/${option.uses.max} left` : ''].filter(Boolean).join(' - ');

/**
 * The format-1 death-move dialog fed by the host: the move select lists host-described death rolls and death actions,
 * and the fields below it are that move's host-described options and inputs. Returns false only for legacy hosts.
 */
export async function openAutomationDeathDialog(actorId: string, before: () => Promise<void>, name?: string): Promise<boolean> {
  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  const error = el('p', { class: 'error', role: 'alert' }, 'Loading host death-move options...');
  const select = el('select', { ariaLabel: 'Death move', disabled: true });
  const description = el('p');
  const fields = el('div', { class: 'dh-roll-options' });
  const cancel = el('button', { type: 'button', class: 'small' }, 'Cancel');
  const confirm = el('button', { type: 'button', class: 'primary', disabled: true }, 'Confirm death move');
  backdrop.append(el('div', { class: 'dh-rolldlg dh-option-rolldlg', role: 'dialog', ariaLabel: 'Death move', ariaModal: 'true' },
    el('h4', {}, name ? `${name} - Death move` : 'Death move'), select, description, fields,
    el('p', { class: 'muted' }, 'The result is shared publicly in chat. Discuss the choice with your GM before confirming.'),
    error, el('div', { class: 'acts' }, cancel, confirm)));
  document.body.append(backdrop);

  let closed = false, pending = false, revision = 0;
  let moves: Move[] = [];
  let read: () => { inputs?: RuleIntent['inputs']; target?: RuleIntent['target']; selections?: RollSelection[] } = () => ({});
  let retry: { index: string; requestId: string; payload: ReturnType<typeof read> } | undefined;
  const close = () => { if (closed) return; closed = true; backdrop.remove(); };
  cancel.addEventListener('click', () => { if (!pending) close(); });

  const paint = () => {
    const move = moves[Number(select.value)];
    fields.replaceChildren();
    read = () => ({});
    if (!move) { description.textContent = ''; confirm.disabled = true; return; }
    if (move.kind === 'roll') {
      description.textContent = move.description ?? '';
      const rows = move.options.map(option => {
        const box = el('input', { type: 'checkbox', checked: !!option.defaultOn && option.available, disabled: !option.available });
        const meta = optionMeta(option);
        const text = el('span', {}, el('b', {}, option.label),
          ...(option.sourceName || meta ? [el('span', { class: 'muted' }, ` - ${option.sourceName ?? ''}${meta ? ` (${meta})` : ''}`)] : []));
        if (option.description) text.append(el('small', { class: 'muted' }, option.description));
        if (!option.available && option.reason) text.append(el('small', { class: 'error' }, option.reason));
        const control = el('div', { class: 'dh-roll-option-control', hidden: !box.checked });
        const readInputs = inputFields(control, option.inputs ?? [], [], CONTROL_TEXT);
        const row = el('div', { class: 'dh-roll-option-row' }, el('label', { class: 'dh-roll-option' }, box, text),
          ...(option.inputs?.length ? [control] : []));
        fields.append(row);
        return { option, box, control, readInputs };
      });
      for (const entry of rows) entry.box.addEventListener('change', () => {
        entry.control.hidden = !entry.box.checked;
        if (entry.box.checked && entry.option.exclusiveGroup) for (const other of rows)
          if (other !== entry && other.option.exclusiveGroup === entry.option.exclusiveGroup) {
            other.box.checked = false;
            other.control.hidden = true;
          }
      });
      read = () => ({ selections: rows.filter(row => row.box.checked && row.option.available).map(({ option, readInputs }) => ({
        sourceInstanceId: option.sourceInstanceId, ruleId: option.ruleId, bindingId: option.bindingId,
        ...(option.eachId === undefined ? {} : { eachId: option.eachId }), inputs: readInputs() })) });
      confirm.disabled = false;
      return;
    }
    const action = move.action;
    description.textContent = action.description ?? '';
    if (!action.available && action.reason) fields.append(el('p', { class: 'error' }, action.reason));
    const readTarget = targetFields(fields, action.targetSelection, [], CONTROL_TEXT);
    const readInputs = inputFields(fields, action.inputs ?? [], [], CONTROL_TEXT);
    read = () => {
      const target = readTarget();
      return { inputs: readInputs(), ...(target ? { target } : {}) };
    };
    confirm.disabled = !action.available;
  };
  select.addEventListener('change', paint);

  const load = async (initial: boolean, notice = ''): Promise<boolean> => {
    try {
      const response = await hostFetch(`${automationUrl(actorId)}/death`, { credentials: 'same-origin', cache: 'no-store' });
      if (closed) return true;
      if (response.status === 404 && initial) { close(); return false; }
      if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
      const result = await response.json() as DeathOffer | { legacy: true };
      if ('legacy' in result && result.legacy === true && initial) { close(); return false; }
      if (!('revision' in result) || !Number.isSafeInteger(result.revision) || !Array.isArray(result.actions))
        throw new Error('The host returned invalid death options.');
      const previous = moves[Number(select.value)];
      const previousKey = previous ? previous.kind === 'roll' ? `roll:${previous.move}`
        : `action:${previous.action.sourceInstanceId}|${previous.action.ruleId}|${previous.action.bindingId}` : '';
      revision = result.revision;
      moves = [
        ...Object.entries(result.rolls ?? {}).map(([move, roll]): Move => ({ kind: 'roll', move, ...roll })),
        ...result.actions.filter(action => action.mode === 'button').map((action): Move => ({ kind: 'action', action })),
      ];
      select.replaceChildren(...moves.map((move, index) => el('option', { value: String(index) }, move.kind === 'roll'
        ? move.label : `${costLabel(move.action)}${move.action.available ? '' : ` — ${move.action.reason ?? ''}`}`)));
      const keep = moves.findIndex(move => (move.kind === 'roll' ? `roll:${move.move}`
        : `action:${move.action.sourceInstanceId}|${move.action.ruleId}|${move.action.bindingId}`) === previousKey);
      select.value = String(Math.max(0, keep));
      select.disabled = moves.length === 0;
      paint();
      error.textContent = notice || (moves.length ? '' : 'No death moves are available.');
    } catch (failure) {
      if (!closed) error.textContent = failure instanceof Error ? failure.message : 'Could not load death options.';
    }
    return true;
  };

  confirm.addEventListener('click', async () => {
    if (confirm.disabled || pending) return;
    const move = moves[Number(select.value)];
    if (!move) return;
    try {
      if (!retry || retry.index !== select.value) retry = { index: select.value, requestId: drawingId(), payload: read() };
    } catch (failure) {
      error.textContent = failure instanceof Error ? failure.message : 'Could not make the death move.';
      return;
    }
    pending = true;
    confirm.disabled = select.disabled = cancel.disabled = true;
    error.textContent = '';
    const { requestId, payload } = retry;
    try {
      if (move.kind === 'roll') await executeDeathRoll(actorId, move.move,
        { requestId, expectedRevision: revision, selections: structuredClone(payload.selections ?? []) });
      else await executeDeathOption(actorId, { requestId, expectedRevision: revision, sourceId: move.action.sourceId,
        sourceInstanceId: move.action.sourceInstanceId, ...(move.action.eachId === undefined ? {} : { eachId: move.action.eachId }),
        ruleId: move.action.ruleId, bindingId: move.action.bindingId, inputs: structuredClone(payload.inputs ?? {}),
        ...(payload.target ? { target: payload.target } : {}) });
      pending = false;
      close();
    } catch (failure) {
      pending = false;
      cancel.disabled = false;
      if (failure instanceof ApiError && failure.status === 409) {
        retry = undefined;
        await load(false, failure.message);
        return;
      }
      // An uncertain response must retry the identical intent, so the move stays locked until it is confirmed.
      error.textContent = failure instanceof Error ? failure.message : 'Could not make the death move.';
      confirm.disabled = false;
    }
  });

  try {
    await before();
  } catch (failure) {
    if (!closed) error.textContent = failure instanceof Error ? failure.message : 'Could not load death options.';
    return true;
  }
  return closed ? true : load(true);
}

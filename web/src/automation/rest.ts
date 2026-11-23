import { ApiError, hostFetch } from '../api';
import { el } from '../login';
import { drawingId } from '../tools';
import { inputFields, optionFields, type RuleDescriptor, type RuleIntent } from './controls';
import { actionReports, automationUrl, CONTROL_TEXT, errorDetails } from './panel';

type RestKind = 'short' | 'long';
type Selection = Pick<RuleIntent, 'sourceInstanceId' | 'ruleId' | 'bindingId' | 'eachId' | 'inputs'>;

interface MoveDescriptor extends RuleDescriptor {
  sourceName: string;
  restMove: { id: string; kind: string; group?: string };
  options: RuleDescriptor[];
}

interface RestOffer {
  kind: RestKind;
  revision: number;
  offerVersion: string;
  plan: { moves: number; longMoves: number };
  preparation: RuleDescriptor[];
  moves: MoveDescriptor[];
  completion: RuleDescriptor[];
}

interface RestRequest {
  requestId: string;
  offerVersion: string;
  kind: RestKind;
  expectedRevision: number;
  preparation: Selection[];
  moves: Array<{ selection: Selection; options: Selection[] }>;
  completion: Selection[];
}

async function restOffer(actorId: string, kind: RestKind): Promise<RestOffer | null> {
  const response = await hostFetch(`${automationUrl(actorId)}/rest?${new URLSearchParams({ kind })}`,
    { credentials: 'same-origin', cache: 'no-store' });
  if (response.status === 404) return null;
  if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
  const data = await response.json() as RestOffer | { legacy: true };
  return 'legacy' in data ? null : data;
}

async function submitRest(actorId: string, request: RestRequest): Promise<unknown> {
  const response = await hostFetch(`${automationUrl(actorId)}/rest`, {
    method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ request }),
  });
  if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
  return response.json();
}

function restReports(response: unknown): string[] {
  const reports = actionReports(response);
  if (!response || typeof response !== 'object' || Array.isArray(response)) return reports;
  const recoveries = (response as Record<string, unknown>).partyRecoveries;
  if (!Array.isArray(recoveries)) return reports;
  for (const recovery of recoveries) {
    if (!recovery || typeof recovery !== 'object') continue;
    const { holderName, track, requested, cleared, label } = recovery as Record<string, unknown>;
    if (typeof holderName === 'string' && typeof track === 'string' && typeof label === 'string'
        && typeof requested === 'number' && typeof cleared === 'number')
      reports.push(`${holderName} cleared ${cleared} ${track} (requested ${requested}; ${label}).`);
  }
  return reports;
}

/** Returns false only when the host explicitly uses the legacy rest workflow. */
export async function openAutomationRestDialog(actorId: string, kind: RestKind,
                                               before: () => Promise<void>): Promise<boolean> {
  const title = kind === 'short' ? 'Short rest' : 'Long rest';
  const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: title });
  const content = el('div', {}, el('p', { role: 'status' }, 'Loading downtime…'));
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const actions = el('div', { class: 'acts' }, cancel);
  dialog.append(el('h3', {}, title), content, error, actions);
  document.body.append(dialog);
  let pending = false;
  let closed = false;
  dialog.addEventListener('cancel', event => { if (pending) event.preventDefault(); });
  dialog.addEventListener('close', () => { closed = true; dialog.remove(); }, { once: true });
  cancel.addEventListener('click', () => { if (!pending) dialog.close(); });
  dialog.showModal();

  let offer: RestOffer | null;
  try {
    await before();
    offer = await restOffer(actorId, kind);
  } catch (failure) {
    if (closed) return true;
    content.replaceChildren();
    error.textContent = failure instanceof Error ? failure.message : 'Could not load downtime.';
    cancel.textContent = 'Close';
    return true;
  }
  if (closed) return true;
  if (!offer) {
    dialog.close();
    return false;
  }

  content.replaceChildren(el('p', { class: 'muted' },
    `${offer.plan.moves} downtime ${offer.plan.moves === 1 ? 'move' : 'moves'} available. ` +
    'Selected options may change the allowance; the host checks the final plan.'));
  const fields: HTMLInputElement[] = [];
  const preparation = el('div');
  if (offer.preparation.length) preparation.append(el('h4', {}, 'Rest preparation'));
  const readPreparation = optionFields(preparation, offer.preparation, fields, CONTROL_TEXT);
  content.append(preparation);

  const moves = el('div', {}, el('h4', {}, 'Downtime moves'));
  const rows: Array<{ select: HTMLSelectElement; fields: HTMLInputElement[];
    readInputs: () => RuleIntent['inputs']; readOptions: () => Selection[] }> = [];
  const add = el('button', { type: 'button', class: 'small' }, 'Add downtime move');
  const addMove = () => {
    if (rows.length >= 64) return;
    const select = el('select', { ariaLabel: `Downtime move ${rows.length + 1}` });
    select.append(el('option', { value: '' }, 'No move'));
    offer.moves.forEach((move, index) => {
      const label = move.sourceName ? `${move.sourceName}: ${move.label}` : move.label;
      select.append(el('option', { value: String(index), disabled: !move.available },
        move.available ? label : `${label} (${move.reason ?? 'unavailable'})`));
    });
    const detail = el('div');
    const row = { select, fields: [] as HTMLInputElement[], readInputs: () => ({}), readOptions: () => [] as Selection[] };
    const update = () => {
      row.fields = [];
      detail.replaceChildren();
      const selected = select.value === '' ? undefined : offer.moves[Number(select.value)];
      if (!selected) return;
      if (selected.description) detail.append(el('p', {}, selected.description));
      row.readInputs = inputFields(detail, selected.inputs ?? [], row.fields, CONTROL_TEXT);
      row.readOptions = optionFields(detail, selected.options, row.fields, CONTROL_TEXT);
    };
    select.addEventListener('change', update);
    rows.push(row);
    moves.append(el('div', {}, el('label', {}, `Move ${rows.length}`, select), detail));
    add.disabled = rows.length >= 64;
  };
  for (let i = 0; i < offer.plan.moves; i++) addMove();
  add.addEventListener('click', addMove);
  content.append(moves, add);
  const completion = el('div');
  if (offer.completion.length) {
    completion.append(el('h4', {}, 'Rest benefits'),
      el('p', { class: 'muted' }, 'Availability is checked again after downtime.'));
  }
  const readCompletion = optionFields(completion, offer.completion, fields, CONTROL_TEXT);
  content.append(completion);

  const go = el('button', { type: 'button', class: 'primary' }, 'Complete rest');
  actions.append(go);
  let retry: RestRequest | undefined;
  let disabled: Array<{ element: HTMLInputElement | HTMLSelectElement | HTMLButtonElement; previous: boolean }> = [];
  const lock = () => {
    disabled = [add, ...fields, ...rows.flatMap(row => [row.select, ...row.fields])]
      .map(element => ({ element, previous: element.disabled }));
    for (const item of disabled) item.element.disabled = true;
  };
  const unlock = () => { for (const item of disabled) item.element.disabled = item.previous; disabled = []; };
  go.addEventListener('click', () => {
    if (pending) return;
    try {
      if (!retry) {
        const chosen: RestRequest['moves'] = [];
        const groups = new Set<string>();
        for (const row of rows) {
          if (row.select.value === '') continue;
          const move = offer.moves[Number(row.select.value)];
          if (!move?.available) throw new Error('Choose an available downtime move.');
          const group = `${move.sourceInstanceId}\u0000${move.restMove.group ?? move.restMove.id}`;
          if (groups.has(group)) throw new Error('The same downtime move cannot be chosen twice.');
          groups.add(group);
          chosen.push({ selection: { sourceInstanceId: move.sourceInstanceId, ruleId: move.ruleId,
            bindingId: move.bindingId, inputs: row.readInputs() }, options: row.readOptions() });
        }
        retry = { requestId: drawingId(), offerVersion: offer.offerVersion, kind, expectedRevision: offer.revision,
          preparation: readPreparation(), moves: chosen, completion: readCompletion() };
        lock();
      }
    } catch (failure) {
      error.textContent = failure instanceof Error ? failure.message : 'Check your choices.';
      return;
    }
    pending = true;
    error.textContent = '';
    go.disabled = true;
    cancel.disabled = true;
    go.textContent = CONTROL_TEXT.pending;
    void submitRest(actorId, retry).then(receipt => {
      const reports = restReports(receipt);
      content.replaceChildren(el('p', { role: 'status' }, reports.length ? reports.join('\n') : 'Rest completed.'));
      actions.replaceChildren(cancel);
      cancel.textContent = 'Done';
    }).catch((failure: unknown) => {
      error.textContent = failure instanceof Error ? failure.message : 'The rest could not be completed.';
      if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500) {
        retry = undefined;
        unlock();
        go.textContent = 'Complete rest';
      } else {
        go.textContent = 'Retry';
      }
    }).finally(() => {
      pending = false;
      go.disabled = false;
      cancel.disabled = false;
    });
  });
  return true;
}

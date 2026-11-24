import { ApiError, hostFetch } from '../api';
import { el } from '../login';
import { drawingId } from '../tools';
import { inputFields, optionFields, targetFields, type RuleDescriptor, type RuleIntent } from './controls';
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
  postOutcomeChoices?: boolean;
  prepareBeforeMoves?: boolean;
  choiceVersion?: string;
  reports?: string[];
  plan: { moves: number; longMoves: number };
  preparation: RuleDescriptor[];
  moves: MoveDescriptor[];
  completion: RuleDescriptor[];
}

interface RestChoiceOffer {
  phase: 'beforeReset' | 'afterReset';
  choiceVersion: string;
  actions: RuleDescriptor[];
  cleared: Record<string, number>;
  reports: string[];
}

interface RestRequest {
  requestId: string;
  offerVersion: string;
  kind: RestKind;
  expectedRevision: number;
  preparation: Selection[];
  moves: Array<{ selection: Selection; options: Selection[]; target?: RuleIntent['target'] }>;
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

async function submitRestStage(actorId: string, path: 'choices' | 'complete', body: unknown): Promise<unknown> {
  const response = await hostFetch(`${automationUrl(actorId)}/rest/${path}`, {
    method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
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

  const renderPlan = (offer: RestOffer, prepared?: { request: RestRequest; version: string }) => {
    content.replaceChildren(el('p', { class: 'muted' },
      `${offer.plan.moves} downtime ${offer.plan.moves === 1 ? 'move' : 'moves'} available. ` +
      'Selected options may change the allowance; the host checks the final plan.'));
    if (offer.reports?.length) content.append(el('p', { role: 'status' }, offer.reports.join('\n')));
    const fields: HTMLInputElement[] = [];
    const preparation = el('div');
    if (offer.preparation.length) preparation.append(el('h4', {}, 'Rest preparation'));
    const readPreparation = optionFields(preparation, offer.preparation, fields, CONTROL_TEXT);
    content.append(preparation);

    const moves = el('div', {}, el('h4', {}, 'Downtime moves'));
    const rows: Array<{ select: HTMLSelectElement; fields: HTMLInputElement[];
      readInputs: () => RuleIntent['inputs']; readOptions: () => Selection[]; readTarget: () => RuleIntent['target'] }> = [];
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
      const row = { select, fields: [] as HTMLInputElement[], readInputs: () => ({}), readOptions: () => [] as Selection[],
        readTarget: (): RuleIntent['target'] => undefined };
      const update = () => {
        row.fields = [];
        detail.replaceChildren();
        const selected = select.value === '' ? undefined : offer.moves[Number(select.value)];
        if (!selected) return;
        const selection = selected.targetSelection;
        const targetSpecificMove = selection?.cardinality === 'one'
          && selection.options.some(option => option.moveDescriptor !== undefined);
        const targetSpecificOptions = selection?.cardinality === 'one'
          && selection.options.some(option => option.downtimeOptions !== undefined);
        if (!targetSpecificMove && selected.description) detail.append(el('p', {}, selected.description));
        const moveDetails = el('div');
        const options = el('div');
        let dynamicStart = 0;
        const showTarget = (id?: string) => {
          const target = selection?.cardinality === 'one' ? selection.options.find(option => option.id === id) : undefined;
          const move = target?.moveDescriptor ?? selected;
          row.fields.splice(dynamicStart);
          moveDetails.replaceChildren();
          options.replaceChildren();
          if (targetSpecificMove && target) {
            moveDetails.append(el('p', {}, move.label));
            if (move.description) moveDetails.append(el('p', {}, move.description));
            if (!move.available) moveDetails.append(el('p', { class: 'muted' }, move.reason ?? 'This move is unavailable.'));
          }
          if (targetSpecificMove && !target) row.readInputs = () => ({});
          else if (!move.available) row.readInputs = () => { throw new Error(move.reason ?? 'This move is unavailable.'); };
          else row.readInputs = inputFields(moveDetails, move.inputs ?? [], row.fields, CONTROL_TEXT);
          row.readOptions = optionFields(options, target?.downtimeOptions
            ?? (targetSpecificOptions ? [] : selected.options), row.fields, CONTROL_TEXT);
        };
        row.readTarget = targetFields(detail, selection, row.fields, CONTROL_TEXT, showTarget);
        dynamicStart = row.fields.length;
        showTarget();
        detail.append(moveDetails, options);
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
    if (!offer.postOutcomeChoices && offer.completion.length) {
      completion.append(el('h4', {}, 'Rest benefits'),
        el('p', { class: 'muted' }, 'Availability is checked again after downtime.'));
    }
    const readCompletion = optionFields(completion, offer.postOutcomeChoices ? [] : offer.completion, fields, CONTROL_TEXT);
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
    let phase: 'plan' | 'beforeReset' | 'afterReset' = 'plan';
    let choice: RestChoiceOffer | undefined;
    let beforeChoices: Selection[] = [];
    let readChoices: () => Selection[] = () => [];
    let choiceFields: HTMLInputElement[] = [];
    let retryStage: { path: 'choices' | 'complete'; body: unknown } | undefined;

    const showChoices = (next: RestChoiceOffer) => {
      choice = next;
      phase = next.phase;
      choiceFields = [];
      const outcome = Object.entries(next.cleared)
        .map(([name, amount]) => `${name}: ${amount}`).join(', ');
      const options = el('div');
      readChoices = optionFields(options, next.actions, choiceFields, CONTROL_TEXT);
      content.replaceChildren(
        el('p', { role: 'status' }, next.reports.length ? next.reports.join('\n') : 'Downtime moves resolved.'),
        el('p', { class: 'muted' }, `Rest changes: ${outcome || 'none'}.`),
        el('h4', {}, next.phase === 'beforeReset' ? 'Rest benefits' : 'After rest benefits'),
        options,
      );
      go.textContent = next.phase === 'beforeReset' ? 'Continue' : 'Complete rest';
    };

    const nextStage = (): { path: 'choices' | 'complete'; body: unknown } => {
      if (!retry) throw new Error('The rest request is missing.');
      if (phase === 'plan') return { path: 'choices', body: prepared
        ? { request: retry, preparedVersion: prepared.version } : { request: retry } };
      if (!choice) throw new Error('The rest choices are missing.');
      const selections = readChoices();
      if (phase === 'beforeReset')
        return { path: 'choices', body: { requestId: retry.requestId, choiceVersion: choice.choiceVersion, selections } };
      return { path: 'complete', body: { choiceVersion: choice.choiceVersion,
        request: { ...retry, completion: [...beforeChoices, ...selections] } } };
    };

    const performStages = async () => {
      while (true) {
        if (!retryStage) retryStage = nextStage();
        const current = retryStage;
        const currentPhase = phase;
        const response = await submitRestStage(actorId, current.path, current.body);
        retryStage = undefined;
        if (currentPhase === 'afterReset') {
          const reports = restReports(response);
          content.replaceChildren(el('p', { role: 'status' }, reports.length ? reports.join('\n') : 'Rest completed.'));
          actions.replaceChildren(cancel);
          cancel.textContent = 'Done';
          return;
        }
        if (currentPhase === 'beforeReset') {
          const sent = current.body as { selections: Selection[] };
          beforeChoices = sent.selections;
        }
        const next = response as RestChoiceOffer;
        if ((currentPhase === 'plan' && next.phase !== 'beforeReset')
            || (currentPhase === 'beforeReset' && next.phase !== 'afterReset')
            || !Array.isArray(next.actions) || !Array.isArray(next.reports)
            || !next.cleared || typeof next.choiceVersion !== 'string')
          throw new Error('The host returned invalid rest choices.');
        disabled = [];
        showChoices(next);
        if (next.actions.some(action => action.available)) return;
      }
    };

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
            const target = row.readTarget();
            chosen.push({ selection: { sourceInstanceId: move.sourceInstanceId, ruleId: move.ruleId,
              bindingId: move.bindingId, inputs: row.readInputs() }, options: row.readOptions(),
              ...(target ? { target } : {}) });
          }
          retry = { requestId: prepared?.request.requestId ?? drawingId(), offerVersion: offer.offerVersion,
            kind, expectedRevision: offer.revision, preparation: prepared?.request.preparation ?? readPreparation(),
            moves: chosen, completion: readCompletion() };
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
      if (offer.postOutcomeChoices) {
        if (phase !== 'plan' && disabled.length === 0)
          disabled = choiceFields.map(element => ({ element, previous: element.disabled }));
        for (const item of disabled) item.element.disabled = true;
        void performStages().catch((failure: unknown) => {
          error.textContent = failure instanceof Error ? failure.message : 'The rest could not be completed.';
          if (!retryStage || failure instanceof ApiError && failure.status >= 400 && failure.status < 500) {
            retryStage = undefined;
            if (phase === 'plan') retry = undefined;
            unlock();
            go.textContent = phase === 'beforeReset' ? 'Continue' : 'Complete rest';
          } else {
            go.textContent = 'Retry';
          }
        }).finally(() => {
          pending = false;
          go.disabled = false;
          cancel.disabled = false;
        });
        return;
      }
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
  };

  if (!offer.prepareBeforeMoves) {
    renderPlan(offer);
    return true;
  }
  const fields: HTMLInputElement[] = [];
  const options = el('div');
  const readPreparation = optionFields(options, offer.preparation, fields, CONTROL_TEXT);
  content.replaceChildren(el('p', { class: 'muted' }, 'Prepare the rest before choosing downtime moves.'), options);
  const prepare = el('button', { type: 'button', class: 'primary' }, 'Prepare rest');
  actions.append(prepare);
  let request: RestRequest | undefined;
  prepare.addEventListener('click', () => {
    if (pending) return;
    try {
      request ??= { requestId: drawingId(), offerVersion: offer.offerVersion, kind, expectedRevision: offer.revision,
        preparation: readPreparation(), moves: [], completion: [] };
    } catch (failure) {
      error.textContent = failure instanceof Error ? failure.message : 'Check your choices.';
      return;
    }
    const sent = request;
    pending = true;
    error.textContent = '';
    prepare.disabled = true;
    cancel.disabled = true;
    fields.forEach(field => { field.disabled = true; });
    prepare.textContent = CONTROL_TEXT.pending;
    void submitRestStage(actorId, 'choices', { request: sent, prepare: true }).then(value => {
      const next = value as RestOffer;
      if (!next || typeof next.choiceVersion !== 'string' || !next.plan || !Array.isArray(next.moves)
          || !Array.isArray(next.preparation) || !Array.isArray(next.completion))
        throw new Error('The host returned invalid prepared downtime.');
      actions.replaceChildren(cancel);
      renderPlan(next, { request: sent, version: next.choiceVersion });
    }).catch((failure: unknown) => {
      error.textContent = failure instanceof Error ? failure.message : 'The rest could not be prepared.';
      if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500) {
        request = undefined;
        fields.forEach(field => { field.disabled = false; });
        prepare.textContent = 'Prepare rest';
      } else {
        prepare.textContent = 'Retry';
      }
    }).finally(() => {
      pending = false;
      prepare.disabled = false;
      cancel.disabled = false;
    });
  });
  return true;
}

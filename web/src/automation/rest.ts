import { ApiError, hostFetch } from '../api';
import { el } from '../login';
import { drawingId } from '../tools';
import { costLabel, inputFields, type RuleDescriptor, type RuleIntent } from './controls';
import { automationUrl, CONTROL_TEXT, errorDetails } from './panel';

type RestKind = 'short' | 'long';
type Selection = Pick<RuleIntent, 'sourceInstanceId' | 'ruleId' | 'bindingId' | 'eachId' | 'inputs'>;

interface MoveDescriptor extends RuleDescriptor {
  sourceName: string;
  /** The move does not use a downtime slot (a format-1 "additional" move). */
  freeSlot?: boolean;
  restMove: { id: string; kind: string; group?: string; ally?: boolean };
  options: RuleDescriptor[];
}

interface RestAdjustment { field?: string; by?: number; before?: number; after?: number; label?: string; sourceName?: string }

interface RestOffer {
  kind: RestKind;
  revision: number;
  offerVersion: string;
  postOutcomeChoices?: boolean;
  prepareBeforeMoves?: boolean;
  choiceVersion?: string;
  reports?: string[];
  previewError?: string;
  plan: { moves: number; longMoves: number; adjustments?: RestAdjustment[] };
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

/** One entry of a legacy move list; a self move and its ally twin share an entry like format 1. */
interface MoveEntry { key: string; label: string; self?: MoveDescriptor; ally?: MoveDescriptor; group: string; long: boolean }
interface RestSlot { label: string; entries: MoveEntry[] }
interface SlotDetail { key: string; target: string; node?: HTMLElement; fields: HTMLInputElement[]; invalid?: string;
  readInputs: () => RuleIntent['inputs']; readOptions: () => Selection[] }
interface RestPick { key: string; target: string; detail?: SlotDetail }

async function restOffer(actorId: string, kind: RestKind, preparation: Selection[] = []): Promise<RestOffer | null> {
  const query = new URLSearchParams({ kind });
  if (preparation.length) query.set('preparation', JSON.stringify(preparation));
  const response = await hostFetch(`${automationUrl(actorId)}/rest?${query}`, { credentials: 'same-origin', cache: 'no-store' });
  if (response.status === 404) return null;
  if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
  const data = await response.json() as RestOffer | { legacy: true };
  return 'legacy' in data ? null : data;
}

async function postRest(actorId: string, path: '' | '/choices' | '/complete', body: unknown): Promise<unknown> {
  const response = await hostFetch(`${automationUrl(actorId)}/rest${path}`, {
    method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
  return response.json();
}

const selectionKey = (item: Pick<Selection, 'sourceInstanceId' | 'ruleId' | 'bindingId' | 'eachId'>) =>
  `${item.sourceInstanceId}\u0000${item.ruleId}\u0000${item.bindingId}\u0000${item.eachId ?? ''}`;

/** Format-1 captions: source features read "Source: Label (cost)", core rules just "Label (cost)". */
function sourceLabel(descriptor: RuleDescriptor): string {
  const label = costLabel(descriptor);
  return descriptor.sourceName && !descriptor.sourceInstanceId.startsWith('ruleset:') ? `${descriptor.sourceName}: ${label}` : label;
}

/** Format-1 rest check rows; blocked options stay hidden like the legacy passives. */
function checkRows(container: HTMLElement, descriptors: RuleDescriptor[], fields: HTMLInputElement[],
                   checked?: Map<string, boolean>): { read: () => Selection[]; state: () => Map<string, boolean> } {
  const rows: Array<{ option: RuleDescriptor; toggle: HTMLInputElement; inputs: HTMLElement; read: () => RuleIntent['inputs'] }> = [];
  for (const option of descriptors) {
    if (!option.available) continue;
    const key = selectionKey(option);
    const toggle = el('input', { type: 'checkbox' });
    toggle.checked = checked?.get(key) ?? (!!option.defaultOn && !(option.exclusiveGroup
      && rows.some(row => row.toggle.checked && row.option.exclusiveGroup === option.exclusiveGroup)));
    const label = el('label', { class: 'dh-roll-option' }, toggle, el('span', {}, sourceLabel(option),
      ...(option.description ? [el('small', { class: 'muted' }, option.description)] : [])));
    fields.push(toggle);
    const inputs = el('div', { hidden: !toggle.checked });
    const read = inputFields(inputs, option.inputs ?? [], fields, CONTROL_TEXT);
    rows.push({ option, toggle, inputs, read });
    toggle.addEventListener('change', () => {
      inputs.hidden = !toggle.checked;
      if (!toggle.checked || !option.exclusiveGroup) return;
      for (const row of rows) if (row.toggle !== toggle && row.option.exclusiveGroup === option.exclusiveGroup) {
        row.toggle.checked = false;
        row.inputs.hidden = true;
      }
    });
    container.append(option.inputs?.length ? el('div', {}, label, inputs) : label);
  }
  return {
    read: () => rows.filter(row => row.toggle.checked).map(({ option, read }) => ({
      sourceInstanceId: option.sourceInstanceId, ruleId: option.ruleId, bindingId: option.bindingId,
      ...(option.eachId === undefined ? {} : { eachId: option.eachId }), inputs: read() })),
    state: () => new Map(rows.map(row => [selectionKey(row.option), row.toggle.checked])),
  };
}

function moveEntries(offer: RestOffer, kind: RestKind): { regular: MoveEntry[]; additional: MoveEntry[] } {
  const regular: MoveEntry[] = [], additional: MoveEntry[] = [];
  const byKey = new Map<string, MoveEntry>();
  for (const move of offer.moves) {
    if (!move.available) continue;
    const long = move.restMove.kind === 'longMove';
    if (kind === 'short' && long && offer.plan.longMoves <= 0) continue;
    const ally = move.targetSelection?.cardinality === 'one';
    if (ally && !move.targetSelection?.options.length) continue;
    const key = `${move.sourceInstanceId}\u0000${move.restMove.id}\u0000${move.freeSlot ? 'free' : 'slot'}`;
    let entry = byKey.get(key);
    if (entry && (ally ? entry.ally : entry.self)) entry = undefined;
    if (!entry) {
      entry = { key: byKey.has(key) ? `${key}\u0000${move.ruleId}` : key, label: '', group:
        `${move.sourceInstanceId}\u0000${move.restMove.group ?? move.restMove.id}`, long };
      byKey.set(entry.key, entry);
      (move.freeSlot ? additional : regular).push(entry);
    }
    if (ally) entry.ally = move; else entry.self = move;
    entry.label = sourceLabel(entry.self ?? move);
  }
  return { regular, additional };
}

function restSlots(offer: RestOffer, kind: RestKind): { base: number; slots: RestSlot[] } {
  const { regular, additional } = moveEntries(offer, kind);
  const extras: string[] = [];
  for (const adjustment of offer.plan.adjustments ?? []) {
    if (adjustment.field !== 'moves') continue;
    const delta = typeof adjustment.after === 'number' && typeof adjustment.before === 'number'
      ? adjustment.after - adjustment.before : adjustment.by ?? 0;
    for (let i = 0; i < delta; i++) extras.push(adjustment.sourceName || adjustment.label || 'Rest');
  }
  const total = Math.max(0, Math.min(64, offer.plan.moves));
  const extra = extras.slice(0, total);
  const base = total - extra.length;
  return { base, slots: [
    ...Array.from({ length: base }, (_, i) => ({ label: `Move ${i + 1}`, entries: regular })),
    ...extra.map(name => ({ label: `Extra move (${name})`, entries: regular })),
    ...additional.map(entry => ({ label: `Additional move (${entry.label})`, entries: [entry] })),
  ] };
}

/** Returns false only when the host explicitly uses the legacy rest workflow. */
export async function openAutomationRestDialog(actorId: string, kind: RestKind,
                                               before: () => Promise<void>): Promise<boolean> {
  let loaded: RestOffer | null;
  let loadError = '';
  try {
    await before();
    loaded = await restOffer(actorId, kind);
    if (!loaded) return false;
  } catch (failure) {
    loaded = null;
    loadError = failure instanceof Error ? failure.message : 'Could not load downtime.';
  }

  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  const body = el('div', { class: 'dh-rest-body dh-roll-options' });
  const error = el('div', { class: 'error', role: 'alert' });
  const goText = kind === 'long' ? 'Take long rest' : 'Take short rest';
  const go = el('button', { class: 'primary', type: 'button' }, goText);
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const acts = el('div', { class: 'acts' }, cancel, go);
  let onGo = () => {};
  go.addEventListener('click', () => onGo());
  let pending = false;
  const close = () => { backdrop.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape' && !pending) close(); };
  cancel.addEventListener('click', () => { if (!pending) close(); });
  backdrop.addEventListener('click', event => { if (!pending && event.target === backdrop) close(); });
  backdrop.append(el('div', { class: 'dh-rolldlg dh-restdlg dh-option-rolldlg' },
    el('h4', {}, kind === 'long' ? 'Long rest - downtime moves' : 'Short rest - downtime moves'), body, error, acts));
  document.addEventListener('keydown', onKey);
  document.body.append(backdrop);
  if (!loaded) {
    error.textContent = loadError;
    go.disabled = true;
    return true;
  }
  const offer = loaded;
  const intro = (base: number) => el('div', { class: 'muted' },
    `Each character gets ${base} downtime move${base === 1 ? '' : 's'}; the same move cannot be chosen twice. ${kind === 'long'
      ? 'Long rest: all rest abilities refresh.' : 'Short rest: short-rest abilities refresh.'}`);

  const renderPlan = (initial: RestOffer, prepared?: { request: RestRequest; version: string }) => {
    let current = initial;
    let previewPending = false;
    let previewFailure = '';
    const picks: RestPick[] = [];
    let locked = false;
    const passives = el('div', { class: 'dh-rest-passives' });
    const preparationRows = el('div');
    const completionRows = el('div');
    passives.append(preparationRows, completionRows);
    const preparationFields: HTMLInputElement[] = [];
    let completionFields: HTMLInputElement[] = [];
    const preparation = checkRows(preparationRows, prepared ? [] : initial.preparation, preparationFields);
    let completion = checkRows(completionRows, initial.completion, completionFields);
    for (const field of preparationFields) field.addEventListener('change', () => { void refresh(); });
    let completionSignature = initial.completion.map(selectionKey).join('\u0001');
    const offeredCompletion = new Map(initial.completion.map(option => [selectionKey(option), option]));
    let invalid = false;

    const detailFor = (entry: MoveEntry, pick: RestPick): SlotDetail => {
      if (pick.detail?.key === entry.key && pick.detail.target === pick.target) return pick.detail;
      const recipient = pick.target === 'self' ? undefined : entry.ally?.targetSelection?.cardinality === 'one'
        ? entry.ally.targetSelection.options.find(option => option.id === pick.target) : undefined;
      const move = recipient ? recipient.moveDescriptor ?? entry.ally! : entry.self ?? entry.ally!;
      const options = recipient ? recipient.downtimeOptions ?? entry.ally!.options : (entry.self ?? entry.ally!).options;
      const detail: SlotDetail = { key: entry.key, target: pick.target, fields: [],
        readInputs: () => ({}), readOptions: () => [] };
      if (!entry.self && !recipient) {
        detail.invalid = CONTROL_TEXT.selectOptions(1);
        return detail;
      }
      const node = el('div', { class: 'dh-mutagen-choices' });
      if (!move.available) {
        detail.invalid = move.reason ?? 'This move is unavailable.';
        node.append(el('div', { class: 'muted' }, detail.invalid));
      } else {
        detail.readInputs = inputFields(node, move.inputs ?? [], detail.fields, CONTROL_TEXT);
      }
      detail.readOptions = checkRows(node, options ?? [], detail.fields).read;
      if (node.children.length) detail.node = node;
      return detail;
    };

    const draw = () => {
      const { base, slots } = restSlots(current, kind);
      picks.length = slots.length;
      const rows = slots.map((slot, index) => {
        const pick = picks[index] ??= { key: '', target: '' };
        const entry = slot.entries.find(item => item.key === pick.key);
        if (!entry) { pick.key = ''; pick.target = ''; pick.detail = undefined; }
        const others = picks.filter((other, i) => i !== index && other.key)
          .map(other => slots[picks.indexOf(other)]?.entries.find(item => item.key === other.key))
          .filter((item): item is MoveEntry => !!item);
        const options = slot.entries.map(item => {
          const why = others.some(other => other.group === item.group) ? 'already chosen'
            : kind === 'short' && item.long && others.filter(other => other.long).length >= current.plan.longMoves
              ? current.plan.longMoves === 1 ? 'only one long-rest move per short rest'
                : `only ${current.plan.longMoves} long-rest moves per short rest` : '';
          const option = el('option', { value: item.key }, why ? `${item.label} (${why})` : item.label);
          option.disabled = !!why && item.key !== pick.key;
          return option;
        });
        const select = el('select', {}, el('option', { value: '' }, 'None'), ...options);
        select.value = pick.key;
        select.disabled = locked;
        select.addEventListener('change', () => {
          pick.key = select.value;
          const chosen = slot.entries.find(item => item.key === pick.key);
          pick.target = chosen?.self ? 'self' : '';
          pick.detail = undefined;
          draw();
        });
        const children: HTMLElement[] = [el('span', { class: 'muted' }, slot.label), select];
        if (entry?.ally?.targetSelection?.cardinality === 'one') {
          const target = el('select', { ariaLabel: 'Move target' },
            el('option', { value: entry.self ? 'self' : '' }, entry.self ? 'Self' : 'Choose an ally'),
            ...entry.ally.targetSelection.options.map(option => el('option', { value: option.id }, option.label)));
          target.value = pick.target;
          target.disabled = locked;
          target.addEventListener('change', () => { pick.target = target.value; draw(); });
          children.push(target);
        }
        if (entry) {
          pick.detail = detailFor(entry, pick);
          if (pick.detail.node) children.push(pick.detail.node);
        }
        return el('div', { class: 'dh-rest-slot' }, ...children);
      });
      invalid = picks.some(pick => pick.key && !!pick.detail?.invalid);
      const blocker = previewFailure || current.previewError;
      body.replaceChildren(
        intro(base),
        ...(kind === 'short' && current.plan.longMoves > 0 ? [el('div', { class: 'muted' }, current.plan.longMoves === 1
          ? 'You may choose one long-rest move during this short rest.'
          : `You may choose up to ${current.plan.longMoves} long-rest moves during this short rest.`)] : []),
        ...(prepared && initial.reports?.length ? initial.reports.map(report => el('div', { class: 'muted' }, report)) : []),
        ...(blocker ? [el('div', { class: 'error' }, blocker)] : []),
        ...rows,
        ...(preparationRows.children.length || completionRows.children.length ? [passives] : []),
      );
      go.disabled = pending || previewPending || !!blocker || invalid;
    };

    let previewSequence = 0;
    const refresh = async () => {
      let selections: Selection[];
      try { selections = preparation.read(); } catch { return; }
      const sequence = ++previewSequence;
      previewPending = true;
      go.disabled = true;
      try {
        const next = selections.length ? await restOffer(actorId, kind, selections) : initial;
        if (sequence !== previewSequence) return;
        if (!next) throw new Error('The host no longer offers this rest.');
        current = next;
        previewFailure = '';
        const signature = next.completion.map(selectionKey).join('\u0001');
        if (signature !== completionSignature) {
          const state = completion.state();
          completionRows.replaceChildren();
          completionFields = [];
          completion = checkRows(completionRows, next.completion, completionFields, state);
          for (const option of next.completion) offeredCompletion.set(selectionKey(option), option);
          completionSignature = signature;
        }
      } catch (failure) {
        if (sequence === previewSequence)
          previewFailure = failure instanceof Error ? failure.message : 'Could not preview this rest.';
      } finally {
        if (sequence === previewSequence) { previewPending = false; draw(); }
      }
    };
    let phase: 'plan' | 'beforeReset' | 'afterReset' = 'plan';
    let retry: RestRequest | undefined;
    let chosenCompletion: Selection[] = [];
    let choice: RestChoiceOffer | undefined;
    let beforeChoices: Selection[] = [];
    let readChoices: () => Selection[] = () => [];
    let stageFields: HTMLInputElement[] = [];
    let retryStage: { path: '/choices' | '/complete'; body: unknown } | undefined;

    const lockPlan = (value: boolean) => {
      locked = value;
      for (const field of [...preparationFields, ...completionFields, ...picks.flatMap(pick => pick.detail?.fields ?? [])])
        field.disabled = value;
      draw();
    };

    /** Applies the up-front completion choices to a host stage; only unforeseen choices need another prompt. */
    const stageSelections = (actions: RuleDescriptor[]) => {
      const auto: Selection[] = [], manual: RuleDescriptor[] = [];
      for (const action of actions) {
        if (!action.available) continue;
        const key = selectionKey(action);
        const offered = offeredCompletion.get(key);
        const chosen = chosenCompletion.find(selection => selectionKey(selection) === key);
        if (chosen && offered && JSON.stringify(offered.inputs ?? []) === JSON.stringify(action.inputs ?? [])) auto.push(chosen);
        else if (chosen || !offered) manual.push(action);
      }
      return { auto, manual };
    };

    const showStage = (next: RestChoiceOffer, auto: Selection[], manual: RuleDescriptor[]) => {
      stageFields = [];
      const rows = el('div', { class: 'dh-rest-passives' });
      const reader = checkRows(rows, manual, stageFields);
      readChoices = () => [...auto, ...reader.read()];
      body.replaceChildren(...next.reports.map(report => el('div', { class: 'muted' }, report)), rows);
    };

    const nextStage = (): { path: '/choices' | '/complete'; body: unknown } => {
      if (!retry) throw new Error('The rest request is missing.');
      if (phase === 'plan') return { path: '/choices', body: prepared
        ? { request: retry, preparedVersion: prepared.version } : { request: retry } };
      if (!choice) throw new Error('The rest choices are missing.');
      const selections = readChoices();
      if (phase === 'beforeReset')
        return { path: '/choices', body: { requestId: retry.requestId, choiceVersion: choice.choiceVersion, selections } };
      return { path: '/complete', body: { choiceVersion: choice.choiceVersion,
        request: { ...retry, completion: [...beforeChoices, ...selections] } } };
    };

    const performStages = async (): Promise<boolean> => {
      while (true) {
        if (!retryStage) retryStage = nextStage();
        const sent = retryStage;
        const sentPhase = phase;
        const response = await postRest(actorId, sent.path, sent.body);
        retryStage = undefined;
        if (sentPhase === 'afterReset') return true;
        if (sentPhase === 'beforeReset') beforeChoices = (sent.body as { selections: Selection[] }).selections;
        const next = response as RestChoiceOffer;
        if ((sentPhase === 'plan' && next.phase !== 'beforeReset')
            || (sentPhase === 'beforeReset' && next.phase !== 'afterReset')
            || !Array.isArray(next.actions) || !Array.isArray(next.reports)
            || !next.cleared || typeof next.choiceVersion !== 'string')
          throw new Error('The host returned invalid rest choices.');
        choice = next;
        phase = next.phase;
        const { auto, manual } = stageSelections(next.actions);
        if (!manual.length) { readChoices = () => auto; continue; }
        showStage(next, auto, manual);
        return false;
      }
    };

    onGo = () => {
      if (pending) return;
      try {
        if (!retry) {
          const chosen: RestRequest['moves'] = [];
          const { slots } = restSlots(current, kind);
          picks.forEach((pick, index) => {
            const entry = slots[index]?.entries.find(item => item.key === pick.key);
            if (!entry || !pick.detail) return;
            if (pick.detail.invalid) throw new Error(pick.detail.invalid);
            const recipient = pick.target !== 'self' && entry.ally?.targetSelection?.cardinality === 'one'
              ? entry.ally.targetSelection.options.find(option => option.id === pick.target) : undefined;
            const move = recipient ? entry.ally! : entry.self!;
            chosen.push({ selection: { sourceInstanceId: move.sourceInstanceId, ruleId: move.ruleId, bindingId: move.bindingId,
              ...(move.eachId === undefined ? {} : { eachId: move.eachId }), inputs: pick.detail.readInputs() },
              options: pick.detail.readOptions(),
              ...(recipient ? { target: { recipientId: recipient.id, version: recipient.version } } : {}) });
          });
          chosenCompletion = completion.read();
          retry = { requestId: prepared?.request.requestId ?? drawingId(), offerVersion: current.offerVersion, kind,
            expectedRevision: current.revision, preparation: prepared?.request.preparation ?? preparation.read(),
            moves: chosen, completion: current.postOutcomeChoices ? [] : chosenCompletion };
        }
      } catch (failure) {
        error.textContent = failure instanceof Error ? failure.message : 'Check your choices.';
        return;
      }
      pending = true;
      error.textContent = '';
      go.disabled = cancel.disabled = true;
      if (phase === 'plan') lockPlan(true);
      else for (const field of stageFields) field.disabled = true;
      const request = retry;
      const submission = current.postOutcomeChoices ? performStages() : postRest(actorId, '', { request }).then(() => true);
      void submission.then(done => {
        pending = false;
        if (done) { close(); return; }
        go.disabled = cancel.disabled = false;
      }).catch((failure: unknown) => {
        pending = false;
        error.textContent = failure instanceof Error ? failure.message : 'Resting failed.';
        go.disabled = cancel.disabled = false;
        // A rejected request may be corrected; an uncertain response must resend the identical request.
        if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500) {
          retryStage = undefined;
          if (phase === 'plan') {
            retry = undefined;
            lockPlan(false);
          } else {
            for (const field of stageFields) field.disabled = false;
          }
        }
      });
    };

    draw();
    if (!prepared && preparation.read().length) void refresh();
  };

  if (!offer.prepareBeforeMoves) {
    renderPlan(offer);
    go.focus();
    return true;
  }

  const fields: HTMLInputElement[] = [];
  const rows = el('div', { class: 'dh-rest-passives' });
  const preparation = checkRows(rows, offer.preparation, fields);
  body.replaceChildren(el('div', { class: 'muted' }, 'Prepare the rest before choosing downtime moves.'), rows);
  go.textContent = 'Prepare rest';
  let request: RestRequest | undefined;
  onGo = () => {
    if (pending) return;
    try {
      request ??= { requestId: drawingId(), offerVersion: offer.offerVersion, kind, expectedRevision: offer.revision,
        preparation: preparation.read(), moves: [], completion: [] };
    } catch (failure) {
      error.textContent = failure instanceof Error ? failure.message : 'Check your choices.';
      return;
    }
    const sent = request;
    pending = true;
    error.textContent = '';
    go.disabled = cancel.disabled = true;
    fields.forEach(field => { field.disabled = true; });
    void postRest(actorId, '/choices', { request: sent, prepare: true }).then(value => {
      const next = value as RestOffer;
      if (!next || typeof next.choiceVersion !== 'string' || !next.plan || !Array.isArray(next.moves)
          || !Array.isArray(next.preparation) || !Array.isArray(next.completion))
        throw new Error('The host returned invalid prepared downtime.');
      pending = false;
      go.textContent = goText;
      cancel.disabled = false;
      renderPlan(next, { request: sent, version: next.choiceVersion });
    }).catch((failure: unknown) => {
      pending = false;
      error.textContent = failure instanceof Error ? failure.message : 'The rest could not be prepared.';
      go.disabled = cancel.disabled = false;
      if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500) {
        request = undefined;
        fields.forEach(field => { field.disabled = false; });
      }
    });
  };
  go.focus();
  return true;
}

import { el } from '../login';
import { drawingId as randomId } from '../tools';

export type RuleInput =
  | { id: string; kind: 'integer'; label: string; min: number; max: number; default?: number }
  | { id: string; kind: 'die.select'; label: string; count: number; min?: number; default?: string[];
      choices: Array<{ id: string; label: string; sides: number; face: number }> }
  | { id: string; kind: 'choice'; label: string; count: number; options: Array<{ id: string; label: string }> }
  | { id: string; kind: 'allocation'; label: string; total: number; max: number; options: Array<{ id: string; label: string }> };

export interface RuleDescriptor {
  sourceId: string;
  sourceInstanceId: string;
  eachId?: string;
  ruleId: string;
  bindingId: string;
  revision: number;
  surface: 'sheet' | 'chat' | 'roll' | 'damage' | 'effect' | 'resource' | 'rest' | 'downtime' | 'creation' | 'companion';
  mode: 'button' | 'option' | 'automatic';
  defaultOn?: boolean;
  exclusiveGroup?: string;
  repeatable?: boolean;
  group?: 'result' | 'sourceFeature' | 'otherFeatures';
  label: string;
  description?: string;
  available: boolean;
  reason?: string;
  reasonCode?: string;
  costsRequireInput?: boolean;
  inputs?: RuleInput[];
  targetSelection?:
    | { cardinality: 'one'; options: Array<{ id: string; label: string; version: string; downtimeOptions?: RuleDescriptor[] }> }
    | { cardinality: 'all'; version: string; options: Array<{ id: string; label: string }> };
}

export interface RuleIntent {
  requestId: string;
  sourceId: string;
  sourceInstanceId: string;
  eachId?: string;
  ruleId: string;
  bindingId: string;
  expectedRevision: number;
  inputs: Record<string, number | string | string[] | Record<string, number>>;
  target?: { version: string; recipientId?: string };
}

export interface ControlText {
  cancel: string;
  retry: string;
  pending: string;
  failed: string;
  recipient: string;
  recipients: string;
  wholeNumber: (min: number, max: number) => string;
  selectDice: (count: number, min?: number) => string;
  selectOptions: (count: number) => string;
  allocate: (total: number, max: number) => string;
}

/** Renders host-described buttons only; roll options belong to the enclosing roll transaction. */
export function ruleControl(descriptor: RuleDescriptor, submit: (intent: RuleIntent) => Promise<void>, text: ControlText): HTMLElement {
  if (descriptor.mode !== 'button') throw new Error('Only button bindings are independent actions; options belong to the enclosing roll transaction.');
  return intentControl(descriptor, intent => submit({
    ...intent, sourceId: descriptor.sourceId, sourceInstanceId: descriptor.sourceInstanceId,
    ...(descriptor.eachId === undefined ? {} : { eachId: descriptor.eachId }),
    ruleId: descriptor.ruleId, bindingId: descriptor.bindingId,
  }), text);
}

type RollSelection = Pick<RuleIntent, 'sourceInstanceId' | 'ruleId' | 'bindingId' | 'eachId' | 'inputs'>;
type ButtonPayload = Pick<RuleIntent, 'inputs' | 'target'> & { selections?: RollSelection[] };
type ButtonIntent = Pick<RuleIntent, 'requestId' | 'expectedRevision'> & ButtonPayload;
type ButtonDescriptor = Pick<RuleDescriptor, 'revision' | 'label' | 'description' | 'available' | 'reason' | 'inputs' | 'targetSelection'>
  & { options?: RuleDescriptor[] };

export function intentControl(descriptor: ButtonDescriptor, submit: (intent: ButtonIntent) => Promise<void>, text: ControlText): HTMLElement {
  const error = el('div', { class: 'error', role: 'alert' });
  const reason = el('span', { class: 'muted' }, descriptor.available ? '' : descriptor.reason ?? '');
  const button = el('button', { type: 'button', class: 'small', disabled: !descriptor.available,
    title: descriptor.description ?? descriptor.reason ?? '' }, descriptor.label);
  const root = el('div', { class: 'dh' }, button, reason, error);
  const selection = descriptor.targetSelection;
  const options = descriptor.options ?? [];
  const hasChoices = options.some(option => option.available);
  if (!hasChoices) for (const option of options)
    root.append(el('span', { class: 'muted' }, `${option.label}: ${option.reason ?? ''}`));
  const party = selection?.cardinality === 'all' ? { version: selection.version } : undefined;
  if (selection?.cardinality === 'all') root.append(el('span', { class: 'muted' },
    `${text.recipients}: ${selection.options.map(option => option.label).join(', ')}`));
  let pending = false;
  let completed = false;
  let retryIntent: ButtonIntent | undefined;
  let activeDialog: HTMLDialogElement | undefined;

  const send = async (payload: ButtonPayload, go?: HTMLButtonElement, cancel?: HTMLButtonElement,
    dialogError?: HTMLElement, fields: HTMLInputElement[] = []): Promise<void> => {
    if (pending || completed) return;
    retryIntent ??= {
      requestId: randomId(), expectedRevision: descriptor.revision, ...structuredClone(payload),
    };
    pending = true;
    error.textContent = '';
    if (dialogError) dialogError.textContent = '';
    button.disabled = true;
    if (cancel) cancel.disabled = true;
    if (go) { go.disabled = true; go.textContent = text.pending; }
    for (const field of fields) field.disabled = true;
    try {
      await submit(structuredClone(retryIntent));
      completed = true;
      activeDialog?.close();
    } catch (failure) {
      const message = failure instanceof Error ? failure.message : text.failed;
      error.textContent = message;
      if (dialogError) dialogError.textContent = message;
      button.textContent = text.retry;
      if (go) { go.textContent = text.retry; go.disabled = false; }
      // An uncertain response must retry the identical intent, not spend again under a fresh ID.
    } finally {
      pending = false;
      button.disabled = completed || activeDialog !== undefined;
      if (cancel) cancel.disabled = false;
    }
  };

  button.addEventListener('click', () => {
    if (pending || completed || !descriptor.available || activeDialog) return;
    if (retryIntent) { void send(retryIntent); return; }
    const definitions = descriptor.inputs ?? [];
    if (!definitions.length && selection?.cardinality !== 'one' && !hasChoices) {
      void send({ inputs: {}, ...(party ? { target: party } : {}) }); return;
    }
    const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: descriptor.label });
    activeDialog = dialog;
    button.disabled = true;
    const fields: HTMLInputElement[] = [];
    dialog.append(el('h4', {}, descriptor.label));
    let readTarget = (): RuleIntent['target'] => party;
    if (selection?.cardinality === 'one') readTarget = targetFields(dialog, selection, fields, text);
    const readInputs = inputFields(dialog, definitions, fields, text);
    const readOptions = optionFields(dialog, options, fields, text);
    const dialogError = el('div', { class: 'error', role: 'alert' });
    const cancel = el('button', { type: 'button' }, text.cancel);
    const go = el('button', { type: 'button', class: 'primary' }, descriptor.label);
    cancel.addEventListener('click', () => { if (!pending) dialog.close(); });
    dialog.addEventListener('cancel', event => { if (pending) event.preventDefault(); });
    dialog.addEventListener('close', () => {
      activeDialog = undefined;
      dialog.remove();
      button.disabled = pending || completed;
      button.focus();
    }, { once: true });
    go.addEventListener('click', () => {
      if (pending || completed) return;
      let payload: ButtonPayload;
      try {
        if (retryIntent) payload = retryIntent;
        else {
          const target = readTarget();
          payload = { inputs: readInputs(), ...(target ? { target } : {}) };
          if (options.length) payload.selections = readOptions();
        }
      } catch (failure) {
        dialogError.textContent = failure instanceof Error ? failure.message : text.failed;
        return;
      }
      void send(payload, go, cancel, dialogError, fields);
    });
    dialog.append(dialogError, el('div', { class: 'acts' }, cancel, go));
    document.body.append(dialog);
    dialog.showModal();
    fields.find(field => !field.disabled)?.focus();
  });
  return root;
}

export function targetFields(container: HTMLElement, selection: RuleDescriptor['targetSelection'],
                             fields: HTMLInputElement[], text: ControlText,
                             onSelection?: (recipientId: string) => void): () => RuleIntent['target'] {
  if (!selection) return () => undefined;
  if (selection.cardinality === 'all') {
    container.append(el('span', { class: 'muted' },
      `${text.recipients}: ${selection.options.map(option => option.label).join(', ')}`));
    return () => ({ version: selection.version });
  }
  const group = el('fieldset', {}, el('legend', {}, text.recipient));
  const name = `recipient-${randomId()}`;
  const radios = selection.options.map(option => {
    const field = el('input', { type: 'radio', name });
    field.addEventListener('change', () => { if (field.checked) onSelection?.(option.id); });
    fields.push(field);
    group.append(el('label', {}, field, option.label));
    return { field, option };
  });
  container.append(group);
  return () => {
    const chosen = radios.find(row => row.field.checked);
    if (!chosen) throw new Error(text.selectOptions(1));
    return { recipientId: chosen.option.id, version: chosen.option.version };
  };
}

export function optionFields(container: HTMLElement, options: RuleDescriptor[], fields: HTMLInputElement[],
                             text: ControlText): () => RollSelection[] {
  const rows: Array<{ option: RuleDescriptor; toggle: HTMLInputElement; inputs: HTMLElement; read: () => RuleIntent['inputs'] }> = [];
  for (const option of options) {
    const toggle = el('input', { type: 'checkbox', disabled: !option.available,
      checked: !!option.defaultOn && option.available && !(option.exclusiveGroup &&
        rows.some(row => row.toggle.checked && row.option.exclusiveGroup === option.exclusiveGroup)) });
    const legend = el('legend', {}, el('label', {}, toggle, option.label));
    const group = el('fieldset', {}, legend);
    if (option.description) group.append(el('p', {}, option.description));
    if (!option.available && option.reason) group.append(el('span', { class: 'muted' }, option.reason));
    const inputs = el('div', { hidden: !toggle.checked });
    fields.push(toggle);
    const read = inputFields(inputs, option.inputs ?? [], fields, text);
    rows.push({ option, toggle, inputs, read });
    toggle.addEventListener('change', () => {
      inputs.hidden = !toggle.checked;
      if (!toggle.checked || !option.exclusiveGroup) return;
      for (const row of rows) if (row.toggle !== toggle && row.option.exclusiveGroup === option.exclusiveGroup) {
        row.toggle.checked = false;
        row.inputs.hidden = true;
      }
    });
    group.append(inputs);
    container.append(group);
  }
  return () => rows.filter(row => row.toggle.checked && row.option.available)
    .map(({ option, read }) => ({ sourceInstanceId: option.sourceInstanceId, ruleId: option.ruleId,
      bindingId: option.bindingId, ...(option.eachId === undefined ? {} : { eachId: option.eachId }), inputs: read() }));
}

export function inputFields(container: HTMLElement, definitions: RuleInput[], fields: HTMLInputElement[], text: ControlText): () => RuleIntent['inputs'] {
    const reads: Array<() => [string, RuleIntent['inputs'][string]]> = [];
    for (const input of definitions) {
      if (input.kind === 'choice') {
        const single = input.count === 1;
        const group = el('fieldset', {}, el('legend', {}, input.label));
        const name = `choice-${randomId()}`;
        const checks = input.options.map(option => {
          const field = el('input', { type: single ? 'radio' : 'checkbox', name });
          fields.push(field);
          group.append(el('label', {}, field, option.label));
          return { field, id: option.id };
        });
        container.append(group);
        reads.push(() => {
          const selected = checks.filter(option => option.field.checked).map(option => option.id);
          if (selected.length !== input.count) throw new Error(text.selectOptions(input.count));
          return [input.id, single ? selected[0] : selected];
        });
      } else if (input.kind === 'allocation') {
        const group = el('fieldset', {}, el('legend', {}, input.label));
        const amounts = input.options.map(option => {
          const field = el('input', { type: 'number', min: '0', max: String(input.max), step: '1', value: '0', ariaLabel: option.label });
          fields.push(field);
          group.append(el('label', {}, option.label, field));
          return { field, id: option.id };
        });
        container.append(group);
        reads.push(() => {
          const allocation: Record<string, number> = {};
          let sum = 0;
          for (const { field, id } of amounts) {
            const value = field.valueAsNumber;
            if (!Number.isSafeInteger(value) || value < 0 || value > input.max) throw new Error(text.allocate(input.total, input.max));
            if (value) allocation[id] = value;
            sum += value;
          }
          if (sum !== input.total) throw new Error(text.allocate(input.total, input.max));
          return [input.id, allocation];
        });
      } else if (input.kind === 'integer') {
        const field = el('input', { type: 'number', min: String(input.min), max: String(input.max), step: '1',
          value: String(input.default ?? input.min), ariaLabel: input.label });
        fields.push(field);
        container.append(el('label', {}, input.label, field));
        reads.push(() => {
          const value = field.valueAsNumber;
          if (!Number.isSafeInteger(value) || value < input.min || value > input.max)
            throw new Error(text.wholeNumber(input.min, input.max));
          return [input.id, value];
        });
      } else {
        const group = el('fieldset', {}, el('legend', {}, input.label));
        const checks = input.choices.map(choice => {
          const field = el('input', { type: 'checkbox', checked: input.default?.includes(choice.id) ?? false });
          fields.push(field);
          group.append(el('label', {}, field, choice.label));
          return { field, id: choice.id };
        });
        container.append(group);
        reads.push(() => {
          const selected = checks.filter(choice => choice.field.checked).map(choice => choice.id);
          if (selected.length < (input.min ?? input.count) || selected.length > input.count)
            throw new Error(text.selectDice(input.count, input.min));
          return [input.id, selected];
        });
      }
    }
    return () => Object.fromEntries(reads.map(read => read()));
}

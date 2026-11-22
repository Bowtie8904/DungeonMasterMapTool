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
  surface: 'sheet' | 'chat' | 'roll' | 'damage' | 'effect' | 'resource' | 'rest';
  mode: 'button' | 'option' | 'automatic';
  group?: 'result' | 'sourceFeature' | 'otherFeatures';
  label: string;
  description?: string;
  available: boolean;
  reason?: string;
  reasonCode?: string;
  costsRequireInput?: boolean;
  inputs?: RuleInput[];
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
}

export interface ControlText {
  cancel: string;
  retry: string;
  pending: string;
  failed: string;
  wholeNumber: (min: number, max: number) => string;
  selectDice: (count: number, min?: number) => string;
  selectOptions: (count: number) => string;
  allocate: (total: number, max: number) => string;
}

/** Renders host-described buttons only; roll options belong to the enclosing roll transaction. */
export function ruleControl(descriptor: RuleDescriptor, submit: (intent: RuleIntent) => Promise<void>, text: ControlText): HTMLElement {
  if (descriptor.mode !== 'button') throw new Error('Only button bindings are independent actions; options belong to the enclosing roll transaction.');
  const error = el('div', { class: 'error', role: 'alert' });
  const reason = el('span', { class: 'muted' }, descriptor.available ? '' : descriptor.reason ?? '');
  const button = el('button', { type: 'button', class: 'small', disabled: !descriptor.available,
    title: descriptor.description ?? descriptor.reason ?? '' }, descriptor.label);
  const root = el('div', { class: 'dh' }, button, reason, error);
  let pending = false;
  let completed = false;
  let retryIntent: RuleIntent | undefined;
  let activeDialog: HTMLDialogElement | undefined;

  const send = async (inputs: RuleIntent['inputs'], go?: HTMLButtonElement, cancel?: HTMLButtonElement,
    dialogError?: HTMLElement, fields: HTMLInputElement[] = []): Promise<void> => {
    if (pending || completed) return;
    retryIntent ??= {
      requestId: randomId(), sourceId: descriptor.sourceId, sourceInstanceId: descriptor.sourceInstanceId,
      ...(descriptor.eachId === undefined ? {} : { eachId: descriptor.eachId }),
      ruleId: descriptor.ruleId, bindingId: descriptor.bindingId, expectedRevision: descriptor.revision,
      inputs: structuredClone(inputs),
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
    if (retryIntent) { void send(retryIntent.inputs); return; }
    const definitions = descriptor.inputs ?? [];
    if (!definitions.length) { void send({}); return; }
    const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: descriptor.label });
    activeDialog = dialog;
    button.disabled = true;
    const fields: HTMLInputElement[] = [];
    const reads: Array<() => [string, RuleIntent['inputs'][string]]> = [];
    dialog.append(el('h4', {}, descriptor.label));
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
        dialog.append(group);
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
        dialog.append(group);
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
        dialog.append(el('label', {}, input.label, field));
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
        dialog.append(group);
        reads.push(() => {
          const selected = checks.filter(choice => choice.field.checked).map(choice => choice.id);
          if (selected.length < (input.min ?? input.count) || selected.length > input.count)
            throw new Error(text.selectDice(input.count, input.min));
          return [input.id, selected];
        });
      }
    }
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
      let inputs: RuleIntent['inputs'];
      try { inputs = retryIntent?.inputs ?? Object.fromEntries(reads.map(read => read())); }
      catch (failure) {
        dialogError.textContent = failure instanceof Error ? failure.message : text.failed;
        return;
      }
      void send(inputs, go, cancel, dialogError, fields);
    });
    dialog.append(dialogError, el('div', { class: 'acts' }, cancel, go));
    document.body.append(dialog);
    dialog.showModal();
    fields[0]?.focus();
  });
  return root;
}

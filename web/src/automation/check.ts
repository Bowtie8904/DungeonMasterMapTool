import { ApiError, hostFetch } from '../api';
import { el } from '../login';
import { drawingId } from '../tools';
import type { TraitName } from '../daggerheart/data';
import { inputFields, type RollSelection, type RuleDescriptor, type RuleRecordValue } from './controls';
import { automationUrl, CONTROL_TEXT, errorDetails } from './panel';

type CheckKind = 'action' | 'reaction';
export interface ReactionPromptRef { id: string | number; ts: number }

interface CheckSummary { modifier: number; mode: RollMode; dice: string[]; applied: string[] }

interface CheckOffer {
  revision: number;
  label: string;
  options: Array<RuleDescriptor & { value?: number; dice?: string[]; suggestsMode?: RollMode }>;
  /** Host dry-run without options: the automatic modifier and bonus names of the format-1 heading. */
  base?: CheckSummary;
  /** Host dry-run of the requested selections. */
  preview?: CheckSummary;
  previewError?: string;
}

const signed = (value: number): string => value >= 0 ? `+${value}` : String(value);
const signedDice = (dice: string): string => dice.startsWith('-') ? dice : `+${dice}`;
const previewText = (summary: CheckSummary): string =>
  `Modifier ${signed(summary.modifier)}${summary.dice.length ? `; extra dice ${summary.dice.join(', ')}` : ''}`;

type RollMode = 'normal' | 'advantage' | 'disadvantage';
const MODES: Array<[RollMode, string, string]> = [
  ['advantage', 'Advantage (+d6)', 'adv'], ['normal', 'Normal', 'norm'], ['disadvantage', 'Disadvantage (-d6)', 'dis'],
];

interface RollControls { read: () => RollSelection[]; suggest: (mode: RollMode) => void; blocker: () => string | undefined }

/** Names an option the format-1 way, e.g. "Minor Stamina Potion - Drink"; the sheet supplies the feature origin. */
export type OptionLabel = (option: RuleDescriptor) => string;

type CheckOption = CheckOffer['options'][number];

const quantityInput = (option: CheckOption) => option.inputs?.length === 1 && option.inputs[0].kind === 'integer'
  ? option.inputs[0] : undefined;

/** Format-1 option note: what selecting it costs or uses up. */
function optionNote(option: CheckOption): string {
  const cost = option.unitCost ? `${option.unitCost} per ${option.costPer ?? 'token'}` : option.cost;
  return [cost ? `costs ${cost} when selected` : '',
    option.consumes ? `uses up 1 ${option.consumes.name} (${option.consumes.left} left)` : ''].filter(Boolean).join('; ');
}

/** Adept-like options replace the Experience cost: same exclusive group, only the Experience record to choose. */
const replacesExperienceCost = (option: CheckOption, experience: CheckOption | undefined) => !!experience?.exclusiveGroup
  && option.exclusiveGroup === experience.exclusiveGroup && option.inputs?.length === 1
  && option.inputs[0].kind === 'record.select';

/**
 * Renders the host-described roll options in the established roll dialog layout: advantage buttons, an Experience
 * dropdown and a checklist of bonuses. The two core ruleset options are presented as those controls but are still
 * submitted as the host's own selections. {@code changed} runs after every edit so the host preview can refresh.
 */
function rollControls(container: HTMLElement, options: CheckOffer['options'], fields: HTMLInputElement[],
                      typeRow: HTMLElement | undefined, changed: () => void,
                      optionLabel: OptionLabel = option => option.label): RollControls {
  const manual = options.find(option => option.ruleId === 'manual-roll-mode');
  const experience = options.find(option => option.ruleId === 'experience');
  const rest = options.filter(option => option !== manual && option !== experience);
  const adept = rest.find(option => replacesExperienceCost(option, experience));
  const reads: Array<() => RollSelection[]> = [];
  let suggest: (mode: RollMode) => void = () => undefined;
  let release = (): void => undefined;
  let chosenExperience = (): RuleRecordValue | undefined => undefined;
  let adeptChecked = (): boolean => false;
  if (manual) {
    let mode: RollMode = 'normal';
    let touched = false;
    const paint = (shown: RollMode) => buttons.forEach((other, index) => {
      other.className = `dh-adv-btn ${MODES[index][2]}${MODES[index][0] === shown ? ' on' : ''}`;
    });
    const buttons = MODES.map(([value, text, cls]) => {
      const button = el('button', { type: 'button', class: `dh-adv-btn ${cls}${value === mode ? ' on' : ''}` }, text);
      button.disabled = !manual.available;
      button.addEventListener('click', () => {
        mode = value;
        touched = true;
        paint(mode);
        changed();
      });
      return button;
    });
    // Like format 1, options that grant or impose advantage re-highlight the mode the host will resolve.
    suggest = next => {
      if (touched) return;
      mode = next;
      paint(mode);
    };
    release = () => { touched = false; };
    fields.push(...buttons as unknown as HTMLInputElement[]);
    container.append(el('div', { class: 'dh-adv-row' }, el('span', {}, 'Roll with'),
      el('div', { class: 'dh-adv', role: 'group', ariaLabel: 'Roll with' }, ...buttons)));
    reads.push(() => !touched ? [] : [{ sourceInstanceId: manual.sourceInstanceId, ruleId: manual.ruleId,
      bindingId: manual.bindingId, inputs: { mode } }]);
  }
  if (typeRow) container.append(typeRow);
  if (experience) {
    const records = experience.inputs?.find(input => input.kind === 'record.select');
    const choices = records?.kind === 'record.select' ? records.options : [];
    const select = el('select', { ariaLabel: 'Experience' }, el('option', { value: '' }, 'None'),
      ...choices.map((choice, index) => el('option', { value: String(index) }, choice.label)));
    select.disabled = !experience.available;
    fields.push(select as unknown as HTMLInputElement);
    const spend = experience.cost;
    container.append(el('label', { class: 'dh-roll-field' }, spend && adept?.cost
      ? `Experience (${spend}, or ${adept.cost} with ${adept.label})` : spend ? `Experience (spend ${spend})` : 'Experience', select));
    if (!experience.available && experience.reason) container.append(el('small', { class: 'error' }, experience.reason));
    chosenExperience = () => select.value === '' ? undefined : choices[Number(select.value)]?.value;
    reads.push(() => {
      const chosen = chosenExperience();
      if (!chosen || !records || adeptChecked()) return [];
      return [{ sourceInstanceId: experience.sourceInstanceId, ruleId: experience.ruleId, bindingId: experience.bindingId,
        inputs: { [records.id]: { ...chosen } } }];
    });
  }
  if (rest.length) {
    const set = el('fieldset', {}, el('legend', {}, 'Optional bonuses (select all that apply)'));
    const rows: Array<{ option: CheckOffer['options'][number]; box: HTMLInputElement; extra: HTMLElement; read: () => RollSelection['inputs'] }> = [];
    for (const option of rest) {
      const box = el('input', { type: 'checkbox', disabled: !option.available,
        checked: !!option.defaultOn && option.available && !(option.exclusiveGroup
          && rows.some(row => row.box.checked && row.option.exclusiveGroup === option.exclusiveGroup)) });
      const quantity = quantityInput(option);
      const note = optionNote(option);
      const text = el('span', {}, `${optionLabel(option)}${option.value ? ` (${signed(option.value)}${quantity?.id === 'quantity' ? ' per token' : ''})` : ''}${option.dice?.length
        ? ` ${option.dice.map(signedDice).join('')}` : ''}${note ? `; ${note}` : ''}`);
      if (option.description) text.append(el('small', { class: 'muted' }, option.description));
      if (!option.available && option.reason) text.append(el('small', { class: 'error' }, option.reason));
      let extra: HTMLElement;
      let read: () => RollSelection['inputs'];
      if (option === adept) {
        // Like format 1, Adept is a plain checkbox that doubles the Experience chosen in the dropdown.
        extra = el('div', { hidden: true });
        const id = option.inputs![0].id;
        read = () => {
          const chosen = chosenExperience();
          if (!chosen) throw new Error(`Select an Experience to use ${option.label}`);
          return { [id]: { ...chosen } };
        };
        adeptChecked = () => box.checked && option.available;
      } else if (quantity) {
        // Format-1 quantity control: always shown beside the option.
        const amount = el('input', { type: 'number', step: '1', min: String(quantity.min), max: String(quantity.max),
          value: String(quantity.default ?? quantity.min), ariaLabel: quantity.label });
        fields.push(amount);
        extra = el('label', { class: 'dh-roll-option-control' }, el('span', {}, quantity.label), amount);
        read = () => {
          const value = amount.valueAsNumber;
          if (!Number.isInteger(value) || value < quantity.min || value > quantity.max)
            throw new Error(`${quantity.label} must be between ${quantity.min} and ${quantity.max}.`);
          return { [quantity.id]: value };
        };
      } else {
        extra = el('div', { class: 'dh-roll-option-control', hidden: !box.checked });
        read = inputFields(extra, option.inputs ?? [], fields, CONTROL_TEXT);
      }
      const toggles = option !== adept && !quantity;
      fields.push(box);
      rows.push({ option, box, extra, read });
      box.addEventListener('change', () => {
        if (toggles) extra.hidden = !box.checked;
        if (option.suggestsMode) release();
        if (!box.checked || !option.exclusiveGroup) return;
        for (const row of rows) if (row.box !== box && row.option.exclusiveGroup === option.exclusiveGroup) {
          row.box.checked = false;
          if (row.option !== adept && !quantityInput(row.option)) row.extra.hidden = true;
        }
      });
      set.append(el('div', { class: 'dh-roll-option-row' }, el('label', { class: 'dh-roll-option' }, box, text),
        ...(option.inputs?.length && option !== adept ? [extra] : [])));
    }
    container.append(el('div', { class: 'dh-roll-options' }, set));
    reads.push(() => rows.filter(row => row.box.checked && row.option.available).map(({ option, read }) => ({
      sourceInstanceId: option.sourceInstanceId, ruleId: option.ruleId, bindingId: option.bindingId,
      ...(option.eachId === undefined ? {} : { eachId: option.eachId }), inputs: read() })));
  }
  container.addEventListener('change', changed);
  container.addEventListener('input', changed);
  const blocker = () => adeptChecked() && !chosenExperience() ? `Select an Experience to use ${adept!.label}` : undefined;
  return { read: () => reads.flatMap(read => read()), suggest, blocker };
}

/** Returns false only when the host explicitly uses legacy rolls. */
export async function openAutomationCheckDialog(actorId: string, kind: CheckKind, trait: TraitName,
                                                label: string, before: () => Promise<void>,
                                                prompt?: ReactionPromptRef, heading = label,
                                                optionLabel?: OptionLabel): Promise<boolean> {
  // Same markup as the format-1 roll dialog: a backdrop div around div.dh-rolldlg.dh-option-rolldlg.
  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  const content = el('div', {}, el('p', { role: 'status' }, 'Loading roll options...'));
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const go = el('button', { type: 'button', class: 'primary', disabled: true }, 'Roll to chat');
  const actions = el('div', { class: 'acts' }, cancel, go);
  let currentKind = kind;
  let loading = true;
  let offer: CheckOffer | undefined;
  let selected: ReturnType<typeof rollControls> | undefined;
  let fields: HTMLInputElement[] = [];
  let retry: object | undefined;
  const modeButtons = kind === 'action' ? (['action', 'reaction'] as const).map(value =>
    el('button', { type: 'button', class: 'dh-adv-btn norm', ariaPressed: String(value === kind) },
      value === 'action' ? 'Normal roll' : 'Reaction roll')) : [];
  const modes = modeButtons.length
    ? el('div', { class: 'dh-adv-row' }, el('span', {}, 'Roll type'),
      el('div', { class: 'dh-adv dh-roll-type', role: 'group', ariaLabel: 'Roll type' }, ...modeButtons))
    : undefined;
  const title = el('h4', {}, heading);
  const applied = el('div', { class: 'muted', hidden: true });
  const preview = el('div', { class: 'muted' });
  backdrop.append(el('div', { class: 'dh-rolldlg dh-option-rolldlg' }, title, applied,
    el('div', { class: 'muted' }, 'Duality roll: Hope d12 + Fear d12 + modifier'), content, preview, error, actions));
  let previewSequence = 0;
  let previewTimer: ReturnType<typeof setTimeout> | undefined;
  const showSummary = (current: CheckOffer, summary: CheckSummary | undefined) => {
    preview.textContent = summary ? previewText(summary) : '';
    if (summary) selected?.suggest(summary.mode);
    if (current.previewError) error.textContent = current.previewError;
    go.disabled = !!current.previewError || blocked();
  };
  let blockText = '';
  /** Format-1 blockers such as Adept without an Experience: shown inline and the roll button is disabled. */
  const blocked = (): boolean => {
    const text = selected?.blocker() ?? '';
    if (text) error.textContent = text;
    else if (blockText && error.textContent === blockText) error.textContent = '';
    blockText = text;
    return !!text;
  };
  const refreshPreview = () => {
    if (previewTimer !== undefined) clearTimeout(previewTimer);
    if (blocked()) { go.disabled = true; return; }
    if (offer && !offer.previewError && !pending && !loading) go.disabled = false;
    previewTimer = setTimeout(() => void (async () => {
      previewTimer = undefined;
      const current = offer;
      if (!current || !selected || closed || pending || loading) return;
      let selections: RollSelection[];
      try {
        selections = selected.read();
      } catch {
        return;
      }
      const sequence = ++previewSequence;
      try {
        const query = new URLSearchParams({ kind: currentKind, trait, selections: JSON.stringify(selections) });
        const response = await hostFetch(`${automationUrl(actorId)}/check?${query}`, {
          credentials: 'same-origin', cache: 'no-store',
        });
        if (!response.ok || sequence !== previewSequence || closed || offer !== current) return;
        const result = await response.json() as CheckOffer;
        if (sequence !== previewSequence || closed || offer !== current || pending) return;
        error.textContent = '';
        showSummary(result, result.preview ?? result.base);
      } catch {
        // The preview is informative only; rolling still validates on the host.
      }
    })(), 150);
  };
  let pending = false;
  let closed = false;
  const onKey = (event: KeyboardEvent): void => { if (event.key === 'Escape' && !pending) close(); };
  const close = (): void => {
    if (closed) return;
    closed = true;
    if (previewTimer !== undefined) clearTimeout(previewTimer);
    document.removeEventListener('keydown', onKey);
    backdrop.remove();
  };
  backdrop.addEventListener('click', event => { if (event.target === backdrop && !pending) close(); });
  cancel.addEventListener('click', () => { if (!pending) close(); });
  document.addEventListener('keydown', onKey);
  document.body.append(backdrop);

  const updateModes = () => {
    modeButtons.forEach((button, index) => {
      const active = (index === 0 ? 'action' : 'reaction') === currentKind;
      button.className = `dh-adv-btn norm${active ? ' on' : ''}`;
      button.ariaPressed = String(active);
      button.disabled = pending || loading || retry !== undefined;
    });
  };
  const load = async (nextKind: CheckKind, initial: boolean): Promise<boolean> => {
    loading = true;
    currentKind = nextKind;
    go.disabled = true;
    error.textContent = '';
    fields.forEach(field => { field.disabled = true; });
    if (initial) content.replaceChildren(el('p', { role: 'status' }, 'Loading roll options...'));
    updateModes();
    try {
      const query = new URLSearchParams({ kind: currentKind, trait });
      const response = await hostFetch(`${automationUrl(actorId)}/check?${query}`, {
        credentials: 'same-origin', cache: 'no-store',
      });
      if (closed) return true;
      if (response.status === 404 && initial) { close(); return false; }
      if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
      const result = await response.json() as CheckOffer | { legacy: true };
      if ('legacy' in result && result.legacy === true && initial) { close(); return false; }
      if (!('revision' in result) || !Number.isSafeInteger(result.revision)
          || typeof result.label !== 'string' || !Array.isArray(result.options))
        throw new Error('The host returned an invalid roll offer.');
      offer = result;
      fields = [];
      content.replaceChildren();
      previewSequence++;
      selected = rollControls(content, offer.options, fields, modes, refreshPreview, optionLabel);
      title.replaceChildren(offer.base ? `${label} (${signed(offer.base.modifier)})` : heading);
      applied.textContent = offer.base?.applied.length ? `Applied: ${offer.base.applied.join(', ')}.` : '';
      applied.hidden = !offer.base?.applied.length;
      showSummary(offer, offer.base);
      if (offer.options.some(option => option.available && option.defaultOn && option.ruleId !== 'manual-roll-mode'))
        refreshPreview();
      go.disabled = false;
    } catch (failure) {
      if (!closed) {
        offer = undefined;
        selected = undefined;
        content.replaceChildren();
        preview.textContent = '';
        error.textContent = failure instanceof Error ? failure.message : 'Could not load roll options.';
        cancel.textContent = 'Close';
      }
    } finally {
      loading = false;
      updateModes();
    }
    return true;
  };
  modeButtons.forEach((button, index) => button.addEventListener('click', () => {
    const next = index === 0 ? 'action' : 'reaction';
    if (!pending && !loading && !retry && next !== currentKind) void load(next, false);
  }));
  updateModes();
  try {
    await before();
  } catch (failure) {
    if (!closed) {
      content.replaceChildren();
      error.textContent = failure instanceof Error ? failure.message : 'Could not load roll options.';
      cancel.textContent = 'Close';
    }
    return true;
  }
  if (closed) return true;
  if (!await load(kind, true)) return false;
  if (closed) return true;
  go.addEventListener('click', () => {
    if (pending || loading || closed || !offer || !selected) return;
    if (blocked()) { go.disabled = true; return; }
    try {
      retry ??= { requestId: drawingId(), expectedRevision: offer.revision,
        checkRoll: { kind: currentKind, trait, ...(prompt ? { prompt } : {}) }, selections: selected.read() };
    } catch (failure) {
      error.textContent = failure instanceof Error ? failure.message : CONTROL_TEXT.failed;
      return;
    }
    pending = true;
    error.textContent = '';
    go.disabled = cancel.disabled = true;
    updateModes();
    fields.forEach(field => { field.disabled = true; });
    void (async () => {
      try {
        const response = await hostFetch(automationUrl(actorId), {
          method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ request: retry }),
        });
        if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
        close();
      } catch (failure) {
        error.textContent = failure instanceof Error ? failure.message : CONTROL_TEXT.failed;
      } finally {
        pending = false;
        go.disabled = cancel.disabled = false;
        updateModes();
      }
    })();
  });
  return true;
}

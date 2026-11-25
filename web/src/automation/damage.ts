import { ApiError, hostFetch } from '../api';
import { el } from '../login';
import { drawingId } from '../tools';
import { inputFields, type RollSelection, type RuleDescriptor } from './controls';
import { automationUrl, CONTROL_TEXT, errorDetails, executeDamage } from './panel';

interface Applied {
  amount: number; incoming?: number; severity: number; hitPoints: number; wouldMark?: number;
  armorSpent?: number; armorMarked: number; armorSaved?: number; hitPointsRemaining: number; armorRemaining: number;
}
interface DamageOffer {
  revision: number;
  thresholds: { major: number; severe: number };
  armorTrack?: { marked: number; capacity: number };
  marks?: { armor?: { allowed?: number } };
  applied: Applied;
  previewError?: string;
  stages: Record<string, { actions: RuleDescriptor[] }>;
}

const TYPES = ['physical', 'magical'];
const SEVERITY_LABELS = ['None', 'Minor', 'Major', 'Severe', 'Massive'];

async function fetchOffer(actorId: string, amount: number, armor: number, type: string,
                          selections: RollSelection[] = []): Promise<DamageOffer | 'legacy'> {
  const query = new URLSearchParams({ amount: String(amount), armor: String(armor), type });
  if (selections.length) query.set('selections', JSON.stringify(selections));
  const response = await hostFetch(`${automationUrl(actorId)}/damage?${query}`, { credentials: 'same-origin', cache: 'no-store' });
  if (response.status === 404) return 'legacy';
  if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
  const result = await response.json() as DamageOffer | { legacy: true };
  if ('legacy' in result && result.legacy === true) return 'legacy';
  const offer = result as DamageOffer;
  if (!Number.isSafeInteger(offer.revision) || !offer.applied || !offer.stages) throw new Error('The host returned invalid damage options.');
  return offer;
}

const key = (option: RuleDescriptor) => `${option.sourceInstanceId}|${option.ruleId}|${option.bindingId}|${option.eachId ?? ''}`;

/** The format-1 " - source (cost - uses)" annotation of a mitigation row. */
function meta(option: RuleDescriptor): string {
  const bits = [option.cost ?? '', option.uses ? `${option.uses.left}/${option.uses.max} left` : ''].filter(Boolean);
  return bits.join(' - ');
}

interface Row { option: RuleDescriptor; box: HTMLInputElement; text: HTMLElement; row: HTMLElement; extra: HTMLElement;
  read: () => RollSelection['inputs'] }

/**
 * The format-1 incoming-damage dialog fed by the host: same markup, with mitigation rows, the automatic list and the
 * preview taken from a host dry-run of the selected mitigation. Returns false only for legacy hosts.
 */
export async function openAutomationDamageDialog(actorId: string, before: () => Promise<void>,
                                                 onApplied?: () => void): Promise<boolean> {
  let startError = '';
  try {
    await before();
    if (await fetchOffer(actorId, 0, 0, TYPES[0]) === 'legacy') return false;
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) return false;
    startError = failure instanceof Error ? failure.message : 'Could not load damage options.';
  }
  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  const dialog = el('div', { class: 'dh-rolldlg dh-damage-dlg dh-option-rolldlg', role: 'dialog' });
  dialog.setAttribute('aria-label', 'Take damage');
  const amount = el('input', { type: 'number', min: '0', step: '1', value: '' });
  let damageType = TYPES[0];
  const typeButtons = TYPES.map(id => el('button', { type: 'button', class: `dh-adv-btn norm${id === damageType ? ' on' : ''}` },
    id === 'physical' ? 'Physical' : 'Magic'));
  const thresholds = el('div', { class: 'muted' });
  const body = el('div', { class: 'dh-dmg-body dh-roll-options' });
  const preview = el('div', { class: 'dh-dmg-preview' });
  preview.setAttribute('aria-live', 'polite');
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const apply = el('button', { class: 'primary', type: 'button', disabled: true }, 'Apply');
  const armorBox = el('input', { type: 'checkbox' });
  const armorCount = el('input', { type: 'number', min: '1', value: '1' });
  dialog.append(el('h4', {}, 'Take damage'), thresholds, el('label', {}, 'Damage taken', amount),
    el('div', { class: 'dh-dmg-type' }, ...typeButtons), body, preview, error, el('div', { class: 'acts' }, cancel, apply));
  backdrop.append(dialog);

  let pending = false, closed = false, sequence = 0, armorAllowed = 1;
  let offer: DamageOffer | undefined, retry: Parameters<typeof executeDamage>[1] | undefined;
  const rows = new Map<string, Row>();
  const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape' && !pending) close(); };
  const close = () => {
    if (closed) return;
    closed = true;
    document.removeEventListener('keydown', onKey);
    backdrop.remove();
  };
  backdrop.addEventListener('click', event => { if (event.target === backdrop && !pending) close(); });
  cancel.addEventListener('click', () => { if (!pending) close(); });

  const chosen = () => [...rows.values()].filter(row => row.box.checked && row.option.available);
  const selections = (strict: boolean): RollSelection[] => chosen().flatMap(({ option, read }) => {
    let inputs: RollSelection['inputs'];
    try { inputs = read(); } catch (failure) { if (strict) throw failure; return []; }
    return [{ sourceInstanceId: option.sourceInstanceId, ruleId: option.ruleId, bindingId: option.bindingId,
      ...(option.eachId === undefined ? {} : { eachId: option.eachId }), inputs }];
  });
  const request = () => ({ amount: Math.trunc(Number(amount.value || 0)),
    armor: armorBox.checked ? armorAllowed > 1 ? Math.max(1, Math.trunc(Number(armorCount.value) || 1)) : 1 : 0,
    type: damageType });

  const mitigationRow = (option: RuleDescriptor): Row => {
    const existing = rows.get(key(option));
    if (existing) {
      existing.option = option;
      return existing;
    }
    const box = el('input', { type: 'checkbox', checked: !!option.defaultOn && option.available });
    const text = el('span');
    const extra = el('div', { class: 'dh-roll-option-control' });
    const read = inputFields(extra, option.inputs ?? [], [], CONTROL_TEXT);
    const row = el('div', { class: 'dh-roll-option-row' }, el('label', { class: 'dh-roll-option' }, box, text),
      ...(option.inputs?.length ? [extra] : []));
    const entry: Row = { option, box, text, row, extra, read };
    box.addEventListener('change', () => {
      if (box.checked && entry.option.exclusiveGroup)
        for (const other of rows.values())
          if (other !== entry && other.option.exclusiveGroup === entry.option.exclusiveGroup) other.box.checked = false;
      refresh();
    });
    extra.addEventListener('change', refresh);
    rows.set(key(option), entry);
    return entry;
  };

  const render = (result: DamageOffer, intent: ReturnType<typeof request>) => {
    const applied = result.applied;
    thresholds.textContent = `Thresholds: Minor < ${result.thresholds.major} \u2264 Major < ${result.thresholds.severe} \u2264 Severe; ${result.thresholds.severe * 2}+ marks 4 HP`;
    armorAllowed = Math.max(1, result.marks?.armor?.allowed ?? 1);
    const track = result.armorTrack;
    const free = track ? Math.max(0, track.capacity - track.marked) : 0;
    const armorWhy = track && free <= 0 ? 'No free Armor Slots' : '';
    armorBox.disabled = !!armorWhy && !armorBox.checked;
    armorCount.max = String(Math.max(1, Math.min(armorAllowed, free)));
    const armorText = el('span', {}, armorAllowed > 1 ? 'Mark Armor Slots' : 'Mark an Armor Slot',
      el('small', { class: 'muted' }, `Reduces severity by one per slot${track ? ` (${free}/${track.capacity} free)` : ''}`));
    if (armorWhy) armorText.append(el('small', { class: 'error' }, armorWhy));
    const armorRow = el('div', { class: 'dh-roll-option-row' }, el('label', { class: 'dh-roll-option' }, armorBox, armorText));
    if (armorAllowed > 1 && armorBox.checked)
      armorRow.append(el('label', { class: 'dh-roll-option-control' }, el('span', {}, 'Armor Slots'), armorCount));
    body.replaceChildren(armorRow);

    const actions = Object.values(result.stages).flatMap(stage => stage.actions);
    const choices = actions.filter(action => action.mode !== 'automatic');
    const passives = actions.filter(action => action.mode === 'automatic');
    const live = new Set(choices.map(key));
    for (const stale of [...rows.keys()]) if (!live.has(stale)) rows.delete(stale);
    if (choices.length) {
      const set = el('fieldset', {}, el('legend', {}, 'Mitigation (select all that apply)'));
      for (const option of choices) {
        const entry = mitigationRow(option);
        if (!option.available) entry.box.checked = false;
        entry.box.disabled = !option.available;
        const info = meta(option);
        entry.text.replaceChildren(el('b', {}, option.label),
          el('span', { class: 'muted' }, ` - ${option.sourceName ?? ''}${info ? ` (${info})` : ''}`));
        if (option.description) entry.text.append(el('small', { class: 'muted' }, option.description));
        if (!option.available && option.reason) entry.text.append(el('small', { class: 'error' }, option.reason));
        entry.extra.hidden = !entry.box.checked;
        set.append(entry.row);
      }
      body.append(set);
    }
    if (passives.length) {
      const list = el('div', { class: 'dh-dmg-passive' }, el('div', { class: 'muted' }, 'Applied automatically'));
      for (const option of passives) {
        const info = meta(option);
        list.append(el('div', { class: option.available ? 'on' : 'muted' },
          `${option.available ? '\u2713' : '\u2013'} ${option.label}${option.sourceName ? ` (${option.sourceName})` : ''}${info ? ` - ${info}` : ''}${!option.available && option.reason ? ` - ${option.reason}` : ''}`));
      }
      body.append(list);
    }

    const negated = intent.amount > 0 && applied.amount === 0;
    const incoming = applied.incoming ?? applied.severity;
    const severity = negated ? 'Negated' : incoming === applied.severity ? SEVERITY_LABELS[incoming]
      : `${SEVERITY_LABELS[incoming]} \u2192 ${SEVERITY_LABELS[applied.severity]}`;
    const extras: string[] = [];
    const spent = applied.armorSpent ?? applied.armorMarked;
    if (spent) extras.push(`${applied.armorMarked} Armor Slot${applied.armorMarked === 1 ? '' : 's'} marked${applied.armorMarked !== spent ? ` (${spent - applied.armorMarked} saved)` : ''}`);
    const costs = chosen().map(row => row.option.cost).filter(Boolean);
    if (costs.length) extras.push(`pay ${costs.join(', ')}`);
    if (applied.wouldMark !== undefined && applied.wouldMark !== applied.hitPoints && applied.hitPoints === 0)
      extras.push(`instead of ${applied.wouldMark} HP`);
    preview.replaceChildren(
      el('div', { class: 'muted' }, intent.amount
        ? `${intent.amount} damage${applied.amount !== intent.amount ? ` \u2192 ${applied.amount}` : ''} \u00b7 ${severity}`
        : 'Enter the damage you took'),
      el('div', { class: 'dh-dmg-hp' }, `Mark ${applied.hitPoints} HP`),
      ...(extras.length ? [el('div', { class: 'muted' }, extras.join(' \u00b7 '))] : []));
  };

  const load = async (notice = '') => {
    const current = ++sequence;
    apply.disabled = true;
    retry = undefined;
    const intent = request();
    if (!Number.isSafeInteger(intent.amount) || intent.amount < 0) {
      error.textContent = 'Enter whole numbers of zero or more.';
      return;
    }
    try {
      const result = await fetchOffer(actorId, intent.amount, intent.armor, intent.type, selections(false));
      if (current !== sequence || closed) return;
      if (result === 'legacy') throw new Error('The host no longer supports automated damage. Reload the page.');
      offer = result;
      render(result, intent);
      error.textContent = notice || result.previewError || '';
      apply.disabled = intent.amount <= 0 || !!result.previewError;
    } catch (failure) {
      if (current === sequence) error.textContent = failure instanceof Error ? failure.message : 'Could not load damage options.';
    }
  };
  const refresh = () => { if (!pending) void load(); };
  amount.addEventListener('input', refresh);
  armorCount.addEventListener('change', refresh);
  armorBox.addEventListener('change', refresh);
  typeButtons.forEach((button, index) => button.addEventListener('click', () => {
    damageType = TYPES[index];
    typeButtons.forEach((other, at) => { other.className = `dh-adv-btn norm${at === index ? ' on' : ''}`; });
    refresh();
  }));
  apply.addEventListener('click', async () => {
    if (pending || !offer) return;
    try {
      retry ??= { requestId: drawingId(), expectedRevision: offer.revision, selections: selections(true), damage: request() };
    } catch (failure) {
      error.textContent = failure instanceof Error ? failure.message : 'Could not apply damage.';
      return;
    }
    pending = true;
    apply.disabled = cancel.disabled = true;
    error.textContent = '';
    try {
      await executeDamage(actorId, retry);
      close();
      onApplied?.();
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 409) {
        pending = false;
        await load(failure.message);
      } else {
        error.textContent = failure instanceof Error ? failure.message : 'Could not apply damage.';
        apply.disabled = false;
      }
    } finally {
      pending = false;
      cancel.disabled = false;
    }
  });
  document.addEventListener('keydown', onKey);
  document.body.append(backdrop);
  amount.focus();
  if (startError) error.textContent = startError;
  else void load();
  return true;
}

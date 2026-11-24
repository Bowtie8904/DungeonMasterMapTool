import { ApiError, hostFetch } from '../api';
import { el } from '../login';
import { drawingId } from '../tools';
import { optionFields, type RollSelection, type RuleDescriptor } from './controls';
import { automationUrl, errorDetails, executeDamage } from './panel';

interface Applied { amount: number; severity: number; hitPoints: number; armorMarked: number; hitPointsRemaining: number; armorRemaining: number }
interface DamageOffer {
  revision: number;
  thresholds: { major: number; severe: number };
  applied: Applied;
  stages: Record<string, { actions: RuleDescriptor[] }>;
}

const STAGE_LABELS: Record<string, string> = {
  'damage.prepare': 'Before thresholds', 'damage.marks': 'Marking', 'damage.applied': 'After damage',
};
const TYPES = ['physical', 'magical'];

async function fetchOffer(actorId: string, amount: number, armor: number, type: string): Promise<DamageOffer | 'legacy'> {
  const query = new URLSearchParams({ amount: String(amount), armor: String(armor), type });
  const response = await hostFetch(`${automationUrl(actorId)}/damage?${query}`, { credentials: 'same-origin', cache: 'no-store' });
  if (response.status === 404) return 'legacy';
  if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
  const result = await response.json() as DamageOffer | { legacy: true };
  if ('legacy' in result && result.legacy === true) return 'legacy';
  const offer = result as DamageOffer;
  if (!Number.isSafeInteger(offer.revision) || !offer.applied || !offer.stages) throw new Error('The host returned invalid damage options.');
  return offer;
}

/** Returns false only when the host explicitly uses the legacy damage calculator. */
export async function openAutomationDamageDialog(actorId: string, before: () => Promise<void>,
                                                 onApplied?: () => void): Promise<boolean> {
  try {
    await before();
    if (await fetchOffer(actorId, 0, 0, TYPES[0]) === 'legacy') return false;
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) return false;
    openMessage(failure instanceof Error ? failure.message : 'Could not load damage options.');
    return true;
  }
  const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: 'Take damage' });
  const amount = el('input', { type: 'number', min: '0', value: '0', ariaLabel: 'Incoming damage' });
  const armor = el('input', { type: 'number', min: '0', value: '0', ariaLabel: 'Armor Slots to mark' });
  const type = el('select', { ariaLabel: 'Damage type' }, ...TYPES.map(id => el('option', { value: id }, id)));
  const detail = el('div', {});
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const preview = el('button', { type: 'button' }, 'Preview');
  const apply = el('button', { type: 'button', class: 'primary', disabled: true }, 'Take damage');
  dialog.append(el('h3', {}, 'Take damage'),
    el('label', {}, 'Incoming damage ', amount), el('label', {}, ' Armor Slots ', armor), el('label', {}, ' Type ', type),
    detail, error, el('div', { class: 'acts' }, cancel, preview, apply));
  document.body.append(dialog);
  let pending = false, offer: DamageOffer | undefined, retry: Parameters<typeof executeDamage>[1] | undefined;
  let readSelections: () => RollSelection[] = () => [];
  dialog.addEventListener('cancel', event => { if (pending) event.preventDefault(); });
  dialog.addEventListener('close', () => dialog.remove(), { once: true });
  cancel.addEventListener('click', () => { if (!pending) dialog.close(); });

  const request = () => ({ amount: Math.trunc(Number(amount.value)), armor: Math.trunc(Number(armor.value)), type: type.value });
  const load = async (notice = '') => {
    error.textContent = '';
    apply.disabled = true;
    retry = undefined;
    const intent = request();
    if (!Number.isSafeInteger(intent.amount) || intent.amount < 0 || !Number.isSafeInteger(intent.armor) || intent.armor < 0) {
      error.textContent = 'Enter whole numbers of zero or more.';
      return;
    }
    try {
      const result = await fetchOffer(actorId, intent.amount, intent.armor, intent.type);
      if (result === 'legacy') throw new Error('The host no longer supports automated damage. Reload the page.');
      offer = result;
      const applied = result.applied;
      detail.replaceChildren(el('p', { role: 'status' },
        `Thresholds ${result.thresholds.major}/${result.thresholds.severe}. Unmitigated: ${applied.amount} damage is severity ${applied.severity}: `
        + `${applied.hitPoints} Hit Point${applied.hitPoints === 1 ? '' : 's'} before ${applied.armorMarked} Armor Slot${applied.armorMarked === 1 ? '' : 's'}.`));
      const groups: Array<() => RollSelection[]> = [];
      for (const [stage, { actions }] of Object.entries(result.stages)) {
        const choices = actions.filter(action => action.mode !== 'automatic');
        if (!choices.length) continue;
        const group = el('fieldset', {}, el('legend', {}, STAGE_LABELS[stage] ?? stage));
        groups.push(optionFields(group, choices, [], {
          cancel: 'Cancel', retry: 'Retry', pending: 'Working...', failed: 'Failed.', recipient: 'Recipient', recipients: 'Recipients',
          wholeNumber: (min, max) => `Enter a whole number from ${min} to ${max}.`,
          selectDice: count => `Select ${count} dice.`, selectOptions: count => `Select ${count} options.`,
          allocate: (total, max) => `Allocate ${total} (up to ${max} each).`,
        }));
        detail.append(group);
      }
      readSelections = () => groups.flatMap(read => read());
      apply.disabled = false;
      error.textContent = notice;
    } catch (failure) {
      error.textContent = failure instanceof Error ? failure.message : 'Could not load damage options.';
    }
  };
  preview.addEventListener('click', () => { if (!pending) void load(); });
  apply.addEventListener('click', async () => {
    if (pending || !offer) return;
    pending = true;
    apply.disabled = preview.disabled = cancel.disabled = true;
    error.textContent = '';
    try {
      retry ??= { requestId: drawingId(), expectedRevision: offer.revision, selections: readSelections(), damage: request() };
      await executeDamage(actorId, retry);
      dialog.close();
      onApplied?.();
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 409) await load(failure.message);
      else {
        error.textContent = failure instanceof Error ? failure.message : 'Could not apply damage.';
        apply.textContent = 'Retry';
        apply.disabled = false;
      }
    } finally {
      pending = false;
      preview.disabled = cancel.disabled = false;
    }
  });
  dialog.showModal();
  return true;
}

function openMessage(message: string): void {
  const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: 'Take damage' });
  const close = el('button', { type: 'button' }, 'Close');
  close.addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => dialog.remove(), { once: true });
  dialog.append(el('h3', {}, 'Take damage'), el('div', { class: 'error', role: 'alert' }, message), el('div', { class: 'acts' }, close));
  document.body.append(dialog);
  dialog.showModal();
}

import { ApiError, hostFetch } from '../api';
import { el } from '../login';
import { drawingId } from '../tools';
import type { TraitName } from '../daggerheart/data';
import { optionFields, type RuleDescriptor } from './controls';
import { automationUrl, CONTROL_TEXT, errorDetails } from './panel';

type CheckKind = 'action' | 'reaction';

interface CheckOffer {
  revision: number;
  label: string;
  options: RuleDescriptor[];
}

/** Returns false only when the host explicitly uses legacy rolls. */
export async function openAutomationCheckDialog(actorId: string, kind: CheckKind, trait: TraitName,
                                                label: string, before: () => Promise<void>): Promise<boolean> {
  const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: label });
  const content = el('div', {}, el('p', { role: 'status' }, 'Loading roll options...'));
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const actions = el('div', { class: 'acts' }, cancel);
  dialog.append(el('h3', {}, label), content, error, actions);
  document.body.append(dialog);
  let pending = false;
  let closed = false;
  dialog.addEventListener('cancel', event => { if (pending) event.preventDefault(); });
  dialog.addEventListener('close', () => { closed = true; dialog.remove(); }, { once: true });
  cancel.addEventListener('click', () => { if (!pending) dialog.close(); });
  dialog.showModal();

  let offer: CheckOffer;
  try {
    await before();
    if (closed) return true;
    const query = new URLSearchParams({ kind, trait });
    const response = await hostFetch(`${automationUrl(actorId)}/check?${query}`, {
      credentials: 'same-origin', cache: 'no-store',
    });
    if (closed) return true;
    if (response.status === 404) { dialog.close(); return false; }
    if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
    const result = await response.json() as CheckOffer | { legacy: true };
    if ('legacy' in result && result.legacy === true) { dialog.close(); return false; }
    if (!('revision' in result) || !Number.isSafeInteger(result.revision)
        || typeof result.label !== 'string' || !Array.isArray(result.options))
      throw new Error('The host returned an invalid roll offer.');
    offer = result;
  } catch (failure) {
    if (closed) return true;
    content.replaceChildren();
    error.textContent = failure instanceof Error ? failure.message : 'Could not load roll options.';
    cancel.textContent = 'Close';
    return true;
  }
  if (closed) return true;

  content.replaceChildren(el('p', {}, offer.label));
  const fields: HTMLInputElement[] = [];
  const selected = optionFields(content, offer.options, fields, CONTROL_TEXT);
  const go = el('button', { type: 'button', class: 'primary' }, 'Roll');
  actions.append(go);
  let retry: object | undefined;
  go.addEventListener('click', () => {
    if (pending || closed) return;
    try {
      retry ??= { requestId: drawingId(), expectedRevision: offer.revision,
        checkRoll: { kind, trait }, selections: selected() };
    } catch (failure) {
      error.textContent = failure instanceof Error ? failure.message : CONTROL_TEXT.failed;
      return;
    }
    pending = true;
    error.textContent = '';
    go.disabled = cancel.disabled = true;
    fields.forEach(field => { field.disabled = true; });
    void (async () => {
      try {
        const response = await hostFetch(automationUrl(actorId), {
          method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ request: retry }),
        });
        if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
        dialog.close();
      } catch (failure) {
        error.textContent = failure instanceof Error ? failure.message : CONTROL_TEXT.failed;
        go.textContent = 'Retry';
      } finally {
        pending = false;
        go.disabled = cancel.disabled = false;
      }
    })();
  });
  return true;
}

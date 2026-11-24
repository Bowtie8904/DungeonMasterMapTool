import { ApiError, hostFetch } from '../api';
import { el } from '../login';
import { drawingId } from '../tools';
import type { TraitName } from '../daggerheart/data';
import { optionFields, type RuleDescriptor } from './controls';
import { automationUrl, CONTROL_TEXT, errorDetails } from './panel';

type CheckKind = 'action' | 'reaction';
export interface ReactionPromptRef { id: string | number; ts: number }

interface CheckOffer {
  revision: number;
  label: string;
  options: RuleDescriptor[];
}

/** Returns false only when the host explicitly uses legacy rolls. */
export async function openAutomationCheckDialog(actorId: string, kind: CheckKind, trait: TraitName,
                                                label: string, before: () => Promise<void>,
                                                prompt?: ReactionPromptRef): Promise<boolean> {
  const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: label });
  const content = el('div', {}, el('p', { role: 'status' }, 'Loading roll options...'));
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const actions = el('div', { class: 'acts' }, cancel);
  const go = el('button', { type: 'button', class: 'primary', disabled: true }, 'Roll');
  let currentKind = kind;
  let loading = true;
  let offer: CheckOffer | undefined;
  let selected: ReturnType<typeof optionFields> | undefined;
  let fields: HTMLInputElement[] = [];
  let retry: object | undefined;
  const modeButtons = kind === 'action' ? (['action', 'reaction'] as const).map(value =>
    el('button', { type: 'button', class: 'dh-adv-btn norm', ariaPressed: String(value === kind) },
      value === 'action' ? 'Action' : 'Reaction')) : [];
  const modes = modeButtons.length
    ? el('div', { class: 'dh-adv-row' }, el('span', {}, 'Roll type'),
      el('div', { class: 'dh-adv dh-roll-type', role: 'group', ariaLabel: 'Roll type' }, ...modeButtons))
    : undefined;
  dialog.append(el('h3', {}, label), ...(modes ? [modes] : []), content, error, actions);
  document.body.append(dialog);
  let pending = false;
  let closed = false;
  dialog.addEventListener('cancel', event => { if (pending) event.preventDefault(); });
  dialog.addEventListener('close', () => { closed = true; dialog.remove(); }, { once: true });
  cancel.addEventListener('click', () => { if (!pending) dialog.close(); });
  dialog.showModal();

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
    content.replaceChildren(el('p', { role: 'status' }, 'Loading roll options...'));
    updateModes();
    try {
      const query = new URLSearchParams({ kind: currentKind, trait });
      const response = await hostFetch(`${automationUrl(actorId)}/check?${query}`, {
        credentials: 'same-origin', cache: 'no-store',
      });
      if (closed) return true;
      if (response.status === 404 && initial) { dialog.close(); return false; }
      if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
      const result = await response.json() as CheckOffer | { legacy: true };
      if ('legacy' in result && result.legacy === true && initial) { dialog.close(); return false; }
      if (!('revision' in result) || !Number.isSafeInteger(result.revision)
          || typeof result.label !== 'string' || !Array.isArray(result.options))
        throw new Error('The host returned an invalid roll offer.');
      offer = result;
      fields = [];
      content.replaceChildren(el('p', {}, offer.label));
      selected = optionFields(content, offer.options, fields, CONTROL_TEXT);
      go.disabled = false;
    } catch (failure) {
      if (!closed) {
        offer = undefined;
        selected = undefined;
        content.replaceChildren();
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
  actions.append(go);
  go.addEventListener('click', () => {
    if (pending || loading || closed || !offer || !selected) return;
    try {
      retry ??= { requestId: drawingId(), expectedRevision: offer.revision,
        checkRoll: { kind: currentKind, trait, ...(prompt ? { prompt } : {}) }, selections: selected() };
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
        dialog.close();
      } catch (failure) {
        error.textContent = failure instanceof Error ? failure.message : CONTROL_TEXT.failed;
        go.textContent = 'Retry';
      } finally {
        pending = false;
        go.disabled = cancel.disabled = false;
        updateModes();
      }
    })();
  });
  return true;
}

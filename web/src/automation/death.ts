import { ApiError, hostFetch } from '../api';
import { el } from '../login';
import { intentControl, ruleControl, type RuleDescriptor } from './controls';
import { automationUrl, CONTROL_TEXT, errorDetails, executeDeathOption, executeDeathRoll } from './panel';

interface DeathOffer {
  revision: number;
  actions: RuleDescriptor[];
  rolls?: Record<string, { label: string; description?: string; options: RuleDescriptor[] }>;
}

/** Returns false only when the host explicitly uses legacy death moves. */
export async function openAutomationDeathDialog(actorId: string, before: () => Promise<void>): Promise<boolean> {
  const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: 'Death move' });
  const content = el('div', {}, el('p', { role: 'status' }, 'Loading death options...'));
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  dialog.append(el('h3', {}, 'Death move'), content, error, el('div', { class: 'acts' }, cancel));
  document.body.append(dialog);
  let closed = false, pending = false;
  let retryId: string | undefined;
  dialog.addEventListener('cancel', event => { if (pending) event.preventDefault(); });
  dialog.addEventListener('close', () => { closed = true; dialog.remove(); }, { once: true });
  cancel.addEventListener('click', () => { if (!pending) dialog.close(); });
  dialog.showModal();

  const load = async (initial: boolean, notice = ''): Promise<boolean> => {
    try {
      const response = await hostFetch(`${automationUrl(actorId)}/death`, {
        credentials: 'same-origin', cache: 'no-store',
      });
      if (closed) return true;
      if (response.status === 404 && initial) { dialog.close(); return false; }
      if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
      const result = await response.json() as DeathOffer | { legacy: true };
      if ('legacy' in result && result.legacy === true && initial) { dialog.close(); return false; }
      if (!('revision' in result) || !Number.isSafeInteger(result.revision) || !Array.isArray(result.actions))
        throw new Error('The host returned invalid death options.');
      const buttons = result.actions.filter(action => action.mode === 'button');
      const controls: HTMLElement[] = [];
      for (const [move, roll] of Object.entries(result.rolls ?? {})) {
        const control = intentControl({ revision: result.revision, label: roll.label, description: roll.description,
          available: true, options: roll.options }, async ({ requestId, expectedRevision, selections = [] }) => {
          if (retryId && retryId !== requestId) throw new Error('Finish the pending death option first.');
          retryId = requestId;
          controls.forEach(other => { other.inert = other !== control; });
          pending = true;
          cancel.disabled = true;
          try {
            await executeDeathRoll(actorId, move, { requestId, expectedRevision, selections });
            dialog.close();
          } catch (failure) {
            if (failure instanceof ApiError && failure.status === 409) {
              retryId = undefined;
              await load(false, failure.message);
              return;
            }
            throw failure;
          } finally {
            pending = false;
            cancel.disabled = false;
          }
        }, CONTROL_TEXT);
        controls.push(control);
      }
      for (const action of buttons) {
        const control = ruleControl({ ...action, revision: result.revision }, async intent => {
          if (retryId && retryId !== intent.requestId) throw new Error('Finish the pending death option first.');
          retryId = intent.requestId;
          controls.forEach(other => { other.inert = other !== control; });
          pending = true;
          cancel.disabled = true;
          try {
            await executeDeathOption(actorId, intent);
            dialog.close();
          } catch (failure) {
            if (failure instanceof ApiError && failure.status === 409) {
              retryId = undefined;
              await load(false, failure.message);
              return;
            }
            throw failure;
          } finally {
            pending = false;
            cancel.disabled = false;
          }
        }, CONTROL_TEXT);
        controls.push(control);
      }
      content.replaceChildren(
        el('p', { class: 'muted' }, 'Death roll results and recovery choices appear in the chat.'),
        ...(controls.length ? controls : [el('p', {}, 'No authored death options are available.')]));
      error.textContent = notice;
    } catch (failure) {
      if (!closed) {
        content.replaceChildren();
        error.textContent = failure instanceof Error ? failure.message : 'Could not load death options.';
        cancel.textContent = 'Close';
      }
    }
    return true;
  };
  try {
    await before();
  } catch (failure) {
    if (!closed) {
      content.replaceChildren();
      error.textContent = failure instanceof Error ? failure.message : 'Could not load death options.';
      cancel.textContent = 'Close';
    }
    return true;
  }
  return closed ? true : load(true);
}

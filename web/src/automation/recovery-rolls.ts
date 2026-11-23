import { ApiError, hostFetch } from '../api';
import { el } from '../login';
import type { ChatMessage } from '../protocol';
import { ruleControl, type RuleDescriptor } from './controls';
import { actionReports, automationUrl, CONTROL_TEXT, errorDetails } from './panel';

interface RecoveryDescription {
  revision: number;
  holderRevision: number;
  offerRevision: number;
  actions: RuleDescriptor[];
}

interface RecoveryActor { id: string; name: string }

export function historyRecoveryRollControls(message: ChatMessage, actors: RecoveryActor[]): HTMLElement | null {
  if (!message.automationRecoveryRoll || message.automationRecoveryRoll.used || !actors.length) return null;
  const status = el('div', { role: 'status' });
  const list = el('div', { class: 'acts automation-actions' });
  const details = el('details', { class: 'dh automation-claim-actions' },
    el('summary', {}, 'Downtime roll actions'), status, list);
  let generation = 0;
  let pendingId: string | undefined;

  const load = async (notice = ''): Promise<void> => {
    const current = ++generation;
    status.textContent = notice || 'Loading downtime roll actions…';
    list.replaceChildren();
    try {
      const descriptions = await Promise.all(actors.map(async actor => {
        const url = `${automationUrl(actor.id)}/rest-rolls/${encodeURIComponent(String(message.id))}/${message.ts}`;
        const response = await hostFetch(url, { credentials: 'same-origin', cache: 'no-store' });
        if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
        return { actor, url, offer: await response.json() as RecoveryDescription };
      }));
      if (current !== generation) return;
      const groups: HTMLElement[] = [];
      const buttons: HTMLElement[] = [];
      for (const { actor, url, offer } of descriptions) {
        const actions = offer.actions.filter(action => action.mode === 'button' || action.mode === 'option');
        if (!actions.length) continue;
        const group = el('fieldset', {}, el('legend', {}, actor.name));
        for (const action of actions) {
          const control = ruleControl({ ...action, mode: 'button', revision: offer.revision }, async intent => {
            if (current !== generation) return;
            if (pendingId && pendingId !== intent.requestId) throw new Error('Finish the pending roll action first.');
            pendingId = intent.requestId;
            buttons.forEach(other => { other.inert = other !== control; });
            const selection: Record<string, unknown> = {
              sourceInstanceId: intent.sourceInstanceId, ruleId: intent.ruleId,
              bindingId: intent.bindingId, inputs: intent.inputs,
            };
            if (intent.eachId !== undefined) selection.eachId = intent.eachId;
            const response = await hostFetch(url, {
              method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ request: { requestId: intent.requestId,
                expectedRevision: intent.expectedRevision, expectedHolderRevision: offer.holderRevision,
                expectedOfferRevision: offer.offerRevision, selection } }),
            });
            if (!response.ok) {
              const error = new ApiError((await errorDetails(response)).text, response.status);
              if (response.status !== 409) throw error;
              pendingId = undefined;
              await load(error.message);
              return;
            }
            const receipt: unknown = await response.json();
            pendingId = undefined;
            await load(actionReports(receipt).join('\n') || 'Downtime roll updated.');
          }, CONTROL_TEXT);
          buttons.push(control);
          group.append(control);
        }
        groups.push(group);
      }
      list.replaceChildren(...groups);
      status.textContent = notice || (groups.length ? '' : 'No downtime roll actions are available for your characters.');
    } catch (failure) {
      if (current !== generation) return;
      status.textContent = failure instanceof Error ? failure.message : CONTROL_TEXT.failed;
      const retry = el('button', { type: 'button' }, CONTROL_TEXT.retry);
      retry.addEventListener('click', () => { void load(notice); });
      list.replaceChildren(retry);
    }
  };
  details.addEventListener('toggle', () => { if (details.open && !pendingId) void load(); });
  return details;
}

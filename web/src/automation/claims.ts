import { ApiError, hostFetch } from '../api';
import { el } from '../login';
import type { ChatMessage } from '../protocol';
import { ruleControl, type RuleDescriptor } from './controls';
import { actionReports, automationUrl, CONTROL_TEXT, errorDetails } from './panel';

interface ClaimDescription {
  revision: number;
  recipientRevision: number;
  claimRevision: number;
  actions: RuleDescriptor[];
}

interface ClaimActor { id: string; name: string }

/** Claim choices are loaded only when a player opens the saved offer. */
export function historyClaimControls(message: ChatMessage, actors: ClaimActor[]): HTMLElement | null {
  const claim = message.automationClaim;
  const eligibleActors = claim?.recipientActorId ? actors.filter(actor => actor.id === claim.recipientActorId) : actors;
  if (!claim || !eligibleActors.length) return null;
  const url = `${automationUrl(claim.sourceActorId)}/claims/${encodeURIComponent(String(message.id))}/${message.ts}`;
  const status = el('div', { role: 'status' });
  const list = el('div', { class: 'acts automation-actions' });
  const details = el('details', { class: 'dh automation-claim-actions' },
    el('summary', {}, 'Claim benefit'), status, list);
  let generation = 0;
  let pendingId: string | undefined;

  const load = async (notice = ''): Promise<void> => {
    const current = ++generation;
    status.textContent = notice || 'Loading benefit choices…';
    list.replaceChildren();
    try {
      const descriptions = await Promise.all(eligibleActors.map(async actor => {
        const query = new URLSearchParams({ recipientId: actor.id });
        const response = await hostFetch(`${url}?${query}`, { credentials: 'same-origin', cache: 'no-store' });
        if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
        return { actor, offer: await response.json() as ClaimDescription };
      }));
      if (current !== generation) return;
      const controls: HTMLElement[] = [];
      const buttons: HTMLElement[] = [];
      for (const { actor, offer } of descriptions) {
        const actions = offer.actions.filter(action => action.mode === 'button' || action.mode === 'option');
        if (!actions.length) continue;
        const group = el('fieldset', {}, el('legend', {}, actor.name));
        for (const action of actions) {
          const control = ruleControl({ ...action, mode: 'button', revision: offer.revision }, async intent => {
            if (current !== generation) return;
            if (pendingId && pendingId !== intent.requestId) throw new Error('Finish the pending claim first.');
            pendingId = intent.requestId;
            buttons.forEach(other => { other.inert = other !== control; });
            const selection: Record<string, unknown> = {
              sourceInstanceId: intent.sourceInstanceId, ruleId: intent.ruleId,
              bindingId: intent.bindingId, inputs: intent.inputs,
            };
            if (intent.eachId !== undefined) selection.eachId = intent.eachId;
            const response = await hostFetch(url, {
              method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ request: { requestId: intent.requestId, recipientId: actor.id,
                expectedRevision: intent.expectedRevision, expectedRecipientRevision: offer.recipientRevision,
                expectedClaimRevision: offer.claimRevision, selection } }),
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
            await load(actionReports(receipt).join('\n') || 'Benefit applied.');
          }, CONTROL_TEXT);
          buttons.push(control);
          group.append(control);
        }
        controls.push(group);
      }
      list.replaceChildren(...controls);
      status.textContent = notice || (controls.length ? '' : 'No benefit choices are available for your characters.');
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

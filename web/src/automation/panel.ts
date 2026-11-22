import { el } from '../login';
import { ApiError, hostFetch } from '../api';
import { intentControl, ruleControl, type ControlText, type RuleDescriptor, type RuleIntent } from './controls';

export interface DescribedActions {
  revision: number;
  actions: RuleDescriptor[];
  profiles?: Array<{ id: string; label: string; sourceInstanceId: string; sourceName: string; damage?: object;
    options?: Partial<Record<'attack' | 'damage', RuleDescriptor[]>> }>;
}

export const CONTROL_TEXT: ControlText = {
  cancel: 'Cancel',
  retry: 'Retry',
  pending: 'Working…',
  failed: 'The action failed.',
  recipient: 'Choose a character',
  recipients: 'Recipients',
  wholeNumber: (min, max) => `Enter a whole number from ${min} to ${max}.`,
  selectDice: (count, min) => min === undefined
    ? `Select exactly ${count} ${count === 1 ? 'die' : 'dice'}.`
    : `Select ${min} to ${count} dice.`,
  selectOptions: count => `Select exactly ${count} ${count === 1 ? 'option' : 'options'}.`,
  allocate: (total, max) => `Distribute exactly ${total}, at most ${max} each.`,
};

async function errorText(response: Response): Promise<string> {
  try {
    const body = await response.json() as { error?: string };
    return body.error || `Request failed (${response.status}).`;
  } catch {
    return `Request failed (${response.status}).`;
  }
}

/** Reads host-described automation actions. Resolves null when the host has no format-2 automation pack. */
export async function describeAutomation(actorId: string, surface = 'sheet', event = 'action.request'): Promise<DescribedActions | null> {
  const query = new URLSearchParams({ surface, event });
  const response = await hostFetch(`/api/automation/${encodeURIComponent(actorId)}?${query}`, { credentials: 'same-origin', cache: 'no-store' });
  if (response.status === 409) return null;
  if (!response.ok) throw new Error(await errorText(response));
  return await response.json() as DescribedActions;
}

/** Submits one selected binding as a host intent; the host recomputes everything from trusted JSON. */
export async function executeAutomation(actorId: string, intent: RuleIntent): Promise<unknown> {
  const selection: Record<string, unknown> = {
    sourceInstanceId: intent.sourceInstanceId, ruleId: intent.ruleId, bindingId: intent.bindingId, inputs: intent.inputs,
  };
  if (intent.eachId !== undefined) selection.eachId = intent.eachId;
  return postIntent(actorId, { requestId: intent.requestId, expectedRevision: intent.expectedRevision, selections: [selection],
    ...(intent.target ? { target: intent.target } : {}) });
}

async function postIntent(actorId: string, request: object): Promise<unknown> {
  const response = await hostFetch(`/api/automation/${encodeURIComponent(actorId)}`, {
    method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ request }),
  });
  if (!response.ok) throw new ApiError(await errorText(response), response.status);
  return response.json();
}

function actionReports(response: unknown): string[] {
  if (!response || typeof response !== 'object' || Array.isArray(response)) return [];
  const actions = (response as Record<string, unknown>).actions;
  if (!Array.isArray(actions)) return [];
  return actions.flatMap(action => {
    if (!action || typeof action !== 'object' || Array.isArray(action)) return [];
    const report = (action as Record<string, unknown>).report;
    return typeof report === 'string' && report.trim() ? [report] : [];
  });
}

/**
 * Renders every independent button the host describes for this actor and surface. The panel stays hidden while the
 * host has no automation pack or no visible actions, and refreshes itself after each completed action.
 */
export function automationPanel(actorId: string, surface = 'sheet', title = 'Actions',
  fetchActions: typeof describeAutomation = describeAutomation,
  submit: typeof executeAutomation = executeAutomation, event = 'action.request'): HTMLElement {
  const list = el('div', { class: 'acts automation-actions' });
  const status = el('div', { class: 'error' });
  status.setAttribute('role', 'alert');
  const output = el('div', { class: 'automation-result' });
  output.setAttribute('role', 'status');
  const root = el('div', { class: 'card automation-panel', hidden: true },
    el('h3', { class: 'card-zone-header' }, title), list, output, status);
  let generation = 0;
  const load = async (notice = ''): Promise<void> => {
    const current = ++generation;
    try {
      const described = await fetchActions(actorId, surface, event);
      if (current !== generation) return;
      const buttons = (described?.actions ?? []).filter(action => action.mode === 'button');
      const controls = buttons.map(action => ruleControl({ ...action, revision: described!.revision }, async intent => {
        let result: unknown;
        try { result = await submit(actorId, intent); }
        catch (failure) {
          if (!(failure instanceof ApiError) || failure.status !== 409) throw failure;
          // The host rejected the intent before committing it. Retire this control and show a fresh offer.
          void load(failure.message);
          return;
        }
        output.textContent = actionReports(result).join('\n');
        void load();
      }, CONTROL_TEXT));
      for (const profile of described?.profiles ?? []) {
        for (const kind of ['attack', 'damage'] as const) {
          if (kind === 'damage' && !profile.damage) continue;
          const label = `${profile.sourceName}: ${profile.label} (${kind})`;
          controls.push(intentControl({ label, available: true, revision: described!.revision, options: profile.options?.[kind] },
            async ({ requestId, expectedRevision, selections = [] }) => {
              let result: unknown;
              try {
                result = await postIntent(actorId, { requestId, expectedRevision, selections,
                  profileRoll: { sourceInstanceId: profile.sourceInstanceId, profileId: profile.id, kind } });
              } catch (failure) {
                if (!(failure instanceof ApiError) || failure.status !== 409) throw failure;
                void load(failure.message);
                return;
              }
              const event = (result as { event: { check: { total: number } } }).event;
              output.textContent = [`${label}: ${event.check.total}`, ...actionReports(result)].join('\n');
              void load();
            }, CONTROL_TEXT));
        }
      }
      list.replaceChildren(...controls);
      status.textContent = notice;
      root.hidden = controls.length === 0 && !output.textContent && !notice;
    } catch (failure) {
      if (current !== generation) return;
      status.textContent = failure instanceof Error ? failure.message : CONTROL_TEXT.failed;
      root.hidden = false;
    }
  };
  void load();
  return root;
}

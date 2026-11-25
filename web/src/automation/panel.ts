import { el } from '../login';
import { ApiError, hostFetch, ONLINE_CONTRACT_VERSION } from '../api';
import type { RollMessage } from '../protocol';
import { intentControl, ruleControl, type ControlText, type RuleDescriptor, type RuleIntent } from './controls';

type ProfileRollKind = 'attack' | 'damage' | 'action' | 'reaction' | 'spellcast';

export const AUTOMATION_COMPATIBILITY_VERSION = ONLINE_CONTRACT_VERSION;

export interface DescribedActions {
  revision: number;
  actions: RuleDescriptor[];
  profiles?: Array<{ id: string; label: string; sourceInstanceId: string; sourceName: string; damage?: object;
    effectInstanceId?: string; rollKind?: 'action' | 'reaction' | 'spellcast';
    available?: boolean; reason?: string;
    options?: Partial<Record<ProfileRollKind, RuleDescriptor[]>> }>;
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
  selectOptions: (count, min) => min === undefined || min === count ? `Select exactly ${count} ${count === 1 ? 'option' : 'options'}.` : `Select ${min} to ${count} options.`,
  allocate: (total, max) => `Distribute exactly ${total}, at most ${max} each.`,
};

export async function errorDetails(response: Response): Promise<{ text: string; code?: string }> {
  try {
    const body = await response.json() as { error?: string; code?: string };
    return { text: body.error || `Request failed (${response.status}).`, code: body.code };
  } catch {
    return { text: `Request failed (${response.status}).` };
  }
}

export function automationUrl(actorId: string): string {
  return `/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/${encodeURIComponent(actorId)}`;
}

/** Reads host-described automation actions. Resolves null when the host has no format-2 automation pack. */
export async function describeAutomation(actorId: string, surface = 'sheet', event = 'action.request'): Promise<DescribedActions | null> {
  return fetchDescription(actorId, new URLSearchParams({ surface, event }));
}

async function fetchDescription(actorId: string, query: URLSearchParams): Promise<DescribedActions | null> {
  const response = await hostFetch(`${automationUrl(actorId)}?${query}`, { credentials: 'same-origin', cache: 'no-store' });
  if (response.status === 404) return null;
  if (response.status === 409) {
    const error = await errorDetails(response);
    if (error.code === 'automationCompatibilityMismatch') throw new ApiError(error.text, response.status);
    return null;
  }
  if (!response.ok) throw new Error((await errorDetails(response)).text);
  return await response.json() as DescribedActions;
}

function selectionFromIntent(intent: RuleIntent): Record<string, unknown> {
  const selection: Record<string, unknown> = {
    sourceInstanceId: intent.sourceInstanceId, ruleId: intent.ruleId, bindingId: intent.bindingId, inputs: intent.inputs,
  };
  if (intent.eachId !== undefined) selection.eachId = intent.eachId;
  return selection;
}

/** Submits one selected binding as a host intent; the host recomputes everything from trusted JSON. */
export async function executeAutomation(actorId: string, intent: RuleIntent, resultId?: string): Promise<unknown> {
  const selection = selectionFromIntent(intent);
  return postIntent(actorId, { requestId: intent.requestId, expectedRevision: intent.expectedRevision, selections: [selection],
    ...(resultId === undefined ? {} : { resultId }),
    ...(intent.target ? { target: intent.target } : {}) });
}

export async function executeDeathOption(actorId: string, intent: RuleIntent): Promise<unknown> {
  return postIntent(actorId, { requestId: intent.requestId, expectedRevision: intent.expectedRevision,
    deathAction: true, selections: [selectionFromIntent(intent)] });
}

export async function executeDamage(actorId: string, request: {
  requestId: string; expectedRevision: number; selections?: import('./controls').RollSelection[];
  damage: { amount: number; armor: number; type?: string };
}): Promise<unknown> {
  return postIntent(actorId, { ...request, selections: request.selections ?? [] });
}
export async function executeDeathRoll(actorId: string, move: string, request: {
  requestId: string; expectedRevision: number; selections: import('./controls').RollSelection[];
}): Promise<unknown> {
  return postIntent(actorId, { ...request, deathRoll: { move } });
}

async function postIntent(actorId: string, request: object): Promise<unknown> {
  const response = await hostFetch(automationUrl(actorId), {
    method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ request }),
  });
  if (response.status === 404) throw new ApiError(
    `Online automation compatibility mismatch (client version ${AUTOMATION_COMPATIBILITY_VERSION}; host version unknown). Update the browser and host to matching versions and reload.`,
    response.status,
  );
  if (!response.ok) throw new ApiError((await errorDetails(response)).text, response.status);
  return response.json();
}

export function actionReports(response: unknown): string[] {
  if (!response || typeof response !== 'object' || Array.isArray(response)) return [];
  const actions = (response as Record<string, unknown>).actions;
  if (!Array.isArray(actions)) return [];
  return actions.flatMap(action => {
    if (!action || typeof action !== 'object' || Array.isArray(action)) return [];
    const report = (action as Record<string, unknown>).report;
    return typeof report === 'string' && report.trim() ? [report] : [];
  });
}

interface RollReceipt {
  revision: number;
  event: { check: { total: number; outcome?: string | null }; resultId?: string };
  resultActions?: RuleDescriptor[];
  resultPublished?: boolean;
}

const SECTION: Record<NonNullable<RuleDescriptor['group']>, { name: string; className: string; label: string }> = {
  result: { name: 'result', className: 'small primary', label: 'Roll result' },
  sourceFeature: { name: 'feature', className: 'small', label: 'Feature effects' },
  otherFeatures: { name: 'other', className: 'small related', label: 'Other features' },
};

/** Keep a chat card pinned to its original result. A sheet can follow a newly published revision instead. */
function resultControls(actorId: string, resultId: string, initial?: DescribedActions, quiet = false) {
  // Same markup as the format-1 chat roll buttons: grouped sections inside div.roll-actions.
  const list = el('div', { class: 'roll-actions', hidden: true });
  const status = el('div', { class: 'muted', role: 'status', hidden: true });
  const root = el('div', { class: 'automation-result-actions' }, list, status);
  const setStatus = (text: string): void => { status.textContent = text; status.hidden = !text; };
  const show = (nodes: HTMLElement[]): void => { list.replaceChildren(...nodes); list.hidden = nodes.length === 0; };
  let generation = 0;
  let retryId: string | undefined;
  let finished = false;
  const paint = (id: string, offer: DescribedActions): void => {
    const current = ++generation;
    const buttons = offer.actions.filter(button => button.mode === 'button');
    const controls = buttons.map(button => {
      const control = ruleControl({ ...button, revision: offer.revision }, async intent => {
        if (current !== generation) return;
        if (retryId && retryId !== intent.requestId) throw new Error('Finish the pending result action first.');
        retryId = intent.requestId;
        controls.forEach(other => { other.inert = other !== control; });
        let receipt: RollReceipt;
        try { receipt = await executeAutomation(actorId, intent, id) as RollReceipt; }
        catch (failure) {
          if (!(failure instanceof ApiError) || failure.status !== 409) throw failure;
          retryId = undefined;
          if (current === generation) void refresh(id, failure.message);
          return;
        }
        retryId = undefined;
        if (current !== generation) return;
        const reports = actionReports(receipt);
        if (receipt.resultPublished) {
          setStatus([`Revised total: ${receipt.event.check.total}`, ...reports].join('\n'));
          if (initial && receipt.event.resultId && receipt.resultActions?.length) {
            if (receipt.resultActions.some(action => action.targetSelection))
              void refresh(receipt.event.resultId, status.textContent ?? '');
            else paint(receipt.event.resultId, { revision: receipt.revision, actions: receipt.resultActions });
          }
          else { finished = true; ++generation; show([]); }
        } else if (offer.actions.length === 1 && !button.repeatable) {
          ++generation; show([]);
          finished = true;
          setStatus(reports.join('\n') || 'Applied.');
        } else void refresh(id, reports.join('\n') || 'Applied.');
      }, CONTROL_TEXT, { bare: true, className: SECTION[button.group ?? 'sourceFeature'].className,
        onError: message => setStatus(message) });
      return control;
    });
    const groups = (Object.keys(SECTION) as Array<keyof typeof SECTION>).map(group => {
      const members = controls.filter((_, index) => (buttons[index].group ?? 'sourceFeature') === group);
      return members.length ? el('div', { class: `roll-action-group ${SECTION[group].name}`, role: 'group',
        ariaLabel: SECTION[group].label }, ...members) : undefined;
    }).filter((group): group is HTMLDivElement => !!group);
    show(groups);
  };
  const refresh = async (id: string, notice = ''): Promise<void> => {
    const current = ++generation;
    show([]);
    setStatus(notice || (quiet ? '' : 'Loading result actions…'));
    try {
      const offer = await fetchDescription(actorId, new URLSearchParams({ resultId: id }));
      if (current !== generation) return;
      setStatus(notice || (offer?.actions.length || quiet ? '' : 'No current actions for this result.'));
      if (offer) paint(id, offer);
    } catch (failure) {
      if (current !== generation) return;
      setStatus(failure instanceof Error ? failure.message : CONTROL_TEXT.failed);
      const retry = el('button', { type: 'button', class: 'small' }, CONTROL_TEXT.retry);
      retry.addEventListener('click', () => { void refresh(id, notice); });
      show([retry]);
    }
  };
  if (initial) {
    if (initial.actions.some(action => action.targetSelection)) void refresh(resultId);
    else paint(resultId, initial);
  }
  return { root, reopen: () => { if (!retryId && !finished) void refresh(resultId); } };
}

/** Restoring a history tail makes no per-card requests. Load only the result the owner opens. */
export function historyResultControls(roll: RollMessage, playerId: string, session: string, ownsActor: boolean): HTMLElement | null {
  const { resultId, sessionId } = roll.automationEvent ?? {};
  if (!ownsActor || !session || session !== sessionId || !resultId || !roll.actorId || playerId !== roll.fromId) return null;
  const actions = resultControls(roll.actorId, resultId, undefined, true);
  const node = el('div', { class: 'private-result-actions' }, actions.root);
  // The buttons sit inline on the card. Their descriptors are fetched only once the card is actually on screen.
  let loaded = false;
  const load = (): void => { if (!loaded) { loaded = true; actions.reopen(); } };
  node.addEventListener('toggle', () => actions.reopen());
  if (typeof IntersectionObserver === 'function') {
    const observer = new IntersectionObserver(entries => {
      if (!entries.some(entry => entry.isIntersecting)) return;
      observer.disconnect();
      load();
    });
    observer.observe(node);
  }
  return node;
}

/**
 * Folds per-item (forEach) buttons into one compact chip per stored item, such as a rolled Prayer die, so that N items
 * do not become 2N labelled buttons. This is presentation only: every glyph submits the host-described binding.
 */
function dieChips(revision: number, buttons: RuleDescriptor[],
  submit: (intent: RuleIntent) => Promise<void>): HTMLElement[] {
  const groups = new Map<string, RuleDescriptor[]>();
  for (const action of buttons) {
    if (action.eachId === undefined) continue;
    const key = `${action.sourceInstanceId}\u0000${action.eachId}`;
    groups.set(key, [...(groups.get(key) ?? []), action]);
  }
  if (!groups.size) return [];
  const chips = [...groups.values()].map(actions => {
    const face = /\(\+?(\d+)\)/.exec(actions[0].label)?.[1] ?? '?';
    const chip = el('span', { class: 'dh-pool-die' }, el('strong', {}, face));
    for (const action of actions) {
      const control = ruleControl({ ...action, revision, label: /remove/i.test(action.ruleId) ? '\u00d7' : '+',
        description: action.available ? action.label : `${action.label}: ${action.reason ?? 'Unavailable'}` },
        submit, CONTROL_TEXT);
      control.children[0]?.setAttribute('aria-label', action.label);
      chip.append(control);
    }
    return chip;
  });
  return [el('div', { class: 'dh-dice-pool', role: 'group', ariaLabel: 'Stored dice' }, ...chips)];
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
  const resultActions = el('div', { class: 'dh' });
  output.setAttribute('role', 'status');
  const root = el('div', { class: 'card automation-panel', hidden: true },
    el('h3', { class: 'card-zone-header' }, title), list, output, resultActions, status);
  let generation = 0;
  const load = async (notice = ''): Promise<void> => {
    const current = ++generation;
    try {
      const described = await fetchActions(actorId, surface, event);
      if (current !== generation) return;
      const buttons = (described?.actions ?? []).filter(action => action.mode === 'button');
      const controls = buttons.filter(action => action.eachId === undefined).map(action => ruleControl({ ...action, revision: described!.revision }, async intent => {
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
        const kinds: ProfileRollKind[] = profile.rollKind ? [profile.rollKind] : ['attack', 'damage'];
        for (const kind of kinds) {
          if (kind === 'damage' && !profile.damage) continue;
          const label = `${profile.sourceName}: ${profile.label} (${kind})`;
          controls.push(intentControl({ label, available: profile.available !== false, reason: profile.reason,
            revision: described!.revision, options: profile.options?.[kind] },
            async ({ requestId, expectedRevision, selections = [] }) => {
              let result: unknown;
              try {
                result = await postIntent(actorId, { requestId, expectedRevision, selections,
                  profileRoll: { sourceInstanceId: profile.sourceInstanceId, profileId: profile.id, kind,
                    ...(profile.effectInstanceId === undefined ? {} : { effectInstanceId: profile.effectInstanceId }) } });
              } catch (failure) {
                if (!(failure instanceof ApiError) || failure.status !== 409) throw failure;
                void load(failure.message);
                return;
              }
              const { event, resultActions: actions, revision } = result as RollReceipt;
              resultActions.replaceChildren(...(event.resultId && actions?.length
                ? [resultControls(actorId, event.resultId, { revision, actions }).root] : []));
              const outcome = event.check.outcome ? ` (${event.check.outcome})` : '';
              output.textContent = [`${label}: ${event.check.total}${outcome}`, ...actionReports(result)].join('\n');
              void load();
            }, CONTROL_TEXT));
        }
      }
      list.replaceChildren(...controls, ...dieChips(described?.revision ?? 0, buttons, async intent => {
        let result: unknown;
        try { result = await submit(actorId, intent); }
        catch (failure) {
          if (!(failure instanceof ApiError) || failure.status !== 409) throw failure;
          void load(failure.message);
          return;
        }
        output.textContent = actionReports(result).join('\n');
        void load();
      }));
      status.textContent = notice;
      root.hidden = list.children.length === 0 && !output.textContent && !notice;
    } catch (failure) {
      if (current !== generation) return;
      status.textContent = failure instanceof Error ? failure.message : CONTROL_TEXT.failed;
      root.hidden = false;
    }
  };
  void load();
  return root;
}

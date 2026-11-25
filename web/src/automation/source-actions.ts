import { el } from '../login';
import { ApiError } from '../api';
import { ruleControl, usesText, type RuleDescriptor } from './controls';
import { actionReports, CONTROL_TEXT, describeAutomation, executeAutomation, type DescribedActions } from './panel';

/** Instance id of a derived (grimoire spell) source, mirroring SheetProjection.collectSources. */
export function derivedInstanceId(parent: string, relative: string, id: string): string {
  return `n${parent.length}.${parent}.${relative.length}.${relative}.${id}`;
}

export function instanceOf(source: unknown): string | undefined {
  const id = (source as { instanceId?: unknown } | undefined)?.instanceId;
  return typeof id === 'string' && id ? id : undefined;
}

export interface SourceActions {
  /** A `div.row.actions` that fills itself with the host-described sheet buttons of the given source instances. */
  row(instanceIds: Array<string | undefined>): HTMLElement;
}

/**
 * Places host-described sheet buttons inside the existing feature, card, weapon, armor and item cards, using the same
 * row and button markup as the format-1 sheet. One description is fetched per sheet render and shared by every row.
 */
export function sourceActions(actorId: string, onLog?: (message: string) => void,
  fetchActions: typeof describeAutomation = describeAutomation,
  submit: typeof executeAutomation = executeAutomation): SourceActions {
  const rows: Array<{ node: HTMLElement; ids: Set<string> }> = [];
  let described: DescribedActions | null = null;
  let generation = 0;
  const paint = (): void => {
    for (const { node, ids } of rows) {
      const buttons = (described?.actions ?? []).filter(a => a.mode === 'button' && ids.has(a.sourceInstanceId));
      const parts: HTMLElement[] = [];
      buttons.forEach((action, index) => {
        parts.push(control(action));
        const key = (a: RuleDescriptor) => a.uses ? `${a.sourceInstanceId}\u0000${a.uses.limit}` : undefined;
        const limit = key(action);
        if (limit && !buttons.slice(index + 1).some(next => key(next) === limit))
          parts.push(el('span', { class: 'muted usage-counter', style: 'font-size:11.5px' }, usesText(action.uses!)));
      });
      node.replaceChildren(...parts);
      node.hidden = buttons.length === 0;
    }
  };
  const control = (action: RuleDescriptor): HTMLElement => {
    const node = ruleControl({ ...action, revision: described!.revision }, async intent => {
      try {
        const result = await submit(actorId, intent);
        const text = actionReports(result).join('\n');
        if (text) onLog?.(text);
      } catch (failure) {
        if (!(failure instanceof ApiError) || failure.status !== 409) throw failure;
        onLog?.(failure.message);
      }
      void load();
    }, CONTROL_TEXT, { bare: true, onError: message => onLog?.(message) });
    node.classList.add('host-action');
    return node;
  };
  const load = async (): Promise<void> => {
    const current = ++generation;
    try {
      const next = await fetchActions(actorId, 'sheet', 'action.request');
      if (current !== generation) return;
      described = next;
    } catch {
      if (current !== generation) return;
      described = null;
    }
    paint();
  };
  let started = false;
  return {
    row(instanceIds) {
      const node = el('div', { class: 'row actions', hidden: true });
      rows.push({ node, ids: new Set(instanceIds.filter((id): id is string => !!id)) });
      if (!started) { started = true; void load(); }
      else if (described) paint();
      return node;
    },
  };
}

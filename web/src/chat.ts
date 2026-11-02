import { el } from './login';
import type { ChatMessage, RollDetail, RollMessage } from './protocol';
import { OUTCOME_LABEL, dualityResult, dualitySucceeds, signed } from './daggerheart/data';
import { renderSharedCard, type SharedCard } from './shared-cards';

export type LogEntry = { kind: 'chat'; m: ChatMessage } | { kind: 'roll'; m: RollMessage };

/** Historical messages and older hosts have dice and a formula, but no named sheet sources. */
export function rollDetails(m: RollMessage): RollDetail[] {
  if (m.breakdown?.length) return m.breakdown;
  const rolls = Array.isArray(m.rolls) ? m.rolls.filter((v): v is number => typeof v === 'number') : [];
  let index = 0;
  const details: RollDetail[] = [];
  for (const match of m.expression.matchAll(/([+-]?)(?:(\d*)d(\d+)|(\d+))/gi)) {
    const expression = match[0];
    if (match[3]) {
      const count = Number(match[2] || 1);
      const values = rolls.slice(index, index + count);
      if (m.kind === 'duality' && index === 0 && count === 2 && Number(match[3]) === 12) {
        details.push({ label: 'Hope die', expression: '1d12', rolls: values.slice(0, 1), total: values[0] ?? 0 });
        details.push({ label: 'Fear die', expression: '1d12', rolls: values.slice(1, 2), total: values[1] ?? 0 });
      } else {
        const advantage = m.kind === 'duality' && index === 2 && count === 1 && Number(match[3]) === 6;
        details.push({
          label: advantage ? (match[1] === '-' ? 'Disadvantage' : 'Advantage') : `${count}d${match[3]} dice`,
          expression, rolls: values, total: values.reduce((n, v) => n + v, 0),
        });
      }
      index += count;
    } else {
      details.push({ label: 'Modifier', expression, rolls: [], total: Number(expression) });
    }
  }
  return details;
}

function rollContent(m: RollMessage): HTMLElement {
  const details = rollDetails(m);
  const values = Array.isArray(m.rolls) ? m.rolls.filter((v): v is number => typeof v === 'number') : [];
  const duality = m.kind === 'duality' && values.length >= 2;
  const dice = el('div', { class: 'roll-dice' });
  let index = 0;
  for (const detail of details) {
    const sides = /d(\d+)/i.exec(detail.expression)?.[1] ?? '?';
    for (const value of detail.rolls) {
      const n = index++;
      if (duality && n > 1) continue;
      const name = duality ? (n === 0 ? 'Hope' : 'Fear') : `d${sides}`;
      const tile = el('span', { class: `roll-die${duality ? (n === 0 ? ' hope' : ' fear') : ''}`, title: `${detail.label}: ${value}` },
        el('span', { class: 'roll-die-name' }, name), el('strong', {}, String(value)));
      tile.setAttribute('aria-label', `${name}: ${value}`);
      dice.append(tile);
    }
  }
  const modifier = m.total - (duality ? values[0] + values[1] : values.reduce((n, v) => n + v, 0));
  const equation = el('div', { class: 'roll-equation' }, dice,
    el('span', { class: `roll-modifier${modifier < 0 ? ' negative' : ''}`, title: duality ? 'Combined modifiers, including bonus dice' : 'Combined modifiers' }, signed(modifier)),
    el('span', { class: 'roll-equals' }, '='),
    el('div', { class: 'roll-total' }, el('span', {}, 'TOTAL'), el('strong', {}, String(m.total))));
  const body = el('div', { class: 'roll-body' },
    el('div', { class: 'roll-title' }, m.label ?? (duality ? 'Duality roll' : 'Dice roll')), equation);
  if (duality) {
    const r = dualityResult(values, m.total);
    const badges = el('div', { class: 'roll-outcomes' }, el('span', { class: `roll-outcome ${r.outcome}` }, OUTCOME_LABEL[r.outcome]));
    if (typeof m.difficulty === 'number') {
      const success = dualitySucceeds(r, m.difficulty);
      badges.append(el('span', { class: `roll-outcome ${success ? 'success' : 'failure'}` }, `${success ? 'Success' : 'Failure'} / Difficulty ${m.difficulty}`));
    }
    body.append(badges);
  }
  const breakdown = el('details', { class: 'roll-breakdown' },
    el('summary', {}, 'Roll breakdown'),
    el('div', { class: 'roll-formula' }, m.expression),
    ...details.flatMap((d) => [
      el('div', { class: 'roll-detail' },
        el('div', { class: 'roll-detail-source' }, el('span', {}, d.label),
          ...(d.rolls.length ? [el('small', {}, `${d.expression.replace(/^\+/, '')}: [${d.rolls.join(', ')}]`)] : [])),
        el('strong', { class: d.total < 0 ? 'negative' : '' }, signed(d.total))),
      ...(d.notes ?? []).map((note) => el('div', { class: 'roll-detail roll-note' },
        el('span', {}, note.label), el('span', {}, note.impact))),
    ]),
    el('div', { class: 'roll-detail roll-detail-total' }, el('span', {}, 'Result'), el('strong', {}, String(m.total))));
  body.append(breakdown);
  return body;
}

export function renderLogEntry(e: LogEntry, playerId: string, onGrab?: (card: SharedCard) => Promise<void>,
                               onError?: (message: string) => void): HTMLElement {
  const m = e.m;
  const time = new Date(m.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const avatar = el('span', { class: 'message-avatar' }, m.from.slice(0, 1).toUpperCase());
  avatar.setAttribute('aria-hidden', 'true');
  const header = el('header', { class: 'message-header' },
    avatar,
    el('strong', { class: 'message-sender' }, m.from),
    el('time', { dateTime: new Date(m.ts).toISOString(), title: new Date(m.ts).toLocaleString() }, time));
  const card = el('article', { class: `entry${e.kind === 'roll' ? ' roll' : ''}${m.toId ? ' whisper' : ''}` }, header);
  if (m.toId) {
    card.append(el('div', { class: 'message-recipient' }, el('span', { class: 'whisper-label' },
      `Whisper to ${m.toId === 'dm' ? 'Gamemaster' : m.to ?? (m.toId === playerId ? 'you' : m.toId)}`)));
  }
  card.append(e.kind === 'roll' ? rollContent(e.m) : e.m.sharedCard
    ? renderSharedCard(e.m.sharedCard, onGrab, onError) : el('div', { class: 'message-text' }, e.m.text));
  return card;
}

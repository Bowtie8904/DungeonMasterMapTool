import { el } from './login';
import type { ChatMessage, RollDetail, RollMessage } from './protocol';
import { OUTCOME_LABEL, dualityResult, dualitySucceeds, signed } from './daggerheart/data';
import { renderSharedCard, type SharedCard } from './shared-cards';
import { parseRestReport } from './daggerheart/rest-summary';
import { parseDamageReport } from './daggerheart/damage-summary';
import { parseActionReport } from './daggerheart/action-summary';
import { rich } from './daggerheart/rich';

export type LogEntry = { kind: 'chat'; m: ChatMessage } | { kind: 'roll'; m: RollMessage };

export function keptRolls(expression: string, rolls: number[]): boolean[] {
  const kept = rolls.map(() => true);
  let index = 0;
  for (const match of expression.matchAll(/(\d*)d\d+(?:kh(\d+))?/gi)) {
    const count = Number(match[1] || 1);
    if (match[2]) {
      const indices = Array.from({ length: Math.min(count, rolls.length - index) }, (_, n) => index + n);
      indices.sort((a, b) => Math.abs(rolls[b]) - Math.abs(rolls[a]) || a - b);
      for (const n of indices.slice(Number(match[2]))) kept[n] = false;
    }
    index += count;
  }
  return kept;
}

/** Historical messages and older hosts have dice and a formula, but no named sheet sources. */
export function rollDetails(m: RollMessage): RollDetail[] {
  if (m.breakdown?.length) return m.breakdown;
  const rolls = Array.isArray(m.rolls) ? m.rolls.filter((v): v is number => typeof v === 'number') : [];
  let index = 0;
  const details: RollDetail[] = [];
  for (const match of m.expression.matchAll(/([+-]?)(?:(\d*)d(\d+)(?:kh(\d+))?|(\d+))/gi)) {
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
          expression, rolls: values,
          total: values.reduce((n, v, i) => n + (keptRolls(expression, values)[i] ? v : 0), 0),
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
  const secret = m.secret === true;
  const details = rollDetails(m);
  const values = Array.isArray(m.rolls) ? m.rolls.filter((v): v is number => typeof v === 'number') : [];
  const kept = secret ? values.map(() => true) : keptRolls(m.expression, values);
  const duality = !secret && m.kind === 'duality' && values.length >= 2;
  const dice = el('div', { class: 'roll-dice' });
  let index = 0;
  for (const detail of details) {
    const sides = /d(\d+)/i.exec(detail.expression)?.[1] ?? '?';
    for (const value of detail.rolls) {
      const n = index++;
      if (duality && n > 1) continue;
      const name = duality ? (n === 0 ? 'Hope' : 'Fear') : `d${sides}`;
      const discarded = kept[n] === false;
      const shown = secret ? '?' : String(value);
      const tile = el('span', { class: `roll-die${discarded ? ' discarded' : ''}${duality ? (n === 0 ? ' hope' : ' fear') : ''}${secret ? ' secret' : ''}`, title: secret ? `${name}: secret` : `${detail.label}: ${value}${discarded ? ' (discarded)' : ''}` },
        el('span', { class: 'roll-die-name' }, name), el('strong', {}, shown));
      tile.setAttribute('aria-label', `${name}: ${secret ? 'secret' : value}${discarded ? ' (discarded)' : ''}`);
      dice.append(tile);
    }
  }
  const modifier = m.total - (duality ? values[0] + values[1] : values.reduce((n, v, i) => n + (kept[i] ? v : 0), 0));
  const equation = el('div', { class: 'roll-equation' }, dice,
    ...(secret ? [] : [el('span', { class: `roll-modifier${modifier < 0 ? ' negative' : ''}`, title: duality ? 'Combined modifiers, including bonus dice' : 'Combined modifiers' }, signed(modifier))]),
    el('span', { class: 'roll-equals' }, '='),
    el('div', { class: 'roll-total' }, el('span', {}, 'TOTAL'), el('strong', {}, secret ? '?' : String(m.total))));
  const body = el('div', { class: 'roll-body' },
    el('div', { class: 'roll-title' }, m.label ?? (secret ? 'Secret roll' : duality ? 'Duality roll' : 'Dice roll')), equation);
  if (secret) return body;
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

export function renderLogEntry(e: LogEntry, playerId: string, onGrab?: (card: SharedCard, all: boolean) => Promise<void>,
                               onError?: (message: string) => void,
                               rollActions?: (m: RollMessage) => HTMLElement | null): HTMLElement {
  const m = e.m;
  const time = new Date(m.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const avatar = el('span', { class: 'message-avatar' }, m.from.slice(0, 1).toUpperCase());
  avatar.setAttribute('aria-hidden', 'true');
  const header = el('header', { class: 'message-header' },
    avatar,
    el('strong', { class: 'message-sender' }, m.from),
    el('time', { dateTime: new Date(m.ts).toISOString(), title: new Date(m.ts).toLocaleString() }, time));
  const report = e.kind === 'chat' && !m.toId && !e.m.sharedCard ? parseRestReport(e.m.text) : null;
  const damage = e.kind === 'chat' && !m.toId && !e.m.sharedCard && !report ? parseDamageReport(e.m.text) : null;
  const action = e.kind === 'chat' && !m.toId && !e.m.sharedCard && !report && !damage ? parseActionReport(e.m.text) : null;
  const card = el('article', { class: `entry${e.kind === 'roll' ? ' roll' : ''}${report ? ' rest-report' : ''}${damage ? ' damage-report' : ''}${action ? ' action-report' : ''}${m.toId ? ' whisper' : ''}` }, header);
  if (m.toId) {
    card.append(el('div', { class: 'message-recipient' }, el('span', { class: 'whisper-label' },
      `Whisper to ${m.toId === 'dm' ? 'Gamemaster' : m.to ?? (m.toId === playerId ? 'you' : m.toId)}`)));
  }
  if (report) {
    card.append(el('div', { class: 'rest-banner' },
      el('strong', {}, report.kind === 'long' ? 'Long rest' : 'Short rest')),
    el('div', { class: 'rest-subtitle' }, report.subtitle),
    el('ul', { class: 'rest-moves' }, ...report.lines.map((l) => el('li', {}, l))));
    return card;
  }
  if (damage) {
    card.append(el('div', { class: 'damage-banner' }, el('strong', {}, 'Damage taken'), el('span', { class: `damage-tag ${damage.type}` }, damage.type)),
    el('div', { class: 'damage-hero' },
      el('div', { class: `damage-hp${damage.hp ? '' : ' none'}` },
        el('strong', {}, damage.hp ? `−${damage.hp}` : '0'), el('span', {}, 'HP marked')),
      el('div', { class: 'damage-facts' },
        el('div', { class: 'damage-name' }, damage.name),
        el('div', { class: 'damage-amount' }, String(damage.input),
          ...(damage.damage !== damage.input ? [el('span', { class: 'damage-arrow' }, ' → '), el('strong', {}, String(damage.damage))] : []),
          ' damage'),
        el('span', { class: `damage-severity${damage.severity === 'Negated' ? ' negated' : ''}` }, damage.severity))));
    if (damage.lines.length) card.append(el('ul', { class: 'damage-lines' }, ...damage.lines.map((l) => el('li', {}, l))));
    return card;
  }
  if (action) {
    card.append(el('div', { class: 'action-banner' }, el('strong', {}, 'Action used')),
      el('div', { class: 'action-title' }, action.title),
      el('ul', { class: 'action-lines' }, ...action.lines.map((l) => el('li', {}, l))));
    if (m.rollSource) {
      card.append(el('details', { class: 'roll-source' },
        el('summary', {}, `Source: ${m.rollSource.name}`),
        el('div', { class: 'roll-source-content' },
          el('div', { class: 'shared-source' }, m.rollSource.source),
          el('strong', {}, m.rollSource.name), rich(m.rollSource.text))));
    }
    return card;
  }
  card.append(e.kind === 'roll' ? rollContent(e.m) : e.m.sharedCard
    ? renderSharedCard(e.m.sharedCard, onGrab, onError) : el('div', { class: 'message-text' }, e.m.text));
  if (m.rollSource) {
    card.append(el('details', { class: 'roll-source' },
      el('summary', {}, `Source: ${m.rollSource.name}`),
      el('div', { class: 'roll-source-content' },
        el('div', { class: 'shared-source' }, m.rollSource.source),
        el('strong', {}, m.rollSource.name), rich(m.rollSource.text))));
  }
  if (e.kind === 'roll' && m.fromId === playerId && rollActions) {
    const actions = rollActions(e.m as RollMessage);
    if (actions) card.append(actions);
  }
  return card;
}

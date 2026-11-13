import { el } from './login';
import type { ChatMessage, RollDetail, RollMessage, RollSource } from './protocol';
import { OUTCOME_LABEL, dualityResult, dualitySucceeds, signed } from './daggerheart/data';
import { renderSharedCard, type GrabQuantity, type SharedCard } from './shared-cards';
import { parseRestReport } from './daggerheart/rest-summary';
import { parseDamageReport } from './daggerheart/damage-summary';
import { parseActionReport } from './daggerheart/action-summary';
import { rich } from './daggerheart/rich';

export type LogEntry = { kind: 'chat'; m: ChatMessage } | { kind: 'roll'; m: RollMessage };
let previewId = 0;

function sourcePreview(row: HTMLElement, source: RollSource | undefined): HTMLElement {
  if (!source) return row;
  const panel = el('div', { class: 'roll-source-preview', role: 'tooltip' },
    el('div', { class: 'shared-source' }, source.source),
    el('strong', {}, source.name), rich(source.text));
  row.tabIndex = 0;
  panel.id = `roll-source-preview-${++previewId}`;
  row.setAttribute('aria-describedby', panel.id);
  row.setAttribute('aria-label', `${row.textContent}. Source: ${source.name}`);
  row.classList.add('has-source-preview');
  const position = () => {
    row.classList.remove('source-preview-dismissed');
    const bounds = row.getBoundingClientRect();
    const width = Math.min(400, window.innerWidth - 24);
    panel.style.width = `${width}px`;
    panel.style.left = `${Math.max(12, Math.min(bounds.left, window.innerWidth - width - 12))}px`;
    const height = panel.getBoundingClientRect().height;
    panel.style.top = `${Math.max(12, bounds.bottom + height + 12 <= window.innerHeight ? bounds.bottom + 4 : bounds.top - height - 4)}px`;
  };
  row.addEventListener('mouseenter', position);
  row.addEventListener('focus', position);
  row.addEventListener('keydown', event => {
    if (event.key === 'Escape') row.classList.add('source-preview-dismissed');
  });
  row.append(panel);
  return row;
}

export function keptRolls(expression: string, rolls: number[]): boolean[] {
  const kept = rolls.map(() => true);
  let index = 0;
  for (const match of expression.matchAll(/(\d*)d\d+(?:k([hl])(\d+))?/gi)) {
    const count = Number(match[1] || 1);
    if (match[2]) {
      const low = match[2].toLowerCase() === 'l';
      const indices = Array.from({ length: Math.min(count, rolls.length - index) }, (_, n) => index + n);
      indices.sort((a, b) => (low ? Math.abs(rolls[a]) - Math.abs(rolls[b]) : Math.abs(rolls[b]) - Math.abs(rolls[a])) || a - b);
      for (const n of indices.slice(Number(match[3]))) kept[n] = false;
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
  const doubled = m.expression.startsWith('(') && m.expression.endsWith(')*2');
  const formula = doubled ? m.expression.slice(1, -3) : m.expression;
  for (const match of formula.matchAll(/([+-]?)(?:(\d*)d(\d+)(?:k[hl](\d+))?|(\d+))/gi)) {
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
  return doubled ? details.map((d) => ({ ...d, expression: `(${d.expression})*2`, total: d.total * 2 })) : details;
}

function isCriticalRoll(m: RollMessage): boolean {
  if (m.secret === true || (m.kind !== 'duality' && m.deathMove !== 'risk')) return false;
  const rolls = Array.isArray(m.rolls) ? m.rolls.filter((v): v is number => typeof v === 'number') : [];
  if (rolls.length < 2) return false;
  const result = dualityResult(rolls, m.total);
  const outcome = m.deathResult?.outcome ?? (result.outcome === 'crit' ? 'crit' : (m.outcome ?? result.outcome));
  return outcome === 'crit';
}

function rollContent(m: RollMessage): HTMLElement {
  const secret = m.secret === true;
  const details = rollDetails(m);
  const values = Array.isArray(m.rolls) ? m.rolls.filter((v): v is number => typeof v === 'number') : [];
  const kept = secret ? values.map(() => true) : keptRolls(m.expression, values);
  const duality = !secret && (m.kind === 'duality' || m.deathMove === 'risk') && values.length >= 2;
  const dice = el('div', { class: 'roll-dice' });
  let index = 0;
  for (const detail of details) {
    const sides: string[] = [];
    for (const match of detail.expression.matchAll(/(\d*)d(\d+)(?:k[hl]\d+)?/gi)) {
      const count = Math.min(Number(match[1] || 1), detail.rolls.length - sides.length);
      for (let i = 0; i < count; i++) sides.push(match[2]);
    }
    for (const [i, value] of detail.rolls.entries()) {
      const n = index++;
      if (duality && n > 1) continue;
      const name = duality ? (n === 0 ? 'Hope' : 'Fear') : `d${sides[i] ?? '?'}`;
      const discarded = kept[n] === false;
      const shown = secret ? '?' : String(value);
      const tile = el('span', { class: `roll-die${discarded ? ' discarded' : ''}${duality ? (n === 0 ? ' hope' : ' fear') : ''}${secret ? ' secret' : ''}`, title: secret ? `${name}: secret` : `${detail.label}: ${value}${discarded ? ' (discarded)' : ''}` },
        el('span', { class: 'roll-die-name' }, name), el('strong', {}, shown));
      tile.setAttribute('aria-label', `${name}: ${secret ? 'secret' : value}${discarded ? ' (discarded)' : ''}`);
      dice.append(tile);
    }
  }
  const doubled = !secret && m.expression.startsWith('(') && m.expression.endsWith(')*2');
  const modifier = m.total / (doubled ? 2 : 1) - (duality ? values[0] + values[1] : values.reduce((n, v, i) => n + (kept[i] ? v : 0), 0));
  const equation = el('div', { class: 'roll-equation' }, dice,
    ...(secret ? [] : [el('span', { class: `roll-modifier${modifier < 0 ? ' negative' : ''}`, title: duality ? 'Combined modifiers, including bonus dice' : 'Combined modifiers' }, signed(modifier))]),
    ...(doubled ? [el('span', { class: 'roll-modifier', title: 'Double the entire rolled damage, including modifiers' }, 'x2 (all)')] : []),
    el('span', { class: 'roll-equals' }, '='),
    el('div', { class: 'roll-total' }, el('span', {}, 'TOTAL'), el('strong', {}, secret ? '?' : String(m.total))));
  const body = el('div', { class: 'roll-body' },
    el('div', { class: 'roll-title' }, `${m.label ?? (secret ? 'Secret roll' : duality ? 'Duality roll' : 'Dice roll')}${m.source?.split(',').includes('reaction') ? ' - Reaction roll' : ''}`), equation);
  if (secret) return body;
  if (duality) {
    const r = dualityResult(values, m.total);
    const outcome = m.deathResult?.outcome ?? (r.outcome === 'crit' ? 'crit' : (m.outcome ?? r.outcome));
    const badges = el('div', { class: 'roll-outcomes' }, el('span', { class: `roll-outcome ${outcome}` }, OUTCOME_LABEL[outcome]));
    if (typeof m.difficulty === 'number') {
      const success = dualitySucceeds(r, m.difficulty);
      badges.append(el('span', { class: `roll-outcome ${success ? 'success' : 'failure'}` }, `${success ? 'Success' : 'Failure'} / Difficulty ${m.difficulty}`));
    }
    body.append(badges);
    if (m.deathResult) body.append(el('p', { class: 'muted' }, `Adjusted Hope ${m.deathResult.adjustedHope} (${signed(m.deathResult.bonus)} bonus) · Recovery ${m.deathResult.budget}${m.deathResult.converted ? " · Won't Stay Dead" : ''}${m.deathResult.claimed ? ' · Recovery resolved' : ''}`));
  }
  if (m.featureRoll) {
    body.append(el('div', { class: 'roll-feature-result' }, m.featureRoll.consequence));
  }
  const breakdown = el('details', { class: 'roll-breakdown' },
    el('summary', {}, 'Roll breakdown'),
    el('div', { class: 'roll-formula' }, m.expression),
    ...details.flatMap((d) => [
      sourcePreview(el('div', { class: 'roll-detail' },
        el('div', { class: 'roll-detail-source' }, el('span', {}, d.label),
          ...(d.rolls.length ? [el('small', {}, `${d.expression.replace(/^\+/, '')}: [${d.rolls.join(', ')}]`)] : []),
          ...(d.notes ?? []).filter(note => d.label === note.label || d.label.startsWith(`${note.label}:`))
            .map(note => el('small', {}, note.impact))),
        el('strong', { class: d.total < 0 ? 'negative' : '' }, signed(d.total))), d.rollSource ?? (
          m.rollSource && (d.label === m.rollSource.name || d.label.startsWith(`${m.rollSource.name} damage`)) ? m.rollSource : undefined)),
      ...(d.notes ?? []).filter(note => d.label !== note.label && !d.label.startsWith(`${note.label}:`))
        .map((note) => el('div', { class: 'roll-detail roll-note' },
        el('span', {}, note.label), el('span', {}, note.impact))),
    ]),
    el('div', { class: 'roll-detail roll-detail-total' }, el('span', {}, 'Result'), el('strong', {}, String(m.total))));
  body.append(breakdown);
  return body;
}

export function renderLogEntry(e: LogEntry, playerId: string, onGrab?: (card: SharedCard, quantity: GrabQuantity) => Promise<void>,
                               onError?: (message: string) => void,
                               rollActions?: (m: RollMessage | ChatMessage) => HTMLElement | null): HTMLElement {
  const m = e.m;
  const time = new Date(m.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const sender = m.fromId === 'dm' ? 'Gamemaster' : m.from;
  const initial = sender.slice(0, 1).toUpperCase();
  const hasPortrait = m.fromId !== 'dm' && m.fromArtwork && /^\/a\/[a-zA-Z0-9_-]+\.img$/.test(m.fromArtwork);
  const avatar = el('span', { class: 'message-avatar' }, ...(hasPortrait ? [] : [initial]));
  avatar.setAttribute('aria-hidden', 'true');
  if (hasPortrait) {
    const portrait = el('img', { src: m.fromArtwork, alt: '' });
    portrait.addEventListener('error', () => { portrait.remove(); avatar.append(initial); });
    avatar.append(portrait);
  }
  const header = el('header', { class: 'message-header' },
    avatar,
    el('strong', { class: 'message-sender' }, sender),
    el('time', { dateTime: new Date(m.ts).toISOString(), title: new Date(m.ts).toLocaleString() }, time));
  const system = e.kind === 'chat' && !m.toId && m.fromId === 'dm' && (e.m.system === 'sceneEnd' || e.m.system === 'newSession') ? e.m.system : null;
  const plainChat = e.kind === 'chat' && !m.toId && !e.m.sharedCard && !system;
  const report = plainChat ? parseRestReport(e.m.text) : null;
  const damage = plainChat && !report ? parseDamageReport(e.m.text) : null;
  const action = plainChat && !report && !damage ? parseActionReport(e.m.text) : null;
  const reaction = e.kind === 'chat' && plainChat && !report && !damage && !action && e.m.reactionPrompt ? e.m.reactionPrompt : null;
  const card = el('article', { class: `entry${e.kind === 'roll' ? ' roll' : ''}${e.kind === 'roll' && isCriticalRoll(e.m) ? ' critical-roll' : ''}${report ? ' rest-report' : ''}${damage ? ' damage-report' : ''}${action ? ' action-report' : ''}${reaction ? ' reaction-report' : ''}${system ? ' system-report' : ''}${m.toId ? ' whisper' : ''}` }, header);
  if (m.toId) {
    card.append(el('div', { class: 'message-recipient' }, el('span', { class: 'whisper-label' },
      `Whisper to ${m.toId === 'dm' ? 'Gamemaster' : m.to ?? (m.toId === playerId ? 'you' : m.toId)}`)));
  }
  if (system && e.kind === 'chat') {
    const lines = e.m.text.split('\n');
    card.append(el('div', { class: 'system-banner' }, el('strong', {}, system === 'newSession' ? 'New session' : 'Scene ended')),
      el('div', { class: 'rest-subtitle' }, lines[1] ?? ''),
      el('ul', { class: 'rest-moves' }, ...lines.slice(2).map((l: string) => el('li', {}, l.replace(/^• /, '')))));
    return card;
  }
  if (report) {
    card.append(el('div', { class: 'rest-banner' },
      el('strong', {}, report.kind === 'long' ? 'Long rest' : 'Short rest')),
    el('div', { class: 'rest-subtitle' }, report.subtitle),
    el('ul', { class: 'rest-moves' }, ...report.lines.map((l) => el('li', {}, l))));
    if (e.kind === 'chat' && rollActions && (e.m.restDice?.length || e.m.actionBenefits)) {
      const actions = rollActions(e.m);
      if (actions) card.append(actions);
    }
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
    card.append(el('div', { class: 'action-banner' }, el('strong', {}, action.title.startsWith('Death move - ') ? 'Death move' : 'Action used')),
      el('div', { class: 'action-title' }, action.title),
      el('ul', { class: 'action-lines' }, ...action.lines.map((l) => el('li', {}, l))));
    if (m.rollSource) {
      card.append(el('details', { class: 'roll-source' },
        el('summary', {}, `Source: ${m.rollSource.name}`),
        el('div', { class: 'roll-source-content' },
          el('div', { class: 'shared-source' }, m.rollSource.source),
          el('strong', {}, m.rollSource.name), rich(m.rollSource.text))));
    }
    if (e.kind === 'chat' && e.m.actionBenefits && rollActions) {
      const actions = rollActions(e.m);
      if (actions) card.append(actions);
    }
    return card;
  }
  if (reaction && e.kind === 'chat') {
    const source = /^(.*?) · \w+ Reaction - /.exec(e.m.text)?.[1];
    card.append(el('div', { class: 'reaction-banner' }, el('strong', {}, 'Reaction roll'),
        el('span', { class: 'reaction-trait' }, reaction.trait)),
      ...(source ? [el('div', { class: 'reaction-source' }, source)] : []),
      el('div', { class: 'reaction-text' }, `Make ${/^[AEIOU]/.test(reaction.trait) ? 'an' : 'a'} ${reaction.trait} Reaction Roll.`));
    if (rollActions) {
      const actions = rollActions(e.m);
      if (actions) card.append(actions);
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
  if (rollActions && (e.kind === 'roll' || e.m.actionBenefits || e.m.reactionPrompt)) {
    const actions = rollActions(e.m);
    if (actions) card.append(actions);
  }
  return card;
}

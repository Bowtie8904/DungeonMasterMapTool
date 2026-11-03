import { el } from '../login';
import {
  Advantage, Armor, CharacterSheet, DomainCard, Feature, InventoryItem, Spell, Track, TRAIT_ACTIONS, TRAIT_ORDER, TraitName, Weapon,
  dualityExpression, handsUsed, multiclassesOf, shortTrait, signed, tierOf,
} from './data';
import {
  ActionDef, ActiveEffect, Automation, RollBonus, StatSource, actionBlocker, addEffect, asksSpellcastRoll, consumeBonus, costText, damageExpression,
  availableRollOptions, commitRollOptions, criticalBonus, damageContributions, derivedStats, evalValue, resourceDefinitions, rollBonus,
  rollOptionDice, runAction, selectedRollBonus, selectionBlocker, sheetActions, statBreakdown, syncDerived, traitContributions, traitTotal, usesLeft,
} from './engine';
import type { RollContribution, RollSource } from '../protocol';
import { rollSourceFor } from './roll-source';

import { openLevelUp, openMancer, openTransformation } from './mancer';
import { inline, rich } from './rich';
import { MAX_LEVEL } from './rules';
import { openTestingEditor } from './testing-view';
import { openRestDialog } from './rest-view';
import { openDamageDialog } from './damage-view';
import { iconButton } from '../icons';
import { INVENTORY_DRAG, prepareInventoryDrag, transferable, type SharedEntry } from '../shared-cards';

const CARD_DRAG = 'application/x-dmmt-domain-card';

export interface RollRequest {
  label: string;
  expression: string;
  kind?: 'duality';
  contributions?: RollContribution[];
  rollSource?: RollSource;
  /** Hints echoed by the host so the roller's chat card can offer Damage and reroll buttons. */
  ref?: string;
  actorId?: string;
  source?: string;
  weapon?: string;
}

/** Lets other parts of the page (the compendium) change a sheet and have an open view refresh. */
export class SheetBus {
  private listeners: Array<() => void> = [];
  /** Recently sent rolls by ref, so chat buttons can repeat them. */
  readonly sent = new Map<string, RollRequest>();
  /** Opens the damage dialog for an equipped weapon; set by the rendered sheet. */
  damage?: (weapon: string, critical?: boolean) => boolean;
  /** Opens the damage dialog for a spell; onSent runs once the roll was sent. */
  spellDamage?: (label: string, spec: { dice: string; fixed?: boolean; extraDice?: number }, critical: boolean, onSent: () => void, rollSource?: RollSource) => boolean;
  subscribe(fn: () => void): void { this.listeners.push(fn); }
  notify(): void { this.listeners.forEach((fn) => fn()); }
}

export interface SheetViewOptions {
  character: CharacterSheet;
  artwork?: string;
  /** Sends a roll to the shared roll log; returns false when it could not be sent. */
  onRoll: (roll: RollRequest) => boolean;
  /** Posts a short message (action results) to the shared chat. */
  onLog?: (text: string, rollSource?: RollSource) => void;
  actorId?: string;
  onShare?: (entry: SharedEntry) => void;
  onPrepareShare?: () => Promise<void>;
  bus?: SheetBus;
  /** Called after anything on the sheet may have changed (the host debounces and saves). */
  onChange?: () => void;
  supportsKeptDice?: () => boolean;
}

type Page = 'char' | 'equip' | 'loot' | 'cards' | 'effects' | 'bg';
const PAGES: Array<[Page, string]> = [
  ['char', 'Character'], ['cards', 'Domain Cards'], ['equip', 'Equipment'], ['loot', 'Loot'], ['bg', 'Background'], ['effects', 'Effects'],
];

function pips(track: Track, kind: string, color: string, round = false): HTMLElement {
  const box = el('div', { class: 'pips' });
  box.dataset.kind = kind;
  box.style.setProperty('--c', color);
  const btns: HTMLButtonElement[] = [];
  const paint = () => btns.forEach((b, i) => b.classList.toggle('on', i < track.on));
  for (let i = 0; i < track.total; i++) {
    const locked = i >= track.slots;
    const b = el('button', { type: 'button', class: `pip${round ? ' round' : ''}${locked ? ' locked' : ''}`, title: locked ? 'Locked - gained through advancement' : '' });
    b.setAttribute('aria-label', `${kind} ${i + 1}`);
    b.addEventListener('click', () => {
      if (locked) return;
      track.on = track.on === i + 1 ? i : i + 1;
      paint();
    });
    btns.push(b);
    box.append(b);
  }
  paint();
  return box;
}

const tag = (text: string, cls = '') => el('span', { class: `tag ${cls}`.trim() }, text);

function weaponBadges(w: Weapon): HTMLElement {
  return el('div', { class: 'badges' }, tag(w.damageType, w.damageType), tag(w.slot), tag(shortTrait(w.trait)), tag(w.range), tag(w.damage), tag(`${w.hands}H`), tag(`T${w.tier}`));
}

function armorBadges(a: Armor): HTMLElement {
  const base = a.major !== undefined && a.severe !== undefined ? [tag(`${a.major} / ${a.severe}`)] : [];
  return el('div', { class: 'badges' }, tag(`Score ${a.score}`), ...base, tag(`T${a.tier}`));
}

function featureCard(f: Feature, onUse?: () => void): HTMLElement {
  const name = el('div', { class: 'ft' }, el('span', { class: 'feature-name' }, f.name));
  if (onUse) {
    const b = el('button', { class: 'small', type: 'button', textContent: `Use (${f.cost ?? 0} Hope)` });
    b.addEventListener('click', onUse);
    name.append(b);
  }
  return el('div', { class: `feat${f.hope ? ' hope' : ''}` }, el('div', { class: 'src' }, f.source), name, rich(f.text));
}

export function renderSheetView(opts: SheetViewOptions): HTMLElement {
  const c = opts.character;
  let page: Page = 'char';
  const root = el('div', { class: 'dh' });
  let sourceTooltip: HTMLElement | undefined;
  const hideSourceTooltip = () => {
    sourceTooltip?.remove();
    sourceTooltip = undefined;
  };
  root.addEventListener('scroll', hideSourceTooltip, true);
  root.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') hideSourceTooltip();
  });

  function share(node: HTMLElement, entry: SharedEntry): HTMLElement {
    if (!opts.onShare) return node;
    const button = iconButton('chat', `Post ${entry.item.name} to chat`, () => opts.onShare!(entry));
    button.classList.add('share-icon');
    const header = entry.category === 'feature'
      ? node.querySelector('.src') ?? node
      : node.querySelector('td, .ft, .top, .hd') ?? node;
    header.append(button);
    if (transferable(entry) && opts.actorId && opts.onPrepareShare) {
      node.draggable = true;
      node.classList.add('inventory-draggable');
      node.addEventListener('dragstart', (e) => {
        if (!e.dataTransfer) return;
        const token = prepareInventoryDrag(opts.onPrepareShare!);
        e.dataTransfer.effectAllowed = 'copy';
        e.dataTransfer.setData(INVENTORY_DRAG, JSON.stringify({ actorId: opts.actorId, entry, token }));
      });
    }
    return node;
  }

  const sendRoll = (source: string, weapon?: string, rollSource?: RollSource) => (r: RollRequest): boolean => {
    const ref = Math.random().toString(36).slice(2, 12);
    const full: RollRequest = { ...r, ref, source, ...(rollSource ? { rollSource } : {}), ...(weapon ? { weapon } : {}), ...(opts.actorId ? { actorId: opts.actorId } : {}) };
    const sent = opts.onRoll(full);
    if (sent && opts.bus) {
      opts.bus.sent.set(ref, full);
      if (opts.bus.sent.size > 60) opts.bus.sent.delete(opts.bus.sent.keys().next().value!);
    }
    return sent;
  };
  const rollDuality = (label: string, trait: TraitName | null, targets: string[], weapon?: Weapon, rollSource?: RollSource) => {
    const allTargets = [...targets, 'action'];
    const bonus = rollBonus(c, allTargets, weapon, false);
    openRollDialog(root, c, label, trait ? traitContributions(c, trait) : [], bonus, sendRoll(allTargets.join(','), weapon?.name, rollSource ?? (weapon ? rollSourceFor(c, `weapon:${weapon.name}`) : undefined)), () => { consumeBonus(c, bonus); render(); }, allTargets, undefined, undefined, weapon);
  };
  const rollDamage = (label: string, expression: string, rollSource?: RollSource) => opts.onRoll({ label, expression, rollSource });
  const openWeaponDamage = (w: Weapon, critical = false, targets = ['damage'], after?: () => void, rollSource?: RollSource) => {
    const bonus = rollBonus(c, targets, w, false);
    openRollDialog(root, c, `${w.name} damage`, [], bonus, sendRoll('damage', w.name, rollSource ?? rollSourceFor(c, `weapon:${w.name}`)),
      () => { consumeBonus(c, bonus); after?.(); render(); }, targets, w, opts.supportsKeptDice, undefined, critical);
  };
  if (opts.bus) opts.bus.spellDamage = (label, spec, critical, onSent, rollSource) => {
    const spell: Weapon = { name: label, slot: 'Primary', damageType: 'magic', trait: 'Presence', range: '', damage: spec.dice, hands: 1, tier: 0, fixedDice: spec.fixed,
      ...(spec.extraDice ? { automation: { damage: { extraDice: spec.extraDice, dropLowest: 0 } } } : {}) };
    openWeaponDamage(spell, critical, ['spelldamage'], onSent, rollSource);
    return true;
  };
  if (opts.bus) opts.bus.damage = (name, critical) => {
    const w = c.activeWeapons.find((x) => x.name === name);
    if (w) openWeaponDamage(w, critical);
    return !!w;
  };
  opts.bus?.subscribe(() => render());
  for (const ev of ['click', 'input', 'change']) root.addEventListener(ev, () => opts.onChange?.());

  const cardResourceIds = (): Set<string> => new Set([...c.loadout.map((d) => d.automation), ...c.inventory.map((i) => i.automation)].flatMap((a) => a?.resources ?? []).map((r) => r.id));
  function actionRow(key: string, a: Automation | undefined, label: string, hooks: { extraBlock?: () => string | null; after?: () => void } = {}): HTMLElement | null {
    const actions = sheetActions(a);
    if (!actions.length && !(key.startsWith('item:') && a?.resources?.length)) return null;
    const row = el('div', { class: 'row actions', style: 'margin-top:6px;gap:6px;flex-wrap:wrap;align-items:center' });
    if (key.startsWith('item:')) {
      for (const r of a?.resources ?? []) row.append(el('label', { class: 'muted', style: 'font-size:12px;display:flex;gap:4px;align-items:center' }, r.label, poolInput(r)));
    }
    for (const action of actions) row.append(actionButton(key, a!, action, label, hooks));
    const left = a ? usesLeft(c, key, a) : null;
    if (left !== null && a?.uses) row.append(el('span', { class: 'muted', style: 'font-size:11.5px' }, `${left}/${a.uses.max} per ${a.uses.per.replace(/([A-Z])/g, ' $1').toLowerCase()}`));
    return row;
  }

  function actionButton(key: string, a: Automation, action: ActionDef, label: string, hooks: { extraBlock?: () => string | null; after?: () => void }): HTMLElement {
    const cost = costText(action.cost, c);
    const b = el('button', { class: 'small primary', type: 'button' }, cost ? `${action.label} (${cost})` : action.label);
    const block = hooks.extraBlock?.() ?? actionBlocker(c, key, a, action);
    if (block) { b.disabled = true; b.title = block; }
    b.addEventListener('click', () => {
      if (action.sendRoll) {
        if (actionBlocker(c, key, a, action)) return;
        const base = action.sendRoll.base === 'evasion' ? derivedStats(c).evasion : 0;
        const sent = opts.onRoll({
          label: action.sendRoll.label.slice(0, 80), expression: `${base}+${action.sendRoll.dice}`,
          contributions: [{ label: 'Evasion', expression: String(base) }, { label: `${label} ${action.sendRoll.dice}`.slice(0, 120), expression: action.sendRoll.dice }],
          rollSource: rollSourceFor(c, key),
          ...(opts.actorId ? { actorId: opts.actorId } : {}),
        });
        if (!sent) { opts.onLog?.('Not connected. The roll was not sent and nothing was spent.'); return; }
      }
      const message = runAction(c, key, a, action, label === action.label ? label : `${label} - ${action.label}`);
      if (message === null) return;
      hooks.after?.();
      opts.onLog?.(`${c.name}: ${message}`, rollSourceFor(c, key));
      render();
    });
    return b;
  }

  const render = () => {
    hideSourceTooltip();
    const scroll = root.querySelector('.pages')?.scrollTop ?? 0;
    const sidebarScroll = root.querySelector('.sheet-sidebar')?.scrollTop ?? 0;
    syncDerived(c);
    root.replaceChildren(header(), ...(c.mancer?.done === false ? [startBanner()] : [
      el('div', { class: 'sheet-layout' },
        el('aside', { class: 'sheet-sidebar', ariaLabel: 'Traits' }, traits()),
        el('div', { class: 'sheet-main' }, tabs(), pages())),
    ]));
    opts.onChange?.();
    const p = root.querySelector('.pages');
    if (p) p.scrollTop = scroll;
    const sidebar = root.querySelector('.sheet-sidebar');
    if (sidebar) sidebar.scrollTop = sidebarScroll;
  };

  const launchMancer = () => { void openMancer(root, c, () => render()); };
  const launchLevelUp = () => { void openLevelUp(root, c, () => render()); };

  function startBanner(): HTMLElement {
    const go = el('button', { class: 'primary', type: 'button' }, 'Start the Character Mancer');
    go.addEventListener('click', launchMancer);
    return el(
      'div', { class: 'dh-start' },
      el('h2', {}, 'Create your character'),
      el('p', {}, 'This sheet is empty. The Character Mancer guides you step by step through class, heritage, traits, gear and more - it only takes a few minutes.'),
      go,
    );
  }

  function header(): HTMLElement {
    const hidden = cardResourceIds();
    const extraResources = resourceDefinitions(c).filter((r) => !hidden.has(r.id));
    const portrait = el('div', { class: 'portrait', title: 'Character image' });
    if (opts.artwork) portrait.append(el('img', { src: opts.artwork, alt: '' }));
    else portrait.textContent = c.name.slice(0, 1).toUpperCase();
    const sub = (label: string, ...bold: string[]) => el('div', { class: 'sub' }, label, ...bold.map((b) => el('b', {}, b)));
    const restActions = el(
      'div', { class: 'header-actions' },
      ...([['Short rest', 'short', 'Short rest: refreshes short-rest abilities and ends effects lasting until a rest'], ['Long rest', 'long', 'Long rest: refreshes all rest abilities and ends effects lasting until a rest']] as const)
        .map(([label, kind, title]) => {
          const b = el('button', { class: 'small', type: 'button', title }, label);
          b.addEventListener('click', () => openRestDialog(root, c, kind, (summary) => { opts.onLog?.(summary); opts.onChange?.(); render(); }));
          return b;
        }),
      el('button', { class: 'small', type: 'button', title: 'Offered when the last HP is marked (not available yet)', disabled: true }, 'Death move...'),
    );
    return el(
      'header', { class: 'ident' },
      el(
        'div', { class: 'ident-profile' },
        el(
          'div', { class: 'profile-top' },
          portrait,
          el(
            'div', { class: 'ident-main' },
            el('p', { class: 'name' }, c.name, el('span', { class: 'muted', style: 'font-size:13px;font-weight:400' }, ` (${c.pronouns})`)),
            ...(c.testingCharacter ? [tag('TESTING CHARACTER - unrestricted', 'hope')] : []),
            sub('Class: ', c.className, ` - Subclass: ${c.subclass}`),
            ...multiclassesOf(c).map((m) => sub('Multiclass: ', m.className, ` - Subclass: ${m.subclass.replace(/^[^:]+:\s*/, '')} - Domain: ${m.domain}`)),
            sub('Domains: ', c.domains.join(' & ')),
            sub('Heritage: ', `${c.ancestry} ancestry, ${c.community} community${c.transformation ? `, ${c.transformation} transformation` : ''}`),
            ...(c.player ? [sub('Played by ', c.player)] : []),
            restActions,
          ),
        ),
        ...(c.mancer?.done === false ? [] : [el('div', { class: 'header-vitals-row' }, vitals())]),
      ),
      el(
        'div', { class: 'ident-right' },
        el(
          'div', { class: 'lvl-row' },
          levelBox(c.level, 'Level'), levelBox(tierOf(c.level), 'Tier'),
          levelUpButton(),
        ),
        vital('Hope', pips(c.hope, 'hope', 'var(--hope)', true)),
        vital('Proficiency', pips(c.proficiency, 'prof', 'var(--accent)', true)),
        ...extraResources.map((r) => vital(r.label, poolInput(r))),
      ),
    );
  }

  function poolInput(r: NonNullable<Automation['resources']>[number]): HTMLInputElement {
    const input = el('input', { type: 'number', min: '0', step: '1', value: String(c.resources?.[r.id] ?? 0), ariaLabel: r.label });
    if (r.max !== undefined) input.max = String(Math.max(0, evalValue(r.max, c)));
    input.addEventListener('change', () => {
      if (!Number.isFinite(input.valueAsNumber)) { input.value = String(c.resources?.[r.id] ?? 0); return; }
      c.resources![r.id] = Math.max(0, Math.min(r.max === undefined ? Infinity : evalValue(r.max, c), Math.floor(input.valueAsNumber)));
      render();
    });
    return input;
  }

  function levelUpButton(): HTMLElement {
    if (c.testingCharacter) {
      const edit = el('button', { class: 'primary small', type: 'button' }, 'Edit testing build');
      edit.addEventListener('click', () => { void openTestingEditor(root, c, () => render()); });
      return edit;
    }
    const max = c.level >= MAX_LEVEL;
    const b = el('button', { class: 'primary small', type: 'button', title: max ? 'Maximum level reached' : `Level up to ${c.level + 1}` }, 'Level up');
    b.disabled = max;
    b.addEventListener('click', launchLevelUp);
    return b;
  }

  const levelBox = (v: number, l: string) => el('div', { class: 'level-box' }, el('div', { class: 'v' }, String(v)), el('div', { class: 'l' }, l));
  const vital = (label: string, body: HTMLElement, cls = '') => el('div', { class: `vital ${cls}`.trim() }, el('div', { class: 'lbl' }, label), body);

  function tabs(): HTMLElement {
    const nav = el('nav', { class: 'tabs' });
    nav.setAttribute('role', 'tablist');
    for (const [id, label] of PAGES) {
      const count = id === 'effects' && c.effects.length ? ` (${c.effects.length})` : '';
      const b = el('button', { class: 'tab', type: 'button' }, label + count);
      b.setAttribute('role', 'tab');
      b.setAttribute('aria-selected', String(id === page));
      b.addEventListener('click', () => { page = id; render(); });
      nav.append(b);
    }
    return nav;
  }

  function traits(): HTMLElement {
    const traits = el('div', { class: 'traits', title: 'Click a trait to roll it. Circular marks are updated automatically by level-ups (marked traits cannot be raised again this tier).' });
    for (const name of TRAIT_ORDER) {
      const t = c.traits.find((x) => x.name === name)!;
      const mark = el('span', {
        class: `trait-mark${t.marked ? ' marked' : ''}`,
        role: 'img',
        title: 'Updated automatically by level-ups; marked traits cannot be raised again this tier.',
      });
      mark.setAttribute('aria-label', `${name}: ${t.marked ? 'marked' : 'unmarked'}`);
      mark.addEventListener('click', (e) => e.stopPropagation());
      const th2 = el('div', { class: 'th' }, el('span', { class: 'nm' }, name));
      if (t.spellcast) th2.append(el('span', { class: 'spell', title: 'Subclass Spellcast trait' }, '\u2726'));
      const total = traitTotal(c, name);
      const md = el('span', { class: 'md', title: total !== t.mod ? `Base ${signed(t.mod)}, modified by equipment and effects` : '' }, signed(total));
      if (total !== t.mod) md.style.color = total > t.mod ? 'var(--hope, #4a9)' : 'var(--hp, #c55)';
      const tile = el('div', { class: 'trait' }, mark, th2, md, el('span', { class: 'vb' }, TRAIT_ACTIONS[name]));
      tile.addEventListener('click', () => rollDuality(name, name, [`trait:${name}`]));
      traits.append(tile);
    }
    return traits;
  }

  function experiences(): HTMLElement {
    return card('Experiences', null, el('div', { class: 'list' }, ...c.experiences.map((x) => el('div', { class: 'row exp' }, el('span', { class: 'grow' }, x.name), el('span', { class: 'mod' }, signed(x.mod))))));
  }

  function vitals(): HTMLElement {
    const th = derivedStats(c);
    const thr = (n: string, t: string) => el('div', { class: 'thr' }, el('div', { class: 'n' }, n), el('div', { class: 't' }, t));
    const sep = () => el('span', { class: 'thr-sep' }, '|');
    const srcs = statBreakdown(c);
    const withSources = (v: HTMLElement, rows: StatSource[], total: number | string, note = '') => {
      v.classList.add('has-src');
      v.tabIndex = 0;
      const tooltip = el('div', { class: 'stat-tooltip', role: 'tooltip' }, ...rows.map((r) => el('div', { class: 'sr' }, el('span', {}, r.label), el('b', {}, signed(r.value)))), el('div', { class: 'sr tot' }, el('span', {}, note || 'Total'), el('b', {}, String(total))));
      tooltip.id = `stat-source-${Math.random().toString(36).slice(2)}`;
      v.setAttribute('aria-describedby', tooltip.id);
      const show = () => {
        hideSourceTooltip();
        root.append(tooltip);
        sourceTooltip = tooltip;
        const bounds = root.getBoundingClientRect();
        const anchor = v.getBoundingClientRect();
        const left = anchor.left - bounds.left;
        const below = anchor.bottom - bounds.top + 4;
        const above = anchor.top - bounds.top - tooltip.offsetHeight - 4;
        tooltip.style.left = `${Math.max(0, Math.min(left, root.clientWidth - tooltip.offsetWidth))}px`;
        tooltip.style.top = `${Math.max(0, Math.min(below + tooltip.offsetHeight <= root.clientHeight ? below : above, root.clientHeight - tooltip.offsetHeight))}px`;
      };
      v.addEventListener('mouseenter', show);
      v.addEventListener('focus', show);
      v.addEventListener('mouseleave', hideSourceTooltip);
      v.addEventListener('blur', hideSourceTooltip);
      return v;
    };
    const thresholds = vital('Thresholds', el('div', { class: 'thresholds' }, thr(`< ${th.major}`, 'Minor'), sep(), thr(String(th.major), 'Major'), sep(), thr(String(th.severe), 'Severe')), 'v-th');
    const damageButton = iconButton('shield', 'Take damage: calculate HP to mark and mitigation', () => {
      openDamageDialog(root, c, (summary) => { opts.onLog?.(summary); opts.onChange?.(); render(); });
    });
    damageButton.classList.add('dh-dmg-btn');
    damageButton.addEventListener('click', (e) => e.stopPropagation());
    thresholds.querySelector('.lbl')?.append(damageButton);
    return el(
      'section', { class: 'vitals' },
      el(
        'div', { class: 'v-left' },
        withSources(vital('Evasion', el('div', { class: 'big' }, String(th.evasion)), 'v-e'), srcs.evasion, th.evasion),
        withSources(vital('Armor', pips(c.armorTrack, 'armor', 'var(--armor)'), 'v-a'), srcs.armor, th.armorSlots, 'Armor slots'),
        withSources(thresholds,
          [...srcs.major.map((r) => ({ ...r, label: `${r.label} (major)` })), ...srcs.severe.map((r) => ({ ...r, label: `${r.label} (severe)` }))], `${th.major} / ${th.severe}`, 'Total (major / severe)'),
        vital('HP', pips(c.hp, 'hp', 'var(--hp)'), 'v-hp'),
        vital('Stress', pips(c.stress, 'stress', 'var(--stress)'), 'v-st'),
      ),
    );
  }

  function pages(): HTMLElement {
    const wrap = el('div', { class: 'pages' });
    // Dragover fires continuously while dragging, so scroll when the pointer is near the top or bottom edge.
    wrap.addEventListener('dragover', (e) => {
      const r = wrap.getBoundingClientRect();
      const zone = Math.min(70, r.height / 4);
      if (e.clientY < r.top + zone) wrap.scrollTop -= Math.ceil((r.top + zone - e.clientY) / 4);
      else if (e.clientY > r.bottom - zone) wrap.scrollTop += Math.ceil((e.clientY - (r.bottom - zone)) / 4);
    });
    wrap.append(page === 'char' ? charPage() : page === 'effects' ? effectsPage() : page === 'equip' ? equipPage() : page === 'loot' ? lootPage() : page === 'cards' ? cardsPage() : bgPage());
    return wrap;
  }

  function effectsPage(): HTMLElement {
    const untilText = (e: ActiveEffect) => ({ manual: 'Until dismissed', rest: 'Until your next rest', shortRest: 'Until a short rest', longRest: 'Until a long rest', scene: 'Until the end of the scene', session: 'Until the end of the session' })[e.until ?? 'manual'];
    const rows = c.effects.map((e) => {
      const dismiss = el('button', { class: 'small', type: 'button' }, 'Remove');
      dismiss.addEventListener('click', () => { c.effects.splice(c.effects.indexOf(e), 1); render(); });
      const mods = (e.modifiers ?? []).map((m) => {
        const v = m.dice ? `+${m.dice}` : typeof m.value === 'number' ? signed(m.value) : String(m.value);
        return m.stat ? `${m.stat.replace('trait.', '')} ${v}` : `next ${(m.roll ?? '').replace('trait:', '')} roll ${v}`;
      });
      return el(
        'div', { class: `feat${e.kind === 'buff' ? ' hope' : ''}`, style: e.kind === 'debuff' ? 'border-left:3px solid var(--hp, #c55)' : '' },
        el('div', { class: 'src' }, `${e.kind === 'buff' ? 'Buff' : 'Debuff'} - ${untilText(e)}${e.source ? ` - from ${e.source}` : ''}`),
        el('div', { class: 'ft' }, e.name, el('span', { style: 'float:right' }, dismiss)),
        rich(e.text),
        ...(mods.length ? [el('div', { class: 'badges' }, ...mods.map((m) => tag(m)))] : []),
      );
    });
    const name = el('input', { placeholder: 'Condition or effect name' });
    const kind = el('select', {}, el('option', { value: 'debuff' }, 'Debuff'), el('option', { value: 'buff' }, 'Buff'));
    const text = el('input', { placeholder: 'What it does (optional)' });
    const add = el('button', { class: 'small', type: 'button' }, 'Add effect');
    add.addEventListener('click', () => {
      if (!name.value.trim()) return;
      addEffect(c, { name: name.value.trim(), kind: kind.value as 'buff' | 'debuff', text: text.value.trim(), until: 'manual' }, 'Manual');
      render();
    });
    return el(
      'div', {},
      card('Active effects', el('span', { class: 'muted' }, 'Buffs and debuffs from potions, features and conditions'), ...(rows.length ? rows : [el('span', { class: 'muted' }, 'No active effects. Drinking a potion or using a feature with a lasting effect adds it here.')])),
      card('Add a manual effect', null, el('div', { class: 'row', style: 'gap:6px;flex-wrap:wrap' }, name, kind, text, add)),
    );
  }

  function card(title: string, right: Node | null, ...body: Array<Node | string>): HTMLElement {
    const h = el('h3', {}, title);
    if (right) h.append(el('span', { class: 'right' }, right));
    return el('div', { class: 'card' }, h, ...body);
  }

  function charPage(): HTMLElement {
    const feat = (f: Feature) => {
      const node = featureCard(f, f.hope && !f.automation?.actions ? () => {
        if (c.hope.on < (f.cost ?? 0)) return;
        c.hope.on -= f.cost ?? 0;
        render();
      } : undefined);
      const actions = actionRow(`feat:${f.name}`, f.automation, f.name);
      if (actions) node.append(actions);
      return share(node, { category: 'feature', item: f });
    };
    const tf = el('button', { class: 'small', type: 'button', title: 'A character can have one transformation; it counts as heritage' }, c.transformation ? `Transformation: ${c.transformation} (change)` : 'Add a transformation');
    tf.addEventListener('click', () => {
      if (c.testingCharacter) void openTestingEditor(root, c, () => render());
      else void openTransformation(root, c, () => render());
    });
    return el(
      'div', { class: 'cols char-cols' },
      el('div', {}, card('Class & subclass features', null, ...c.features.map(feat))),
      el('div', {}, card('Heritage features', tf, ...c.heritage.map(feat))),
    );
  }

  function weaponSlot(w: Weapon): HTMLElement {
    const mod = traitTotal(c, w.trait);
    const attack = el('button', { class: 'primary small', type: 'button' }, `Attack ${signed(mod)}`);
    attack.addEventListener('click', () => rollDuality(`Attack - ${w.name} (${w.trait})`, w.trait, ['attack', `trait:${w.trait}`], w));
    const dmg = el('button', { class: 'small', type: 'button' }, 'Damage');
    dmg.addEventListener('click', () => openWeaponDamage(w));
    const unequip = el('button', { class: 'small', type: 'button' }, 'Unequip');
    unequip.addEventListener('click', () => { c.activeWeapons.splice(c.activeWeapons.indexOf(w), 1); c.carriedWeapons.push(w); render(); });
    return share(el(
      'div', { class: 'slot' },
      el('div', { class: 'top' }, el('span', { class: 'cat' }, w.slot), el('span', { class: 'nm' }, w.name)),
      weaponBadges(w),
      ...(w.feature ? [el('div', { class: 'feat', style: 'margin-top:6px' }, el('div', { class: 'ft' }, w.feature.name), rich(w.feature.text), ...(actionRow(`weapon:${w.name}`, w.automation, w.feature.name) ? [actionRow(`weapon:${w.name}`, w.automation, w.feature.name)!] : []))] : []),
      el('div', { class: 'row', style: 'margin-top:6px;gap:6px' }, attack, dmg, el('span', { class: 'grow' }), unequip),
    ), { category: 'weapon', item: w });
  }

  function equipPage(): HTMLElement {
    const used = handsUsed(c);
    const hands = el('span', { class: 'right hands', title: 'Max burden is 2 hands' }, el('span', { class: 'muted' }, 'Hands'), el('span', { class: `hand${used >= 1 ? ' on' : ''}` }), el('span', { class: `hand${used >= 2 ? ' on' : ''}` }), el('span', { class: 'muted' }, `${used} / 2`));
    const weapons = el('div', { class: 'card' }, el('h3', {}, 'Active weapons', hands));
    const primary = c.activeWeapons.find((w) => w.slot === 'Primary');
    const secondary = c.activeWeapons.find((w) => w.slot === 'Secondary');
    const emptySlot = (name: string) => el('div', { class: 'slot empty' }, el('div', { class: 'top' }, el('span', { class: 'cat' }, name), el('span', { class: 'nm', style: 'font-weight:400' }, 'Empty')));
    weapons.append(primary ? weaponSlot(primary) : emptySlot('Primary'));
    if (secondary) weapons.append(weaponSlot(secondary));
    else if (used < 2) weapons.append(emptySlot('Secondary'));

    const armorBody: Array<Node | string> = [];
    if (c.activeArmor) {
      const a = c.activeArmor;
      const un = el('button', { class: 'small', type: 'button' }, 'Unequip');
      un.addEventListener('click', () => { c.carriedArmor.push(a); c.activeArmor = undefined; render(); });
      armorBody.push(share(el('div', { class: 'slot' }, el('div', { class: 'top' }, el('span', { class: 'nm' }, a.name), un), armorBadges(a), ...(a.feature ? [el('div', { class: 'feat', style: 'margin-top:6px' }, el('div', { class: 'ft' }, a.feature.name), rich(a.feature.text), ...(actionRow(`armor:${a.name}`, a.automation, a.feature.name) ? [actionRow(`armor:${a.name}`, a.automation, a.feature.name)!] : []))] : [])), { category: 'armor', item: a }));
    } else {
      armorBody.push(el('div', { class: 'slot empty' }, 'No armor equipped'));
    }

    const rows: HTMLElement[] = [];
    const equipBtn = (fn: () => void) => { const b = el('button', { class: 'small', type: 'button' }, 'Equip'); b.addEventListener('click', fn); return b; };
    for (const w of c.carriedWeapons) {
      rows.push(share(el('tr', {}, el('td', {}, el('b', {}, w.name), weaponBadges(w)), featureCell(w.feature), el('td', {}, equipBtn(() => equipWeapon(w)))), { category: 'weapon', item: w }));
    }
    for (const a of c.carriedArmor) {
      rows.push(share(el('tr', {}, el('td', {}, el('b', {}, a.name), armorBadges(a)), featureCell(a.feature), el('td', {}, equipBtn(() => equipArmor(a)))), { category: 'armor', item: a }));
    }
    const table = el('table', { class: 't' }, el('thead', {}, el('tr', {}, el('th', {}, 'Name'), el('th', {}, 'Feature'), el('th', {}))), el('tbody', {}, ...rows));
    return el('div', {}, el('div', { class: 'eq-top' }, weapons, card('Active armor', null, ...armorBody)), el('div', { class: 'card', style: 'margin-top:14px' }, el('h3', {}, 'Carried, not equipped'), rows.length ? table : el('span', { class: 'muted' }, 'Nothing carried')));
  }

  function featureCell(f?: { name: string; text: string }): HTMLElement {
    return f ? el('td', {}, el('b', {}, f.name), ' ', el('span', { class: 'muted' }, ...inline(f.text))) : el('td', { class: 'muted' }, '-');
  }

  function equipWeapon(w: Weapon): void {
    // a two-handed weapon replaces everything; otherwise make room for it
    if (w.hands === 2) c.carriedWeapons.push(...c.activeWeapons.splice(0));
    else {
      c.carriedWeapons.push(...c.activeWeapons.filter((x) => x.hands === 2 || x.slot === w.slot));
      c.activeWeapons = c.activeWeapons.filter((x) => x.hands !== 2 && x.slot !== w.slot);
    }
    c.carriedWeapons.splice(c.carriedWeapons.indexOf(w), 1);
    c.activeWeapons.push(w);
    render();
  }

  function equipArmor(a: Armor): void {
    if (c.activeArmor) c.carriedArmor.push(c.activeArmor);
    c.carriedArmor.splice(c.carriedArmor.indexOf(a), 1);
    c.activeArmor = a;
    render();
  }

  function qty(item: InventoryItem): HTMLElement {
    const out = el('span', { class: 'qty' });
    const val = el('span', {}, String(item.qty));
    const step = (d: number) => {
      const b = el('button', { type: 'button' }, d < 0 ? '-' : '+');
      b.addEventListener('click', () => {
        item.qty = Math.max(0, Math.min(item.type === 'consumable' ? 5 : 99, item.qty + d));
        val.textContent = String(item.qty);
      });
      return b;
    };
    out.append(step(-1), val, step(1));
    return out;
  }

  function lootPage(): HTMLElement {
    const input = el('input', { type: 'number', value: String(c.gold), min: '0' });
    input.setAttribute('aria-label', 'Gold coins');
    input.addEventListener('change', () => { c.gold = Math.max(0, Math.floor(Number(input.value) || 0)); input.value = String(c.gold); });
    const adj = (d: number) => { const b = el('button', { type: 'button' }, d < 0 ? '-' : '+'); b.addEventListener('click', () => { c.gold = Math.max(0, c.gold + d); input.value = String(c.gold); }); return b; };
    const gold = el(
      'div', { class: 'card goldbar' },
      el('span', { class: 'coin' }), el('span', { class: 'cat' }, 'Gold'),
      el('span', { class: 'qty' }, adj(-1), input, adj(1)),
      el('span', { class: 'muted', style: 'font-size:11.5px' }, 'coins (10 coins = 1 handful, 10 handfuls = 1 bag, 10 bags = 1 chest)'),
    );
    const rows = c.inventory.map((i) => {
      const use = i.use ? el('button', { class: 'small', type: 'button' }, 'Use') : null;
      use?.addEventListener('click', () => {
        if (i.qty > 0) { i.qty--; rollDamage(i.name + (i.note ? ` (${i.note})` : ''), i.use!, rollSourceFor(c, `item:${i.name}`)); render(); }
      });
      const key = `item:${i.name}`;
      const consumable = i.type === 'consumable';
      const actions = actionRow(key, i.automation, i.name, {
        extraBlock: () => (i.qty <= 0 ? 'None left' : null),
        after: () => { if (consumable) i.qty = Math.max(0, i.qty - 1); },
      });
      return share(el('tr', {}, el('td', {}, i.name, ...(i.note ? [el('div', { class: 'muted' }, i.note)] : [])), el('td', {}, tag(i.type)), el('td', {}, qty(i)), el('td', {}, ...(use ? [use] : []), ...(actions ? [actions] : []))), { category: 'item', item: i });
    });
    return el(
      'div', {}, gold,
      el(
        'div', { class: 'card', style: 'margin-top:14px' }, el('h3', {}, 'Inventory'),
        el('table', { class: 't' }, el('thead', {}, el('tr', {}, el('th', {}, 'Item'), el('th', {}, 'Type'), el('th', {}, 'Qty'), el('th', {}))), el('tbody', {}, ...rows)),
        el('p', { class: 'muted', style: 'font-size:11.5px;margin:6px 0 0' }, 'Consumables are single-use; at most 5 of each. Loot stays until sold, discarded or lost.'),
      ),
    );
  }

  /** A grimoire spell: its own text, Spellcast roll and automation buttons, independent of the other spells. */
  function spellBlock(d: DomainCard, s: Spell, loadout: boolean): HTMLElement {
    const acts = el('div', { class: 'act' });
    if (d.spellcast && asksSpellcastRoll(s.text)) {
      const sp = el('button', { class: 'small primary', type: 'button' }, 'Spellcast roll');
      sp.addEventListener('click', () => rollDuality(`Spellcast (${d.spellcast}) - ${s.name}`.slice(0, 80), d.spellcast!, ['spellcast', `trait:${d.spellcast}`, `card:${d.name}`, `card:${s.name}`], undefined, rollSourceFor(c, `card:${d.name}/${s.name}`)));
      acts.append(sp);
    }
    if (loadout) {
      const actions = actionRow(`card:${d.name}/${s.name}`, s.automation, s.name);
      if (actions) acts.append(actions);
    }
    return el('div', { class: 'dspell' }, el('span', { class: 'sn' }, s.name), rich(s.text), ...(acts.childElementCount ? [acts] : []));
  }

  function dcard(d: DomainCard, loadout: boolean): HTMLElement {
    const acts = el('div', { class: 'act' });
    const spells = d.spells?.length ? d.spells : null;
    if (!spells && d.spellcast && asksSpellcastRoll(d.text)) {
      const sp = el('button', { class: 'small primary', type: 'button' }, 'Spellcast roll');
      sp.addEventListener('click', () => rollDuality(`Spellcast (${d.spellcast}) - ${d.name}`.slice(0, 80), d.spellcast!, ['spellcast', `trait:${d.spellcast}`, `card:${d.name}`], undefined, rollSourceFor(c, `card:${d.name}`)));
      acts.append(sp);
    }
    if (loadout && !spells) {
      const actions = actionRow(`card:${d.name}`, d.automation, d.name);
      if (actions) acts.append(actions);
    }
    const move = () => {
      if (c.testingCharacter) return;
      if (loadout) { c.loadout.splice(c.loadout.indexOf(d), 1); c.vault.push(d); }
      else if (c.loadout.length < derivedStats(c).loadoutMax) { c.vault.splice(c.vault.indexOf(d), 1); c.loadout.push(d); }
      render();
    };
    let control: HTMLElement;
    if (loadout) control = c.testingCharacter ? tag('Always active') : cardMenu('Move to vault', move);
    else {
      control = el('button', { class: 'small', type: 'button' }, `Recall to loadout (mark ${d.recall} Stress)`);
      control.addEventListener('click', move);
      acts.append(control);
    }
    const pools = loadout ? (d.automation?.resources ?? []).map((r) => el('label', { class: 'card-resource' }, 'Tokens', poolInput({ ...r, label: 'Tokens' }))) : [];
    const hd = el('div', { class: 'hd' }, el('span', { class: 'lv' }, String(d.level)), el('span', { class: 'dm' }, d.domain), ...pools, el('span', { class: 'rc' }, `\u26A1 ${d.recall}`), ...(loadout ? [control] : []));
    const node = share(el('div', { class: 'dcard' }, hd, el('span', { class: 'ty' }, d.type), el('span', { class: 'nm' }, d.name), ...(spells ? spells.map((s) => spellBlock(d, s, loadout)) : [rich(d.text)]), acts), { category: 'domain', item: d });
    node.draggable = !c.testingCharacter;
    node.classList.add('inventory-draggable');
    node.addEventListener('dragstart', (e) => {
      draggedCard = { card: d, fromLoadout: loadout };
      if (e.dataTransfer) { e.dataTransfer.effectAllowed = 'copyMove'; e.dataTransfer.setData(CARD_DRAG, d.name); }
    });
    node.addEventListener('dragend', () => { draggedCard = null; });
    return node;
  }

  let draggedCard: { card: DomainCard; fromLoadout: boolean } | null = null;

  function cardMenu(label: string, action: () => void): HTMLElement {
    const wrap = el('div', { class: 'card-menu' });
    const toggle = el('button', { class: 'card-menu-btn', type: 'button', title: 'Card actions', ariaLabel: 'Card actions', ariaHasPopup: 'menu' }, '\u22EE');
    const list = el('div', { class: 'card-menu-list', role: 'menu' });
    list.hidden = true;
    const item = el('button', { type: 'button', role: 'menuitem' }, label);
    const close = () => { list.hidden = true; document.removeEventListener('pointerdown', outside, true); };
    const outside = (e: Event) => { if (!wrap.contains(e.target as Node)) close(); };
    toggle.addEventListener('click', () => {
      if (!list.hidden) { close(); return; }
      list.hidden = false;
      document.addEventListener('pointerdown', outside, true);
    });
    item.addEventListener('click', () => { close(); action(); });
    list.append(item);
    wrap.append(toggle, list);
    return wrap;
  }

  /** Accepts dragged domain cards that currently live in the opposite zone and moves them into `toLoadout`. */
  function cardDropTarget(node: HTMLElement, toLoadout: boolean): HTMLElement {
    const accepts = () => !c.testingCharacter && !!draggedCard && draggedCard.fromLoadout !== toLoadout && (!toLoadout || c.loadout.length < derivedStats(c).loadoutMax);
    node.addEventListener('dragover', (e) => {
      if (!accepts()) return;
      e.preventDefault();
      if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
      node.classList.add('drop-active');
    });
    node.addEventListener('dragleave', (e) => {
      if (!node.contains(e.relatedTarget as Node | null)) node.classList.remove('drop-active');
    });
    node.addEventListener('drop', (e) => {
      node.classList.remove('drop-active');
      if (!accepts()) return;
      e.preventDefault();
      const d = draggedCard!.card;
      draggedCard = null;
      const [from, to] = toLoadout ? [c.vault, c.loadout] : [c.loadout, c.vault];
      const i = from.indexOf(d);
      if (i < 0) return;
      from.splice(i, 1);
      to.push(d);
      render();
    });
    return node;
  }

  const cardQuery = { loadout: '', vault: '' };

  function cardMatches(d: DomainCard, q: string): boolean {
    const words = q.toLowerCase().split(/\s+/).filter(Boolean);
    const hay = `${d.name} ${d.domain} ${d.type} ${d.text}`.toLowerCase();
    return words.every((w) => hay.includes(w));
  }

  /** Card panel with a header search box; filtering toggles visibility in place so typing is not interrupted by a re-render. */
  function cardZone(key: 'loadout' | 'vault', title: string, info: string, cards: DomainCard[], extras: HTMLElement[]): HTMLElement {
    const nodes = cards.map((d) => ({ d, node: dcard(d, key === 'loadout') }));
    const search = el('input', { type: 'search', placeholder: 'Search name, domain, text', value: cardQuery[key], ariaLabel: `Search ${title.toLowerCase()} cards` });
    search.style.width = '190px';
    const grid = el('div', { class: 'cards' }, ...nodes.map((n) => n.node), ...extras);
    const none = el('p', { class: 'muted', style: 'font-size:12px;margin:6px 0 0' }, 'No cards match the search.');
    const apply = () => {
      const q = search.value.trim();
      cardQuery[key] = search.value;
      let shown = 0;
      for (const { d, node } of nodes) { const ok = !q || cardMatches(d, q); node.hidden = !ok; if (ok) shown++; }
      for (const x of extras) x.hidden = !!q;
      none.hidden = !q || shown > 0 || nodes.length === 0;
    };
    search.addEventListener('input', apply);
    apply();
    return el('div', { class: 'card drop-zone' }, el('h3', {}, title, el('span', { class: 'right muted' }, search, info)), grid, none);
  }

  function cardsPage(): HTMLElement {
    if (c.testingCharacter) return cardZone('loadout', 'Testing loadout', `${c.loadout.length} active - no limit`, c.loadout, []);
    const max = derivedStats(c).loadoutMax;
    const empties = Array.from({ length: Math.max(0, max - c.loadout.length) }, () => el('div', { class: 'dcard empty' }, 'Empty loadout slot'));
    return el(
      'div', {},
      cardDropTarget(cardZone('loadout', 'Loadout', `${c.loadout.length} / ${max} active`, c.loadout, empties), true),
      cardDropTarget(cardZone('vault', 'Vault', 'Inactive cards', c.vault, []), false),
      el('p', { class: 'muted', style: 'font-size:12px' }, 'Subclass, ancestry and community cards are always active and do not count towards loadout or vault (see the Character tab).'),
    );
  }

  function bgPage(): HTMLElement {
    const area = (value: string, set: (v: string) => void, placeholder = '') => {
      const t = el('textarea', { value, placeholder });
      t.addEventListener('input', () => set(t.value));
      return t;
    };
    const fields = el('div', { class: 'fields' }, ...Object.keys(c.description).map((k) => {
      const i = el('input', { value: c.description[k] });
      i.addEventListener('input', () => { c.description[k] = i.value; });
      return el('label', { class: 'field' }, k, i);
    }));
    const qa = (list: string[], prefix: string) => el('div', { class: 'qa' }, ...list.map((v, i) => el('label', { class: 'q' }, `${prefix} ${i + 1}`, area(v, (x) => { list[i] = x; }))));
    return el(
      'div', { class: 'cols' },
      el(
        'div', {},
        experiences(),
        card('Character description', null, fields, el('label', { class: 'field', style: 'margin-top:8px' }, 'Other details', area(c.otherDetails, (v) => { c.otherDetails = v; }))),
        card('Notes', null, area(c.notes, (v) => { c.notes = v; }, 'Free notes')),
      ),
      el('div', {}, card('Background questions', el('span', { class: 'muted' }, 'From the class guide - editable'), qa(c.backgroundQuestions, 'Question')), card('Connections', el('span', { class: 'muted' }, 'Relationships with other PCs'), qa(c.connections, 'Connection question'))),
    );
  }

  render();
  return root;
}

/** Small modal: advantage and Experience spend; posts the duality roll to the chat. */
export function openRollDialog(host: HTMLElement, c: CharacterSheet, label: string, trait: RollContribution[], bonus: RollBonus, onRoll: (r: RollRequest) => boolean, onSent: () => void, targets: string[], weapon?: Weapon, supportsKeptDice?: () => boolean, actionWeapon?: Weapon, critical = false): void {
  const mod = trait.reduce((n, s) => n + Number(s.expression), 0);
  let advantage = '0';
  const advButtons = [['1', 'Advantage (+d6)', 'adv'], ['0', 'Normal', 'norm'], ['-1', 'Disadvantage (-d6)', 'dis']].map(([v, t, cls]) => {
    const b = el('button', { type: 'button', class: `dh-adv-btn ${cls}` }, t);
    b.addEventListener('click', () => { advantage = v; advButtons.forEach((x) => x.classList.toggle('on', x === b)); });
    b.classList.toggle('on', v === advantage);
    return b;
  });
  const adv = el('div', { class: 'dh-adv', role: 'group', ariaLabel: 'Roll with' }, ...advButtons);
  const exp = el('select', {}, el('option', { value: '' }, 'None'), ...c.experiences.map((x, i) => el('option', { value: String(i) }, x.name)));
  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  const close = () => { backdrop.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
  const go = el('button', { class: 'primary', type: 'button' }, 'Roll to chat');
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const error = el('div', { class: 'error', role: 'alert' });
  const options = availableRollOptions(c, targets, weapon ?? actionWeapon);
  const checked = new Set<number>();
  const selected = () => options.filter((_, i) => checked.has(i));
  const combined = () => selectedRollBonus(c, bonus, selected());
  const blocker = () => {
    if (weapon?.automation?.damage?.dropLowest && !supportsKeptDice?.()) return 'This host does not support kept damage dice. Update the host to use this weapon feature.';
    const active = availableRollOptions(c, targets, weapon ?? actionWeapon);
    if (selected().some((s) => !active.some((a) => a.key === s.key && a.option.id === s.option.id))) return 'A selected bonus is no longer available.';
    return selectionBlocker(c, selected(), !weapon && exp.value !== '');
  };
  const preview = el('div', { class: 'muted' });
  const crit = el('input', { type: 'checkbox' });
  crit.checked = critical;
  const damageText = (b: RollBonus) => {
    const base = damageExpression(weapon!.damage, derivedStats(c).proficiency, b, weapon!.automation?.damage, weapon!.fixedDice);
    const extra = crit.checked ? criticalBonus(base) : 0;
    return { base, extra, text: extra ? `${base}+${extra}` : base };
  };
  const update = () => {
    const b = combined();
    preview.textContent = weapon
      ? damageText(b).text
      : `Modifier ${signed(mod + b.value + (exp.value === '' ? 0 : c.experiences[Number(exp.value)]?.mod ?? 0))}${b.dice.length ? `; extra dice ${b.dice.join(', ')}` : ''}`;
    error.textContent = blocker() ?? '';
    go.disabled = !!error.textContent;
  };
  const choices = options.map((s, i) => {
    const input = el('input', { type: 'checkbox' });
    const dice = rollOptionDice(c, s.option);
    const value = evalValue(s.option.value, c);
    const cost = costText(s.option.cost, c);
    const block = s.active ? null : actionBlocker(c, s.key, s.automation, s.option);
    input.disabled = !!block;
    if (s.active) { input.checked = true; checked.add(i); }
    input.addEventListener('change', () => { if (input.checked) checked.add(i); else checked.delete(i); update(); });
    const note = s.prepared ? 'ready - already used up'
      : s.active ? 'active - no cost'
      : [cost ? `costs ${cost} when selected${s.option.effect ? ` (then stays active ${s.option.effect.until === 'scene' ? 'for the scene' : 'as an effect'})` : ''}` : '',
        s.option.consumeItem && s.item ? `uses up 1 ${s.item.name} (${s.item.qty} left)` : ''].filter(Boolean).join('; ');
    return el('label', { class: 'dh-roll-option' }, input,
      el('span', {}, `${s.source} - ${s.option.label}${value ? ` (${signed(value)})` : ''}${dice ? ` +${dice}` : ''}${note ? `; ${note}` : ''}`,
        ...(s.option.description ? [el('small', { class: 'muted' }, s.option.description)] : []),
        ...(block ? [el('small', { class: 'error' }, block)] : [])));
  });
  exp.addEventListener('change', update);
  crit.addEventListener('change', update);
  cancel.addEventListener('click', close);
  go.addEventListener('click', () => {
    const block = blocker();
    if (block) { error.textContent = block; go.disabled = true; return; }
    const e = weapon || exp.value === '' ? undefined : c.experiences[Number(exp.value)];
    const b = combined();
    const total = mod + b.value + (e?.mod ?? 0);
    const request: RollRequest = weapon ? {
      label,
      expression: damageText(b).text,
      contributions: [...damageContributions(c, weapon, b), ...(damageText(b).extra ? [{ label: 'Critical hit (maximum dice)', expression: String(damageText(b).extra) }] : [])],
    } : {
      label,
      expression: dualityExpression(total, Number(advantage) as Advantage) + b.dice.map((d) => `+${d.startsWith('d') ? `1${d}` : d}`).join(''),
      kind: 'duality',
      contributions: [
        { label: 'Hope die', expression: '1d12' },
        { label: 'Fear die', expression: '1d12' },
        ...(advantage === '0' ? [] : [{ label: advantage === '1' ? 'Advantage' : 'Disadvantage', expression: advantage === '1' ? '1d6' : '-1d6' }]),
        ...trait,
        ...b.contributions,
        ...(e ? [{ label: `Experience - ${e.name}`.slice(0, 120), expression: String(e.mod) }] : []),
      ],
    };
    if (onRoll(request)) { commitRollOptions(c, selected(), !!e); close(); onSent(); }
    else error.textContent = 'Not connected. The roll was not sent and no costs were paid.';
  });
  backdrop.addEventListener('click', (ev) => { if (ev.target === backdrop) close(); });
  backdrop.append(
    el(
      'div', { class: 'dh-rolldlg' },
      el('h4', {}, `${label} (${signed(mod + bonus.value)})`),
      ...(bonus.names.length ? [el('div', { class: 'muted' }, `Applied: ${bonus.names.join(', ')}.`)] : []),
      ...(weapon ? [el('label', { class: 'dh-roll-option' }, crit, el('span', {}, 'Critical damage (adds the maximum of the damage dice)'))] : [
        el('div', { class: 'muted' }, 'Duality roll: Hope d12 + Fear d12 + modifier'),
        el('div', { class: 'dh-adv-row' }, el('span', {}, 'Roll with'), adv),
        el('label', {}, 'Experience (spend 1 Hope)', exp),
      ]),
      ...(choices.length ? [el('fieldset', {}, el('legend', {}, 'Optional bonuses (select all that apply)'), ...choices)] : []),
      preview,
      error,
      el('div', { class: 'acts' }, cancel, go),
    ),
  );
  document.addEventListener('keydown', onKey);
  (host.ownerDocument.body).append(backdrop);
  update();
  go.focus();
}

import { el } from '../login';
import {
  Advantage, Armor, CharacterSheet, DomainCard, Feature, InventoryItem, Track, TRAIT_ACTIONS, TRAIT_ORDER, TraitName, Weapon,
  dualityExpression, handsUsed, multiclassesOf, shortTrait, signed, tierOf,
} from './data';
import {
  ActionDef, ActiveEffect, Automation, RollBonus, StatSource, actionBlocker, addEffect, applyRest, consumeBonus, costText, damageExpression,
  damageContributions, derivedStats, rollBonus, runAction, statBreakdown, syncDerived, traitContributions, traitTotal, usesLeft,
} from './engine';
import type { RollContribution } from '../protocol';

import { openLevelUp, openMancer, openTransformation } from './mancer';
import { inline, rich } from './rich';
import { MAX_LEVEL } from './rules';
import { iconButton } from '../icons';
import { INVENTORY_DRAG, prepareInventoryDrag, transferable, type SharedEntry } from '../shared-cards';

export interface RollRequest {
  label: string;
  expression: string;
  kind?: 'duality';
  difficulty?: number;
  contributions?: RollContribution[];
}

/** Lets other parts of the page (the compendium) change a sheet and have an open view refresh. */
export class SheetBus {
  private listeners: Array<() => void> = [];
  subscribe(fn: () => void): void { this.listeners.push(fn); }
  notify(): void { this.listeners.forEach((fn) => fn()); }
}

export interface SheetViewOptions {
  character: CharacterSheet;
  artwork?: string;
  /** Sends a roll to the shared roll log; returns false when it could not be sent. */
  onRoll: (roll: RollRequest) => boolean;
  /** Posts a short message (action results) to the shared chat. */
  onLog?: (text: string) => void;
  actorId?: string;
  onShare?: (entry: SharedEntry) => void;
  onPrepareShare?: () => Promise<void>;
  bus?: SheetBus;
  /** Called after anything on the sheet may have changed (the host debounces and saves). */
  onChange?: () => void;
}

type Page = 'char' | 'equip' | 'loot' | 'cards' | 'effects' | 'bg';
const PAGES: Array<[Page, string]> = [
  ['char', 'Character'], ['effects', 'Effects'], ['cards', 'Domain Cards'], ['equip', 'Equipment'], ['loot', 'Loot'], ['bg', 'Background'],
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
  const name = el('div', { class: 'ft' }, f.name);
  if (onUse) {
    const b = el('button', { class: 'small', type: 'button', textContent: `Use (${f.cost ?? 0} Hope)` });
    b.style.float = 'right';
    b.addEventListener('click', onUse);
    name.append(b);
  }
  return el('div', { class: `feat${f.hope ? ' hope' : ''}` }, el('div', { class: 'src' }, f.source), name, rich(f.text));
}

export function renderSheetView(opts: SheetViewOptions): HTMLElement {
  const c = opts.character;
  let page: Page = 'char';
  const root = el('div', { class: 'dh' });

  function share(node: HTMLElement, entry: SharedEntry): HTMLElement {
    if (!opts.onShare) return node;
    const button = iconButton('chat', `Post ${entry.item.name} to chat`, () => opts.onShare!(entry));
    button.classList.add('share-icon');
    (node.querySelector('td, .ft, .top, .hd') ?? node).append(button);
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

  const rollDuality = (label: string, trait: TraitName | null, targets: string[]) => {
    const bonus = rollBonus(c, targets);
    openRollDialog(root, c, label, trait ? traitContributions(c, trait) : [], bonus, opts.onRoll, () => { consumeBonus(c, bonus); render(); });
  };
  const rollDamage = (label: string, expression: string) => opts.onRoll({ label, expression });
  opts.bus?.subscribe(() => render());
  for (const ev of ['click', 'input', 'change']) root.addEventListener(ev, () => opts.onChange?.());

  function actionRow(key: string, a: Automation | undefined, label: string, hooks: { extraBlock?: () => string | null; after?: () => void } = {}): HTMLElement | null {
    if (!a?.actions?.length) return null;
    const row = el('div', { class: 'row actions', style: 'margin-top:6px;gap:6px;flex-wrap:wrap' });
    for (const action of a.actions) row.append(actionButton(key, a, action, label, hooks));
    const left = usesLeft(c, key, a);
    if (left !== null && a.uses) row.append(el('span', { class: 'muted', style: 'font-size:11.5px' }, `${left}/${a.uses.max} per ${a.uses.per.replace(/([A-Z])/g, ' $1').toLowerCase()}`));
    return row;
  }

  function actionButton(key: string, a: Automation, action: ActionDef, label: string, hooks: { extraBlock?: () => string | null; after?: () => void }): HTMLElement {
    const cost = costText(action.cost);
    const b = el('button', { class: 'small primary', type: 'button' }, cost ? `${action.label} (${cost})` : action.label);
    const block = hooks.extraBlock?.() ?? actionBlocker(c, key, a, action);
    if (block) { b.disabled = true; b.title = block; }
    b.addEventListener('click', () => {
      const message = runAction(c, key, a, action, label === action.label ? label : `${label} - ${action.label}`);
      if (message === null) return;
      hooks.after?.();
      opts.onLog?.(`${c.name}: ${message}`);
      render();
    });
    return b;
  }

  const render = () => {
    const scroll = root.querySelector('.pages')?.scrollTop ?? 0;
    syncDerived(c);
    root.replaceChildren(header(), ...(c.mancer?.done === false ? [startBanner()] : [tabs(), ...(page === 'char' ? [vitals()] : []), pages()]));
    opts.onChange?.();
    const p = root.querySelector('.pages');
    if (p) p.scrollTop = scroll;
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
    const portrait = el('div', { class: 'portrait', title: 'Character image' });
    if (opts.artwork) portrait.append(el('img', { src: opts.artwork, alt: '' }));
    else portrait.textContent = c.name.slice(0, 1).toUpperCase();
    const sub = (label: string, ...bold: string[]) => el('div', { class: 'sub' }, label, ...bold.map((b) => el('b', {}, b)));
    return el(
      'header', { class: 'ident' },
      portrait,
      el(
        'div', { class: 'ident-main' },
        el('p', { class: 'name' }, c.name, el('span', { class: 'muted', style: 'font-size:13px;font-weight:400' }, ` (${c.pronouns})`)),
        sub('Class: ', c.className, ` - Subclass: ${c.subclass}`),
        ...multiclassesOf(c).map((m) => sub('Multiclass: ', m.className, ` - Subclass: ${m.subclass.replace(/^[^:]+:\s*/, '')} - Domain: ${m.domain}`)),
        sub('Domains: ', c.domains.join(' & ')),
        sub('Heritage: ', `${c.ancestry} ancestry, ${c.community} community${c.transformation ? `, ${c.transformation} transformation` : ''}`),
        ...(c.player ? [sub('Played by ', c.player)] : []),
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
      ),
    );
  }

  function levelUpButton(): HTMLElement {
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
    nav.append(el('span', { class: 'spacer' }));
    for (const [label, kind, title] of [['Short rest', 'short', 'Short rest: refreshes short-rest abilities and ends effects lasting until a rest'], ['Long rest', 'long', 'Long rest: refreshes all rest abilities and ends effects lasting until a rest']] as const) {
      const b = el('button', { class: 'small', type: 'button', title, style: 'margin-left:6px' }, label);
      b.addEventListener('click', () => { applyRest(c, kind); opts.onLog?.(`${c.name} takes a ${kind} rest.`); render(); });
      nav.append(b);
    }
    nav.append(el('button', { class: 'small', type: 'button', title: 'Offered when the last HP is marked (not available yet)', disabled: true, style: 'margin-left:6px' }, 'Death move...'));
    return nav;
  }

  function vitals(): HTMLElement {
    const th = derivedStats(c);
    const thr = (n: string, t: string) => el('div', { class: 'thr' }, el('div', { class: 'n' }, n), el('div', { class: 't' }, t));
    const sep = () => el('span', { class: 'thr-sep' }, '|');
    const traits = el('div', { class: 'traits', title: 'Click a trait to roll it. The checkbox marks it (marked traits cannot be raised again this tier).' });
    for (const name of TRAIT_ORDER) {
      const t = c.traits.find((x) => x.name === name)!;
      const box = el('input', { type: 'checkbox', checked: t.marked });
      box.addEventListener('click', (e) => e.stopPropagation());
      box.addEventListener('change', () => { t.marked = box.checked; });
      const th2 = el('div', { class: 'th' }, box, el('span', { class: 'nm' }, name));
      if (t.spellcast) th2.append(el('span', { class: 'spell', title: 'Subclass Spellcast trait' }, '\u2726'));
      const total = traitTotal(c, name);
      const md = el('span', { class: 'md', title: total !== t.mod ? `Base ${signed(t.mod)}, modified by equipment and effects` : '' }, signed(total));
      if (total !== t.mod) md.style.color = total > t.mod ? 'var(--hope, #4a9)' : 'var(--hp, #c55)';
      const tile = el('div', { class: 'trait' }, th2, md, el('span', { class: 'vb' }, TRAIT_ACTIONS[name]));
      tile.addEventListener('click', () => rollDuality(name, name, [`trait:${name}`]));
      traits.append(tile);
    }
    const srcs = statBreakdown(c);
    const withSources = (v: HTMLElement, rows: StatSource[], total: number | string, note = '') => {
      v.classList.add('has-src');
      v.tabIndex = 0;
      v.append(el('div', { class: 'srcs', role: 'tooltip' }, ...rows.map((r) => el('div', { class: 'sr' }, el('span', {}, r.label), el('b', {}, signed(r.value)))), el('div', { class: 'sr tot' }, el('span', {}, note || 'Total'), el('b', {}, String(total)))));
      return v;
    };
    return el(
      'section', { class: 'vitals' },
      el(
        'div', { class: 'v-left' },
        withSources(vital('Evasion', el('div', { class: 'big' }, String(th.evasion)), 'v-e'), srcs.evasion, th.evasion),
        withSources(vital('Armor', pips(c.armorTrack, 'armor', 'var(--armor)'), 'v-a'), srcs.armor, th.armorSlots, 'Armor slots'),
        withSources(vital('Thresholds', el('div', { class: 'thresholds' }, thr(`< ${th.major}`, 'Minor'), sep(), thr(String(th.major), 'Major'), sep(), thr(String(th.severe), 'Severe')), 'v-th'),
          [...srcs.major.map((r) => ({ ...r, label: `${r.label} (major)` })), ...srcs.severe.map((r) => ({ ...r, label: `${r.label} (severe)` }))], `${th.major} / ${th.severe}`, 'Total (major / severe)'),
        vital('HP', pips(c.hp, 'hp', 'var(--hp)'), 'v-hp'),
        vital('Stress', pips(c.stress, 'stress', 'var(--stress)'), 'v-st'),
      ),
      traits,
    );
  }

  function pages(): HTMLElement {
    const wrap = el('div', { class: 'pages' });
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
    const exps = el('div', { class: 'list' }, ...c.experiences.map((x) => el('div', { class: 'row exp' }, el('span', { class: 'grow' }, x.name), el('span', { class: 'mod' }, signed(x.mod)))));
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
    tf.addEventListener('click', () => { void openTransformation(root, c, () => render()); });
    return el(
      'div', { class: 'cols char-cols' },
      el('div', {}, card('Experiences', null, exps)),
      el('div', {}, card('Class & subclass features', null, ...c.features.map(feat)), card('Heritage features', tf, ...c.heritage.map(feat))),
    );
  }

  function weaponSlot(w: Weapon): HTMLElement {
    const mod = traitTotal(c, w.trait);
    const attack = el('button', { class: 'primary small', type: 'button' }, `Attack ${signed(mod)}`);
    attack.addEventListener('click', () => rollDuality(`Attack - ${w.name} (${w.trait})`, w.trait, ['attack', `trait:${w.trait}`]));
    const dmg = el('button', { class: 'small', type: 'button' }, 'Damage');
    dmg.addEventListener('click', () => {
      const bonus = rollBonus(c, ['damage']);
      const sent = opts.onRoll({
        label: `${w.name} damage`,
        expression: damageExpression(w.damage, derivedStats(c).proficiency, bonus),
        contributions: damageContributions(c, w, bonus),
      });
      if (sent) { consumeBonus(c, bonus); render(); }
    });
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
        if (i.qty > 0) { i.qty--; rollDamage(i.name + (i.note ? ` (${i.note})` : ''), i.use!); render(); }
      });
      const key = `item:${i.name}`;
      const consumable = i.type === 'consumable';
      const actions = actionRow(key, i.automation, i.name, {
        extraBlock: () => (consumable && i.qty <= 0 ? 'None left' : null),
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

  function dcard(d: DomainCard, loadout: boolean): HTMLElement {
    const acts = el('div', { class: 'act' });
    if (d.spellcast) {
      const sp = el('button', { class: 'small primary', type: 'button' }, 'Spellcast roll');
      sp.addEventListener('click', () => rollDuality(`Spellcast (${d.spellcast})`, d.spellcast!, ['spellcast', `trait:${d.spellcast}`]));
      acts.append(sp);
    }
    if (loadout) {
      const actions = actionRow(`card:${d.name}`, d.automation, d.name);
      if (actions) acts.append(actions);
    }
    const mv = el('button', { class: 'small', type: 'button' }, loadout ? 'Move to vault' : `Recall to loadout (mark ${d.recall} Stress)`);
    mv.addEventListener('click', () => {
      if (loadout) { c.loadout.splice(c.loadout.indexOf(d), 1); c.vault.push(d); }
      else if (c.loadout.length < derivedStats(c).loadoutMax) { c.vault.splice(c.vault.indexOf(d), 1); c.loadout.push(d); }
      render();
    });
    acts.append(mv);
    return share(el('div', { class: 'dcard' }, el('div', { class: 'hd' }, el('span', { class: 'lv' }, String(d.level)), el('span', { class: 'dm' }, d.domain), el('span', { class: 'rc' }, `\u26A1 ${d.recall}`)), el('span', { class: 'ty' }, d.type), el('span', { class: 'nm' }, d.name), rich(d.text), acts), { category: 'domain', item: d });
  }

  function cardsPage(): HTMLElement {
    const max = derivedStats(c).loadoutMax;
    const empties = Array.from({ length: Math.max(0, max - c.loadout.length) }, () => el('div', { class: 'dcard empty' }, 'Empty loadout slot'));
    return el(
      'div', {},
      el('div', { class: 'card' }, el('h3', {}, 'Loadout', el('span', { class: 'right muted' }, `${c.loadout.length} / ${max} active`)), el('div', { class: 'cards' }, ...c.loadout.map((d) => dcard(d, true)), ...empties)),
      el('div', { class: 'card' }, el('h3', {}, 'Vault', el('span', { class: 'right muted' }, 'Inactive cards - no effect on play')), el('div', { class: 'cards' }, ...c.vault.map((d) => dcard(d, false)))),
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
        card('Character description', null, fields, el('label', { class: 'field', style: 'margin-top:8px' }, 'Other details', area(c.otherDetails, (v) => { c.otherDetails = v; }))),
        card('Notes', null, area(c.notes, (v) => { c.notes = v; }, 'Free notes')),
      ),
      el('div', {}, card('Background questions', el('span', { class: 'muted' }, 'From the class guide - editable'), qa(c.backgroundQuestions, 'Question')), card('Connections', el('span', { class: 'muted' }, 'Relationships with other PCs'), qa(c.connections, 'Connection question'))),
    );
  }

  render();
  return root;
}

/** Small modal: advantage, Experience spend and difficulty; posts the duality roll to the chat. */
function openRollDialog(host: HTMLElement, c: CharacterSheet, label: string, trait: RollContribution[], bonus: RollBonus, onRoll: (r: RollRequest) => boolean, onSent: () => void): void {
  const mod = trait.reduce((n, s) => n + Number(s.expression), 0);
  const adv = el('select', {}, ...[['0', 'Normal'], ['1', 'Advantage (+d6)'], ['-1', 'Disadvantage (-d6)']].map(([v, t]) => el('option', { value: v }, t)));
  const exp = el('select', {}, el('option', { value: '' }, 'None'), ...c.experiences.map((x, i) => el('option', { value: String(i) }, x.name)));
  const diff = el('input', { type: 'number', min: '0', placeholder: '-' });
  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  const close = () => { backdrop.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
  const go = el('button', { class: 'primary', type: 'button' }, 'Roll to chat');
  const cancel = el('button', { type: 'button' }, 'Cancel');
  cancel.addEventListener('click', close);
  go.addEventListener('click', () => {
    const e = exp.value === '' ? undefined : c.experiences[Number(exp.value)];
    const total = mod + bonus.value + (e?.mod ?? 0);
    const request: RollRequest = {
      label,
      expression: dualityExpression(total, Number(adv.value) as Advantage) + bonus.dice.map((d) => `+${d.startsWith('d') ? `1${d}` : d}`).join(''),
      kind: 'duality',
      contributions: [
        { label: 'Hope die', expression: '1d12' },
        { label: 'Fear die', expression: '1d12' },
        ...(adv.value === '0' ? [] : [{ label: adv.value === '1' ? 'Advantage' : 'Disadvantage', expression: adv.value === '1' ? '1d6' : '-1d6' }]),
        ...trait,
        ...bonus.contributions,
        ...(e ? [{ label: `Experience - ${e.name}`.slice(0, 120), expression: String(e.mod) }] : []),
      ],
    };
    if (diff.value !== '') request.difficulty = Math.floor(Number(diff.value));
    if (onRoll(request)) { close(); onSent(); }
  });
  backdrop.addEventListener('click', (ev) => { if (ev.target === backdrop) close(); });
  backdrop.append(
    el(
      'div', { class: 'dh-rolldlg' },
      el('h4', {}, `${label} (${signed(mod + bonus.value)})`),
      ...(bonus.names.length ? [el('div', { class: 'muted' }, `Applied: ${bonus.names.join(', ')}.`)] : []),
      el('div', { class: 'muted' }, 'Duality roll: Hope d12 + Fear d12 + modifier'),
      el('label', {}, 'Roll with', adv),
      el('label', {}, 'Experience (spend 1 Hope)', exp),
      el('label', {}, 'Difficulty', diff),
      el('div', { class: 'acts' }, cancel, go),
    ),
  );
  document.addEventListener('keydown', onKey);
  (host.ownerDocument.body).append(backdrop);
  go.focus();
}

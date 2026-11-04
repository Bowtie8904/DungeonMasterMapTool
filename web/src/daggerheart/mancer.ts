// Guided wizards: the Character Mancer (creation) and the Level-up guide. One decision per step, remembered on the sheet.

import { el } from '../login';
import { Advancement, AdvancementType, CharacterSheet, CreationChoices, LevelUpRecord, TRAIT_ACTIONS, TRAIT_ORDER, TraitName, signed, tierOf } from './data';
import { CompendiumEntry } from './compendium';
import { rich } from './rich';
import {
  ADVANCEMENT_TYPES, MAX_LEVEL, RulesData, STAGES, TRAIT_VALUES, achievementsAt, advancementCost, advancementLeft, applyLevelUp, buildCharacter,
  classEntries, creationAllowances, creationCardProblems, creationExperienceProblems, creationProblems, emptyChoices, experienceProblems, findClass, guideFor, levelUpCards, levelUpProblems, loadRulesData, setTransformation, shortSubclass,
  starterGear, subclassCard, subclassStageOf, subclassesOf, suggestedTraits, startingCards, traitsValid,
} from './rules';

interface Step {
  key: string;
  title: string;
  hint?: string;
  render: (body: HTMLElement, touch: () => void, redraw: () => void) => void;
  /** Content for the side panel while nothing is hovered, usually the details of the current selection. */
  side?: () => Node | null;
  /** Why the player cannot continue yet; null when the step is complete. */
  problem: () => string | null;
}

interface WizardOptions {
  host: HTMLElement;
  heading: string;
  steps: () => Step[];
  finishLabel: string;
  onFinish: () => void;
}

const cap = (s: string) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);
const clip = (s: string | undefined, n = 150) => ((s ?? '').replace(/\*\*|_/g, '').length > n ? `${(s ?? '').replace(/\*\*|_/g, '').slice(0, n).trimEnd()}...` : (s ?? '').replace(/\*\*|_/g, ''));

let sidePanel: { show: (n: Node | null) => void } | null = null;

function runWizard(o: WizardOptions): void {
  const backdrop = el('div', { class: 'dh dh-wiz-backdrop' });
  const box = el('div', { class: 'dh-wiz', role: 'dialog' });
  backdrop.append(box);
  let index = 0;
  let shownKey = '';
  const close = () => { backdrop.remove(); sidePanel = null; document.removeEventListener('keydown', onKey); };
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', onKey);

  const draw = () => {
    const previous = box.querySelector('.dh-wiz-body');
    const steps = o.steps();
    index = Math.max(0, Math.min(index, steps.length - 1));
    const step = steps[index];
    const keepScroll = step.key === shownKey && previous ? previous.scrollTop : 0;
    shownKey = step.key;
    const last = index === steps.length - 1;
    const x = el('button', { class: 'dh-wiz-x', type: 'button', title: 'Close (your choices are kept only when you finish)' }, 'x');
    x.addEventListener('click', close);
    const progress = el('div', { class: 'dh-wiz-progress' }, ...steps.map((_, i) => el('span', { class: i < index ? 'done' : i === index ? 'now' : '' })));
    const body = el('div', { class: 'dh-wiz-body' });
    const aside = el('aside', { class: 'dh-wiz-side' });
    const back = el('button', { type: 'button' }, 'Back');
    back.disabled = index === 0;
    back.addEventListener('click', () => { index--; draw(); });
    const next = el('button', { class: 'primary', type: 'button' }, last ? o.finishLabel : 'Next');
    const warn = el('span', { class: 'dh-wiz-warn' });
    const touch = () => {
      const p = step.problem();
      next.disabled = !!p;
      warn.textContent = p ?? '';
      showDefault();
    };
    const show = (n: Node | null) => {
      aside.replaceChildren(...(n ? [n] : []));
      aside.hidden = !n;
      main.classList.toggle('has-side', !!n);
    };
    const showDefault = () => show(step.side?.() ?? null);
    sidePanel = { show: (n) => show(n ?? step.side?.() ?? null) };
    next.addEventListener('click', () => {
      if (step.problem()) return;
      if (last) { close(); o.onFinish(); } else { index++; draw(); }
    });
    const main = el('div', { class: 'dh-wiz-main' }, body, aside);
    box.replaceChildren(
      el('div', { class: 'dh-wiz-head' }, el('div', {}, el('div', { class: 'dh-wiz-kicker' }, `${o.heading} - step ${index + 1} of ${steps.length}`), el('h2', {}, step.title)), x),
      progress,
      ...(step.hint ? [el('p', { class: 'dh-wiz-hint' }, step.hint)] : []),
      main,
      el('div', { class: 'dh-wiz-foot' }, back, warn, next),
    );
    step.render(body, touch, draw);
    touch();
    body.scrollTop = keepScroll;
  };
  o.host.ownerDocument.body.append(backdrop);
  draw();
}

// ---- building blocks ----

/** A selectable card. `detail` is shown in the side panel while the card is hovered or focused. */
function choice(title: string, sub: string, selected: boolean, onClick: () => void, extra: Array<Node | string> = [], disabled = false, detail?: () => Node): HTMLElement {
  const b = el('button', { type: 'button', class: `cho${selected ? ' sel' : ''}` }, el('b', {}, title), ...(sub ? [el('span', {}, sub)] : []), ...extra);
  b.disabled = disabled;
  b.addEventListener('click', onClick);
  if (detail) hoverDetail(b, detail);
  return b;
}

function hoverDetail(target: HTMLElement, detail: () => Node): void {
  target.addEventListener('mouseenter', () => sidePanel?.show(detail()));
  target.addEventListener('focus', () => sidePanel?.show(detail()));
  target.addEventListener('mouseleave', () => sidePanel?.show(null));
  target.addEventListener('blur', () => sidePanel?.show(null));
}

const badge = (t: string, cls = '') => el('i', { class: `tag ${cls}`.trim() }, t);

/** Side panel content: a title, optional description and a list of features. */
function detailPanel(title: string, tags: string[], text: string | undefined, features: Array<{ name: string; text: string; type?: string }> = []): HTMLElement {
  return el(
    'div', { class: 'side' },
    el('h4', {}, title),
    ...(tags.length ? [el('div', { class: 'side-tags' }, ...tags.filter(Boolean).map((t) => badge(t)))] : []),
    ...(text ? [rich(text, 'side-text')] : []),
    ...features.map((f) => el('div', { class: 'side-feat' }, el('h5', {}, f.name, ...(f.type && f.type !== 'passive' ? [badge(f.type)] : [])), rich(f.text))),
  );
}

const cardPanel = (e: CompendiumEntry) => detailPanel(e.name, [`Lv ${e.level ?? 1}`, e.domain ?? '', e.cardType ?? '', `Recall ${e.recall ?? 0}`], e.text);

const gearPanel = (e: CompendiumEntry) => detailPanel(e.name, e.weapon ? [cap(e.weapon.trait), cap(e.weapon.range), e.weapon.burden] : [`Armor ${e.armor?.score ?? 0}`, `${e.armor?.major} / ${e.armor?.severe}`], undefined, e.feature ? [e.feature] : [{ name: 'No feature', text: 'This gear has no special feature.' }]);

const damageText = (e: CompendiumEntry) => {
  const w = e.weapon!;
  return `${w.damage.dice}${w.damage.bonus ? signed(w.damage.bonus) : ''} ${w.damage.type === 'magic' ? 'magic' : 'phys'}`;
};

interface Column { head: string; cell: (e: CompendiumEntry) => Node | string }

/** A selectable gear table with a heading row per group. */
function gearTable(groups: Array<{ title: string; items: CompendiumEntry[] }>, columns: Column[], selected: string, suggested: string, pick: (e: CompendiumEntry) => void): HTMLElement {
  const tbody = el('tbody', {});
  for (const g of groups.filter((x) => x.items.length)) {
    tbody.append(el('tr', { class: 'grp' }, el('th', { colSpan: columns.length }, g.title)));
    for (const e of g.items) {
      const tr = el('tr', { class: `pick${e.name === selected ? ' sel' : ''}`, tabIndex: 0 },
        el('td', { class: 'nm' }, e.name, ...(e.name === suggested ? [badge('suggested', 'feat')] : [])),
        ...columns.map((c) => el('td', {}, c.cell(e))));
      tr.addEventListener('click', () => pick(e));
      tr.addEventListener('keydown', (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); pick(e); } });
      hoverDetail(tr, () => gearPanel(e));
      tbody.append(tr);
    }
  }
  return el('div', { class: 'gear-wrap' }, el('table', { class: 'gear' }, el('thead', {}, el('tr', {}, el('th', {}, 'Name'), ...columns.map((c) => el('th', {}, c.head)))), tbody));
}
function textInput(value: string, placeholder: string, set: (v: string) => void, touch: () => void, multiline = false): HTMLElement {
  const i = multiline ? el('textarea', { value, placeholder, rows: 3 }) : el('input', { value, placeholder });
  i.addEventListener('input', () => { set(i.value); touch(); });
  return i;
}

function section(title: string, ...children: Array<Node | string>): HTMLElement {
  return el('div', { class: 'dh-wiz-sec' }, el('h4', {}, title), ...children);
}

function cardDetails(e: { name: string; level?: number; domain?: string; cardType?: string; recall?: number; text?: string }): Array<Node | string> {
  return [el('span', { class: 'meta' }, badge(`Lv ${e.level ?? 1}`), badge(e.domain ?? ''), badge(e.cardType ?? ''), badge(`Recall ${e.recall ?? 0}`)), el('span', {}, clip(e.text, 80))];
}

/** Domain card grid with domain filter chips; selection is limited to `max` cards. */
function cardPicker(list: CompendiumEntry[], selected: string[], max: number, onChange: () => void, rerender: () => void, filter: { domain: string }): HTMLElement {
  const domains = [...new Set(list.map((e) => e.domain ?? ''))];
  const chips = domains.length > 1
    ? el('div', { class: 'dh-wiz-chips' }, ...['', ...domains].map((d) => {
      const b = el('button', { type: 'button', class: `chip${filter.domain === d ? ' on' : ''}` }, d || 'All');
      b.addEventListener('click', () => { filter.domain = d; rerender(); });
      return b;
    }))
    : el('span', {});
  const grid = el('div', { class: 'cho-grid' });
  for (const e of list.filter((x) => !filter.domain || x.domain === filter.domain)) {
    const on = selected.includes(e.name);
    grid.append(choice(e.name, '', on, () => {
      if (on) selected.splice(selected.indexOf(e.name), 1);
      else if (max === 1) selected.splice(0, selected.length, e.name);
      else if (selected.length < max) selected.push(e.name);
      else return;
      onChange();
      rerender();
    }, cardDetails(e), !on && max > 1 && selected.length >= max, () => cardPanel(e)));
  }
  return el('div', {}, chips, grid);
}

function classPanel(e: CompendiumEntry): HTMLElement {
  return detailPanel(e.name, [...(e.domains ?? []), `${e.startingHp ?? 6} HP`, `Evasion ${e.startingEvasion ?? 10}`], e.text, e.features ?? []);
}

function subclassPanel(data: RulesData, name: string): HTMLElement {
  const f = subclassCard(data, name, 'foundation');
  return detailPanel(shortSubclass(name), [f?.spellcastTrait ? `Spellcast: ${f.spellcastTrait}` : '', 'Foundation'], undefined, f?.features ?? []);
}

/** Ancestry details; with `second` the second feature comes from another ancestry (mixed ancestry). */
function ancestryPanel(a: CompendiumEntry, second?: CompendiumEntry): HTMLElement {
  if (!second) return detailPanel(a.name, [], a.text, a.features ?? []);
  return detailPanel(`${a.name} / ${second.name}`, ['Mixed ancestry'], undefined, [...(a.features ?? []).slice(0, 1), ...(second.features ?? []).slice(1, 2)]);
}

function transformationSide(data: RulesData, name: string): HTMLElement | null {
  const e = data.transformations.find((x) => x.name === name);
  return e ? detailPanel(e.name, ['Transformation'], e.text, e.features ?? []) : null;
}
// ---- Character Mancer ----

/** Opens the creation wizard for the sheet. The sheet object is updated in place when the player finishes. */
export async function openMancer(host: HTMLElement, c: CharacterSheet, onDone: () => void): Promise<void> {
  const data = await loadWithNotice(host);
  if (!data) return;
  const ch: CreationChoices = c.mancer?.choices ? structuredClone(c.mancer.choices) : emptyChoices();
  if (!ch.name) ch.name = c.name;
  const pickClass = (name: string) => {
    if (ch.className === name) return;
    const g = guideFor(name);
    ch.className = name;
    ch.subclass = '';
    ch.traits = suggestedTraits(g);
    ch.secondary = undefined;
    ch.primary = '';
    ch.armor = '';
    ch.classItem = '';
    ch.cards = [];
    ch.background = g.background.map((q) => ({ question: q, answer: '' }));
    ch.connections = g.connections.map((q) => ({ question: q, answer: '' }));
  };
  const weaponOf = (name: string) => data.weapons.find((e) => e.name === name);
  const twoHanded = () => /two/i.test(weaponOf(ch.primary)?.weapon?.burden ?? '');
  const cardFilter = { domain: '' };
  let mixedMode = !!ch.ancestry2 && ch.ancestry2 !== ch.ancestry;

  const steps = (): Step[] => {
    const guide = guideFor(ch.className);
    const creation = creationAllowances(data, ch);
    const list: Step[] = [];

    list.push({
      key: 'class', title: 'Choose your class', hint: 'Your class sets your starting Hit Points, Evasion, domains and Hope feature. Hover a class to read its features.',
      problem: () => (ch.className ? null : 'Pick a class to continue.'),
      side: () => { const e = findClass(data, ch.className); return e ? classPanel(e) : null; },
      render: (body, _t, redraw) => {
        const grid = el('div', { class: 'cho-grid' });
        for (const e of classEntries(data)) {
          grid.append(choice(e.name, clip(e.text, 120), ch.className === e.name, () => { pickClass(e.name); redraw(); }, [
            el('span', { class: 'meta' }, ...(e.domains ?? []).map((d) => badge(d)), badge(`${e.startingHp ?? 6} HP`), badge(`Evasion ${e.startingEvasion ?? 10}`)),
          ], false, () => classPanel(e)));
        }
        body.append(grid);
      },
    });

    list.push({
      key: 'subclass', title: 'Choose your subclass', hint: 'You start with the Foundation of the subclass. Later level-ups unlock the rest.',
      problem: () => (ch.subclass ? null : 'Pick a subclass to continue.'),
      side: () => (ch.subclass ? subclassPanel(data, ch.subclass) : null),
      render: (body, _t, redraw) => {
        const grid = el('div', { class: 'cho-grid' });
        for (const name of subclassesOf(data, ch.className)) {
          const f = subclassCard(data, name, 'foundation');
          grid.append(choice(shortSubclass(name), clip(f?.text, 140), ch.subclass === name, () => { ch.subclass = name; redraw(); },
            (f?.features ?? []).map((x) => el('span', { class: 'meta' }, badge(x.name, 'feat'))), false, () => subclassPanel(data, name)));
        }
        body.append(grid);
      },
    });

    list.push({
      key: 'ancestry', title: 'Choose your ancestry', hint: 'Each ancestry has two features. You may combine the first feature of one ancestry with the second of another.',
      problem: () => (!ch.ancestry ? 'Pick an ancestry to continue.' : mixedMode && !ch.ancestry2 ? 'Pick the ancestry for your second feature.' : null),
      side: () => {
        const a = data.ancestries.find((x) => x.name === ch.ancestry);
        const b = mixedMode ? data.ancestries.find((x) => x.name === ch.ancestry2) : undefined;
        return a ? ancestryPanel(a, b) : null;
      },
      render: (body, _t, redraw) => {
        const first = el('div', { class: 'cho-grid' });
        for (const e of data.ancestries) {
          const feats = mixedMode ? (e.features ?? []).slice(0, 1) : (e.features ?? []);
          first.append(choice(e.name, clip(e.text, 110), ch.ancestry === e.name, () => { ch.ancestry = e.name; redraw(); },
            [el('span', { class: 'meta' }, ...feats.map((f) => badge(f.name, 'feat')))], false, () => ancestryPanel(e)));
        }
        const toggle = el('button', { type: 'button', class: `chip${mixedMode ? ' on' : ''}` }, mixedMode ? 'Mixed ancestry: on (click to use a single ancestry)' : 'Mixed ancestry: combine two ancestries');
        toggle.addEventListener('click', () => {
          mixedMode = !mixedMode;
          if (!mixedMode) ch.ancestry2 = undefined;
          redraw();
        });
        body.append(section(mixedMode ? 'First feature from' : 'Ancestry', first), el('div', {}, toggle));
        if (mixedMode) {
          const second = el('div', { class: 'cho-grid' });
          for (const e of data.ancestries) {
            const f = e.features?.[1];
            second.append(choice(e.name, f?.name ?? '', ch.ancestry2 === e.name, () => { ch.ancestry2 = e.name; redraw(); }, [], false,
              () => detailPanel(e.name, ['Second feature'], undefined, f ? [f] : [])));
          }
          body.append(section('Second feature from', second));
        }
      },
    });

    list.push({
      key: 'community', title: 'Choose your community', hint: 'Your community is where you grew up or live now. It grants one feature.',
      problem: () => (ch.community ? null : 'Pick a community to continue.'),
      side: () => { const e = data.communities.find((x) => x.name === ch.community); return e ? detailPanel(e.name, [], e.text, e.features ?? []) : null; },
      render: (body, _t, redraw) => {
        const grid = el('div', { class: 'cho-grid' });
        for (const e of data.communities) {
          grid.append(choice(e.name, clip(e.text ?? e.features?.[0]?.text, 110), ch.community === e.name, () => { ch.community = e.name; redraw(); },
            [el('span', { class: 'meta' }, ...(e.features ?? []).map((f) => badge(f.name, 'feat')))], false, () => detailPanel(e.name, [], e.text, e.features ?? [])));
        }
        body.append(grid);
      },
    });

    if (data.transformations.length) {
      list.push({
        key: 'transformation', title: 'Transformation (optional)', hint: 'A transformation changes your character with a benefit and a drawback. Only take one if your GM offers it - most heroes choose none.',
        problem: () => null,
        side: () => transformationSide(data, ch.transformation ?? ''),
        render: (body, _t, redraw) => body.append(transformationPicker(data, ch.transformation ?? '', (n) => { ch.transformation = n || undefined; redraw(); })),
      });
    }

    list.push({
      key: 'traits', title: 'Assign your traits', hint: `Give each value +2, +1, +1, 0, 0 and -1 exactly once. ${ch.className ? `The suggestion for ${ch.className} is pre-filled.` : ''}`,
      problem: () => (traitsValid(ch.traits) ? null : 'Use +2, +1, +1, 0, 0 and -1 exactly once.'),
      render: (body, _touch, redraw) => {
        const pool = [...TRAIT_VALUES];
        for (const n of TRAIT_ORDER) { const i = pool.indexOf(ch.traits[n]); if (i >= 0) pool.splice(i, 1); }
        body.append(el('div', { class: 'dh-wiz-note' }, pool.length ? `Still to place: ${pool.map(signed).join(', ')}` : 'All values placed. Choosing a value that is already used swaps the two traits.'));
        const tiles = el('div', { class: 'trait-tiles' });
        for (const n of TRAIT_ORDER) {
          const seg = el('div', { class: 'seg', role: 'group' });
          for (const v of [2, 1, 0, -1]) {
            const b = el('button', { type: 'button', class: ch.traits[n] === v ? 'on' : '' }, signed(v));
            b.addEventListener('click', () => {
              const old = ch.traits[n];
              const used = TRAIT_ORDER.filter((o) => o !== n && ch.traits[o] === v).length;
              const allowed = TRAIT_VALUES.filter((x) => x === v).length;
              if (used >= allowed) {
                const other = TRAIT_ORDER.find((o) => o !== n && ch.traits[o] === v)!;
                ch.traits[other] = old;
              }
              ch.traits[n] = v;
              redraw();
            });
            seg.append(b);
          }
          tiles.append(el('div', { class: 'trait-tile' }, el('div', { class: 'tt-head' }, el('b', {}, n), el('span', { class: 'tt-val' }, signed(ch.traits[n]))), el('div', { class: 'tt-acts' }, TRAIT_ACTIONS[n]), seg));
        }
        const suggest = el('button', { type: 'button', class: 'small' }, `Use the ${ch.className || 'class'} suggestion`);
        suggest.addEventListener('click', () => { ch.traits = suggestedTraits(guide); redraw(); });
        body.append(tiles, suggest);
      },
    });

    const weaponCols = (hands: boolean): Column[] => [
      { head: 'Trait', cell: (e) => cap(e.weapon!.trait) },
      { head: 'Range', cell: (e) => cap(e.weapon!.range) },
      { head: 'Damage', cell: (e) => damageText(e) },
      ...(hands ? [{ head: 'Hands', cell: (e: CompendiumEntry) => (/two/i.test(e.weapon!.burden) ? 'Two' : 'One') }] : []),
      { head: 'Feature', cell: (e) => e.feature?.name ?? '-' },
    ];
    const byName = (suggested: string) => (a: CompendiumEntry, b: CompendiumEntry) => Number(b.name === suggested) - Number(a.name === suggested) || a.name.localeCompare(b.name);
    const kind = (e: CompendiumEntry) => (e.weapon?.damage.type === 'magic' ? 'Magic' : 'Physical');

    list.push({
      key: 'primary', title: 'Choose your primary weapon', hint: 'A two-handed weapon fills both hands; otherwise you will also pick a one-handed secondary weapon next.',
      problem: () => (ch.primary ? null : 'Pick a primary weapon.'),
      side: () => { const e = weaponOf(ch.primary); return e ? gearPanel(e) : null; },
      render: (body, _t, redraw) => {
        const all = starterGear(data.weapons).filter((e) => e.weapon?.slot === 'primary').sort(byName(guide.primary));
        const groups = (['Physical', 'Magic'] as const).flatMap((k) => (['One-Handed', 'Two-Handed'] as const).map((h) => ({
          title: `${k} - ${h.toLowerCase()}`,
          items: all.filter((e) => kind(e) === k && (/two/i.test(e.weapon!.burden) ? 'Two-Handed' : 'One-Handed') === h),
        })));
        body.append(gearTable(groups, weaponCols(true), ch.primary, guide.primary, (e) => { ch.primary = e.name; if (twoHanded()) ch.secondary = undefined; redraw(); }));
      },
    });

    if (ch.primary && !twoHanded()) {
      list.push({
        key: 'secondary', title: 'Choose your secondary weapon', hint: 'Your off-hand weapon. It is always one-handed.',
        problem: () => (ch.secondary ? null : 'Pick a secondary weapon.'),
        side: () => { const e = weaponOf(ch.secondary ?? ''); return e ? gearPanel(e) : null; },
        render: (body, _t, redraw) => {
          const all = starterGear(data.weapons).filter((e) => e.weapon?.slot === 'secondary').sort(byName(''));
          const groups = (['Physical', 'Magic'] as const).map((k) => ({ title: `${k} weapons`, items: all.filter((e) => kind(e) === k) }));
          body.append(gearTable(groups, weaponCols(false), ch.secondary ?? '', '', (e) => { ch.secondary = e.name; redraw(); }));
        },
      });
    }

    list.push({
      key: 'armor', title: 'Choose your armor', hint: 'Armor sets your Armor Score and your damage thresholds.',
      problem: () => (ch.armor ? null : 'Pick your armor.'),
      side: () => { const e = data.armor.find((x) => x.name === ch.armor); return e ? gearPanel(e) : null; },
      render: (body, _t, redraw) => {
        const items = starterGear(data.armor).sort((a, b) => Number(b.name === guide.armor) - Number(a.name === guide.armor) || (a.armor?.score ?? 0) - (b.armor?.score ?? 0));
        body.append(gearTable([{ title: 'Starting armor', items }], [
          { head: 'Armor', cell: (e) => String(e.armor?.score ?? 0) },
          { head: 'Thresholds', cell: (e) => `${e.armor?.major} / ${e.armor?.severe}` },
          { head: 'Feature', cell: (e) => e.feature?.name ?? '-' },
        ], ch.armor, guide.armor, (e) => { ch.armor = e.name; redraw(); }));
      },
    });

    list.push({
      key: 'supplies', title: 'Pick your supplies', hint: 'You also start with a torch, 50 feet of rope, basic supplies and a handful of gold.',
      problem: () => (!ch.classItem ? 'Pick a class item.' : null),
      render: (body, _t, redraw) => {
        const potions = el('div', { class: 'cho-row' },
          choice('Minor Health Potion', 'Clear 1d4 Hit Points', ch.potion === 'health', () => { ch.potion = 'health'; redraw(); }),
          choice('Minor Stamina Potion', 'Clear 1d4 Stress', ch.potion === 'stamina', () => { ch.potion = 'stamina'; redraw(); }));
        const items = el('div', { class: 'cho-row' });
        const options = guide.classItems.length ? guide.classItems : ['A keepsake from your past'];
        for (const i of options) items.append(choice(cap(i), '', ch.classItem === cap(i), () => { ch.classItem = cap(i); redraw(); }));
        body.append(section('Potion', potions), section('Class item', items));
      },
    });

    list.push({
      key: 'background', title: 'Your background', hint: 'Answer as much or as little as you like - these are prompts for roleplay. You can change them later.',
      problem: () => null,
      render: (body, touch) => {
        for (const q of ch.background) body.append(el('label', { class: 'dh-wiz-q' }, q.question, textInput(q.answer, 'Your answer', (v) => { q.answer = v; }, touch, true)));
      },
    });

    list.push({
      key: 'experiences', title: 'Write two Experiences', hint: 'A short phrase about your history or skills, like "Tavern brawler" or "Raised by wolves". Each gives +2 when it applies to a roll.',
      problem: () => experienceProblems(ch.experiences)[0] ?? null,
      render: (body, touch) => {
        for (let i = 0; i < 2; i++) body.append(el('label', { class: 'dh-wiz-q' }, `Experience ${i + 1}`, textInput(ch.experiences[i] ?? '', 'e.g. Silver tongue', (v) => { ch.experiences[i] = v; }, touch)));
      },
    });

    if (creation.experienceBonus > 0) list.push({
      key: 'experience-bonus', title: 'Strengthen an Experience',
      hint: `Your creation features grant a permanent ${signed(creation.experienceBonus)} to one of your Experiences. Choose which one.`,
      problem: () => creationExperienceProblems(data, ch)[0] ?? null,
      render: (body, _touch, redraw) => {
        body.append(el('div', { class: 'cho-row' }, ...ch.experiences.map((name, i) => choice(
          name.trim() || `Experience ${i + 1}`, `+2 → ${signed(2 + creation.experienceBonus)}`,
          ch.experienceBonusIndex === i, () => { ch.experienceBonusIndex = i; redraw(); }, [], !name.trim(),
        ))));
      },
    });

    list.push({
      key: 'cards', title: `Choose ${creation.domainCards} domain cards`, hint: `Level 1 cards from your available domains (${creation.domains.join(', ')}). Up to five go into your loadout; any others go into your vault.`,
      problem: () => creationCardProblems(data, ch)[0] ?? null,
      side: () => { const e = data.cards.find((x) => x.name === ch.cards[ch.cards.length - 1]); return e ? cardPanel(e) : null; },
      render: (body, touch, redraw) => {
        if (!creation.domains.includes(cardFilter.domain)) cardFilter.domain = '';
        const eligible = startingCards(data, ch);
        const unavailable = ch.cards.filter((name) => !eligible.some((e) => e.name === name));
        for (const name of unavailable) body.append(choice(name, 'No longer available. Remove this selection.', true, () => {
          ch.cards.splice(ch.cards.indexOf(name), 1); touch(); redraw();
        }));
        body.append(cardPicker(eligible, ch.cards, creation.domainCards, touch, redraw, cardFilter));
      },
    });

    list.push({
      key: 'connections', title: 'Connect with your party', hint: 'Answer these together with the other players, or just jot down ideas.',
      problem: () => null,
      render: (body, touch) => {
        for (const q of ch.connections) body.append(el('label', { class: 'dh-wiz-q' }, q.question, textInput(q.answer, 'Your answer', (v) => { q.answer = v; }, touch, true)));
      },
    });

    list.push({
      key: 'identity', title: 'Who are you?', hint: '',
      problem: () => (ch.name.trim() ? null : 'Give your character a name.'),
      render: (body, touch) => {
        body.append(
          el('label', { class: 'dh-wiz-q' }, 'Name', textInput(ch.name, 'Character name', (v) => { ch.name = v; }, touch)),
          el('label', { class: 'dh-wiz-q' }, 'Pronouns', textInput(ch.pronouns, 'e.g. she/her', (v) => { ch.pronouns = v; }, touch)),
          el('label', { class: 'dh-wiz-q' }, 'Description (optional)', textInput(ch.description, 'Appearance, mannerisms...', (v) => { ch.description = v; }, touch, true)),
        );
      },
    });

    list.push({
      key: 'review', title: 'Ready to begin', hint: 'Check your hero. You can go back to change anything before creating the character.',
      problem: () => creationProblems(data, ch)[0] ?? null,
      render: (body) => {
        const row = (k: string, v: string) => el('div', { class: 'dh-wiz-row' }, el('span', { class: 'muted' }, k), el('b', {}, v));
        body.append(
          el('div', { class: 'dh-wiz-sum' },
            row('Name', `${ch.name}${ch.pronouns ? ` (${ch.pronouns})` : ''}`),
            row('Class', `${ch.className} - ${shortSubclass(ch.subclass)}`),
            row('Heritage', `${ch.ancestry2 && ch.ancestry2 !== ch.ancestry ? `${ch.ancestry} / ${ch.ancestry2}` : ch.ancestry}, ${ch.community}`),
            ...(ch.transformation ? [row('Transformation', ch.transformation)] : []),
            row('Traits', TRAIT_ORDER.map((n) => `${n.slice(0, 3)} ${signed(ch.traits[n])}`).join('  ')),
            row('Weapons', [ch.primary, ch.secondary].filter(Boolean).join(' + ')),
            row('Armor', ch.armor),
            row('Experiences', ch.experiences.map((name, i) => `${name} ${signed(2 + (i === ch.experienceBonusIndex ? creation.experienceBonus : 0))}`).join(', ')),
            row(`Domain cards (${ch.cards.length}/${creation.domainCards})`, ch.cards.join(', ')),
          ),
        );
      },
    });
    return list;
  };

  runWizard({
    host, heading: 'Character Mancer', steps, finishLabel: 'Create character',
    onFinish: () => {
      const built = buildCharacter(data, ch, c);
      for (const k of Object.keys(c)) delete (c as unknown as Record<string, unknown>)[k];
      Object.assign(c, built);
      onDone();
    },
  });
}

/** "None" plus one card per transformation, each showing its benefit and drawback. */
function transformationPicker(data: RulesData, current: string, pick: (name: string) => void): HTMLElement {
  const grid = el('div', { class: 'cho-grid' });
  grid.append(choice('None', 'No transformation.', !current, () => pick('')));
  for (const e of data.transformations) {
    grid.append(choice(e.name, clip(e.text, 140), current === e.name, () => pick(e.name), [], false, () => transformationSide(data, e.name)!));
  }
  return grid;
}
/** Lets the player take, change or remove a transformation (for example one granted by the GM during the campaign). */
export async function openTransformation(host: HTMLElement, c: CharacterSheet, onDone: () => void): Promise<void> {
  const data = await loadWithNotice(host);
  if (!data) return;
  if (!data.transformations.length) { alert('No transformations are available in the compendium.'); return; }
  let chosen = c.transformation ?? '';
  runWizard({
    host, heading: 'Transformation', finishLabel: 'Apply',
    steps: () => [{
      key: 'transformation', title: 'Choose a transformation', hint: 'A character can only have one. It always stays active and does not count against your domain cards.',
      problem: () => null,
      side: () => transformationSide(data, chosen),
      render: (body, _t, redraw) => body.append(transformationPicker(data, chosen, (n) => { chosen = n; redraw(); })),
    }],
    onFinish: () => { setTransformation(data, c, chosen); onDone(); },
  });
}

async function loadWithNotice(host: HTMLElement): Promise<RulesData | null> {
  const note = el('div', { class: 'dh dh-wiz-backdrop' }, el('div', { class: 'dh-wiz small' }, el('p', {}, 'Loading the rules...')));
  host.ownerDocument.body.append(note);
  try {
    return await loadRulesData();
  } catch (e) {
    alert(`Could not load the compendium: ${e instanceof Error ? e.message : e}`);
    return null;
  } finally {
    note.remove();
  }
}

// ---- Level up ----

const ADV_INFO: Record<AdvancementType, [string, string]> = {
  traits: ['Increase two traits', '+1 to two traits that are not marked yet. They become marked.'],
  hp: ['Add a Hit Point slot', 'One more Hit Point slot.'],
  stress: ['Add a Stress slot', 'One more Stress slot.'],
  experiences: ['Strengthen two Experiences', '+1 to two of your Experiences.'],
  card: ['Take another domain card', 'An extra domain card at your level or lower.'],
  evasion: ['Raise your Evasion', '+1 Evasion.'],
  subclass: ['Upgrade your subclass', 'Unlock the next level of your subclass.'],
  proficiency: ['Raise your Proficiency', '+1 Proficiency (costs 2 points).'],
  multiclass: ['Multiclass', 'Take a second class\'s domain, feature and subclass Foundation (costs 2 points).'],
};

/** Opens the level-up guide for the next level. The sheet is updated in place and the choices are remembered in its level history. */
export async function openLevelUp(host: HTMLElement, c: CharacterSheet, onDone: () => void): Promise<void> {
  if (c.level >= MAX_LEVEL) return;
  const data = await loadWithNotice(host);
  if (!data) return;
  const level = c.level + 1;
  const ach = achievementsAt(level);
  const rec: LevelUpRecord = { level, advancements: [], experience: '', card: undefined, swap: undefined };
  const points = () => rec.advancements.reduce((n, a) => n + advancementCost(a.type), 0);
  const detailTypes: AdvancementType[] = ['traits', 'experiences', 'card', 'multiclass'];
  const filters = new Map<string, { domain: string }>();
  const filterFor = (k: string) => { if (!filters.has(k)) filters.set(k, { domain: '' }); return filters.get(k)!; };
  const experienceNames = () => [...c.experiences.map((x) => x.name), ...(ach.experience ? [rec.experience?.trim() || 'New Experience'] : [])];
  const nextStage = () => STAGES[STAGES.indexOf(subclassStageOf(c)) + 1];
  const hasNextSubclass = () => !!nextStage() && !!subclassCard(data, `${c.className}: ${c.subclass}`, nextStage());
  const pickedCards = () => [rec.card, ...rec.advancements.map((a) => a.card)].filter((x): x is string => !!x);
  const cardPool = () => levelUpCards(data, c, level, rec.advancements.map((a) => ({ ...a, card: undefined })));

  const steps = (): Step[] => {
    const list: Step[] = [];
    if (ach.experience) {
      list.push({
        key: 'tier', title: `Tier ${tierOf(level)} begins`,
        hint: `Reaching level ${level} is a tier milestone: you gain +1 Proficiency${ach.clearTraits ? ', all your marked traits are cleared' : ''} and a new Experience.`,
        problem: () => (rec.experience?.trim() ? null : 'Write your new Experience.'),
        render: (body, touch) => {
          body.append(el('label', { class: 'dh-wiz-q' }, 'New Experience (+2)', textInput(rec.experience ?? '', 'e.g. Veteran of the border wars', (v) => { rec.experience = v; }, touch)));
        },
      });
    }

    list.push({
      key: 'advance', title: 'Choose two advancements', hint: 'You have 2 points. Most options cost 1; Proficiency and Multiclass cost 2.',
      problem: () => (points() === 2 ? null : `Spend ${2 - points()} more point(s).`),
      render: (body, touch, redraw) => {
        const grid = el('div', { class: 'cho-grid' });
        for (const t of ADVANCEMENT_TYPES) {
          const count = rec.advancements.filter((a) => a.type === t).length;
          const left = advancementLeft(c, t, level, rec.advancements);
          if (count === 0 && left === 0) continue;
          if (t === 'subclass' && !hasNextSubclass()) continue;
          const canAdd = left > 0 && points() + advancementCost(t) <= 2;
          const [title, text] = ADV_INFO[t];
          const info = t === 'subclass' && nextStage() ? `Unlock the ${nextStage()} level of ${c.subclass}.` : text;
          const extra: Array<Node | string> = [el('span', { class: 'meta' }, badge(`${advancementCost(t)} point${advancementCost(t) > 1 ? 's' : ''}`), badge(`${left} left`))];
          const b = choice(title + (count ? ` x${count}` : ''), info, count > 0, () => { rec.advancements.push({ type: t }); redraw(); }, extra, !canAdd && count === 0);
          if (!canAdd) b.classList.toggle('full', true);
          if (count > 0) {
            const undo = el('button', { type: 'button', class: 'undo' }, 'Remove one');
            undo.addEventListener('click', (ev) => {
              ev.stopPropagation();
              rec.advancements.splice(rec.advancements.map((a) => a.type).lastIndexOf(t), 1);
              redraw();
            });
            const wrap = el('div', { class: 'cho-wrap' }, b, undo);
            if (!canAdd) (b as HTMLButtonElement).disabled = true;
            grid.append(wrap);
          } else grid.append(b);
        }
        void touch;
        body.append(el('div', { class: 'dh-wiz-note' }, `Points spent: ${points()} / 2`), grid);
      },
    });

    rec.advancements.forEach((a, i) => {
      if (!detailTypes.includes(a.type)) return;
      const others = rec.advancements.filter((_, j) => j !== i);
      if (a.type === 'traits') {
        list.push({
          key: `adv-${i}`, title: 'Which traits improve?', hint: 'Pick two. Marked traits cannot be raised again until a tier milestone clears them.',
          problem: () => ((a.traits ?? []).length === 2 ? null : 'Pick two traits.'),
          render: (body, touch, redraw) => {
            const taken = new Set(others.flatMap((o) => o.traits ?? []));
            const grid = el('div', { class: 'cho-grid' });
            for (const n of TRAIT_ORDER) {
              const t = c.traits.find((x) => x.name === n)!;
              const blocked = (t.marked && !ach.clearTraits) || taken.has(n);
              const on = (a.traits ?? []).includes(n);
              grid.append(choice(n, blocked ? 'Already marked' : `${signed(t.mod)} -> ${signed(t.mod + 1)}`, on, () => {
                const cur = a.traits ?? (a.traits = []);
                if (on) cur.splice(cur.indexOf(n), 1);
                else if (cur.length < 2) cur.push(n as TraitName);
                touch();
                redraw();
              }, [], blocked || (!on && (a.traits ?? []).length >= 2)));
            }
            body.append(grid);
          },
        });
      } else if (a.type === 'experiences') {
        list.push({
          key: `adv-${i}`, title: 'Which Experiences grow?', hint: 'Pick two. Each gains +1.',
          problem: () => ((a.experiences ?? []).length === 2 ? null : 'Pick two Experiences.'),
          render: (body, touch, redraw) => {
            const grid = el('div', { class: 'cho-grid' });
            const names = experienceNames();
            const picked = a.experiences ?? (a.experiences = []);
            names.forEach((n, idx) => {
              const on = picked.includes(idx);
              const mod = idx < c.experiences.length ? c.experiences[idx].mod : 2;
              grid.append(choice(n, `${signed(mod)} -> ${signed(mod + 1)}`, on, () => {
                if (on) picked.splice(picked.indexOf(idx), 1);
                else if (picked.length < 2) picked.push(idx);
                touch();
                redraw();
              }, [], !on && picked.length >= 2));
            });
            body.append(grid);
          },
        });
      } else if (a.type === 'card') {
        list.push({
          key: `adv-${i}`, title: 'Choose an extra domain card', hint: 'At your level or lower, from your domains.',
          problem: () => (a.card ? null : 'Pick a card.'),
          side: () => { const e = data.cards.find((x) => x.name === a.card); return e ? cardPanel(e) : null; },
          render: (body, touch, redraw) => {
            const sel = a.card ? [a.card] : [];
            const pool = cardPool().filter((e) => !pickedCards().includes(e.name) || e.name === a.card);
            body.append(cardPicker(pool, sel, 1, () => { a.card = sel[0]; touch(); }, redraw, filterFor(`adv-${i}`)));
          },
        });
      } else if (a.type === 'multiclass') {
        list.push({
          key: `adv-${i}`, title: 'Multiclass', hint: 'Choose the class you branch into, one of its domains and a subclass Foundation. You can multiclass only once.',
          side: () => { const m = a.multiclass; if (m?.subclass) return subclassPanel(data, m.subclass); const e = m?.className ? findClass(data, m.className) : undefined; return e ? classPanel(e) : null; },
          problem: () => (!a.multiclass?.className ? 'Pick a class.' : !a.multiclass.domain ? 'Pick a domain.' : !a.multiclass.subclass ? 'Pick a subclass.' : null),
          render: (body, _t, redraw) => {
            const m = a.multiclass;
            const grid = el('div', { class: 'cho-grid' });
            for (const e of classEntries(data).filter((x) => x.name !== c.className)) {
              grid.append(choice(e.name, clip(e.text, 100), m?.className === e.name, () => { a.multiclass = { className: e.name, domain: '', subclass: '' }; redraw(); },
                [el('span', { class: 'meta' }, ...(e.domains ?? []).map((d) => badge(d)))], false, () => classPanel(e)));
            }
            body.append(section('Class', grid));
            if (m?.className) {
              const doms = el('div', { class: 'cho-row' });
              for (const d of (findClass(data, m.className)?.domains ?? []).filter((x) => !c.domains.includes(x))) doms.append(choice(d, '', m.domain === d, () => { m.domain = d; redraw(); }));
              const subs = el('div', { class: 'cho-grid' });
              for (const s of subclassesOf(data, m.className)) {
                subs.append(choice(shortSubclass(s), clip(subclassCard(data, s, 'foundation')?.text, 110), m.subclass === s, () => { m.subclass = s; redraw(); }, [], false, () => subclassPanel(data, s)));
              }
              body.append(section('Domain', doms), section('Subclass Foundation', subs));
            }
          },
        });
      }
    });

    const pool = () => cardPool().filter((e) => e.name === rec.card || !pickedCards().includes(e.name));
    list.push({
      key: 'card', title: 'Take a new domain card', hint: `A card of level ${level} or lower from your domains. It goes into your loadout if there is room, otherwise into the vault.`,
      problem: () => (rec.card || !cardPool().length ? null : 'Pick a card.'),
      side: () => { const e = data.cards.find((x) => x.name === rec.card); return e ? cardPanel(e) : null; },
      render: (body, touch, redraw) => {
        const sel = rec.card ? [rec.card] : [];
        body.append(cardPicker(pool(), sel, 1, () => { rec.card = sel[0]; touch(); }, redraw, filterFor('card')));
      },
    });

    list.push({
      key: 'swap', title: 'Swap a card? (optional)', hint: 'You may exchange one card you already own for another of the same level or lower. Hover a card to read it, or just continue to keep all your cards.',
      problem: () => (!!rec.swap?.out !== !!rec.swap?.in ? 'Choose both cards, or neither.' : null),
      side: () => {
        const e = data.cards.find((x) => x.name === (rec.swap?.in || rec.swap?.out));
        return e ? cardPanel(e) : null;
      },
      render: (body, touch, redraw) => {
        const swap = rec.swap ?? (rec.swap = { out: '', in: '' });
        const owned = [...c.loadout, ...c.vault];
        const entryOf = (name: string) => data.cards.find((x) => x.name === name);
        const mine = el('div', { class: 'cho-grid' });
        mine.append(choice('Keep all my cards', 'No swap.', !swap.out, () => { swap.out = ''; swap.in = ''; redraw(); }));
        for (const x of owned) {
          const e = entryOf(x.name);
          mine.append(choice(x.name, '', swap.out === x.name, () => { swap.out = x.name; swap.in = ''; redraw(); },
            [el('span', { class: 'meta' }, badge(`Lv ${x.level}`), badge(x.domain), badge(c.vault.includes(x) ? 'Vault' : 'Loadout'))], false, e ? () => cardPanel(e) : undefined));
        }
        body.append(section('Card to give up', mine));
        const outCard = owned.find((x) => x.name === swap.out);
        if (outCard) {
          const options = cardPool().filter((e) => (e.level ?? 1) <= outCard.level && !pickedCards().includes(e.name));
          const sel = swap.in ? [swap.in] : [];
          body.append(section(`Card to take instead of ${outCard.name}`, cardPicker(options, sel, 1, () => { swap.in = sel[0] ?? ''; touch(); }, redraw, filterFor('swap'))));
        }
      },
    });
    list.push({
      key: 'review', title: `Level ${level}`, hint: 'Confirm your choices. They are saved with your character.',
      problem: () => levelUpProblems(data, c, normalised())[0] ?? null,
      render: (body) => {
        const row = (k: string, v: string) => el('div', { class: 'dh-wiz-row' }, el('span', { class: 'muted' }, k), el('b', {}, v));
        const rows: HTMLElement[] = [];
        if (ach.experience) rows.push(row('Tier milestone', `+1 Proficiency, new Experience "${rec.experience}"${ach.clearTraits ? ', traits cleared' : ''}`));
        for (const a of rec.advancements) rows.push(row(ADV_INFO[a.type][0], describe(a)));
        rows.push(row('Damage thresholds', '+1 (automatic)'));
        if (rec.card) rows.push(row('New domain card', rec.card));
        if (rec.swap?.out && rec.swap.in) rows.push(row('Card swap', `${rec.swap.out} -> ${rec.swap.in}`));
        body.append(el('div', { class: 'dh-wiz-sum' }, ...rows));
      },
    });
    return list;
  };

  const describe = (a: Advancement): string => {
    switch (a.type) {
      case 'traits': return (a.traits ?? []).join(' & ');
      case 'experiences': return (a.experiences ?? []).map((i) => experienceNames()[i]).join(' & ');
      case 'card': return a.card ?? '';
      case 'multiclass': return a.multiclass ? `${a.multiclass.className} (${a.multiclass.domain}, ${shortSubclass(a.multiclass.subclass)})` : '';
      case 'subclass': return `${c.subclass}: ${nextStage() ?? ''}`;
      default: return '';
    }
  };
  const normalised = (): LevelUpRecord => ({ ...rec, swap: rec.swap?.out && rec.swap.in ? rec.swap : undefined, experience: ach.experience ? rec.experience : undefined });

  runWizard({
    host, heading: `Level up to ${level}`, steps, finishLabel: 'Level up!',
    onFinish: () => {
      applyLevelUp(data, c, normalised());
      onDone();
    },
  });
}

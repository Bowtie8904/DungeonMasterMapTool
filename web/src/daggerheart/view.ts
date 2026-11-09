import { el } from '../login';
import { confirmDialog } from '../dialogs';
import { editTokenArtwork } from '../token-artwork';
import { chooseTalismanHope } from './witch-view';
import { chooseActionAmount } from './amount-view';
import { chooseSpecialCard, type SpecialCardChoice } from './special-card-view';
import {
  Advantage, Armor, CharacterSheet, DomainCard, Feature, InventoryItem, Spell, Track, TRAIT_ACTIONS, TRAIT_ORDER, TraitName, Weapon,
  dualityExpression, handsUsed, ignoresWeaponBurden, multiclassesOf, shortTrait, signed, tierOf,
} from './data';
import {
  ActionDef, ActiveEffect, Automation, RollBonus, StatSource, activeFeatures, activeWeapons, actionBlocker, actionCost, addEffect, asksSpellcastRoll, consumeBonus, costText, damageExpression,
  availableRollOptions, commitRollOptions, configuredRollOption, criticalBonus, damageContributions, damageRollFormula, derivedStats, deathrunProficiency, evalValue, experienceModifier, resourceDefinitions, rollBonus, rollExperienceRefund, rollOptionQuantityMax,
  damagePool, effectCounterMax, effectCounterText, effectiveAdvantage, maximizeDamageDie, maximizableDamageDice, poolDice, rollLocal, rollOptionDice, rollOptionLabel, rollTargets, routeDeathAction, runActionDetailed, selectedRollBonus, selectionBlocker, setTrackSlots, sheetActions, statBreakdown, suggestedAdvantage, syncDerived, traitContributions, traitTotal, usesLeft, usesMax,
} from './engine';
import { comboDie, criticalMeleeBonus, refocus, shiftStance } from './brawler';
import { chooseStance } from './brawler-view';
import { activateAura, canCastDomain, channelElement, transformBeastform } from './druid';
import { chooseBeastform } from './druid-view';
import { channelRawPower, chooseActionEffects } from './sorcerer';
import { chooseChannelCard, chooseFeatureEffects } from './sorcerer-view';
import { chooseWarlockAmount } from './warlock-view';
import type { RollContribution, RollSource } from '../protocol';
import { rollSourceFor } from './roll-source';
import { characterFeatureGroups } from './feature-groups';
import { isManualOverride, removeManualOverride, setManualStat } from './engine';
import { isVitalityEffect, removeVitality } from './vitality';

import { openCompanionMancer, openLevelUp, openMancer, openTransformation } from './mancer';
import { companionAvailable, companionFeatures, companionRules, companionWeapon, hasCompanionTraining, markCompanionDamage, resolveBonded } from './companion';
import { inline, rich } from './rich';
import { MAX_LEVEL, replaceAncestry } from './rules';
import { chooseAncestryFeature } from './ancestry-choice';
import { openTestingEditor } from './testing-view';
import { openRestDialog } from './rest-view';
import { openDamageDialog } from './damage-view';
import { canMakeDeathMove, hpHealingFloor, type DeathMove, type DeathMoveExtras, type DeathOptions } from './death';
import { openDeathDialog, openScarsDialog } from './death-view';
import { actionReportTitle, formatActionReport } from './action-summary';
import { iconButton } from '../icons';
import { mutagenEffectSource } from '../effects';
import { INVENTORY_DRAG, prepareInventoryDrag, transferable, type SharedEntry } from '../shared-cards';
import { activeCards, activeCardSources, actorAutomationAvailable, copiedCardAvailable } from './copycat';
import type { CardPreferences, CardPreferenceState } from './card-preferences';
import type { DomainCardView } from '../api';

const CARD_DRAG = 'application/x-dmmt-domain-card';

export interface RollRequest {
  hostOptions?: Array<{ key: string; id: string; trait?: TraitName }>;
  hostAction?: { key: string; id: string; amount?: number };
  deathrun?: { effectId: string; count: number };
  deathrunRemove?: number;
  weaponDamage?: { proficiency: number; proficiencyBonus: number; maximizeDie?: number; multiplier: number; options: Array<{ key: string; id: string }> };
  damageRules?: { comboDie?: number; maximumOnes?: boolean; rerollBelow?: number; matchingDice?: boolean };
  label: string;
  expression: string;
  kind?: 'duality';
  difficulty?: number;
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
  artwork?: string;
  featureAction?: (name: string, id: string) => boolean;
  private listeners: Array<(save?: boolean) => void> = [];
  /** Recently sent rolls by ref, so chat buttons can repeat them. */
  readonly sent = new Map<string, RollRequest>();
  /** Opens the damage dialog for an equipped weapon; set by the rendered sheet. */
  damage?: (weapon: string, critical?: boolean, attackFear?: number, proficiencyBonus?: number, linkedBonus?: number, action?: WeaponDamageAction, outcome?: 'hope' | 'fear' | 'critical') => boolean;
  /** Opens the damage dialog for a spell; onSent runs once the roll was sent. */
  spellDamage?: (label: string, spec: NonNullable<ActionDef['damage']>, critical: boolean, onSent: () => void, rollSource?: RollSource, pendingAction?: PendingAction) => boolean;
  subscribe(fn: (save?: boolean) => void): void { this.listeners.push(fn); }
  notify(save = true): void { this.listeners.forEach((fn) => fn(save)); }
}

export interface WeaponDamageAction {
  pending: PendingAction;
  submit: (request: RollRequest) => Promise<boolean>;
}

export interface SheetViewOptions {
  /** Hidden windows retain their live model and dialog handlers without building sheet DOM. */
  visible?: () => boolean;
  supportsSpecialCardActions?: () => boolean;
  gmFear?: () => number;
  onSpecialCard?: (key: string, actionId: string, choice: SpecialCardChoice) => Promise<void>;
  supportsSorcererAutomation?: () => boolean;
  supportsWarlockAutomation?: () => boolean;
  supportsWitchAutomation?: () => boolean;
  supportsDomainCardAutomation?: () => boolean;
  supportsBloodAutomation?: () => boolean;
  supportsSeraphAutomation?: () => boolean;
  supportsDruidAutomation?: () => boolean;
  supportsBrawlerRolls?: () => boolean;
  character: CharacterSheet;
  readOnly?: () => boolean;
  cardPreferences?: CardPreferences;
  artwork?: string;
  /** Sends a roll to the shared roll log; returns false when it could not be sent. */
  onRoll: (roll: RollRequest) => boolean | Promise<boolean>;
  onRest?: (rest: NonNullable<import('../api').FeatureRequest['rest']>) => Promise<void>;
  restActors?: () => import('../protocol').Actor[];
  /** Posts a short message (action results) to the shared chat. */
  onLog?: (text: string, rollSource?: RollSource, restDice?: import('./rest').RestDie[]) => void;
  onFeature?: (key: string, actionId: string, amount?: number) => Promise<void>;
  onCopycat?: (key: string, actionId: string) => Promise<void>;
  onDamageTaken?: (severity: number) => Promise<void>;
  onIncomingDamage?: (damage: NonNullable<import('../api').FeatureRequest['incomingDamage']>) => Promise<void>;
  onDeathMove?: (move: DeathMove, extras?: DeathMoveExtras) => Promise<void>;
  onDeathOptions?: () => Promise<DeathOptions>;
  actorId?: string;
  onShare?: (entry: SharedEntry, offerQuantity?: number) => void;
  onPrepareShare?: () => Promise<void>;
  bus?: SheetBus;
  /** Called after anything on the sheet may have changed (the host debounces and saves). */
  onChange?: () => void;
  supportsKeptDice?: () => boolean;
  supportsZeroKeptDice?: () => boolean;
  supportsRollMultipliers?: () => boolean;
  supportsPartyFeatures?: () => boolean;
  /** Receives a compact Evasion and traits strip after every render (the floating window shows it on its minimised bar). */
  onMiniStrip?: (strip: HTMLElement | null) => void;
  onEditControl?: (control: HTMLElement) => void;
}

type Page = 'char' | 'companion' | 'beastform' | 'equip' | 'loot' | 'cards' | 'effects' | 'bg';
const PAGES: Array<[Page, string]> = [
  ['char', 'Character'], ['companion', 'Companion'], ['beastform', 'Beastform'], ['cards', 'Domain Cards'], ['equip', 'Equipment'], ['loot', 'Loot'], ['bg', 'Background'], ['effects', 'Effects'],
];

export function pips(track: Track, kind: string, color: string, round = false, onChange?: () => void, scars = 0, permanent = 0, temporary = 0, onSlotEdit?: (slots: number) => void, interactive = true): HTMLElement {
  const box = el('div', { class: 'pips' });
  box.dataset.kind = kind;
  box.style.setProperty('--c', color);
  const btns: HTMLButtonElement[] = [];
  const paint = () => btns.forEach((b, i) => b.classList.toggle('on', i < track.on));
  for (let i = 0; i < track.total; i++) {
    const locked = i >= track.slots || i < permanent + temporary;
    const scarred = i >= track.total - scars;
    const b = el('button', { type: 'button', disabled: !interactive || locked && (!onSlotEdit || i < permanent + temporary), class: `pip${round ? ' round' : ''}${locked ? ' locked' : ''}${scarred ? ' scarred' : ''}${i < permanent ? ' permanent-hp' : ''}`, title: i < permanent ? 'Permanently marked HP — cannot be healed' : scarred ? 'Crossed out by a permanent scar' : onSlotEdit ? (i >= track.slots ? `Enable ${kind} slots through ${i + 1}` : `Disable ${kind} slot ${i + 1} and later slots`) : locked ? 'Locked - gained through advancement' : '' }, i < permanent ? '×' : scarred ? 'X' : '');
    b.setAttribute('aria-label', `${kind} ${i + 1}`);
    if (i >= permanent && i < permanent + temporary) {
      b.title = 'Marked HP locked by Volatile Toxins until your next rest';
      b.textContent = 'X';
    }
    b.addEventListener('click', () => {
      if (!interactive) return;
      if (onSlotEdit) {
        if (i < permanent + temporary) return;
        onSlotEdit(i >= track.slots ? i + 1 : i);
        return;
      }
      if (locked) return;
      track.on = Math.max(permanent + temporary, track.on === i + 1 ? i : i + 1);
      paint();
      onChange?.();
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
  let editMode = false;
  const canEdit = () => editMode && !opts.readOnly?.();
  const displayedPronouns = () => c.description.Pronouns?.trim() ? c.description.Pronouns : c.pronouns;
  const pronounsText = (value: string) => value.trim() ? ` (${value})` : '';
  let portraitErrorMessage = '';
  let pronounsDisplay: HTMLElement;
  const root = el('div', { class: 'dh' });
  root.addEventListener('click', event => {
    if (!opts.readOnly?.()) return;
    const target = event.target as Element;
    if (target.closest('.tabs [role="tab"], .loot-description button, .card-view-switch button, .feature-search')
      || target.closest('.domain-list-summary') && !target.closest('button')) return;
    event.preventDefault();
    event.stopImmediatePropagation();
  }, true);
  for (const type of ['dragstart', 'dragover', 'drop']) {
    root.addEventListener(type, event => {
      if (opts.readOnly?.()) { event.preventDefault(); event.stopImmediatePropagation(); }
    }, true);
  }
  const deathDialog = (initial?: string) => openDeathDialog(document.body, c, async (move, extras) => {
    if (!opts.onDeathMove) throw new Error('This host does not support death moves. Update the host.');
    await opts.onDeathMove(move, extras);
  }, opts.onDeathOptions, initial);
  const inventoryQuery = { equipment: '', loot: '' };
  const expandedDescriptions = new Set<string>();
  let layoutPending = false;
  const scheduleLayout = () => {
    if (layoutPending || opts.visible?.() === false) return;
    layoutPending = true;
    requestAnimationFrame(() => {
      layoutPending = false;
      if (opts.visible?.() === false) return;
      measureDescriptions();
      layoutCards();
    });
  };
  const descriptionObserver = new ResizeObserver(scheduleLayout);
  descriptionObserver.observe(root);
  const cardLayoutObserver = new ResizeObserver(scheduleLayout);
  function layoutCards(): void {
    root.querySelectorAll<HTMLElement>('.cards:not(.cards-list)').forEach(grid => {
      const width = grid.clientWidth;
      if (!width) return;
      const gap = 10;
      const columns = width >= 610 ? 2 : 1;
      const cardWidth = (width - gap * (columns - 1)) / columns;
      const cards = [...grid.children].filter((node): node is HTMLElement => node instanceof HTMLElement && !node.hidden);
      for (const node of cards) node.style.width = `${cardWidth}px`;
      const heights = cards.map(node => node.getBoundingClientRect().height);
      const bottoms = Array<number>(columns).fill(0);
      cards.forEach((node, i) => {
        const column = columns === 2 && bottoms[1] < bottoms[0] ? 1 : 0;
        node.style.left = `${column * (cardWidth + gap)}px`;
        node.style.top = `${bottoms[column]}px`;
        bottoms[column] += heights[i] + gap;
      });
      const height = `${Math.max(0, ...bottoms.map(bottom => bottom - gap))}px`;
      if (grid.style.height !== height) grid.style.height = height;
    });
  }
  function measureDescriptions(): void {
    root.querySelectorAll<HTMLElement>('.loot-description').forEach(wrap => {
      const text = wrap.querySelector<HTMLElement>('.loot-description-text')!;
      const toggle = wrap.querySelector<HTMLButtonElement>('button')!;
      const expanded = toggle.getAttribute('aria-expanded') === 'true';
      toggle.hidden = !expanded && text.scrollHeight <= text.clientHeight + 1;
    });
  }
  let sourceTooltip: HTMLElement | undefined;
  const hideSourceTooltip = () => {
    sourceTooltip?.remove();
    sourceTooltip = undefined;
  };
  root.addEventListener('scroll', hideSourceTooltip, true);
  root.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') hideSourceTooltip();
  });

  function share(node: HTMLElement, entry: SharedEntry, offer = false, canOffer = offer): HTMLElement {
    const header = (node.classList.contains('slot') ? node.querySelector('.top') : node.querySelector('.inventory-controls')) ?? (entry.category === 'feature'
      ? node.querySelector('.src') ?? node
      : node.querySelector('td, .ft, .top, .hd') ?? node);
    if (offer) {
      const remove = iconButton('delete', `Delete ${entry.item.name}`, () => {
        const list = (entry.category === 'item' ? c.inventory : entry.category === 'weapon' ? c.carriedWeapons : c.carriedArmor) as unknown[];
        const index = list.indexOf(entry.item);
        if (index >= 0) list.splice(index, 1);
        render();
      });
      remove.classList.add('share-icon', 'delete-item');
      header.append(remove);
    }
    if (entry.category === 'domain' && canEdit()) header.append(deleteCardButton(entry.item));
    if (!opts.onShare) return node;
    const button = iconButton('chat', `Post ${entry.item.name} to chat`, () => opts.onShare!(entry));
    button.classList.add('share-icon');
    header.insertBefore(button, header.querySelector('.delete-item'));
    if (canOffer) {
      const transfer = iconButton('transfer', `Offer ${entry.item.name} for transfer`, () => offerEntry(entry));
      transfer.classList.add('share-icon');
      header.insertBefore(transfer, header.querySelector('.delete-item'));
    }
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

  function offerEntry(entry: SharedEntry): void {
    const identity = (item: SharedEntry['item']) => JSON.stringify(
      Object.fromEntries(Object.entries(item).filter(([key]) => key !== 'qty')),
      (_key, value: unknown) => value && typeof value === 'object' && !Array.isArray(value)
        ? Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b))) : value,
    );
    const available = entry.category === 'gold' ? Math.max(0, Math.floor(c.gold))
      : (entry.category === 'item' ? c.inventory : entry.category === 'weapon' ? c.carriedWeapons : c.carriedArmor)
        .filter(item => identity(item) === identity(entry.item))
        .reduce((n, item) => n + ('qty' in item ? item.qty : 1), 0);
    if (available <= 0) return;
    if (available <= 1) { opts.onShare?.(entry, 1); return; }
    const dialog = el('dialog', { class: 'inventory-picker' });
    const input = el('input', { type: 'number', min: '1', max: String(available), step: '1', value: String(available), required: true, ariaLabel: 'Quantity to offer' });
    const cancel = el('button', { type: 'button' }, 'Cancel');
    const submit = el('button', { type: 'submit', class: 'primary' }, 'Offer transfer');
    const form = el('form', {}, el('h3', {}, `Offer ${entry.item.name}`),
      el('label', {}, `Quantity (up to ${available})`, input), el('div', { class: 'row' }, cancel, submit));
    cancel.addEventListener('click', () => dialog.close());
    form.addEventListener('submit', e => {
      e.preventDefault();
      if (!form.reportValidity()) return;
      opts.onShare?.(entry, input.valueAsNumber);
      dialog.close();
    });
    dialog.addEventListener('close', () => dialog.remove(), { once: true });
    dialog.append(form);
    document.body.append(dialog);
    dialog.showModal();
    input.focus();
    input.select();
  }

  function addCustomItem(): void {
    const dialog = el('dialog', { class: 'inventory-picker' });
    const name = el('input', { type: 'text', required: true, maxLength: 120, ariaLabel: 'Item name' });
    const note = el('textarea', { rows: 3, maxLength: 2000, ariaLabel: 'Item description' }) as HTMLTextAreaElement;
    const type = el('select', { ariaLabel: 'Item type' }, ...(['loot', 'gear', 'consumable', 'class item'] as const).map(t => el('option', { value: t }, t)));
    type.value = 'loot';
    const amount = el('input', { type: 'number', min: '1', max: '99', step: '1', value: '1', required: true, ariaLabel: 'Quantity' });
    type.addEventListener('change', () => { amount.max = type.value === 'consumable' ? '5' : '99'; });
    const cancel = el('button', { type: 'button' }, 'Cancel');
    const submit = el('button', { type: 'submit', class: 'primary' }, 'Add item');
    const form = el('form', {}, el('h3', {}, 'Add custom item'),
      el('label', {}, 'Name', name), el('label', {}, 'Description (optional)', note),
      el('label', {}, 'Type', type), el('label', {}, 'Quantity', amount), el('div', { class: 'row' }, cancel, submit));
    cancel.addEventListener('click', () => dialog.close());
    form.addEventListener('submit', e => {
      e.preventDefault();
      if (!name.value.trim()) name.value = '';
      if (!form.reportValidity()) return;
      const item: InventoryItem = { name: name.value.trim(), type: type.value as InventoryItem['type'], qty: amount.valueAsNumber, custom: true };
      if (note.value.trim()) item.note = note.value.trim();
      c.inventory.push(item);
      dialog.close();
      opts.onChange?.();
      render();
    });
    dialog.addEventListener('close', () => dialog.remove(), { once: true });
    dialog.append(form);
    document.body.append(dialog);
    dialog.showModal();
    name.focus();
  }

  const sendRoll = (source: string, weapon?: string, rollSource?: RollSource) => (r: RollRequest): boolean | Promise<boolean> => {
    if (c.pendingDeathRoll) { opts.onLog?.('Resolve pending Risk It All recovery in chat before rolling.'); return false; }
    const ref = Math.random().toString(36).slice(2, 12);
    const full: RollRequest = { ...r, ref, source: r.source ?? source, ...(rollSource ? { rollSource } : {}), ...(weapon ? { weapon } : {}), ...(opts.actorId ? { actorId: opts.actorId } : {}) };
    const sent = opts.onRoll(full);
    if (sent && opts.bus) {
      opts.bus.sent.set(ref, full);
      if (opts.bus.sent.size > 60) opts.bus.sent.delete(opts.bus.sent.keys().next().value!);
    }
    return sent;
  };
  const rollDuality = (label: string, trait: TraitName | null, targets: string[], weapon?: Weapon, rollSource?: RollSource, difficulty?: number) => {
    const allTargets = [...targets, 'action'];
    const bonus = rollBonus(c, allTargets, weapon, false);
    const send = sendRoll(allTargets.join(','), weapon?.name, rollSource ?? (targets.includes('companion') ? rollSourceFor(c, 'companion') : weapon ? rollSourceFor(c, `weapon:${weapon.name}`) : undefined));
    openRollDialog(root, c, label, trait ? traitContributions(c, trait) : [], bonus, request => send({ ...request, ...(difficulty === undefined ? {} : { difficulty }) }), () => { consumeBonus(c, bonus); render(); }, allTargets, undefined, undefined, weapon, false, undefined, undefined, undefined, undefined, 0, undefined, undefined, opts.supportsDomainCardAutomation);
  };
  const rollDamage = (label: string, expression: string, rollSource?: RollSource) => opts.onRoll({ label, expression, rollSource });
  const openWeaponDamage = (w: Weapon, critical = false, targets = ['damage'], after?: () => void, rollSource?: RollSource, pendingAction?: PendingAction, attackFear?: number, proficiencyBonus = 0, linkedBonus = 0, action?: WeaponDamageAction, outcome?: 'hope' | 'fear' | 'critical') => {
    const exhausted = c.effects.find(e => e.deathrun && deathrunProficiency(c, e.counter?.value ?? 0) <= 0);
    if (exhausted && !targets.includes('spelldamage')) {
      if (!opts.onFeature) { opts.onLog?.('End the exhausted Deathrun before rolling damage.'); return; }
      void opts.onFeature(`effect:${exhausted.id}`, 'end').then(() =>
        openWeaponDamage(w, critical, targets, after, rollSource, pendingAction, attackFear, proficiencyBonus, linkedBonus, action, outcome))
        .catch((e: Error) => opts.onLog?.(`Could not end Deathrun: ${e.message}`));
      return;
    }
    const bonus = rollBonus(c, targets, w, false);
    if (linkedBonus) {
      bonus.value += linkedBonus;
      bonus.contributions.push({ label: 'Rage Up', expression: String(linkedBonus) });
      bonus.names.push('Rage Up');
    }
    openRollDialog(root, c, `${w.name} damage`, [], bonus, action?.submit ?? sendRoll(['damage', ...(w.damageType === 'magic' ? ['magicdamage'] : []), ...(targets.includes('spelldamage') ? ['spelldamage'] : [])].join(','), w.name, rollSource ?? rollSourceFor(c, `weapon:${w.name}`)),
      () => { consumeBonus(c, action ? rollBonus(c, targets, w, false) : bonus); after?.(); render(); }, targets, w, opts.supportsKeptDice, undefined, critical, action?.pending ?? pendingAction, opts.supportsRollMultipliers, attackFear, opts.supportsBrawlerRolls, proficiencyBonus, opts.supportsDruidAutomation, opts.supportsSeraphAutomation, opts.supportsDomainCardAutomation, opts.supportsZeroKeptDice, !!action, outcome);
  };
  if (opts.bus) opts.bus.spellDamage = (label, spec, critical, onSent, rollSource, pendingAction) => {
    const spell = featureWeapon(label, { ...spec, type: spec.type ?? 'magic' });
    openWeaponDamage(spell, critical, ['damage', 'spelldamage'], onSent, rollSource, pendingAction);
    return true;
  };
  if (opts.bus) opts.bus.damage = (name, critical, attackFear, proficiencyBonus, linkedBonus, action, outcome) => {
    const companion = companionWeapon(c);
    const w = companion?.name === name ? companion : activeWeapons(c).find((x) => x.name === name);
    if (w?.generated === 'companion' && !companionAvailable(c)) { opts.onLog?.('Your companion is unavailable.'); return false; }
    if (w) openWeaponDamage(w, critical, ['damage'], undefined, undefined, undefined, attackFear, proficiencyBonus, linkedBonus, action, outcome);
    else {
      if (action) return false;
      const feature = [...activeFeatures(c), ...c.heritage].find((f) => f.name === name);
      const featureAction = feature?.automation?.actions?.find((a) => a.damage);
      if (!feature || !featureAction) return false;
      openFeatureDamage(`feat:${name}`, feature.automation!, featureAction, name, critical, undefined, attackFear);
    }
    return true;
  };
  let renderPending = false;
  let renderSave = false;
  opts.bus?.subscribe((save = true) => {
    renderSave ||= save;
    if (renderPending) return;
    renderPending = true;
    queueMicrotask(() => {
      renderPending = false;
      const save = renderSave;
      renderSave = false;
      render(save);
    });
  });
  if (opts.bus) opts.bus.featureAction = (name, id) => {
    const feature = activeFeatures(c).find(f => f.name === name);
    const action = feature?.automation?.actions?.find(a => a.id === id);
    if (!feature || !action || !feature.automation) return false;
    actionButton(`feat:${name}`, feature.automation, action, name, {}).click();
    return true;
  };
  for (const ev of ['click', 'input', 'change']) root.addEventListener(ev, event => {
    const target = event.target as Element;
    if (target.closest('.tabs, .card-view-switch, .feature-search, .sheet-edit-control')
      || ev === 'click' && target.closest('.domain-list-summary') && !target.closest('button')) return;
    if (!opts.readOnly?.()) opts.onChange?.();
  });

  const cardResourceIds = (): Set<string> => new Set([...activeCards(c).map((d) => d.automation), ...c.inventory.map((i) => i.automation)].flatMap((a) => a?.resources ?? []).map((r) => r.id));
  /** Re-evaluates rendered action buttons after a resource changes without re-rendering the sheet. */
  let actionRefreshers: Array<() => void> = [];
  const refreshActions = () => actionRefreshers.forEach((fn) => fn());
  function actionRow(key: string, a: Automation | undefined, label: string, hooks: { extraBlock?: () => string | null; after?: () => void } = {}): HTMLElement | null {
    const actions = sheetActions(a);
    if (!actions.length && (!a?.resources?.length || a.classResourceOnly) && !a?.effectCounters?.length && !a?.dicePool && !a?.uses) return null;
    const row = el('div', { class: 'row actions' });
    if (key.startsWith('item:') || key.startsWith('feat:')) {
      for (const r of a?.classResourceOnly ? [] : a?.resources ?? []) row.append(el('label', { class: 'muted', style: 'font-size:12px;display:flex;gap:4px;align-items:center' }, r.label, poolControls(r)));
      for (const r of a?.effectCounters ?? []) row.append(el('span', { class: 'muted', style: 'font-size:12px;display:flex;gap:4px;align-items:center' }, r.label, counterResource(r)));
    }
    const sharedUses = a?.uses && actions.some(action => action.usesSource === false)
      ? el('span', { class: 'row actions', ariaLabel: `${label} limited actions` }) : row;
    for (const action of actions.filter(action => !action.requiresFeature || c.features.some(f => f.name === action.requiresFeature))) {
      const button = actionButton(key, a!, action, label, hooks);
      const remaining = action.uses ? usesLeft(c, `${key}:action:${action.id}`, { uses: action.uses }) : null;
      (action.usesSource === false ? row : sharedUses).append(action.uses
        ? el('span', { class: 'row' }, button, el('span', { class: 'muted', style: 'font-size:11.5px' }, `${remaining}/${action.uses.max} per ${action.uses.per.replace(/([A-Z])/g, ' $1').toLowerCase()}`))
        : button);
    }
    const left = a ? usesLeft(c, key, a) : null;
    if (left !== null && a?.uses) sharedUses.append(el('span', { class: 'muted', style: 'font-size:11.5px' }, `${left}/${usesMax(c, key, a)} per ${a.uses.per.replace(/([A-Z])/g, ' $1').toLowerCase()}`));
    if (sharedUses !== row) row.append(sharedUses);
    return row;
  }

  function featureWeapon(label: string, spec: NonNullable<ActionDef['damage']>): Weapon {
    const spellcast = c.traits.find(trait => trait.spellcast);
    const count = spellcast ? Math.max(0, Math.floor(traitTotal(c, spellcast.name))) : 0;
    const dice = spec.count === 'spellcast'
      ? spec.dice.replace(/(\d*)d(\d+)/gi, (_term, diceCount: string, sides: string) => count > 0 ? `${Number(diceCount || 1) * count}d${sides}` : '0')
      : spec.dice;
    return { name: label, slot: 'Primary', damageType: spec.type === 'magic' ? 'magic' : 'phys', trait: 'Instinct',
      range: '', damage: dice, hands: 1, tier: 0, fixedDice: spec.fixed || spec.count === 'spellcast',
      ...(spec.extraDice ? { automation: { damage: { extraDice: spec.extraDice, dropLowest: 0 } } } : {}) };
  }

  function openFeatureDamage(key: string, a: Automation, action: ActionDef, label: string, critical = false, after?: () => void, attackFear?: number): void {
    openWeaponDamage(featureWeapon(label, action.damage!), critical, ['damage'],
      () => { runActionDetailed(c, key, a, action, label); after?.(); }, rollSourceFor(c, key), { key, automation: a, action }, attackFear);
  }

  function actionButton(key: string, a: Automation, action: ActionDef, label: string, hooks: { extraBlock?: () => string | null; after?: () => void }): HTMLElement {
    if (action.specialAction === 'vitality' && c.vitalityBenefits?.length) {
      return el('span', { class: 'muted' }, `Vitality permanently used: ${c.vitalityBenefits.join(', ')}.${c.vitalityEffectRemoved ? ' Buff removed; benefits no longer apply.' : ' Buff removable only in edit mode under Effects.'}${c.testingCharacter ? ' Retained only to preserve the fixed testing loadout.' : ''}`);
    }
    if (key.startsWith('card:') && !canCastDomain(c) && action.actionRoll?.trait === 'spellcast') {
      return el('button', { type: 'button', class: 'small', disabled: true, title: 'Cannot cast domain spells in Beastform' }, action.label);
    }
    const cost = costText(actionCost(c, action), c);
    const b = el('button', { class: 'small primary', type: 'button' }, cost ? `${action.label} (${cost})` : action.label);
    if (action.actionRoll?.trait === 'highest') {
      const trait = TRAIT_ORDER.reduce((best, name) => traitTotal(c, name) > traitTotal(c, best) ? name : best);
      b.textContent = `Attack ${signed(traitTotal(c, trait))} (${trait})`;
    }
    const blocker = () => action.bloodAutomation && !opts.supportsBloodAutomation?.()
      ? 'Update the host to use Blood automation.' : hooks.extraBlock?.() ?? actionBlocker(c, key, a, action);
    const block = blocker();
    if (block) { b.disabled = true; b.title = block; }
    let busy = false;
    actionRefreshers.push(() => {
      if (busy || !b.isConnected) return;
      const current = blocker();
      b.disabled = !!current;
      b.title = current ?? '';
      const currentCost = costText(actionCost(c, action), c);
      if (action.actionRoll?.trait !== 'highest') b.textContent = currentCost ? `${action.label} (${currentCost})` : action.label;
    });
    const setBusy = (value: boolean) => { busy = value; b.disabled = value; };
    const originalAction = action;
    b.addEventListener('click', async () => {
      let action = originalAction;
      const currentBlock = blocker();
      if (currentBlock) { b.title = currentBlock; return; }
      if (action.castTokens || action.selectAmount) {
        const amount = await chooseActionAmount(c, action);
        if (amount === undefined) return;
        action = { ...action, selectedAmount: amount, ...(action.castTokens
          ? { cost: { ...action.cost, [action.castTokens.resource]: amount } } : {}) };
      }
      if (action.hostOwned && !action.actionRoll) {
        if (!opts.onFeature || !opts.supportsDomainCardAutomation?.()) { opts.onLog?.('Update the host to use this card action.'); return; }
        setBusy(true);
        try { await opts.onFeature(key, action.id, action.selectedAmount); hooks.after?.(); render(false); }
        catch (e) { opts.onLog?.(`Could not use ${label}: ${(e as Error).message}`); }
        finally { setBusy(false); }
        return;
      }
      if (action.specialAction) {
        if (!opts.supportsSpecialCardActions?.() || !opts.onSpecialCard) { opts.onLog?.('Update the host to use this special card action.'); return; }
        const submit = async (choice: SpecialCardChoice) => {
          await opts.onSpecialCard!(key, action.id, choice);
          hooks.after?.();
          render();
        };
        if (action.specialAction !== 'massDisguise') chooseSpecialCard(c, action, opts.gmFear ?? (() => 0), submit);
        else {
          setBusy(true);
          try { await submit({}); }
          catch (e) { opts.onLog?.((e as Error).message); }
          finally { setBusy(false); }
        }
        return;
      }
      if (action.copycat) {
        if (!opts.onCopycat || !opts.supportsDomainCardAutomation?.()) { opts.onLog?.('Update the host to use Copycat.'); return; }
        setBusy(true);
        try { await opts.onCopycat(key, action.id); hooks.after?.(); render(); }
        catch (e) { opts.onLog?.((e as Error).message); }
        finally { setBusy(false); }
        return;
      }
      if ((action.target === 'anyActor' || action.giveFear || action.chatDamage
          || action.steps?.some(step => step.effect?.exclusiveSource || step.effect?.rest)
          || action.chatBenefits?.some(benefit => benefit.transferStress)) && !opts.supportsDomainCardAutomation?.()) {
        opts.onLog?.('Update the host to use this domain-card effect.');
        return;
      }
      if ((action.witchAction || a.resources?.some(r => r.whileEffect) || action.steps?.some(s => s.effect?.witchGrant) || a.rollTriggers?.some(t => t.automatic))
          && !opts.supportsWitchAutomation?.()) {
        opts.onLog?.('Update the host to use Witch automation.');
        return;
      }
      if (action.bloodAutomation && !opts.supportsBloodAutomation?.()) {
        opts.onLog?.('Update the host to use Blood automation.');
        return;
      }
      if (action.witchAction === 'lifeblood') {
        if (!opts.onFeature) { opts.onLog?.('Update the host to create a Lifeblood Talisman.'); return; }
        setBusy(true);
        try { await opts.onFeature(key, action.id); hooks.after?.(); render(); }
        catch (e) { opts.onLog?.((e as Error).message); }
        finally { setBusy(false); }
        return;
      }
      if (action.witchAction === 'talisman') {
        if (!opts.onFeature) { opts.onLog?.('Update the host to create an Enchanted Talisman.'); return; }
        chooseTalismanHope(c, async amount => {
          await opts.onFeature!(key, action.id, amount);
          hooks.after?.();
          render();
        });
        return;
      }
      if (action.confirm && !await confirmDialog(action.confirm, action.label)) return;
      if (a.deathMove?.kind === 'mask' && action.steps?.some(s => s.op === 'gain' && s.resource === 'death-mask')
          && !await confirmDialog('Confirm at the table that you have earned this Mask token.')) return;
      if (routeDeathAction(action, deathDialog)) return;
      if (action.codexSwap) {
        if (c.testingCharacter) { opts.onLog?.('The testing loadout cannot be changed.'); return; }
        const dialog = el('dialog', { class: 'dh dh-rolldlg dh-option-rolldlg', ariaLabel: action.label });
        const error = el('div', { class: 'error', role: 'alert' });
        const choices = el('div', { class: 'list dh-roll-options' });
        for (const card of c.vault) {
          const button = el('button', { type: 'button', class: 'small' }, card.name);
          button.addEventListener('click', () => {
            const sourceIndex = c.loadout.findIndex(item => key === `card:${item.name}`
              || item.name === 'Copycat' && item.borrowedCard && key === `card:Copycat/${item.borrowedCard.card.name}`);
            const targetIndex = c.vault.indexOf(card);
            const blocker = actionBlocker(c, key, a, action);
            if (sourceIndex < 0 || targetIndex < 0 || blocker) {
              error.textContent = blocker ?? 'The selected cards are no longer available.';
              return;
            }
            const effects = runActionDetailed(c, key, a, action, label);
            if (effects === null) { error.textContent = 'This action is no longer available.'; return; }
            const original = c.loadout[sourceIndex];
            c.loadout[sourceIndex] = card;
            c.vault[targetIndex] = original;
            opts.onLog?.(formatActionReport({ title: action.label, character: c.name, lines: [`Replaced ${original.name} with ${card.name} without Recall cost.`] }), rollSourceFor(c, key));
            dialog.close();
            render();
          });
          choices.append(button);
        }
        if (!c.vault.length) choices.append(el('p', { class: 'muted' }, 'No cards in the vault.'));
        const cancel = el('button', { type: 'button' }, 'Cancel');
        cancel.addEventListener('click', () => dialog.close());
        dialog.addEventListener('close', () => dialog.remove(), { once: true });
        dialog.append(el('h4', {}, action.label), el('p', { class: 'muted' }, 'Choose a card from your vault.'), choices, error, cancel);
        document.body.append(dialog);
        dialog.showModal();
        return;
      }
      if (action.hopeDice || action.resourceRoll) {
        const spec = action.resourceRoll ?? { ...action.hopeDice!, resource: 'hope' as const };
        const resourceLabel = spec.resource === 'hope' ? 'Hope' : 'Stress';
        const available = () => spec.resource === 'hope' ? c.hope.on : c.stress.slots - c.stress.on;
        const inputLabel = `${resourceLabel} to ${spec.resource === 'hope' ? 'spend' : 'mark'}`;
        const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: action.label });
        const input = el('input', { type: 'number', min: '1', max: String(available()), step: '1', value: '1', ariaLabel: inputLabel });
        const error = el('div', { class: 'error', role: 'alert' });
        const cancel = el('button', { type: 'button' }, 'Cancel');
        const go = el('button', { type: 'button', class: 'primary', disabled: available() < 1 },
          `${spec.resource === 'hope' ? 'Spend' : 'Mark'} ${resourceLabel} and roll`);
        cancel.addEventListener('click', () => dialog.close());
        dialog.addEventListener('close', () => dialog.remove(), { once: true });
        go.addEventListener('click', () => {
          const amount = input.valueAsNumber;
          if (!Number.isInteger(amount) || amount < 1 || amount > available()) {
            error.textContent = `Enter a whole number from 1 to ${available()}.`;
            return;
          }
          const active = activeCardSources(c).some(source => source.key === key);
          if (!active) { error.textContent = 'This card is no longer in your active loadout.'; return; }
          const paidAction = { ...action, cost: { ...action.cost, [spec.resource]: amount } };
          const blocker = actionBlocker(c, key, a, paidAction);
          if (blocker) { error.textContent = blocker; return; }
          const expression = `${amount}d${spec.sides}${spec.bonusPerDie ? `+${amount * spec.bonusPerDie}` : ''}`;
          const source = spec.type ? `damage,${spec.type === 'magic' ? 'magicdamage,spelldamage' : 'physicaldamage'}` : key;
          const sent = sendRoll(source, undefined, rollSourceFor(c, key))({
            label: spec.type ? `${action.label} damage` : action.label, expression,
            contributions: [{ label: `${amount} ${resourceLabel}`, expression }],
          });
          if (!sent) { error.textContent = 'Not connected. Nothing was spent.'; return; }
          runActionDetailed(c, key, a, paidAction, label);
          hooks.after?.();
          dialog.close();
          render();
        });
        dialog.append(el('h4', {}, action.label), el('label', {}, inputLabel, input), error,
          el('div', { class: 'acts' }, cancel, go));
        document.body.append(dialog);
        dialog.showModal();
        input.focus();
        input.select();
        return;
      }
      if (action.patronDice || action.spendFavor || action.menacingReach) {
        if (!opts.supportsWarlockAutomation?.() || !opts.onFeature) {
          opts.onLog?.('Update the host to use Warlock automation.');
          return;
        }
        const submit = async (amount?: number) => {
          await opts.onFeature!(key, action.id, amount);
          hooks.after?.();
          render();
        };
        if (action.menacingReach) {
          setBusy(true);
          try { await submit(); }
          catch (e) { opts.onLog?.(`Could not use ${label}: ${(e as Error).message}`); }
          finally { setBusy(false); }
        } else chooseWarlockAmount(c, action, submit);
        return;
      }
      if (action.channelRawPower || action.chooseEffects) {
        const report = (lines: string[]) => {
          opts.onLog?.(formatActionReport({ title: label, character: c.name, lines }), rollSourceFor(c, key));
          hooks.after?.();
          render();
        };
        if (action.channelRawPower) chooseChannelCard(c, (card, benefit) => report(channelRawPower(c, key, a, action, card, benefit)));
        else chooseFeatureEffects(action, (ids, trait) => report(chooseActionEffects(c, key, a, action, ids, trait)));
        return;
      }
      if ((action.beastform || action.evolution || action.channel || action.aura) && !opts.supportsDruidAutomation?.()) {
        opts.onLog?.('Update the host to use Druid automation.');
        return;
      }
      if (action.beastform || action.evolution) {
        chooseBeastform(c, !!action.evolution, choice => {
          const lines = transformBeastform(c, key, a, action, choice);
          opts.onLog?.(formatActionReport({ title: label, character: c.name, lines }), rollSourceFor(c, key));
          page = 'beastform';
          render();
        });
        return;
      }
      if (action.channel || action.aura) {
        try {
          const lines = action.channel ? channelElement(c, key, a, action) : activateAura(c, key, a, action);
          opts.onLog?.(formatActionReport({ title: label, character: c.name, lines }), rollSourceFor(c, key));
          render();
        } catch (e) { opts.onLog?.(`Could not use ${label}: ${(e as Error).message}`); }
        return;
      }
      if (action.shiftStance) {
        chooseStance(c, (name, trait) => {
          const lines = shiftStance(c, key, a, action, name, trait);
          opts.onLog?.(formatActionReport({ title: 'Stance Fighter', character: c.name, lines }), rollSourceFor(c, key));
          render();
        }, () => render());
        return;
      }
      if (action.refocus) {
        try {
          const lines = refocus(c, key, a, action, rollLocal);
          opts.onLog?.(formatActionReport({ title: 'Refocus', character: c.name, lines }), rollSourceFor(c, key));
          render();
        } catch (e) { opts.onLog?.((e as Error).message); }
        return;
      }
      if (action.ancestryChoice) {
        setBusy(true);
        void chooseAncestryFeature(root, c.ancestry, action.ancestryChoice === 'ancestry', c.ancestryFeature).then((picked) => {
          if (!picked) return;
          replaceAncestry(picked.entries, c, picked.choice.ancestry, picked.choice.feature);
          opts.onLog?.(formatActionReport({ title: label, character: c.name, lines: [`Used a downtime move: ${c.ancestry} - ${c.ancestryFeature}`] }), rollSourceFor(c, key));
          render();
        }).catch((e: Error) => opts.onLog?.(`Could not use ${label}: ${e.message}`)).finally(() => { setBusy(false); });
        return;
      }
      if (action.target === 'allActors' || action.chatBenefits) {
        if (!opts.supportsPartyFeatures?.()) { opts.onLog?.('Update the host to use party feature actions.'); return; }
      }
      if (action.target || action.check || action.chatBenefits && !action.actionRoll) {
        if (a.deathMove?.kind === 'lifeWard' && !await confirmDialog('Confirm the chosen ally is within Close range. Recasting ends your previous Life Ward. Spend 3 Hope?')) return;
        if (!opts.onFeature) { opts.onLog?.('This host does not support targeted feature actions. Update the host.'); return; }
        if (action.transferHope) {
          openHopeTransfer(root, c, (amount) => opts.onFeature!(key, action.id, amount).then(() => { hooks.after?.(); render(); }));
          return;
        }
        setBusy(true);
        void opts.onFeature(key, action.id).then(() => { busy = false; hooks.after?.(); render(); })
          .catch((e: Error) => { setBusy(false); opts.onLog?.(`Could not use ${label}: ${e.message}`); });
        return;
      }
      if (action.damage) {
        openFeatureDamage(key, a, action, label, false, hooks.after);
        return;
      }
      if (action.actionRoll) {
        const spec = action.actionRoll;
        const trait = spec.trait === 'highest' ? TRAIT_ORDER.reduce((best, name) => traitTotal(c, name) > traitTotal(c, best) ? name : best)
          : spec.trait === 'spellcast' ? c.traits.find(t => t.spellcast)?.name
            : spec.trait === 'beastform' ? activeWeapons(c)[0]?.trait : spec.trait;
        const targets = [spec.reaction ? 'reaction' : 'action', ...(key.startsWith('card:') ? [`featureAction:${key}:${action.id}`] : []),
          ...(trait ? [`trait:${trait}`] : []), ...(spec.attack ? ['attack'] : []),
          ...(spec.trait === 'spellcast' ? ['spellcast'] : []),
          ...(key.startsWith('card:') || key.startsWith('feat:') ? [key] : [])];
        const bonus = rollBonus(c, targets, undefined, false);
        const damage = a.actions?.find((candidate) => candidate.damage)?.damage;
        const weapon = spec.attack && damage ? featureWeapon(label, damage) : undefined;
        openRollDialog(root, c, action.label, trait ? traitContributions(c, trait) : [], bonus,
          (request) => sendRoll(targets.join(','), weapon?.name, rollSourceFor(c, key))({ ...request, difficulty: spec.difficulty }),
          () => { if (!action.hostRollAction) runActionDetailed(c, key, a, action, label); consumeBonus(c, bonus); hooks.after?.(); render(); },
          targets, undefined, undefined, weapon, false, { key, automation: a, action }, undefined, undefined, undefined, 0, undefined, undefined, opts.supportsDomainCardAutomation);
        return;
      }
      if (action.sendRoll) {
        if (c.pendingDeathRoll) { opts.onLog?.('Resolve pending Risk It All recovery in chat before rolling.'); return; }
        if (actionBlocker(c, key, a, action)) return;
        const base = action.sendRoll.base === 'evasion' ? derivedStats(c).evasion : 0;
        const dice = action.sendRoll.dice.replace(/@tier(?=d|\b)/g, String(tierOf(c.level)))
          .replace(/@spellcast(?=d|\b)/g, String(Math.max(0, evalValue('@spellcast', c))));
        const sent = opts.onRoll({
          label: action.sendRoll.label.slice(0, 80), expression: `${base}+${dice}`,
          contributions: [...(action.sendRoll.base ? [{ label: 'Evasion', expression: String(base) }] : []), { label: `${label} ${dice}`.slice(0, 120), expression: dice }],
          rollSource: rollSourceFor(c, key),
          ...(opts.actorId ? { actorId: opts.actorId } : {}),
        });
        if (!sent) { opts.onLog?.('Not connected. The roll was not sent and nothing was spent.'); return; }
      }
      const title = actionReportTitle(c, key, label, action.label);
      let effects: string[] | null;
      try { effects = runActionDetailed(c, key, a, action, label === action.label ? label : `${label} - ${action.label}`); }
      catch (e) { opts.onLog?.(`Could not use ${label}: ${(e as Error).message}`); return; }
      if (effects === null) return;
      hooks.after?.();
      opts.onLog?.(formatActionReport({ title, character: c.name, lines: effects.length ? effects : ['used'] }), rollSourceFor(c, key));
      render();
    });
    return b;
  }

  const render = (save = true) => {
    hideSourceTooltip();
    cardLayoutObserver.disconnect();
    const scroll = root.querySelector('.pages')?.scrollTop ?? 0;
    const sidebarScroll = root.querySelector('.sheet-sidebar')?.scrollTop ?? 0;
    const readOnly = opts.readOnly?.() === true;
    const bonded = readOnly ? null : resolveBonded(c, rollLocal);
    if (bonded) opts.onLog?.(bonded);
    syncDerived(c);
    if (opts.visible?.() === false) {
      if (!readOnly && save) opts.onChange?.();
      return;
    }
    actionRefreshers = [];
    const editControl = editSwitch();
    opts.onEditControl?.(editControl);
    root.classList.toggle('edit-mode', canEdit());
    root.replaceChildren(...(opts.onEditControl ? [] : [el('div', { class: 'sheet-title' }, el('h2', {}, 'Daggerheart sheet'), editControl)]), header(), ...(c.mancer?.done === false ? [startBanner()] : c.transformation === 'Shapeshifter' && !c.ancestryFeature ? [ancestryBanner()] : [
      el('div', { class: 'sheet-layout' },
        el('aside', { class: 'sheet-sidebar', ariaLabel: 'Traits' }, traits()),
        el('div', { class: 'sheet-main' }, tabs(), pages())),
    ]));
    root.querySelectorAll<HTMLElement>('.cards, .cards > .dcard, .cards > .feat').forEach(node => cardLayoutObserver.observe(node));
    layoutCards();
    opts.onMiniStrip?.(c.mancer?.done === false || (c.transformation === 'Shapeshifter' && !c.ancestryFeature) ? null : miniStrip());
    if (readOnly) {
      applyReadOnlyControls();
    } else if (save) opts.onChange?.();
    const p = root.querySelector('.pages');
    if (p) p.scrollTop = scroll;
    const sidebar = root.querySelector('.sheet-sidebar');
    if (sidebar) sidebar.scrollTop = sidebarScroll;
    scheduleLayout();
  };

  function editSwitch(): HTMLElement {
    const button = el('button', { type: 'button', class: 'sheet-edit-switch', role: 'switch', disabled: opts.readOnly?.() === true, ariaLabel: 'Edit mode' },
      el('span', { class: 'switch-track', ariaHidden: 'true' }, el('span', { class: 'switch-thumb' })),
      el('span', {}, canEdit() ? 'On' : 'Off'));
    button.setAttribute('aria-checked', String(canEdit()));
    button.addEventListener('click', () => {
      if (opts.readOnly?.()) return;
      editMode = !editMode;
      render(false);
    });
    return el('div', { class: 'sheet-edit-control' }, el('span', { class: canEdit() ? 'sheet-edit-label' : 'sheet-edit-label muted' }, 'Edit mode'), button);
  }

  function textEdit(label: string, value: string, set: (value: string) => void, commitOnBlur = false): HTMLInputElement {
    const input = el('input', { type: 'text', value, ariaLabel: label, readOnly: !canEdit() });
    let committed = value;
    input.addEventListener(commitOnBlur ? 'blur' : 'input', () => {
      if (!canEdit() || commitOnBlur && input.value === committed) return;
      set(input.value);
      committed = input.value;
      if (commitOnBlur) opts.onChange?.();
    });
    return input;
  }

  function numberEdit(label: string, value: number, set: (value: number) => void, limit?: number): HTMLInputElement {
    const input = el('input', { type: 'number', ...(limit === undefined ? {} : { min: String(-limit), max: String(limit) }), step: '1', value: String(value), ariaLabel: label });
    input.addEventListener('click', event => event.stopPropagation());
    input.addEventListener('change', () => {
      if (!canEdit()) return;
      if (!Number.isSafeInteger(input.valueAsNumber) || limit !== undefined && Math.abs(input.valueAsNumber) > limit) {
        input.setCustomValidity(limit === undefined ? 'Enter a whole number.' : `Enter a whole number from -${limit} to +${limit}.`);
        input.reportValidity();
        return;
      }
      input.setCustomValidity('');
      set(input.valueAsNumber);
      render();
    });
    return input;
  }

  function slotEditor(kind: 'hp' | 'stress' | 'armor'): ((slots: number) => void) | undefined {
    return canEdit() ? slots => {
      if (!canEdit()) return;
      setTrackSlots(c, kind, slots);
      render();
    } : undefined;
  }

  function applyReadOnlyControls(): void {
    root.querySelectorAll<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement | HTMLButtonElement>('input, textarea, select, button').forEach(control => {
      if (control.matches('.tabs [role="tab"], .loot-description button, .card-view-switch button, .feature-search')) return;
      if (control instanceof HTMLInputElement && ['text', 'number', 'search'].includes(control.type) || control instanceof HTMLTextAreaElement)
        control.readOnly = true;
      else control.disabled = true;
    });
    root.querySelectorAll<HTMLElement>('[draggable="true"]').forEach(node => { node.draggable = false; });
  }

  const launchMancer = () => { void openMancer(root, c, () => render()); };
  const launchLevelUp = () => { void openLevelUp(root, c, () => render()); };

  function ancestryBanner(): HTMLElement {
    const choose = el('button', { class: 'primary', type: 'button' }, 'Choose retained ancestry feature');
    choose.addEventListener('click', () => {
      if (c.testingCharacter) void openTestingEditor(root, c, () => render());
      else void openTransformation(root, c, () => render());
    });
    return card('Only Skin Deep', null, 'Choose the single ancestry feature your Shapeshifter retains before using the sheet.', choose);
  }

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
    for (const effect of c.effects.filter(e => e.witchLinked)) {
      for (const resource of effect.resources ?? []) hidden.add(resource.id);
    }
    const extraResources = resourceDefinitions(c).filter((r) => !r.cardOnly && !hidden.has(r.id));
    const portrait = el('div', { class: 'portrait', title: canEdit() ? 'Edit character image' : 'Character image' });
    const artwork = opts.bus?.artwork ?? opts.artwork;
    if (artwork) portrait.append(el('img', { src: artwork, alt: '' }));
    else portrait.textContent = c.name.slice(0, 1).toUpperCase();
    const portraitError = el('span', { class: 'error portrait-error', role: 'alert' }, portraitErrorMessage);
    if (canEdit() && opts.actorId) {
      portrait.classList.add('editable-portrait');
      portrait.tabIndex = 0;
      portrait.setAttribute('role', 'button');
      portrait.setAttribute('aria-label', 'Edit character image');
      portrait.append(el('span', { class: 'portrait-edit-indicator' }, 'Edit image'));
      let editing = false;
      const edit = async () => {
        if (editing || !canEdit()) return;
        editing = true;
        portraitErrorMessage = '';
        try {
          const artwork = await editTokenArtwork(opts.actorId!, canEdit);
          if (artwork) {
            opts.artwork = artwork;
            if (opts.bus) opts.bus.artwork = artwork;
            render(false);
          }
        } catch (error) {
          portraitErrorMessage = `Could not update character image: ${(error as Error).message}`;
          render(false);
        }
        finally { editing = false; }
      };
      portrait.addEventListener('click', () => { void edit(); });
      portrait.addEventListener('keydown', event => {
        if ((event.key === 'Enter' || event.key === ' ') && canEdit()) { event.preventDefault(); void edit(); }
      });
    }
    const sub = (label: string, ...bold: string[]) => el('div', { class: 'sub' }, label, ...bold.map((b) => el('b', {}, b)));
    const death = el('button', { class: 'small', type: 'button', title: 'Choose a death move when all HP are marked' }, 'Death move...');
    death.disabled = !canMakeDeathMove(c);
    death.addEventListener('click', () => deathDialog());
    const scars = el('button', { class: 'small', type: 'button' }, `Scars (${c.scars ?? 0})`);
    scars.addEventListener('click', () => openScarsDialog(root, c, render));
    const restActions = el(
      'div', { class: 'header-actions' },
      ...([['Short rest', 'short', 'Short rest: refreshes short-rest abilities and ends effects lasting until a rest'], ['Long rest', 'long', 'Long rest: refreshes all rest abilities and ends effects lasting until a rest']] as const)
        .map(([label, kind, title]) => {
          const b = el('button', { class: 'small', type: 'button', title }, label);
          b.addEventListener('click', () => openRestDialog(root, c, kind, (summary, result) => { opts.onLog?.(summary, undefined, kind === 'short' ? result.dice : undefined); opts.onChange?.(); render(); },
            async (key, actionId) => {
              if (!opts.supportsDomainCardAutomation?.() || !opts.onFeature) throw new Error('Update the host to cast this spell during downtime.');
              await opts.onFeature(key, actionId);
            }, opts.restActors, opts.onRest));
          return b;
        }),
      death,
    );
    return el(
      'header', { class: 'ident' },
      el(
        'div', { class: 'ident-profile' },
        el(
          'div', { class: 'profile-top' },
          el('div', { class: 'portrait-wrap' }, portrait, portraitError),
          el(
            'div', { class: 'ident-main' },
            el('p', { class: 'name' }, canEdit() ? textEdit('Character name', c.name, value => {
              c.name = value;
              if ('Name' in c.description) c.description.Name = value;
            }, true) : c.name, pronounsDisplay = el('span', { class: 'muted', style: 'font-size:13px;font-weight:400' }, pronounsText(displayedPronouns())),
              ...(opts.readOnly?.() ? [el('span', { class: 'tag', style: 'margin-left:8px', role: 'status' }, 'Disabled - read-only')] : [])),
            ...(c.testingCharacter ? [tag('TESTING CHARACTER - unrestricted', 'hope')] : []),
            sub('Class: ', c.className, ` - Subclass: ${c.subclass}`),
            ...multiclassesOf(c).map((m) => sub('Multiclass: ', m.className, ` - Subclass: ${m.subclass.replace(/^[^:]+:\s*/, '')} - Domain: ${m.domain}`)),
            sub('Domains: ', c.domains.join(' & ')),
            sub('Heritage: ', `${c.ancestry} ancestry, ${c.community} community${c.transformation ? `, ${c.transformation} transformation` : ''}`),
            ...(c.player ? [sub('Played by ', c.player)] : []),
            restActions,
            ...(c.pendingDeathRoll ? [el('p', { class: 'muted' }, 'Risk recovery pending — resolve the result in chat before rolling.')] : []),
            ...(c.deathNarrative ? [el('p', { class: 'muted' }, c.deathNarrative)] : []),
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
        vital('Hope', el('div', {}, hopePips(), scars)),
        vital('Proficiency', proficiencyPips()),
        ...extraResources.map((r) => vital(r.label, poolControls(r))),
        ...[...activeFeatures(c), ...c.heritage].flatMap(f => f.automation?.effectCounters ?? []).filter(r => !r.cardOnly)
          .map(r => vital(r.label, counterResource(r))),
        ...activeFeatures(c).flatMap(f => f.automation?.dicePool ? [vital(f.automation.dicePool.label, dicePoolControls(f.automation.dicePool))] : []),
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

  function counterResource(r: NonNullable<Automation['effectCounters']>[number]): HTMLElement {
    return el('output', { ariaLabel: r.label }, effectCounterText(c, r.effect));
  }

  function dicePoolControls(pool: NonNullable<Automation['dicePool']>): HTMLElement {
    const dice = poolDice(c, pool);
    return el('div', { class: 'dh-dice-pool', ariaLabel: pool.label },
      ...(dice.length ? dice.map(die => {
        const value = die.counter!.value;
        const remove = el('button', { type: 'button', class: 'dh-die-remove',
          title: `Remove die showing ${value} (manual use)`, ariaLabel: `Remove ${pool.label} die showing ${value}` }, '\u00d7');
        remove.addEventListener('click', () => { c.effects = c.effects.filter(e => e.id !== die.id); render(); });
        const hope = el('button', { type: 'button', class: 'dh-die-hope',
          title: `Spend this die to gain ${value} Hope`, ariaLabel: `Spend ${pool.label} die to gain ${value} Hope` }, '+');
        hope.disabled = c.hope.on >= c.hope.slots;
        hope.addEventListener('click', () => {
          const gained = Math.min(value, c.hope.slots - c.hope.on);
          c.hope.on += gained;
          c.effects = c.effects.filter(e => e.id !== die.id);
          opts.onLog?.(formatActionReport({ title: pool.label, character: c.name, lines: [`Spent die ${value}: gained ${gained} Hope.`] }));
          render();
        });
        return el('span', { class: 'dh-pool-die' }, el('strong', {}, String(value)), hope, remove);
      }) : [el('span', { class: 'muted' }, 'No dice rolled')]));
  }

  function poolControls(r: NonNullable<Automation['resources']>[number]): HTMLElement {
    const input = poolInput(r);
    if (r.id !== 'death-mask') return input;
    input.title = 'Manual Mask token adjustment (0–4); confirm the reason at the table.';
    const gain = el('button', { type: 'button', class: 'small', disabled: (c.resources?.[r.id] ?? 0) >= 4 }, 'Gain token');
    gain.addEventListener('click', async () => {
      if (!await confirmDialog('Confirm at the table that you have earned one Mask token.')) return;
      c.resources ??= {};
      c.resources[r.id] = Math.min(4, (c.resources[r.id] ?? 0) + 1);
      render();
    });
    return el('div', { class: 'row' }, input, gain);
  }

  function levelUpButton(): HTMLElement {
    if (c.testingCharacter) {
      const edit = el('button', { class: 'primary small', type: 'button' }, 'Edit testing build');
      edit.addEventListener('click', () => { void openTestingEditor(root, c, () => render()); });
      return edit;
    }
    const max = c.level >= MAX_LEVEL;
    const unfinished = c.mancer?.done === false;
    const b = el('button', { class: 'primary small', type: 'button', title: unfinished ? 'Complete character creation before leveling up' : max ? 'Maximum level reached' : `Level up to ${c.level + 1}` }, 'Level up');
    b.disabled = unfinished || max;
    b.addEventListener('click', launchLevelUp);
    return b;
  }

  const levelBox = (v: number, l: string) => el('div', { class: 'level-box' }, el('div', { class: 'v' }, String(v)), el('div', { class: 'l' }, l));
  const vital = (label: string, body: HTMLElement, cls = '') => el('div', { class: `vital ${cls}`.trim() }, el('div', { class: 'lbl' }, label), body);

  function hopePips(): HTMLElement {
    const track = pips(c.hope, 'hope', 'var(--hope)', true, render, c.scars);
    if (hasCompanionTraining(c, 'light-in-the-dark')) {
      const slot = track.children[c.hope.slots - 1] as HTMLElement | undefined;
      if (slot) {
        slot.classList.add('companion-hope');
        slot.textContent = '\u2726';
        slot.title = `Light in the Dark - ${c.companion!.name}${slot.classList.contains('scarred') ? ' (crossed out by a scar)' : ''}`;
        slot.setAttribute('aria-label', slot.title);
      }
    }
    return track;
  }

  function proficiencyPips(): HTMLElement {
    const draft = { ...c.proficiency };
    return pips(draft, 'prof', 'var(--accent)', true, () => {
      if (!canEdit()) return;
      setManualStat(c, 'proficiency', draft.on);
      render();
    }, 0, 0, 0, undefined, canEdit());
  }

  function tabs(): HTMLElement {
    const nav = el('nav', { class: 'tabs' });
    nav.setAttribute('role', 'tablist');
    for (const [id, label] of PAGES) {
      if (id === 'companion' && !companionRules(c)) continue;
      if (id === 'beastform' && !c.effects.some(e => e.beastform)) continue;
      const effectCount = c.effects.filter(e => !e.dicePool).length;
      const count = id === 'effects' && effectCount ? ` (${effectCount})` : '';
      const b = el('button', { class: 'tab', type: 'button' }, label + count);
      b.setAttribute('role', 'tab');
      b.setAttribute('aria-selected', String(id === page));
      b.addEventListener('click', () => { page = id; render(false); });
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
      const md = canEdit() ? numberEdit(`${name} base modifier`, t.mod, value => { setManualStat(c, `trait.${name}`, value); }, 99)
        : el('span', { class: 'md', title: total !== t.mod ? `Base ${signed(t.mod)}, modified by equipment and effects` : '' }, signed(total));
      md.classList.add('md');
      if (!canEdit() && total !== t.mod) md.style.color = total > t.mod ? 'var(--hope, #4a9)' : 'var(--hp, #c55)';
      const tile = el('div', { class: 'trait' }, mark, th2, md, el('span', { class: 'vb' }, TRAIT_ACTIONS[name]));
      tile.addEventListener('click', () => rollDuality(name, name, [`trait:${name}`]));
      traits.append(tile);
    }
    return traits;
  }

  function miniStrip(): HTMLElement {
    const strip = el('div', { class: 'mini-strip' });
    const evasion = el('div', { class: 'mini-evasion', title: 'Evasion' },
      el('span', { class: 'nm' }, 'Evasion'), el('span', { class: 'md' }, String(derivedStats(c).evasion)));
    const group = el('div', { class: 'mini-traits', role: 'group', ariaLabel: 'Traits' });
    for (const name of TRAIT_ORDER) {
      const base = c.traits.find((x) => x.name === name)!.mod;
      const total = traitTotal(c, name);
      const md = el('span', { class: 'md' }, signed(total));
      if (total !== base) md.style.color = total > base ? 'var(--hope, #4a9)' : 'var(--hp, #c55)';
      const tile = el('button', { type: 'button', class: 'mini-trait', title: `Roll ${name}`, disabled: opts.readOnly?.() === true }, el('span', { class: 'nm' }, name), md);
      tile.addEventListener('click', () => rollDuality(name, name, [`trait:${name}`]));
      group.append(tile);
    }
    strip.append(evasion, el('span', { class: 'mini-sep', ariaHidden: 'true' }), group);
    return strip;
  }

  function experiences(): HTMLElement {
    const add = iconButton('add', 'Add Experience', () => {
      if (!canEdit()) return;
      c.experiences.push({ name: 'New Experience', mod: 2 });
      render();
    });
    return card('Experiences', canEdit() ? add : null, el('div', { class: 'list' }, ...c.experiences.map((x, i) => {
      if (!canEdit()) return el('div', { class: 'row exp' }, el('span', { class: 'grow' }, x.name), el('span', { class: 'mod' }, signed(x.mod)));
      const remove = iconButton('delete', `Delete Experience ${i + 1}`, () => {
        if (!canEdit()) return;
        c.experiences.splice(c.experiences.indexOf(x), 1);
        render();
      });
      return el('div', { class: 'row exp' }, textEdit(`Experience ${i + 1} name`, x.name, value => { x.name = value; }),
        numberEdit(`Experience ${i + 1} bonus`, x.mod, value => { x.mod = value; }), remove);
    })));
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
      openDamageDialog(root, c, (summary, severity) => {
        opts.onLog?.(summary);
        opts.onChange?.();
        render();
        if (severity >= 3 && opts.onDamageTaken) {
          void opts.onDamageTaken(severity).catch((e: Error) => opts.onLog?.(`Could not expire damage-linked effects: ${e.message}`));
        }
      }, undefined, opts.onIncomingDamage, (label, value) => opts.onLog?.(`${label}\nWard Die: 1d8 = ${value}\nIncoming damage reduced by ${value}.${value === 8 ? ' The Rune Ward trinket is destroyed.' : ''}`));
    });
    damageButton.classList.add('dh-dmg-btn');
    damageButton.addEventListener('click', (e) => e.stopPropagation());
    thresholds.querySelector('.lbl')?.append(damageButton);
    return el(
      'section', { class: 'vitals' },
      el(
        'div', { class: 'v-left' },
        withSources(vital('Evasion', canEdit() ? numberEdit('Evasion base value', c.evasion, value => { setManualStat(c, 'evasion', value); })
          : el('div', { class: 'big' }, String(th.evasion)), 'v-e'), srcs.evasion, th.evasion),
        withSources(vital('Armor', pips(c.armorTrack, 'armor', 'var(--armor)', false, refreshActions, 0, 0, 0, slotEditor('armor')), 'v-a'), srcs.armor, th.armorSlots, 'Armor slots'),
        withSources(thresholds,
          [...srcs.major.map((r) => ({ ...r, label: `${r.label} (major)` })), ...srcs.severe.map((r) => ({ ...r, label: `${r.label} (severe)` }))], `${th.major} / ${th.severe}`, 'Total (major / severe)'),
        vital('HP', pips(c.hp, 'hp', 'var(--hp)', false, render, 0, c.permanentHp, hpHealingFloor(c) - (c.permanentHp ?? 0), slotEditor('hp')), 'v-hp'),
        vital('Stress', pips(c.stress, 'stress', 'var(--stress)', false, render, 0, 0, 0, slotEditor('stress')), 'v-st'),
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
    if (page === 'beastform' && !c.effects.some(e => e.beastform)) page = 'char';
    if (page === 'companion' && !companionRules(c)) page = 'char';
    wrap.append(page === 'char' ? charPage() : page === 'companion' ? companionPage() : page === 'beastform' ? beastformPage() : page === 'effects' ? effectsPage() : page === 'equip' ? equipPage() : page === 'loot' ? lootPage() : page === 'cards' ? cardsPage() : bgPage());
    return wrap;
  }

  function companionPage(): HTMLElement {
    const pet = c.companion;
    if (!pet) {
      const create = el('button', { type: 'button', class: 'primary' }, 'Create companion');
      create.addEventListener('click', () => { void openCompanionMancer(root, c, render); });
      return el('div', { class: 'dh-start' }, create);
    }
    const w = companionWeapon(c)!;
    const available = companionAvailable(c);
    const edit = (label: string, value: string, set: (value: string) => void) => {
      const input = el('input', { value, ariaLabel: label, required: true });
      input.addEventListener('change', () => {
        if (!input.value.trim()) { input.value = value; opts.onLog?.(`${label} cannot be empty.`); return; }
        set(input.value.trim()); render();
      });
      return el('label', { class: 'field' }, label, input);
    };
    const command = el('button', { type: 'button', class: 'small primary', disabled: !available }, 'Command');
    command.addEventListener('click', () => rollDuality(`Command - ${pet.name}`, w.trait, ['companion', 'spellcast', `trait:${w.trait}`]));
    const takeDamage = el('button', { type: 'button', class: 'small', disabled: !available }, 'Take damage');
    takeDamage.addEventListener('click', () => openCompanionDamage());
    const stress = pips(pet.stress, 'companion Stress', 'var(--stress)', false, render);
    if (!available) stress.querySelectorAll<HTMLButtonElement>('button').forEach(b => { b.disabled = true; });
    const learned = companionFeatures(c).map(f => {
      const feature = featureCard(f);
      const count = pet.training.filter(t => companionRules(c)!.options.find(o => o.id === t.id)?.name === f.name).length;
      if (count > 1) feature.querySelector('.ft')?.append(tag(`x${count}`));
      const actions = actionRow(`feat:${f.name}`, f.automation, f.name);
      if (actions) feature.append(actions);
      return feature;
    });
    return el('div', { class: 'cols companion-page' },
      el('div', {},
        card('Companion', null, el('div', { class: 'fields' },
          edit('Name', pet.name, value => { pet.name = value; }),
          edit('Animal', pet.animal, value => { pet.animal = value; })),
          el('div', { class: 'row' }, vital('Evasion', el('div', { class: 'big' }, String(pet.evasion))), vital('Stress', stress)),
          el('p', { class: available ? 'muted' : 'error' }, available ? 'Available' : 'Unavailable until the start of your next long rest.'),
          el('div', { class: 'actions' }, command, takeDamage)),
        card('Active attack', null, weaponSlot(w),
          edit('Standard attack', pet.attack, value => { pet.attack = value; })),
        card('Companion Experiences', null, ...pet.experienceStats.map((experience, i) =>
          el('div', { class: 'row exp' }, edit(`Experience ${i + 1}`, experience.name, value => { experience.name = value; }),
            el('span', { class: 'mod' }, signed(experience.mod)))))),
      el('div', {}, card('Training', null, ...(learned.length ? learned : [el('p', { class: 'muted' }, 'Companion training is chosen during your level-ups.')])),
        card('Working together', null, rich('Any positive damage marks one companion Stress. Marking the last slot makes your companion unavailable until the next long rest, when they return with one Stress cleared. Downtime moves that clear your Stress also clear an equal amount for your available companion.'))));
  }

  function openCompanionDamage(): void {
    const dialog = el('dialog', { class: 'dh dh-rolldlg', ariaLabel: 'Companion damage' });
    const amount = el('input', { type: 'number', min: '1', step: '1', value: '1', ariaLabel: 'Damage taken' });
    const type = el('select', { ariaLabel: 'Damage type' }, el('option', { value: 'physical' }, 'Physical'), el('option', { value: 'magic' }, 'Magic'));
    const armored = el('input', { type: 'checkbox', disabled: !hasCompanionTraining(c, 'armored') || c.armorTrack.on >= c.armorTrack.slots });
    const error = el('div', { class: 'error', role: 'alert' });
    const apply = el('button', { type: 'button', class: 'primary' }, 'Apply');
    const cancel = el('button', { type: 'button' }, 'Cancel');
    cancel.addEventListener('click', () => dialog.close());
    apply.addEventListener('click', () => {
      if (!Number.isInteger(amount.valueAsNumber) || amount.valueAsNumber < 1) { error.textContent = 'Enter positive whole-number damage.'; return; }
      if (!companionAvailable(c)) { error.textContent = 'Your companion is unavailable.'; return; }
      try {
        const summary = markCompanionDamage(c, armored.checked);
        opts.onLog?.(summary);
        dialog.close(); render();
      }
      catch (e) { error.textContent = (e as Error).message; }
    });
    dialog.addEventListener('close', () => dialog.remove(), { once: true });
    dialog.append(el('h4', {}, 'Companion takes damage'),
      el('label', {}, 'Damage taken', amount), type,
      el('label', { class: 'dh-roll-option' }, armored, el('span', {}, 'Armored: mark one owner Armor Slot instead of Stress')),
      error, el('div', { class: 'acts' }, cancel, apply));
    document.body.append(dialog);
    dialog.showModal();
  }

  function effectsPage(): HTMLElement {
    const untilText = (e: ActiveEffect) => isVitalityEffect(e) ? 'Permanent (edit mode removal)' : ({ manual: 'Until dismissed', rest: 'Until your next rest', shortRest: 'Until a short rest', longRest: 'Until a long rest', scene: 'Until the end of the scene', session: 'Until the end of the session' })[e.until ?? 'manual'];
    const rows = c.effects.filter(e => !e.dicePool).map((e) => {
      const dismiss = el('button', { class: 'small', type: 'button' }, 'Remove');
      const manual = isManualOverride(e);
      const vitality = isVitalityEffect(e);
      dismiss.disabled = !!e.lockedHp || !!e.earthAuraGrant || (manual || vitality) && !canEdit();
      if (manual && !canEdit()) dismiss.title = 'Enable edit mode to remove the manual adjustments.';
      if (vitality && !canEdit()) dismiss.title = 'Enable edit mode to remove Vitality and reverse its benefits.';
      if (e.earthAuraGrant) dismiss.title = 'Ends automatically with the granting druid\'s Earth aura; select the roll option only when in range.';
      if (e.lockedHp) dismiss.title = 'This HP lock ends only when you rest.';
      dismiss.addEventListener('click', () => {
        if ((manual || vitality) && !canEdit()) return;
        if (vitality) removeVitality(c, e, canEdit());
        else if (manual) removeManualOverride(c, e);
        else c.effects.splice(c.effects.indexOf(e), 1);
        render();
      });
      const mods = (e.modifiers ?? []).filter(m => !manual || m.stat !== 'armorScore').map((m) => {
        const v = m.traitValue ? `${m.traitValue} (${signed(traitTotal(c, m.traitValue))})`
          : m.dice ? `+${m.dice}` : typeof m.value === 'number' ? signed(m.value) : String(m.value);
        return m.stat ? `${m.stat.replace('trait.', '')} ${v}` : `next ${(m.roll ?? '').replace('trait:', '')} roll ${v}`;
      });
      return el(
        'div', { class: `feat${e.kind === 'buff' ? ' hope' : ''}`, style: e.kind === 'debuff' ? 'border-left:3px solid var(--hp, #c55)' : '' },
        el('div', { class: 'src' }, `${e.kind === 'buff' ? 'Buff' : 'Debuff'} - ${untilText(e)}${e.source ? ` - from ${manual ? 'sheet edit mode' : e.source}` : ''}`),
        el('div', { class: 'ft' }, e.name, el('span', { style: 'float:right' }, dismiss)),
        rich(mutagenEffectSource(c, e)?.text ?? e.text),
        ...(e.counter ? [el('div', { class: 'badges' }, tag(e.deathrun
          ? `Deathrun: ${e.counter.value} damage use${e.counter.value === 1 ? '' : 's'}${e.deathrunRemaining ? ` · ${e.deathrunRemaining.length} weapon dice remain` : ''}`
          : `Die: ${e.counter.value} / d${effectCounterMax(c, e)}`))] : []),
        ...(mods.length ? [el('div', { class: 'badges' }, ...mods.map((m) => tag(m)))] : []),
        ...(e.beastform || !actorAutomationAvailable(e.sourceActorId) ? [] : e.features ?? []).map(f => {
          const node = featureCard(f);
          const actions = actionRow(`feat:${f.name}`, f.automation, f.name);
          if (actions) node.append(actions);
          return node;
        }),
        ...((e.actions?.length ?? 0) && actorAutomationAvailable(e.sourceActorId) ? [el('div', { class: 'acts' },
          ...e.actions!.map(action => actionButton(`effect:${e.id}`, {}, action, e.name, {
            extraBlock: () => c.effects.includes(e) ? null : 'This effect is no longer active',
          })))] : []),
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

  let featureView: DomainCardView = 'card';
  let featureQuery = '';
  const expandedFeatures = new Set<Feature>();
  let featurePage: HTMLElement | undefined;

  function charPage(): HTMLElement {
    featurePage = el('div', {}, featurePanel());
    return featurePage;
  }

  function featurePanel(): HTMLElement {
    const feat = (f: Feature, children: Feature[] = []) => {
      const node = featureCard(f, f.hope && !f.automation?.actions ? () => {
        if (c.hope.on < (f.cost ?? 0)) return;
        c.hope.on -= f.cost ?? 0;
        render();
      } : undefined);
      const actions = actionRow(`feat:${f.name}`, f.automation, f.name);
      if (f.automation?.elementChoice && (c.element || c.mancer?.choices?.element)) {
        node.append(el('p', { class: 'muted' }, `Element: ${c.element || c.mancer?.choices?.element}`));
      } else if (f.automation?.elementChoice) {
        const select = el('select', { ariaLabel: 'Elementalist element' }, el('option', { value: '' }, 'Choose your element'),
          ...f.automation.elementChoice.map(element => el('option', { value: element }, element)));
        select.value = c.element ?? c.mancer?.choices?.element ?? '';
        select.addEventListener('change', () => {
          if (!select.value) return;
          c.element = select.value;
          if (c.mancer?.choices) c.mancer.choices.element = select.value;
          render();
        });
        node.append(el('label', { class: 'row' }, 'Element', select));
      }
      if (actions) node.append(actions);
      for (const child of children) {
        const section = el('div', { class: 'feature-section' },
          el('div', { class: 'src' }, el('span', { class: 'feature-section-name' }, child.name)),
          rich(child.text));
        const childActions = actionRow(`feat:${child.name}`, child.automation, child.name);
        if (childActions) section.append(childActions);
        node.append(share(section, { category: 'feature', item: child }));
      }
      return share(node, { category: 'feature', item: f });
    };
    const tf = el('button', { class: 'small', type: 'button', title: 'A character can have one transformation; it counts as heritage' }, c.transformation ? `Transformation: ${c.transformation} (change)` : 'Add a transformation');
    tf.addEventListener('click', () => {
      if (!canEdit()) return;
      if (c.testingCharacter) void openTestingEditor(root, c, () => render());
      else void openTransformation(root, c, () => render());
    });
    const nodes = characterFeatureGroups(c.features, c.heritage).map(({ feature, children }) => {
      const full = feat(feature, children);
      if (featureView === 'card') return { feature, children, node: full };
      const details = el('details', { class: 'domain-list-row feature-list-row', open: expandedFeatures.has(feature) });
      const chat = el('span', { class: 'list-chat' });
      if (opts.onShare) {
        const button = iconButton('chat', `Post ${feature.name} to chat`, () => opts.onShare!({ category: 'feature', item: feature }));
        button.classList.add('share-icon');
        chat.append(button);
      }
      const summary = el('summary', { class: 'domain-list-summary feature-list-summary', title: `Expand or collapse ${feature.name}` },
        el('span', { class: 'list-name' }, feature.name),
        el('span', { class: 'list-domain' }, feature.source), chat);
      summary.addEventListener('click', event => {
        if ((event.target as Element).closest('button')) event.preventDefault();
      });
      details.addEventListener('toggle', () => {
        if (details.open) expandedFeatures.add(feature);
        else expandedFeatures.delete(feature);
      });
      details.append(summary, full);
      return { feature, children, node: details };
    });
    const search = el('input', { class: 'feature-search', type: 'search', placeholder: 'Search name, source, text', value: featureQuery, ariaLabel: 'Search features' });
    search.style.width = '190px';
    const grid = el('div', { class: `cards feature-cards${featureView === 'list' ? ' cards-list' : ''}` }, ...nodes.map(n => n.node));
    const none = el('p', { class: 'muted' }, 'No features match the search.');
    const apply = () => {
      featureQuery = search.value;
      let shown = 0;
      for (const { feature, children, node } of nodes) {
        node.hidden = !textMatches([feature, ...children].map(f => `${f.name} ${f.source} ${f.text}`).join(' '), featureQuery);
        if (!node.hidden) shown++;
      }
      none.hidden = shown > 0 || !featureQuery.trim();
      layoutCards();
    };
    search.addEventListener('input', apply);
    apply();
    const viewSwitch = cardViewSwitch(featureView, false, 'Feature view', view => {
      featureView = view;
      cardLayoutObserver.disconnect();
      featurePage?.replaceChildren(featurePanel());
      if (opts.readOnly?.()) applyReadOnlyControls();
      root.querySelectorAll<HTMLElement>('.cards, .cards > .feat').forEach(node => cardLayoutObserver.observe(node));
      layoutCards();
    });
    return el('div', { class: 'card' },
      el('h3', { class: 'card-zone-header' }, 'Features', el('span', { class: 'right' }, search, ...(canEdit() ? [tf] : []), viewSwitch)), grid, none);
  }

  function beastformPage(): HTMLElement {
    const form = c.effects.find(e => e.beastform)!;
    const drop = el('button', { type: 'button', class: 'small' }, 'Drop Beastform');
    drop.addEventListener('click', () => { c.effects = c.effects.filter(e => e !== form); render(); });
    const rows = (form.features ?? []).map(f => {
      const node = featureCard(f);
      const actions = actionRow(`feat:${f.name}`, f.automation, f.name);
      if (actions) node.append(actions);
      return node;
    });
    return card(form.name, drop, ...(form.weapon ? [weaponSlot(form.weapon)] : []), ...rows);
  }

  function weaponSlot(w: Weapon): HTMLElement {
    const companion = w.generated === 'companion';
    const mod = traitTotal(c, w.trait);
    const attack = el('button', { class: 'primary small', type: 'button' }, `Attack ${signed(mod)}`);
    attack.disabled = companion && !companionAvailable(c);
    attack.addEventListener('click', () => rollDuality(`Attack - ${w.name} (${w.trait})`, w.trait, [...(companion ? ['companion', 'spellcast'] : []), 'attack', `trait:${w.trait}`], w));
    const dmg = el('button', { class: 'small', type: 'button' }, 'Damage');
    dmg.disabled = companion && !companionAvailable(c);
    dmg.addEventListener('click', () => openWeaponDamage(w));
    const unequip = el('button', { class: 'small', type: 'button' }, 'Unequip');
    unequip.hidden = !!w.generated;
    unequip.addEventListener('click', () => { c.activeWeapons.splice(c.activeWeapons.indexOf(w), 1); c.carriedWeapons.push(w); render(); });
    const actions = actionRow(`weapon:${w.name}`, w.automation, w.feature?.name ?? w.name);
    const traitChoice = w.generated === 'brawler' ? el('select', { ariaLabel: "Brawler's Strike trait" }, ...TRAIT_ORDER.map(name => el('option', { value: name }, name))) : undefined;
    if (traitChoice) {
      traitChoice.value = w.trait;
      traitChoice.addEventListener('change', () => { c.brawlerTrait = traitChoice.value as TraitName; render(); });
    }
    return share(el(
      'div', { class: 'slot' },
      el('div', { class: 'top' }, el('span', { class: 'cat' }, w.slot), el('span', { class: 'nm' }, w.name)),
      weaponBadges(w),
      ...(traitChoice ? [el('label', {}, 'Attack trait', traitChoice)] : []),
      ...(w.feature ? [el('div', { class: 'feat', style: 'margin-top:6px' }, el('div', { class: 'ft' }, w.feature.name), rich(w.feature.text))] : []),
      el('div', { class: 'inventory-controls' }, attack, dmg, ...(actions ? [actions] : []), unequip),
    ), { category: 'weapon', item: w });
  }

  function equipPage(): HTMLElement {
    const used = handsUsed(c);
    const hands = ignoresWeaponBurden(c)
      ? el('span', { class: 'right hands muted' }, 'Weapon burden ignored')
      : el('span', { class: 'right hands', title: 'Max burden is 2 hands' }, el('span', { class: 'muted' }, 'Hands'), el('span', { class: `hand${used >= 1 ? ' on' : ''}` }), el('span', { class: `hand${used >= 2 ? ' on' : ''}` }), el('span', { class: 'muted' }, `${used} / 2`));
    const weapons = el('div', { class: 'card' }, el('h3', {}, 'Active weapons', hands));
    const primary = activeWeapons(c).find((w) => w.slot === 'Primary');
    const secondary = activeWeapons(c).find((w) => w.slot === 'Secondary');
    const emptySlot = (name: string) => el('div', { class: 'slot empty' }, el('div', { class: 'top' }, el('span', { class: 'cat' }, name), el('span', { class: 'nm', style: 'font-weight:400' }, 'Empty')));
    weapons.append(primary ? weaponSlot(primary) : emptySlot('Primary'));
    if (secondary) weapons.append(weaponSlot(secondary));
    else if (used < 2) weapons.append(emptySlot('Secondary'));

    const armorBody: Array<Node | string> = [];
    if (c.activeArmor) {
      const a = c.activeArmor;
      const un = el('button', { class: 'small', type: 'button' }, 'Unequip');
      un.addEventListener('click', () => { c.carriedArmor.push(a); c.activeArmor = undefined; render(); });
      const actions = actionRow(`armor:${a.name}`, a.automation, a.feature?.name ?? a.name);
      armorBody.push(share(el('div', { class: 'slot' }, el('div', { class: 'top' }, el('span', { class: 'nm' }, a.name)), armorBadges(a), ...(a.feature ? [el('div', { class: 'feat', style: 'margin-top:6px' }, el('div', { class: 'ft' }, a.feature.name), rich(a.feature.text))] : []),
        el('div', { class: 'inventory-controls' }, ...(actions ? [actions] : []), un)), { category: 'armor', item: a }));
    } else {
      armorBody.push(el('div', { class: 'slot empty' }, 'No armor equipped'));
    }

    const rows: HTMLElement[] = [];
    const equipBtn = (fn: () => void) => { const b = el('button', { class: 'small', type: 'button' }, 'Equip'); b.addEventListener('click', fn); return b; };
    for (const w of c.carriedWeapons) {
      rows.push(share(el('tr', {}, el('td', {}, el('b', {}, w.name), weaponBadges(w)), featureCell(w.feature), el('td', { class: 'controls-cell' }, el('div', { class: 'inventory-controls' }, equipBtn(() => equipWeapon(w))))), { category: 'weapon', item: w }, true));
    }
    for (const a of c.carriedArmor) {
      rows.push(share(el('tr', {}, el('td', {}, el('b', {}, a.name), armorBadges(a)), featureCell(a.feature), el('td', { class: 'controls-cell' }, el('div', { class: 'inventory-controls' }, equipBtn(() => equipArmor(a))))), { category: 'armor', item: a }, true));
    }
    const table = el('table', { class: 't inventory-table equipment-table' }, el('thead', {}, el('tr', {}, el('th', {}, 'Name'), el('th', {}, 'Feature'), el('th', { class: 'controls-cell' }, 'Actions'))), el('tbody', {}, ...rows));
    const entries = [...c.carriedWeapons, ...c.carriedArmor];
    const search = inventorySearch('equipment', rows.map((node, i) => ({ node, text: `${entries[i].name} ${entries[i].feature?.name ?? ''} ${entries[i].feature?.text ?? ''}` })));
    return el('div', {}, el('div', { class: 'eq-top' }, weapons, card('Active armor', null, ...armorBody)), el('div', { class: 'card', style: 'margin-top:14px' }, el('h3', {}, 'Carried, not equipped', search.input), rows.length ? table : el('span', { class: 'muted' }, 'Nothing carried'), search.none));
  }

  function featureCell(f?: { name: string; text: string }): HTMLElement {
    return f ? el('td', {}, el('b', {}, f.name), ' ', el('span', { class: 'muted' }, ...inline(f.text))) : el('td', { class: 'muted' }, '-');
  }

  function equipWeapon(w: Weapon): void {
    // a two-handed weapon replaces everything; otherwise make room for it
    c.activeWeapons = c.activeWeapons.filter(x => !x.generated);
    if (ignoresWeaponBurden(c)) {
      c.carriedWeapons.push(...c.activeWeapons.filter(x => x.slot === w.slot));
      c.activeWeapons = c.activeWeapons.filter(x => x.slot !== w.slot);
    } else if (w.hands === 2) c.carriedWeapons.push(...c.activeWeapons.splice(0));
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
        render();
      });
      return b;
    };
    out.append(step(-1), val, step(1));
    return out;
  }

  // Only using an item up removes it; the stepper may rest at 0 so accidental clicks lose nothing.
  function dropIfEmpty(item: InventoryItem): void {
    if (item.qty <= 0) c.inventory = c.inventory.filter(i => i !== item);
  }

  function lootPage(): HTMLElement {
    const input = el('input', { type: 'number', value: String(c.gold), min: '0' });
    input.setAttribute('aria-label', 'Gold coins');
    input.addEventListener('change', () => { c.gold = Math.max(0, Math.floor(Number(input.value) || 0)); input.value = String(c.gold); refreshActions(); });
    const adj = (d: number) => { const b = el('button', { type: 'button' }, d < 0 ? '-' : '+'); b.addEventListener('click', () => { c.gold = Math.max(0, c.gold + d); input.value = String(c.gold); refreshActions(); }); return b; };
    const gold = el(
      'div', { class: 'card goldbar' },
      el('span', { class: 'coin' }), el('span', { class: 'cat' }, 'Gold'),
      el('span', { class: 'qty' }, adj(-1), input, adj(1)),
      el('span', { class: 'muted', style: 'font-size:11.5px' }, 'coins (10 coins = 1 handful, 10 handfuls = 1 bag, 10 bags = 1 chest)'),
    );
    if (opts.onShare) {
      const transfer = iconButton('transfer', 'Offer gold for transfer', () => offerEntry({ category: 'gold', item: { name: 'Gold' } }));
      transfer.classList.add('share-icon', 'gold-transfer');
      transfer.disabled = c.gold <= 0;
      input.addEventListener('change', () => { transfer.disabled = c.gold <= 0; });
      gold.querySelectorAll('.qty button').forEach(b => b.addEventListener('click', () => { transfer.disabled = c.gold <= 0; }));
      gold.append(transfer);
    }
    const rows = c.inventory.map((i) => {
      const use = i.use && !i.custom ? el('button', { class: 'small', type: 'button' }, 'Use') : null;
      if (use) {
        use.disabled = i.qty <= 0 || !!c.pendingDeathRoll;
        if (c.pendingDeathRoll) use.title = 'Resolve pending Risk It All recovery in chat before rolling';
      }
      use?.addEventListener('click', () => {
        if (c.pendingDeathRoll) { opts.onLog?.('Resolve pending Risk It All recovery in chat before rolling.'); return; }
        if (i.qty > 0) { i.qty--; dropIfEmpty(i); rollDamage(i.name + (i.note ? ` (${i.note})` : ''), i.use!, rollSourceFor(c, `item:${i.name}`)); render(); }
      });
      const key = `item:${i.name}`;
      const consumable = i.type === 'consumable';
      const actions = i.custom ? null : actionRow(key, i.automation, i.name, {
        extraBlock: () => (i.qty <= 0 ? 'None left' : null),
        after: () => { if (consumable) { i.qty = Math.max(0, i.qty - 1); dropIfEmpty(i); } },
      });
      return share(el('tr', {}, el('td', {}, el('b', {}, i.name), ...(i.note ? [lootDescription(i)] : [])), el('td', {}, tag(i.type)), el('td', {}, qty(i)), el('td', { class: 'controls-cell' }, el('div', { class: 'inventory-controls' }, ...(use ? [use] : []), ...(actions ? [actions] : [])))), { category: 'item', item: i }, true, i.qty > 0);
    });
    const search = inventorySearch('loot', rows.map((node, i) => ({ node, text: `${c.inventory[i].name} ${c.inventory[i].note ?? ''}` })));
    const addItem = iconButton('add', 'Add custom item', addCustomItem);
    addItem.classList.add('share-icon', 'add-custom-item');
    return el(
      'div', {}, gold,
      el(
        'div', { class: 'card', style: 'margin-top:14px' },         el('h3', {}, 'Inventory', el('span', { class: 'right inventory-tools' }, search.input, addItem)),
                el('table', { class: 't inventory-table loot-table' }, el('thead', {}, el('tr', {}, el('th', {}, 'Item'), el('th', {}, 'Type'), el('th', {}, 'Qty'), el('th', { class: 'controls-cell' }, 'Actions'))), el('tbody', {}, ...rows)),
        search.none,
        el('p', { class: 'muted', style: 'font-size:11.5px;margin:6px 0 0' }, 'Consumables are single-use; at most 5 of each. Loot stays until sold, discarded or lost.'),
      ),
    );
  }

  function lootDescription(item: InventoryItem): HTMLElement {
    const key = `${item.name}\n${item.note}`;
    const text = el('div', { class: 'loot-description-text muted' }, item.note ?? '');
    const toggle = el('button', { type: 'button', class: 'description-toggle' });
    const wrap = el('div', { class: 'loot-description' }, text, toggle);
    const paint = () => {
      const expanded = expandedDescriptions.has(key);
      text.classList.toggle('expanded', expanded);
      toggle.setAttribute('aria-expanded', String(expanded));
      toggle.setAttribute('aria-label', `${expanded ? 'Show less' : 'Show more'} of ${item.name}'s description`);
      toggle.textContent = expanded ? 'Show less' : 'Show more';
    };
    toggle.addEventListener('click', () => {
      if (expandedDescriptions.has(key)) expandedDescriptions.delete(key);
      else expandedDescriptions.add(key);
      paint();
      measureDescriptions();
    });
    paint();
    toggle.hidden = !expandedDescriptions.has(key);
    return wrap;
  }

  function inventorySearch(key: 'equipment' | 'loot', rows: Array<{ node: HTMLElement; text: string }>): { input: HTMLInputElement; none: HTMLElement } {
    const input = el('input', { type: 'search', class: 'inventory-search', placeholder: 'Search name, description', value: inventoryQuery[key], ariaLabel: key === 'loot' ? 'Search inventory' : 'Search carried equipment' });
    const none = el('p', { class: 'muted' }, 'No items match the search.');
    const apply = () => {
      inventoryQuery[key] = input.value;
      rows.forEach(({ node, text }) => { node.hidden = !textMatches(text, input.value); });
      none.hidden = !input.value.trim() || rows.some(({ node }) => !node.hidden);
      requestAnimationFrame(measureDescriptions);
    };
    input.addEventListener('input', apply);
    apply();
    return { input, none };
  }

  /** A grimoire spell: its own text, Spellcast roll and automation buttons, independent of the other spells. */
  function spellBlock(d: DomainCard, s: Spell, loadout: boolean, cardKey = `card:${d.name}`): HTMLElement {
    const acts = el('div', { class: 'act' });
    if (d.spellcast && asksSpellcastRoll(s.text) && !s.automation?.actions?.some(action => action.actionRoll?.trait === 'spellcast')) {
      const sp = el('button', { class: 'small primary', type: 'button' }, 'Spellcast roll');
      const tracked = s.automation?.automaticRestUse;
      const blocked = tracked && (!loadout || !opts.supportsDomainCardAutomation?.() || usesLeft(c, `${cardKey}/${s.name}`, s.automation!) === 0);
      sp.disabled = !loadout || !canCastDomain(c) || !!blocked;
      if (tracked && !loadout) sp.title = 'Move this card to your active loadout to cast.';
      else if (tracked && !opts.supportsDomainCardAutomation?.()) sp.title = 'Update the host to track this spell.';
      else if (blocked) sp.title = 'No uses remaining until rest.';
      sp.addEventListener('click', () => rollDuality(`Spellcast (${d.spellcast}) - ${s.name}`.slice(0, 80), d.spellcast!, ['spellcast', `trait:${d.spellcast}`, cardKey, cardKey.startsWith('card:Copycat/') ? `${cardKey}/${s.name}` : `card:${s.name}`], undefined, rollSourceFor(c, `${cardKey}/${s.name}`), s.automation?.spellcastDifficulty));
      acts.append(sp);
    }
    if (loadout) {
      const actions = actionRow(`${cardKey}/${s.name}`, s.automation, s.name, {
        extraBlock: () => canCastDomain(c) ? null : 'Cannot cast domain spells in Beastform',
      });
      if (actions) acts.append(actions);
    }
    return el('div', { class: 'dspell' }, el('span', { class: 'sn' }, s.name), rich(s.text), ...(acts.childElementCount ? [acts] : []));
  }

  function dcard(d: DomainCard, loadout: boolean, embedded = false): HTMLElement {
    const cardKey = embedded ? `card:Copycat/${d.name}` : `card:${d.name}`;
    const acts = el('div', { class: 'act' });
    const spells = d.spells?.length ? d.spells : null;
    if (!spells && d.spellcast && !d.automation?.hideSpellcast && asksSpellcastRoll(d.text) && !d.automation?.actions?.some(action => action.actionRoll?.trait === 'spellcast')) {
      const sp = el('button', { class: 'small primary', type: 'button' }, 'Spellcast roll');
      const tracked = d.automation?.automaticRestUse;
      const blocked = tracked && (!opts.supportsDomainCardAutomation?.() || usesLeft(c, cardKey, d.automation!) === 0);
      sp.disabled = !loadout || !canCastDomain(c) || !!blocked;
      if (tracked && !loadout) sp.title = 'Move this card to your active loadout to cast.';
      else if (tracked && !opts.supportsDomainCardAutomation?.()) sp.title = 'Update the host to track this spell.';
      else if (blocked) sp.title = 'No uses remaining until rest.';
      sp.addEventListener('click', () => rollDuality(`Spellcast (${d.spellcast}) - ${d.name}`.slice(0, 80), d.spellcast!, ['spellcast', `trait:${d.spellcast}`, cardKey], undefined, rollSourceFor(c, cardKey), d.automation?.spellcastDifficulty));
      acts.append(sp);
    }
    if (loadout && !spells) {
      const actions = actionRow(cardKey, d.automation, d.name, {
        extraBlock: () => d.type === 'Ability' || canCastDomain(c) ? null : 'Cannot cast domain spells in Beastform',
      });
      if (actions) acts.append(actions);
    }
    if (d.automation?.banishDifficulty) {
      const input = el('input', { type: 'number', class: 'banish-difficulty', step: '1', value: d.banishDifficulty === undefined ? '' : String(d.banishDifficulty), ariaLabel: 'Banish difficulty' });
      const error = el('span', { class: 'error', role: 'alert' });
      input.addEventListener('change', () => {
        if (input.value.trim() === '') {
          delete d.banishDifficulty;
        } else if (Number.isSafeInteger(input.valueAsNumber)) {
          d.banishDifficulty = input.valueAsNumber;
        } else {
          error.textContent = 'Enter a whole number or leave the difficulty blank.';
          return;
        }
        error.textContent = '';
        opts.onChange?.();
      });
      acts.append(el('label', { class: 'banish-field' }, 'Banish difficulty', input), error);
    }
    if (embedded) {
      const pools = loadout ? (d.automation?.resources ?? []).map(r => el('label', { class: 'card-resource' }, 'Tokens', poolInput({ ...r, label: 'Tokens' }))) : [];
      return el('div', { class: 'dspell copycat-borrowed' }, el('div', { class: 'hd' }, `Level ${d.level} · ${d.domain} · ${d.type}`, ...pools),
        el('span', { class: 'sn' }, d.name), ...(spells ? spells.map(s => spellBlock(d, s, loadout, cardKey)) : [rich(d.text)]), acts);
    }
    const move = () => {
      if (c.testingCharacter) return;
      if (loadout) { c.loadout.splice(c.loadout.indexOf(d), 1); c.vault.push(d); }
      else if (!(d.name === 'Vitality' && c.vitalityBenefits?.length) && c.loadout.length < derivedStats(c).loadoutMax) { c.vault.splice(c.vault.indexOf(d), 1); c.loadout.push(d); }
      render();
    };
    let control: HTMLElement;
    if (d.name === 'Vitality' && c.vitalityBenefits?.length) control = tag(c.testingCharacter ? 'Permanently used · fixed testing loadout' : 'Permanently vaulted');
    else if (loadout) control = c.testingCharacter ? tag('Always active') : cardMenu('Move to vault', move);
    else {
      control = el('button', { class: 'small', type: 'button' }, `Recall to loadout (mark ${d.recall} Stress)`);
      control.addEventListener('click', move);
      acts.append(control);
    }
    const pools = loadout ? (d.automation?.resources ?? []).map((r) => el('label', { class: 'card-resource' }, 'Tokens', poolInput({ ...r, label: 'Tokens' }))) : [];
    const hd = el('div', { class: 'hd' }, el('span', { class: 'lv' }, String(d.level)), el('span', { class: 'dm' }, d.domain), ...pools, el('span', { class: 'rc' }, `\u26A1 ${d.recall}`), ...(loadout ? [control] : []));
    const copied = d.name === 'Copycat' && d.borrowedCard ? [
      el('p', { class: 'muted' }, `Copied from ${d.borrowedCard.sourceActorName}. Ends on rest or source vaulting.${copiedCardAvailable(d) ? '' : ' Source disabled; copied card is unavailable.'}`),
      ...(copiedCardAvailable(d) ? [dcard(d.borrowedCard.card, loadout, true)] : []),
    ] : [];
    const node = share(el('div', { class: 'dcard' }, hd, el('span', { class: 'ty' }, d.type), el('span', { class: 'nm' }, d.name), ...(spells ? spells.map((s) => spellBlock(d, s, loadout)) : [rich(d.text)]), acts, ...copied), { category: 'domain', item: d });
    return draggableCard(node, d, loadout);
  }

  function draggableCard(node: HTMLElement, d: DomainCard, loadout: boolean): HTMLElement {
    node.draggable = !c.testingCharacter && !opts.readOnly?.();
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
    const accepts = () => !c.testingCharacter && !!draggedCard && !(draggedCard.card.name === 'Vitality' && c.vitalityBenefits?.length)
      && draggedCard.fromLoadout !== toLoadout && (!toLoadout || c.loadout.length < derivedStats(c).loadoutMax);
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
  const expandedCards = new Set<DomainCard>();
  let cardState: CardPreferenceState = { view: 'card', pending: false, error: '' };
  let cardPage: HTMLElement | undefined;

  function updateCardPage(): void {
    if (page !== 'cards' || !cardPage) return;
    cardLayoutObserver.disconnect();
    cardPage.replaceChildren(...cardPageContents());
    if (opts.readOnly?.()) applyReadOnlyControls();
    root.querySelectorAll<HTMLElement>('.cards, .cards > .dcard').forEach(node => cardLayoutObserver.observe(node));
    layoutCards();
  }

  function cardViewSwitch(current = cardState.view, pending = cardState.pending, label = 'Domain card view', onSelect?: (view: DomainCardView) => void): HTMLElement {
    const buttons = (['card', 'list'] as DomainCardView[]).map(view => {
      const button = iconButton(view === 'card' ? 'cards' : 'list', view === 'card' ? 'Card view' : 'List view', () => {
        if (view === current || pending) return;
        if (onSelect) onSelect(view);
        else if (opts.cardPreferences) void opts.cardPreferences.save(view);
        else { cardState = { ...cardState, view }; updateCardPage(); }
      });
      button.setAttribute('aria-pressed', String(current === view));
      button.disabled = pending;
      return button;
    });
    return el('span', { class: 'card-view-switch', role: 'group', ariaLabel: label }, ...buttons);
  }

  function cardListRow(d: DomainCard, loadout: boolean): HTMLElement {
    const details = el('details', { class: 'domain-list-row', open: expandedCards.has(d) });
    const move = () => {
      if (c.testingCharacter || opts.readOnly?.()) return;
      if (loadout) { c.loadout.splice(c.loadout.indexOf(d), 1); c.vault.push(d); }
      else if (!(d.name === 'Vitality' && c.vitalityBenefits?.length) && c.loadout.length < derivedStats(c).loadoutMax) { c.vault.splice(c.vault.indexOf(d), 1); c.loadout.push(d); }
      render();
    };
    const menu = d.name === 'Vitality' && c.vitalityBenefits?.length ? tag(c.testingCharacter ? 'Permanently used' : 'Permanently vaulted')
      : c.testingCharacter ? el('span', { class: 'list-menu' }) : cardMenu(
      loadout ? 'Move to vault' : `Recall to loadout (mark ${d.recall} Stress)`, move);
    const chat = el('span', { class: 'list-chat' });
    if (opts.onShare) {
      const button = iconButton('chat', `Post ${d.name} to chat`, () => opts.onShare!({ category: 'domain', item: d }));
      button.classList.add('share-icon');
      chat.append(button);
    }
    if (canEdit()) chat.append(deleteCardButton(d));
    const summary = draggableCard(el('summary', { class: 'domain-list-summary', title: `Expand or collapse ${d.name}` },
      el('span', { class: 'list-level', title: 'Level' }, String(d.level)),
      el('span', { class: 'list-name' }, d.name),
      el('span', { class: 'list-domain' }, d.domain),
      el('span', { class: 'list-recall', title: 'Recall cost', ariaLabel: `Recall cost: ${d.recall}` }, `\u26A1 ${d.recall}`),
      menu, chat), d, loadout);
    summary.addEventListener('click', event => {
      if ((event.target as Element).closest('button, .card-menu-list')) event.preventDefault();
    });
    details.addEventListener('toggle', () => {
      if (details.open) expandedCards.add(d);
      else expandedCards.delete(d);
    });
    details.append(summary, dcard(d, loadout));
    return details;
  }

  function cardMatches(d: DomainCard, q: string): boolean {
    return textMatches(`${d.name} ${d.domain} ${d.type} ${d.text}`, q);
  }

  function deleteCardButton(d: DomainCard): HTMLButtonElement {
    const button = iconButton('delete', `Remove ${d.name} from this character`, () => {
      if (!canEdit()) return;
      const list = c.loadout.includes(d) ? c.loadout : c.vault;
      const index = list.indexOf(d);
      if (index < 0) return;
      list.splice(index, 1);
      expandedCards.delete(d);
      render();
    });
    button.classList.add('share-icon', 'delete-domain-card');
    return button;
  }

  function textMatches(text: string, q: string): boolean {
    const words = q.toLowerCase().split(/\s+/).filter(Boolean);
    const hay = text.toLowerCase();
    return words.every((w) => hay.includes(w));
  }

  /** Card panel with a header search box; filtering toggles visibility in place so typing is not interrupted by a re-render. */
  function cardZone(key: 'loadout' | 'vault', title: string, info: string, cards: DomainCard[], extras: HTMLElement[]): HTMLElement {
    const nodes = [...cards].sort((a, b) => a.level - b.level).map((d) => ({
      d, node: cardState.view === 'list' ? cardListRow(d, key === 'loadout') : dcard(d, key === 'loadout'),
    }));
    const search = el('input', { type: 'search', placeholder: 'Search name, domain, text', value: cardQuery[key], ariaLabel: `Search ${title.toLowerCase()} cards` });
    search.style.width = '190px';
    const grid = el('div', { class: `cards${cardState.view === 'list' ? ' cards-list' : ''}` }, ...nodes.map((n) => n.node), ...extras);
    const none = el('p', { class: 'muted', style: 'font-size:12px;margin:6px 0 0' }, 'No cards match the search.');
    const apply = () => {
      const q = search.value.trim();
      cardQuery[key] = search.value;
      let shown = 0;
      for (const { d, node } of nodes) { const ok = !q || cardMatches(d, q); node.hidden = !ok; if (ok) shown++; }
      for (const x of extras) x.hidden = !!q;
      none.hidden = !q || shown > 0 || nodes.length === 0;
      layoutCards();
    };
    search.addEventListener('input', apply);
    apply();
    return el('div', { class: 'card drop-zone' }, el('h3', { class: 'card-zone-header' }, title,
      el('span', { class: 'right muted' }, search, info, ...(key === 'loadout' ? [cardViewSwitch()] : []))), grid, none);
  }

  function cardPageContents(): HTMLElement[] {
    const error = el('p', { class: 'error card-view-error', role: 'alert' }, cardState.error);
    error.hidden = !cardState.error;
    if (c.testingCharacter) return [error, cardZone('loadout', 'Testing loadout', `${c.loadout.length} active - no limit`, c.loadout, [])];
    const max = derivedStats(c).loadoutMax;
    const empties = Array.from({ length: Math.max(0, max - c.loadout.length) }, () => el('div', { class: 'dcard empty' }, 'Empty loadout slot'));
    return [
      error,
      cardDropTarget(cardZone('loadout', 'Loadout', `${c.loadout.length} / ${max} active`, c.loadout, empties), true),
      cardDropTarget(cardZone('vault', 'Vault', 'Inactive cards', c.vault, []), false),
      el('p', { class: 'muted', style: 'font-size:12px' }, 'Subclass, ancestry and community cards are always active and do not count towards loadout or vault (see the Character tab).'),
    ];
  }

  function cardsPage(): HTMLElement {
    cardPage = el('div', {}, ...cardPageContents());
    return cardPage;
  }

  function bgPage(): HTMLElement {
    const area = (value: string, set: (v: string) => void, placeholder = '') => {
      const t = el('textarea', { value, placeholder, readOnly: !canEdit() });
      t.addEventListener('input', () => { if (canEdit()) set(t.value); });
      return t;
    };
    const fields = el('div', { class: 'fields' }, ...Object.keys(c.description).map((k) => {
      const i = textEdit(k, k === 'Pronouns' ? displayedPronouns() : c.description[k], value => {
        c.description[k] = value;
        if (k === 'Name') c.name = value;
        if (k === 'Pronouns') {
          c.pronouns = value;
          pronounsDisplay.textContent = pronounsText(value);
        }
      }, k === 'Name');
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
      el('div', {}, card('Background questions', el('span', { class: 'muted' }, 'From the class guide'), qa(c.backgroundQuestions, 'Question')), card('Connections', el('span', { class: 'muted' }, 'Relationships with other PCs'), qa(c.connections, 'Connection question'))),
    );
  }

  render();
  opts.cardPreferences?.subscribe(state => {
    if (state.view === cardState.view && state.pending === cardState.pending && state.error === cardState.error) return;
    cardState = state;
    updateCardPage();
  });
  void opts.cardPreferences?.load();
  return root;
}

export interface PendingAction { key: string; automation: Automation; action: ActionDef; blocker?: () => string | null }

function openHopeTransfer(host: HTMLElement, c: CharacterSheet, send: (amount: number) => Promise<void>): void {
  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  const close = () => { backdrop.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
  const amount = el('input', { type: 'number', min: '1', max: String(c.hope.on), step: '1', value: '1', ariaLabel: 'Hope to give' });
  const error = el('div', { class: 'error', role: 'alert' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const go = el('button', { type: 'button', class: 'primary' }, 'Choose ally');
  cancel.addEventListener('click', close);
  go.addEventListener('click', async () => {
    const n = amount.valueAsNumber;
    if (!Number.isInteger(n) || n < 1 || n > c.hope.on) { error.textContent = `Choose 1 to ${c.hope.on} Hope.`; return; }
    go.disabled = true;
    try { await send(n); close(); }
    catch (e) { error.textContent = (e as Error).message; go.disabled = false; }
  });
  backdrop.append(el('div', { class: 'dh-rolldlg' }, el('h4', {}, 'Close-Knit'), el('label', {}, 'Hope to give', amount), error, el('div', { class: 'acts' }, cancel, go)));
  backdrop.addEventListener('click', (e) => { if (e.target === backdrop) close(); });
  document.addEventListener('keydown', onKey);
  host.ownerDocument.body.append(backdrop);
  amount.focus();
}

/** Small modal: advantage and Experience spend; posts the duality roll to the chat. */
export function openRollDialog(host: HTMLElement, c: CharacterSheet, label: string, trait: RollContribution[], bonus: RollBonus, onRoll: (r: RollRequest) => boolean | Promise<boolean>, onSent: () => void, targets: string[], weapon?: Weapon, supportsKeptDice?: () => boolean, actionWeapon?: Weapon, critical = false, pendingAction?: PendingAction, supportsRollMultipliers?: () => boolean, attackFear?: number, supportsBrawlerRolls?: () => boolean, proficiencyBonus = 0, supportsDruidAutomation?: () => boolean, supportsSeraphAutomation?: () => boolean, supportsDomainCardAutomation?: () => boolean, supportsZeroKeptDice?: () => boolean, halfProficiency = false, outcome?: 'hope' | 'fear' | 'critical'): void {
  const mod = trait.reduce((n, s) => n + Number(s.expression), 0);
  let reaction = targets.includes('reaction');
  const canChooseReaction = !weapon && !pendingAction && !targets.includes('attack') && targets.some((target) => target.startsWith('trait:'));
  const currentTargets = () => weapon ? targets : rollTargets(targets, reaction);
  let advantage = '0';
  const advButtons = [['1', 'Advantage (+d6)', 'adv'], ['0', 'Normal', 'norm'], ['-1', 'Disadvantage (-d6)', 'dis']].map(([v, t, cls]) => {
    const b = el('button', { type: 'button', class: `dh-adv-btn ${cls}` }, t);
    b.addEventListener('click', () => { advantage = v; advButtons.forEach((x) => x.classList.toggle('on', x === b)); });
    b.classList.toggle('on', v === advantage);
    return b;
  });
  const adv = el('div', { class: 'dh-adv', role: 'group', ariaLabel: 'Roll with' }, ...advButtons);
  const experiences = targets.includes('companion') ? c.companion?.experienceStats ?? [] : c.experiences;
  const exp = el('select', {}, el('option', { value: '' }, 'None'), ...experiences.map((x, i) => el('option', { value: String(i) }, `${x.name} (${signed(x.mod)})`)));
  const backdrop = el('div', { class: 'dh dh-rolldlg-backdrop' });
  let sending = false;
  const close = () => { if (!sending) { backdrop.remove(); document.removeEventListener('keydown', onKey); } };
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
  const go = el('button', { class: 'primary', type: 'button' }, 'Roll to chat');
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const error = el('div', { class: 'error', role: 'alert' });
  const hostSupportsOption = (key: string) => !c.effects.some(effect => key === `effect:${effect.id}` && effect.linkedTokenResource)
    || !!supportsDomainCardAutomation?.();
  const currentOptions = () => availableRollOptions(c, currentTargets(), weapon ?? actionWeapon, outcome).filter(option => hostSupportsOption(option.key));
  let options = currentOptions();
  const checked = new Set<number>();
  const values = new Map<number, number>();
  const traitChoices = new Map<number, TraitName>();
  const dieChoices = new Map<number, HTMLSelectElement>();
  const checks = new Map<number, HTMLInputElement>();
  const runEffect = () => c.effects.find(e => e.deathrun && e.deathrunRoll);
  const removeRunDie = el('select', { ariaLabel: 'Deathrun die to remove' });
  const runControls = el('label', { class: 'dh-roll-option-control' }, 'Remove one die from the first damage roll', removeRunDie);
  runControls.hidden = !runEffect();
  if (runEffect()) {
    const effect = runEffect()!;
    removeRunDie.append(...(effect.deathrunRemaining ?? []).map(index =>
      el('option', { value: String(index) }, `Die ${index + 1}: ${effect.deathrunRoll!.rolls[index]}`)));
    const remaining = effect.deathrunRemaining ?? [];
    const lowest = remaining.slice(1).reduce((best, index) =>
      effect.deathrunRoll!.rolls[index] < effect.deathrunRoll!.rolls[best] ? index : best, remaining[0]);
    removeRunDie.value = String(lowest ?? '');
  }
  const selected = () => options.flatMap((s, i) => checked.has(i) ? [{
    ...configuredRollOption(c, s, values.get(i)),
    option: { ...configuredRollOption(c, s, values.get(i)).option,
      ...(s.option.traitChoices ? { traitValue: traitChoices.get(i) } : {}) },
  }] : []);
  const currentTrait = () => {
    const index = options.findIndex((s, i) => checked.has(i) && s.option.chooseTrait);
    return index >= 0 ? traitContributions(c, traitChoices.get(index)!) : trait;
  };
  const effectiveTargets = () => {
    const index = options.findIndex((s, i) => checked.has(i) && s.option.chooseTrait);
    return index < 0 ? currentTargets() : [...currentTargets().filter(t => !t.startsWith('trait:')), `trait:${traitChoices.get(index)}`];
  };
  const currentMod = () => currentTrait().reduce((n, s) => n + Number(s.expression), 0);
  const combined = () => selectedRollBonus(c, selected().some(s => s.option.chooseTrait)
    ? rollBonus(c, effectiveTargets(), actionWeapon, false) : bonus, selected());
  const suggest = () => {
    advantage = String(suggestedAdvantage(options.filter((_, i) => checked.has(i))));
    advButtons.forEach((button, i) => button.classList.toggle('on', ['1', '0', '-1'][i] === advantage));
  };
  const blocker = () => {
    try { selected(); } catch (e) { return (e as Error).message; }
    if (weapon && damagePool(c, weapon)?.dropLowest && !supportsKeptDice?.()) return 'This host does not support kept damage dice. Update the host to use this weapon feature.';
    if (selected().some(s => s.option.rerollBelowProficiency || s.option.rerollBelow) && !supportsDruidAutomation?.()) return 'Update the host to use damage-die rerolls.';
    if (selected().some(s => s.option.matchingDice) && !supportsSeraphAutomation?.()) return 'Update the host to use Sacred Resonance.';
    if (weapon && (damagePool(c, weapon)?.maximumOnes || selected().some(s => s.option.combo)) && !supportsBrawlerRolls?.()) return 'Update the host to roll Combo Strike or Exacting damage.';
    if (selected().some((s) => s.option.multiplier) && !supportsRollMultipliers?.()) return 'This host does not support damage multipliers. Update the host to use this option.';
    if (selected().some(s => !hostSupportsOption(s.key))) return 'Update the host to spend Invisibility tokens.';
    if (weapon && selected().some(s => s.option.maximizeDie) && !maximizableDamageDice(damageText(combined()).text).length)
      return 'No available damage dice can be maximized.';
    if (weapon && selected().some(s => s.option.maximizeDie)
        && /kh0\b/i.test(maximizeDamageDie(damageText(combined()).text, Number(dieChoices.get(options.findIndex((s, i) => checked.has(i) && s.option.maximizeDie))?.value)))
        && !supportsZeroKeptDice?.()) return 'Update the host to maximize a die in this damage pool.';
    const active = availableRollOptions(c, currentTargets(), weapon ?? actionWeapon, outcome);
    if (selected().some((s) => !active.some((a) => a.key === s.key && a.option.id === s.option.id && a.active === s.active))) return 'A selected bonus changed. Reopen this roll dialog.';
    if (pendingAction) {
      const changed = pendingAction.blocker?.();
      if (changed) return changed;
      const why = actionBlocker(c, pendingAction.key, pendingAction.automation, pendingAction.action);
      if (why) return why;
    }
    return selectionBlocker(c, [...selected(), ...(pendingAction ? [{
      key: `${pendingAction.key}:pending`, source: label, automation: {},
      option: { id: 'pending', label, roll: '', cost: actionCost(c, pendingAction.action) },
    }] : [])], !weapon && exp.value !== '');
  };
  const preview = el('div', { class: 'muted' });
  const crit = el('input', { type: 'checkbox' });
  crit.checked = critical;
  const baseProficiency = () => halfProficiency ? Math.ceil(derivedStats(c).proficiency / 2) : derivedStats(c).proficiency;
  const proficiencyExtra = () => (halfProficiency ? baseProficiency() - derivedStats(c).proficiency : proficiencyBonus) + criticalMeleeBonus(c, weapon?.name, crit.checked).proficiency
    + selected().reduce((n, s) => n + (s.option.deathrun ? deathrunProficiency(c, c.effects.find(e => s.key === `effect:${e.id}`)?.counter?.value ?? 0) - derivedStats(c).proficiency : s.option.proficiency ?? 0), 0);
  const damageType = el('select', { ariaLabel: 'Otherworldly damage type' },
    el('option', { value: 'physical' }, 'Physical damage'), el('option', { value: 'magic' }, 'Magic damage'));
  damageType.value = weapon?.damageType === 'magic' ? 'magic' : 'physical';
  const otherworldly = !!weapon && c.effects.some(e => e.stance && e.name === 'Otherworldly');
  const damageText = (b: RollBonus) => {
    const base = damageExpression(weapon!.damage, derivedStats(c).proficiency + proficiencyExtra(), b, damagePool(c, weapon!), weapon!.fixedDice);
    const extra = crit.checked ? criticalBonus(base) : 0;
    return { base, extra, text: extra ? `${base}+${extra}` : base };
  };
  const damageParts = (b: RollBonus) => {
    let expression = damageText(b).text;
    const contributions = [...damageContributions(c, weapon!, b, proficiencyExtra()), ...(damageText(b).extra ? [{ label: 'Critical hit (maximum dice)', expression: String(damageText(b).extra) }] : [])];
    const choiceIndex = options.findIndex((s, i) => checked.has(i) && s.option.maximizeDie);
    if (choiceIndex >= 0) {
      const index = Number(dieChoices.get(choiceIndex)?.value);
      expression = maximizeDamageDie(expression, index);
      let offset = 0;
      for (const part of contributions) {
        const count = [...part.expression.matchAll(/(\d*)d\d+(?:kh\d+)?/gi)].reduce((n, match) => n + Number(match[1] || 1), 0);
        if (index >= offset && index < offset + count) {
          part.expression = maximizeDamageDie(part.expression, index - offset);
          part.notes = [...(part.notes ?? []), { label: 'Versatile Fighter', impact: 'One damage die maximized instead of rolled' }];
          break;
        }
        offset += count;
      }
    }
    return damageRollFormula(expression, contributions, selected());
  };
  const update = () => {
    const continuing = !!runEffect() && options.some((s, i) => checked.has(i) && s.option.deathrun);
    for (const [i, check] of checks) if (!options[i].option.deathrun && continuing) {
      check.checked = false; checked.delete(i); check.disabled = true;
    } else check.disabled = !!actionBlocker(c, options[i].key, options[i].automation, options[i].option);
    runControls.hidden = !continuing;
    if (weapon) for (const select of dieChoices.values()) {
      const previous = select.value;
      const dice = maximizableDamageDice(damageText(combined()).text);
      select.replaceChildren(...dice.map(die => el('option', { value: String(die.index) }, `Die ${die.index + 1} (d${die.sides})`)));
      if (dice.some(die => String(die.index) === previous)) select.value = previous;
      else if (dice.length) select.value = String(dice[0].index);
    }
    const invalid = blocker();
    error.textContent = invalid ?? '';
    go.disabled = !!invalid;
    if (invalid) return;
    const b = combined();
    preview.textContent = weapon
      ? damageParts(b).expression
      : `Modifier ${signed(currentMod() + b.value + experienceModifier(exp.value === '' ? 0 : experiences[Number(exp.value)]?.mod ?? 0, selected()))}${b.dice.length ? `; extra dice ${b.dice.join(', ')}` : ''}`;
    if (continuing) {
      const effect = runEffect()!;
      const kept = (effect.deathrunRemaining ?? []).filter(index => index !== Number(removeRunDie.value))
        .reduce((sum, index) => sum + Number(effect.deathrunRoll!.rolls[index]), 0);
      const base = effect.deathrunRoll!.breakdown?.[0];
      const total = effect.deathrunRoll!.total - (base?.total ?? 0) + kept * (/^\(.*\)\*2$/.test(base?.expression ?? '') ? 2 : 1);
      preview.textContent = `Reuse the first damage roll; remove die ${Number(removeRunDie.value) + 1}. Remaining damage: ${total}. No new dice are rolled.`;
    }
    error.textContent = blocker() ?? '';
    go.disabled = !!error.textContent;
  };
  const choices = () => options.map((s, i) => {
    const input = el('input', { type: 'checkbox' });
    checks.set(i, input);
    const dice = rollOptionDice(c, s.option);
    const value = evalValue(s.option.value, c);
    const cost = costText(s.option.cost, c);
    const block = s.active ? null : actionBlocker(c, s.key, s.automation, s.option);
    input.disabled = !!block;
    if (!block && (s.active || s.option.defaultOn)) { input.checked = true; checked.add(i); }
    input.addEventListener('change', () => {
      if (input.checked) checked.add(i); else checked.delete(i);
      if (s.option.advantage) suggest();
      update();
    });
    const note = s.prepared ? 'ready - already used up'
      : s.active ? 'active - no cost'
      : [cost ? `costs ${cost}${s.option.quantity ? s.option.quantity.resource === 'stress' ? ' per use' : ' per token' : ''} when selected${s.option.effect ? ` (then stays active ${s.option.effect.until === 'scene' ? 'for the scene' : 'as an effect'})` : ''}` : '',
        s.option.consumeItem && s.item ? `uses up 1 ${s.item.name} (${s.item.qty} left)` : ''].filter(Boolean).join('; ');
    const initial = s.option.input?.source === 'attackFear' && typeof attackFear === 'number' && Number.isInteger(attackFear)
      && attackFear >= s.option.input.min && attackFear <= s.option.input.max ? attackFear : s.option.input?.default ?? 1;
    const amount = s.option.input || s.option.quantity ? el('input', { type: 'number', step: '1',
      min: String(s.option.input?.min ?? 1), max: String(s.option.input?.max ?? rollOptionQuantityMax(c, s.option)),
      value: String(initial), ariaLabel: s.option.input?.label ?? s.option.quantity!.label }) : undefined;
    if (amount) {
      values.set(i, amount.valueAsNumber);
      amount.addEventListener('input', () => { values.set(i, amount.valueAsNumber); update(); });
    }
    const allowedTraits = s.option.traitChoices ?? TRAIT_ORDER;
    const traitSelect = s.option.chooseTrait || s.option.traitChoices ? el('select', { ariaLabel: s.option.traitChoices ? 'Damage bonus trait' : 'Weapon trait' },
      ...allowedTraits.map(name => el('option', { value: name }, `${name} (${signed(traitTotal(c, name))})`))) : undefined;
    if (traitSelect) {
      const highest = allowedTraits.reduce((best, name) => traitTotal(c, name) > traitTotal(c, best) ? name : best);
      traitSelect.value = highest;
      traitChoices.set(i, highest);
      traitSelect.addEventListener('change', () => { traitChoices.set(i, traitSelect.value as TraitName); update(); });
    }
    const dieSelect = s.option.maximizeDie && weapon ? el('select', { ariaLabel: 'Damage die to maximize' }) : undefined;
    if (dieSelect) { dieChoices.set(i, dieSelect); dieSelect.addEventListener('change', update); }
    const choice = el('label', { class: 'dh-roll-option' }, input,
      el('span', {}, `${rollOptionLabel(s)}${value ? ` (${signed(value)}${s.option.quantity ? ' per token' : ''})` : ''}${dice ? ` +${dice}` : ''}${note ? `; ${note}` : ''}`,
        ...(s.option.description ? [el('small', { class: 'muted' }, s.option.description)] : []),
        ...(block ? [el('small', { class: 'error' }, block)] : [])));
    const continuingRun = s.option.deathrun && !!runEffect();
    return el('div', { class: `dh-roll-option-row${continuingRun ? ' dh-deathrun-option-row' : ''}` },
      choice, ...(continuingRun ? [runControls] : []),
      ...(amount ? [el('label', { class: 'dh-roll-option-control' },
        el('span', {}, s.option.input?.label ?? s.option.quantity!.label), amount)] : []),
      ...(traitSelect ? [el('label', { class: 'dh-roll-option-control' }, s.option.traitChoices ? 'Damage bonus trait' : 'Weapon trait', traitSelect)] : []),
      ...(dieSelect ? [el('label', { class: 'dh-roll-option-control' }, 'Damage die', dieSelect)] : []));
  });
  exp.addEventListener('change', update);
  crit.addEventListener('change', update);
  cancel.addEventListener('click', close);
  go.addEventListener('click', async () => {
    if (sending) return;
    const block = blocker();
    if (block) { error.textContent = block; go.disabled = true; return; }
    const e = weapon || exp.value === '' ? undefined : experiences[Number(exp.value)];
    const b = combined();
    const expMod = experienceModifier(e?.mod ?? 0, selected());
    const refund = e ? rollExperienceRefund(c, selected()) : undefined;
    const total = currentMod() + b.value + expMod;
    const requestedAdvantage = Number(advantage) as Advantage;
    const actualAdvantage = effectiveAdvantage(requestedAdvantage, selected());
    const ignored = selected().filter((s) => s.option.ignoreDisadvantage);
    const request: RollRequest = weapon ? {
      label: selected().some(s => s.option.magicDamage) ? `${label} (magic)`.slice(0, 80) : otherworldly ? `${label} (${damageType.value})`.slice(0, 80) : label,
      ...(selected().some(s => s.option.magicDamage) ? { source: ['damage', 'magicdamage', ...(targets.includes('spelldamage') ? ['spelldamage'] : [])].join(',') } : {}),
      ...(selected().some(s => s.option.combo || s.option.rerollBelowProficiency || s.option.rerollBelow || s.option.matchingDice) || damagePool(c, weapon)?.maximumOnes ? { damageRules: {
        ...(selected().some(s => s.option.matchingDice) ? { matchingDice: true } : {}),
        ...(selected().some(s => s.option.combo) ? { comboDie: comboDie(c) } : {}),
        ...(damagePool(c, weapon)?.maximumOnes ? { maximumOnes: true } : {}),
        ...(selected().some(s => s.option.rerollBelowProficiency || s.option.rerollBelow) ? { rerollBelow: Math.max(
          ...selected().map(s => s.option.rerollBelowProficiency ? derivedStats(c).proficiency : s.option.rerollBelow ?? 0)) } : {}),
      } } : {}),
      ...damageParts(b),
      ...(halfProficiency ? { weaponDamage: {
        proficiency: baseProficiency(),
        proficiencyBonus: proficiencyExtra() + derivedStats(c).proficiency - baseProficiency(),
        ...(selected().some(s => s.option.maximizeDie) ? { maximizeDie: Number(dieChoices.get(options.findIndex((s, i) => checked.has(i) && s.option.maximizeDie))?.value) } : {}),
        multiplier: selected().some(s => s.option.multiplier === 2) ? 2 : 1,
        options: selected().map(s => ({ key: s.key, id: s.option.id })),
      } } : {}),
    } : {
      label,
      source: [...effectiveTargets(),
        ...selected().filter(s => s.key.startsWith('card:') || s.key.startsWith('effect:')).map(s => `option:${s.key}:${s.option.id}`),
        ...(selected().some(s => s.option.proficiency) ? [`proficiency:${selected().reduce((n, s) => n + (s.option.proficiency ?? 0), 0)}`] : []),
        ...(selected().some(s => s.option.linkedDamageTrait) ? [`linkedDamage:${selected().reduce((n, s) => n + (s.option.linkedDamageTrait ? (s.option.linkedDamageMultiplier ?? 2) * traitTotal(c, s.option.linkedDamageTrait) : 0), 0)}`] : [])].join(','),
      expression: dualityExpression(total, actualAdvantage, selected().some((s) => s.option.hopeDie === 20) ? 20 : 12,
        selected().some((s) => s.option.fearDie === 20) ? 20 : 12, selected().some(s => s.option.advantageDie === 8) ? 8 : 6)
        + b.dice.map((d) => `+${d.startsWith('d') ? `1${d}` : d}`).join(''),
      kind: 'duality',
      contributions: [
        { label: 'Hope die', expression: selected().some((s) => s.option.hopeDie === 20) ? '1d20' : '1d12' },
        { label: 'Fear die', expression: selected().some((s) => s.option.fearDie === 20) ? '1d20' : '1d12' },
        ...(actualAdvantage === 0 ? [] : [{ label: actualAdvantage === 1 ? 'Advantage' : 'Disadvantage',
          expression: actualAdvantage === 1 ? selected().some(s => s.option.advantageDie === 8) ? '1d8' : '1d6' : '-1d6' }]),
        ...(requestedAdvantage !== actualAdvantage ? [{ label: 'Disadvantage ignored', expression: '0',
          notes: ignored.map((s) => ({ label: rollOptionLabel(s), impact: 'Disadvantage die not rolled' })) }] : []),
        ...currentTrait(),
        ...b.contributions,
        ...(e ? [{ label: `${targets.includes('companion') ? 'Companion Experience' : 'Experience'} - ${e.name}${selected().some(s => s.option.experience) ? ' (Adept: doubled)' : ''}`.slice(0, 120), expression: String(expMod) }] : []),
        ...(refund ? [refund.contribution] : []),
      ],
    };
    const paidOptions = selected();
    const hostOptions = paidOptions.filter(s => s.option.hostOwned);
    if (hostOptions.length) {
      request.hostOptions = hostOptions.map(s => ({ key: s.key, id: s.option.id, trait: s.option.traitValue }));
    }
    if (pendingAction?.action.hostRollAction) request.hostAction = { key: pendingAction.key, id: pendingAction.action.id, amount: pendingAction.action.selectedAmount };
    const run = hostOptions.find(s => s.option.deathrun);
    if (run) {
      const effect = c.effects.find(e => run.key === `effect:${e.id}`)!;
      request.deathrun = { effectId: effect.id, count: effect.counter?.value ?? 0 };
      if (effect.deathrunRoll) request.deathrunRemove = Number(removeRunDie.value);
    }
    try {
      const result = onRoll(request);
      if (typeof result !== 'boolean') {
        sending = true;
        go.disabled = cancel.disabled = true;
        optionFieldset.disabled = true;
      }
      const sent = typeof result === 'boolean' ? result : await result;
      sending = false;
      if (sent) {
        const refreshed = typeof result === 'boolean' ? paidOptions : paidOptions.map(option => {
          const current = availableRollOptions(c, currentTargets(), weapon ?? actionWeapon, outcome)
            .find(candidate => candidate.key === option.key && candidate.option.id === option.option.id);
          return current ? { ...option, item: current.item, prepared: current.prepared } : option;
        });
        commitRollOptions(c, refreshed, !!e, targets, refund?.refunded);
        close(); onSent();
      }
      else error.textContent = 'Not connected. The roll was not sent and no costs were paid.';
    } catch (e) { error.textContent = (e as Error).message; }
    finally { sending = false; go.disabled = cancel.disabled = false; optionFieldset.disabled = false; }
  });
  const optionFieldset = el('fieldset', {}, el('legend', {}, 'Optional bonuses (select all that apply)'), ...choices());
  removeRunDie.addEventListener('change', update);
  optionFieldset.hidden = options.length === 0;
  const optionContainer = el('div', { class: 'dh-roll-options' }, optionFieldset);
  optionContainer.hidden = optionFieldset.hidden;
  const applied = el('div', { class: 'muted' }, `Applied: ${bonus.names.join(', ')}.`);
  applied.hidden = bonus.names.length === 0;
  const heading = el('h4', {}, `${label} (${signed(mod + bonus.value)})`);
  const modeButtons = [[false, 'Normal roll'], [true, 'Reaction roll']].map(([value, text]) => {
    const isReaction = value === true;
    const button = el('button', { type: 'button', class: 'dh-adv-btn norm', ariaPressed: String(reaction === isReaction) }, String(text));
    button.classList.toggle('on', reaction === isReaction);
    button.addEventListener('click', () => {
      if (reaction === isReaction) return;
      reaction = isReaction;
      modeButtons.forEach((b, i) => {
        b.classList.toggle('on', i === (reaction ? 1 : 0));
        b.setAttribute('aria-pressed', String(i === (reaction ? 1 : 0)));
      });
      checked.clear();
      values.clear();
      bonus = rollBonus(c, currentTargets(), actionWeapon, false);
      options = currentOptions();
      optionFieldset.replaceChildren(el('legend', {}, 'Optional bonuses (select all that apply)'), ...choices());
      optionFieldset.hidden = options.length === 0;
      optionContainer.hidden = optionFieldset.hidden;
      optionContainer.scrollTop = 0;
      applied.textContent = `Applied: ${bonus.names.join(', ')}.`;
      applied.hidden = bonus.names.length === 0;
      heading.textContent = `${label} (${signed(mod + bonus.value)})`;
      suggest();
      update();
    });
    return button;
  });
  backdrop.addEventListener('click', (ev) => { if (ev.target === backdrop) close(); });
  backdrop.append(
    el(
      'div', { class: 'dh-rolldlg dh-option-rolldlg' },
      heading,
      applied,
      ...(weapon ? [el('label', { class: 'dh-roll-option' }, crit, el('span', {}, 'Critical damage (adds the maximum of the damage dice)'))] : [
        el('div', { class: 'muted' }, 'Duality roll: Hope d12 + Fear d12 + modifier'),
        el('div', { class: 'dh-adv-row' }, el('span', {}, 'Roll with'), adv),
        ...(canChooseReaction ? [el('div', { class: 'dh-adv-row' }, el('span', {}, 'Roll type'),
          el('div', { class: 'dh-adv dh-roll-type', role: 'group', ariaLabel: 'Roll type' }, ...modeButtons))] : []),
        el('label', { class: 'dh-roll-field' }, `${targets.includes('companion') ? 'Companion Experience' : 'Experience'} (${options.some(s => s.option.experience) ? '1 Hope, or 1 Stress with Adept' : 'spend 1 Hope'})`, exp),
      ]),
      ...(otherworldly ? [el('label', { class: 'dh-roll-field' }, 'Otherworldly: damage type', damageType)] : []),
      optionContainer,
      preview,
      error,
      el('div', { class: 'acts' }, cancel, go),
    ),
  );
  document.addEventListener('keydown', onKey);
  (host.ownerDocument.body).append(backdrop);
  if (selected().some((s) => s.option.advantage)) suggest();
  update();
  go.focus();
}

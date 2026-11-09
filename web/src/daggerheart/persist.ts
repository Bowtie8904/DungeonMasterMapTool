// Loads and saves a character sheet on the host (one JSON file per actor).

import { acceptSheetVersion, getSheet, InventoryConflict, saveSheet } from '../api';
import { CharacterSheet, emptyCharacter, multiclassesOf, normalizeSheet } from './data';
import { CompendiumFile, fetchCompendiumCategory, fetchCompendiumIndex } from './compendium';
import { syncResources } from './engine';
import { applyTestingBuild, defaultTestingBuild } from './testing';
import { loadRulesData } from './rules';
import { activeCardSources, prepareBorrowedCard } from './copycat';

/** Refresh copied rule metadata without rebuilding a character or resetting spent resources/advancements. */
export function updateCharacterAutomation(c: CharacterSheet, files: CompendiumFile[]): void {
  const entries = (category: string) => files.find((f) => f.category === category)?.entries ?? [];
  const equipment = (category: string, items: Array<{ name: string; automation?: import('./engine').Automation }>) => {
    for (const item of items) {
      const entry = entries(category).find((e) => e.name === item.name);
      if (entry && !('catalog' in item)) item.automation = entry.automation;
    }
  };
  // Class, heritage, transformation and domain card rules are shared content and follow the compendium on every load.
  // Only missing rest and damage-mitigation metadata is added to equipment; existing GM-authored mechanics are preserved.
  const restMetadata = (category: string, items: Array<{ name: string; automation?: import('./engine').Automation }>) => {
    for (const item of items) {
      if ('catalog' in item) continue;
      const rules = entries(category).find((e) => e.name === item.name)?.automation;
      if (!rules) continue;
      if (rules.deathMove) {
        item.automation ??= {};
        item.automation.deathMove = structuredClone(rules.deathMove);
        if (rules.resources) item.automation.resources = structuredClone(rules.resources);
      }
      if (rules.rest && !item.automation?.rest) {
        item.automation ??= {};
        item.automation.rest = structuredClone(rules.rest);
      }
      if (rules.mitigation && !item.automation?.mitigation) {
        item.automation ??= {};
        item.automation.mitigation = structuredClone(rules.mitigation);
      }
      for (const option of rules.rollOptions ?? []) {
        if (option.roll !== 'reaction') continue;
        item.automation ??= {};
        const options = item.automation.rollOptions ??= [];
        if (!options.some((current) => current.id === option.id)) options.push(structuredClone(option));
      }
      for (const action of rules.actions ?? []) {
        if (!action.timing && !action.deathMove && !rules.deathMove) continue;
        item.automation ??= {};
        const actions = item.automation.actions ??= [];
        const current = actions.find((a) => a.id === action.id);
        if (!current) actions.push(structuredClone(action));
        else {
          current.timing ??= action.timing;
          current.deathMove ??= action.deathMove;
          if (action.downtime && !current.downtime) current.downtime = structuredClone(action.downtime);
        }
      }
    }
  };
  restMetadata('weapons', [...c.activeWeapons, ...c.carriedWeapons]);
  restMetadata('armor', [...c.carriedArmor, ...(c.activeArmor ? [c.activeArmor] : [])]);
  restMetadata('items', c.inventory.filter((i) => !i.custom && i.type !== 'consumable'));
  restMetadata('consumables', c.inventory.filter((i) => !i.custom && i.type === 'consumable'));
  equipment('domain-cards', [...c.loadout, ...c.vault]);
  for (const card of [...c.loadout, ...c.vault]) {
    if (card.name !== 'Copycat' || !card.borrowedCard) continue;
    const entry = entries('domain-cards').find(entry => entry.name === card.borrowedCard!.card.name);
    if (entry) card.borrowedCard.card = prepareBorrowedCard({ ...card.borrowedCard.card, automation: entry.automation, spells: entry.spells });
  }
  for (const card of [...c.loadout, ...c.vault]) {
    if (card.catalog) continue;
    const entry = entries('domain-cards').find((e) => e.name === card.name && e.spells?.length);
    if (entry) { card.text = entry.text ?? card.text; card.spells = structuredClone(entry.spells); }
  }
  const classes = new Set([c.className, ...multiclassesOf(c).map((m) => m.className)]);
  const subclasses = new Set([c.subclass, ...multiclassesOf(c).map((m) => m.subclass.replace(/^[^:]+:\s*/, ''))]);
  // Old elemental cards had one "Fire" feature containing all four elements.
  c.features = c.features.filter(f => !(f.name === 'Fire' && /Warden of the Elements/.test(f.source)));
  for (const f of [...c.features, ...c.heritage]) {
    if (f.catalog) continue;
    const stage = /Subclass (foundation|specialization|mastery)/i.exec(f.source)?.[1]?.toLowerCase()
      ?? (/^Multiclass .* foundation$/i.test(f.source) ? 'foundation' : undefined);
    const candidates = [
      ...entries('classes').filter((e) => stage
        ? e.kind === 'subclass' && e.stage === stage && classes.has(e.parent ?? '') && subclasses.has(e.name.replace(/^[^:]+:\s*/, ''))
        : e.kind === 'class' && classes.has(e.name)),
      ...['ancestries', 'communities', 'transformations'].flatMap(entries).filter((e) => f.source.includes(e.name)),
    ];
    const feature = candidates.flatMap((e) => e.features ?? []).find((a) => a.name === f.name);
    if (feature) {
      f.automation = feature.automation;
      if (f.name === 'Battle-Bonded' && !feature.automation) {
        c.effects = c.effects.filter(e => e.name !== 'Battle-Bonded (one attack)');
      }
      const witchChart = ['Commune', 'Lunar Phases'].includes(feature.name)
        && candidates.some(e => (e.id === 'witch' || e.parent === 'Witch') && e.features?.includes(feature));
      if (feature.automation?.stances || feature.automation?.grantsFeatures || feature.automation?.companion || witchChart) f.text = feature.text;
      for (const name of feature.automation?.grantsFeatures ?? []) {
        const granted = candidates.flatMap(e => e.features ?? []).find(item => item.name === name);
        if (!granted) continue;
        const existing = c.features.find(item => item.name === name);
        if (existing) existing.text = granted.text;
        else c.features.push({ ...structuredClone(granted), source: f.source });
      }
    } else {
      const entry = candidates.find((e) => e.name === f.name);
      if (entry) f.automation = entry.automation;
    }
  }
  if (c.features.some(f => f.name === 'Focus' && f.automation?.actions?.some(a => a.refocus))) {
    const previous = c.usage['feat:Stance Fighter:action:refocus'];
    if (previous) {
      const current = c.usage['feat:Focus:action:refocus'];
      c.usage['feat:Focus:action:refocus'] = { ...previous, used: Math.max(previous.used, current?.used ?? 0) };
      delete c.usage['feat:Stance Fighter:action:refocus'];
    }
  }
  const stances = c.features.flatMap(f => f.automation?.stances ?? []);
  for (const feature of [...c.features, ...c.heritage]) {
    const pool = feature.automation?.dicePool;
    if (!pool) continue;
    const dice = c.effects.filter(e => e.dicePool === pool.id);
    if (dice.length && feature.automation?.uses?.per === 'session') {
      c.usage[`feat:${feature.name}`] ??= { used: 1, per: 'session' };
    }
    for (const die of dice) {
      const value = die.counter?.value;
      if (value === undefined) continue;
      for (const trigger of die.rollTriggers ?? []) trigger.label = `Prayer die: +${value}`;
      for (const mitigation of die.mitigation ?? []) mitigation.label = `Prayer die: reduce by ${value}`;
    }
  }
  for (const effect of c.effects.filter(e => e.stance)) {
    const stance = stances.find(s => s.name === effect.name);
    if (stance) effect.text = stance.effect.text;
  }
  const effectDefinitions = [...c.features, ...c.heritage].flatMap(f => f.automation?.actions ?? [])
    .flatMap(a => a.steps ?? []).flatMap(step => step.effect ? [step.effect] : []);
  for (const effect of c.effects) {
    const definition = effectDefinitions.find(e => e.name === effect.name);
    for (const mitigation of effect.mitigation ?? []) {
      const current = definition?.mitigation?.find(m => m.id === mitigation.id);
      if (current?.description) mitigation.description = current.description;
    }
  }
  syncResources(c);
}

function automationSources(c: CharacterSheet): Array<{ key: string; automation?: import('./engine').Automation }> {
  return [
    ...c.features.map((item) => ({ key: `feat:${item.name}`, automation: item.automation })),
    ...c.heritage.map((item) => ({ key: `feat:${item.name}`, automation: item.automation })),
    ...activeCardSources(c).map(source => ({ key: source.key, automation: source.auto })),
    ...c.activeWeapons.map((item) => ({ key: `weapon:${item.name}`, automation: item.automation })),
    ...c.carriedWeapons.map((item) => ({ key: `weapon:${item.name}`, automation: item.automation })),
    ...(c.activeArmor ? [{ key: `armor:${c.activeArmor.name}`, automation: c.activeArmor.automation }] : []),
    ...c.carriedArmor.map((item) => ({ key: `armor:${item.name}`, automation: item.automation })),
    ...c.vault.map((item) => ({ key: `card:${item.name}`, automation: item.automation })),
    ...c.inventory.map((item) => ({ key: `item:${item.name}`, automation: item.automation })),
  ];
}

export function featureTarget(c: CharacterSheet, key: string, actionId: string): import('./engine').ActionDef['target'] {
  const source = automationSources(c).find(source => source.key === key);
  const action = [...(source?.automation?.actions ?? []), ...(source?.automation?.conditionalActions ?? [])].find(item => item.id === actionId);
  return action?.target;
}

export function isLifeWard(c: CharacterSheet, key: string): boolean {
  return automationSources(c).some(source => source.key === key && source.automation?.deathMove?.kind === 'lifeWard');
}

export interface FeatureSheetState {
  actorId: string;
  featureRevision: number;
  hp?: CharacterSheet['hp'];
  stress?: CharacterSheet['stress'];
  hope?: CharacterSheet['hope'];
  scars?: number;
  permanentHp?: number;
  longRestRevision?: number;
  vitalityBenefits?: CharacterSheet['vitalityBenefits'];
  vitalityEffectRemoved?: boolean;
  spectralBlocked?: boolean;
  deathNarrative?: string;
  pendingDeathRoll?: CharacterSheet['pendingDeathRoll'] | null;
  inventoryRevision?: number;
  compendiumRevision?: number;
  inventory?: CharacterSheet['inventory'];
  loadout?: CharacterSheet['loadout'];
  vault?: CharacterSheet['vault'];
  armorTrack?: CharacterSheet['armorTrack'];
  effects?: CharacterSheet['effects'];
  usage?: CharacterSheet['usage'];
  resources?: CharacterSheet['resources'];
}

export function applyFeatureState(c: CharacterSheet, update: FeatureSheetState): boolean {
  if (update.featureRevision <= ((c as CharacterSheet & { featureRevision?: number }).featureRevision ?? 0)) return false;
  applyInventory(c, update, true);
  (c as CharacterSheet & { featureRevision?: number }).featureRevision = update.featureRevision;
  if (update.hp) c.hp = structuredClone(update.hp);
  if (update.stress) c.stress = structuredClone(update.stress);
  if (update.hope) c.hope = structuredClone(update.hope);
  if (update.scars !== undefined) c.scars = update.scars;
  if (update.permanentHp !== undefined) c.permanentHp = update.permanentHp;
  if (update.longRestRevision !== undefined) c.longRestRevision = update.longRestRevision;
  if (update.vitalityBenefits !== undefined) c.vitalityBenefits = [...update.vitalityBenefits];
  if (update.vitalityEffectRemoved !== undefined) c.vitalityEffectRemoved = update.vitalityEffectRemoved;
  if (update.spectralBlocked !== undefined) c.spectralBlocked = update.spectralBlocked;
  if (update.deathNarrative !== undefined) c.deathNarrative = update.deathNarrative;
  if (update.pendingDeathRoll !== undefined) c.pendingDeathRoll = update.pendingDeathRoll ?? undefined;
  if (update.armorTrack) c.armorTrack = structuredClone(update.armorTrack);
  if (update.effects) c.effects = structuredClone(update.effects);
  if (update.usage) c.usage = structuredClone(update.usage);
  if (update.resources) c.resources = structuredClone(update.resources);
  return true;
}

export async function refreshCharacterAutomation(c: CharacterSheet): Promise<void> {
  const index = await fetchCompendiumIndex();
  const files = await Promise.all(index.map((entry) => fetchCompendiumCategory(entry.category)));
  updateCharacterAutomation(c, files);
}

export async function loadCharacter(actorId: string, actorName: string, onWarning?: (message: string) => void): Promise<CharacterSheet> {
  const res = await getSheet(actorId);
  acceptSheetVersion(actorId, res.version);
  const c = res.sheet ? normalizeSheet(res.sheet, actorName) : emptyCharacter(actorName);
  c.testingCharacter = res.testingCharacter === true;
  if (!c.testingCharacter) delete c.testingBuild;
  if (c.testingCharacter) {
    if (c.testingBuild && c.mancer?.done === true) await refreshCharacterAutomation(c);
    else {
      const data = await loadRulesData();
      const build = c.testingBuild ?? defaultTestingBuild(data);
      if (build.transformation !== 'Shapeshifter' || build.ancestryFeature) applyTestingBuild(data, c, build);
    }
  } else if (res.sheet) {
    try { await refreshCharacterAutomation(c); }
    catch (e) {
      if (!onWarning) throw e;
      onWarning(`Could not refresh compendium rules; using saved rules: ${(e as Error).message}`);
    }
  }
  return c;
}

/** Returns a function to call after every possible change; it saves (debounced) only when the sheet really differs from the last save. */
export function applyInventory(c: CharacterSheet, incoming: Partial<Omit<CharacterSheet, 'pendingDeathRoll'>>, preserveMissing = false): boolean {
  let changed = false;
  if ((incoming.inventoryRevision ?? 0) > (c.inventoryRevision ?? 0)) {
    changed = true;
    c.inventoryRevision = incoming.inventoryRevision;
    if (incoming.inventory || !preserveMissing) c.inventory = incoming.inventory ?? [];
    if (incoming.activeWeapons || !preserveMissing) c.activeWeapons = incoming.activeWeapons ?? [];
    if (incoming.carriedWeapons || !preserveMissing) c.carriedWeapons = incoming.carriedWeapons ?? [];
    if (incoming.carriedArmor || !preserveMissing) c.carriedArmor = incoming.carriedArmor ?? [];
    if ('activeArmor' in incoming || !preserveMissing) c.activeArmor = incoming.activeArmor;
    if (typeof incoming.gold === 'number') c.gold = incoming.gold;
  }
  if ((incoming.compendiumRevision ?? 0) > (c.compendiumRevision ?? 0)) {
    changed = true;
    c.compendiumRevision = incoming.compendiumRevision;
    if (incoming.loadout) c.loadout = incoming.loadout;
    if (incoming.vault) c.vault = incoming.vault;
  }
  return changed;
}

export type SheetSave = (() => void) & { flush: () => Promise<void>; refresh: () => Promise<void> };

export function autosave(actorId: string, c: CharacterSheet, onError: (message: string) => void, delayMs = 700,
                         readOnly: () => boolean = () => false): SheetSave {
  let last = '';
  const baseline = JSON.stringify(c);
  const dirty = () => JSON.stringify(c) !== (last || baseline);
  let timer: number | undefined;
  let saving: Promise<void> | undefined;
  let refreshPending = false;
  let refreshing: Promise<void> | undefined;
  const replace = (sheet: Partial<CharacterSheet>, version?: string) => {
    const normalized = normalizeSheet(sheet, c.name);
    normalized.testingCharacter = c.testingCharacter;
    for (const key of Object.keys(c)) Reflect.deleteProperty(c, key);
    Object.assign(c, normalized);
    acceptSheetVersion(actorId, version);
    last = JSON.stringify(c);
    document.dispatchEvent(new CustomEvent('dmmt-inventory-refreshed', { detail: actorId }));
    document.dispatchEvent(new CustomEvent('dmmt-feature-refreshed', { detail: actorId }));
  };
  const refreshFull = async () => {
    refreshPending = true;
    if (saving || dirty()) return;
    if (refreshing) { await refreshing; return; }
    refreshing = (async () => {
      do {
        refreshPending = false;
        const data = await getSheet(actorId);
        if (saving || dirty()) { refreshPending = true; break; }
        if (data.version && data.sheet) {
          const normalized = normalizeSheet(data.sheet, c.name);
          normalized.testingCharacter = c.testingCharacter;
          if (JSON.stringify(normalized) !== JSON.stringify(c))
            replace(data.sheet, data.version);
          else acceptSheetVersion(actorId, data.version);
        } else if (data.version && !data.sheet && data.name) {
          if (c.name !== data.name || c.description.Name !== data.name)
            replace({ ...c, name: data.name, description: { ...c.description, Name: data.name } }, data.version);
          else acceptSheetVersion(actorId, data.version);
        } else {
          if (data.sheet && applyInventory(c, data.sheet))
            document.dispatchEvent(new CustomEvent('dmmt-inventory-refreshed', { detail: actorId }));
          if (data.sheet && applyFeatureState(c, { ...data.sheet, actorId,
            featureRevision: (data.sheet as { featureRevision?: number }).featureRevision ?? 0 }))
            document.dispatchEvent(new CustomEvent('dmmt-feature-refreshed', { detail: actorId }));
          last = JSON.stringify(c);
        }
      } while (refreshPending && !saving && !dirty());
    })();
    try { await refreshing; } finally { refreshing = undefined; }
  };
  const flush = async () => {
    if (timer !== undefined) window.clearTimeout(timer);
    timer = undefined;
    if (readOnly()) return;
    while (saving) await saving;
    if (readOnly()) return;
    const json = JSON.stringify(c);
    if (json === last) return;
    const operation = async () => {
      try {
        const result = await saveSheet(actorId, c);
        if (result?.version && result.sheet && JSON.stringify(c) === json) {
          const normalized = normalizeSheet(result.sheet, c.name);
          normalized.testingCharacter = c.testingCharacter;
          if (JSON.stringify(normalized) !== json) replace(result.sheet, result.version);
          else last = json;
        } else last = json;
      } catch (e) {
        if (e instanceof InventoryConflict) {
          if (e.version) replace(e.sheet, e.version);
          else {
            applyInventory(c, e.sheet);
            applyFeatureState(c, { actorId, featureRevision: (e.sheet as { featureRevision?: number }).featureRevision ?? 0,
              hp: e.sheet.hp, stress: e.sheet.stress, hope: e.sheet.hope, scars: e.sheet.scars, armorTrack: e.sheet.armorTrack,
              effects: e.sheet.effects, usage: e.sheet.usage, resources: e.sheet.resources,
              permanentHp: e.sheet.permanentHp, longRestRevision: e.sheet.longRestRevision, spectralBlocked: e.sheet.spectralBlocked, deathNarrative: e.sheet.deathNarrative, pendingDeathRoll: e.sheet.pendingDeathRoll ?? null });
            document.dispatchEvent(new CustomEvent('dmmt-inventory-refreshed', { detail: actorId }));
          }
        }
        onError(`Could not save the character sheet: ${(e as Error).message}`);
        throw e;
      }
    };
    saving = operation();
    try { await saving; } finally { saving = undefined; }
    if (refreshPending) await refreshFull();
  };
  const backgroundFlush = () => { void flush().catch(() => { /* reported by flush */ }); };
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') backgroundFlush(); });
  const changed = () => {
    if (timer !== undefined) window.clearTimeout(timer);
    timer = undefined;
    if (readOnly()) return;
    timer = window.setTimeout(backgroundFlush, delayMs);
  };
  return Object.assign(changed, {
    flush,
    refresh: refreshFull,
  });
}

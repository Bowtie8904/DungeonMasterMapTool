// Loads and saves a character sheet on the host (one JSON file per actor).

import { getSheet, InventoryConflict, saveSheet } from '../api';
import { CharacterSheet, emptyCharacter, multiclassesOf, normalizeSheet } from './data';
import { CompendiumFile, fetchCompendiumCategory, fetchCompendiumIndex } from './compendium';
import { syncResources } from './engine';
import { applyTestingBuild, defaultTestingBuild } from './testing';
import { loadRulesData } from './rules';

/** Refresh copied rule metadata without rebuilding a character or resetting spent resources/advancements. */
export function updateCharacterAutomation(c: CharacterSheet, files: CompendiumFile[]): void {
  const entries = (category: string) => files.find((f) => f.category === category)?.entries ?? [];
  const equipment = (category: string, items: Array<{ name: string; automation?: import('./engine').Automation }>) => {
    for (const item of items) {
      const entry = entries(category).find((e) => e.name === item.name);
      if (entry) item.automation = entry.automation;
    }
  };
  // Class, heritage, transformation and domain card rules are shared content and follow the compendium on every load.
  // Only missing rest and damage-mitigation metadata is added to equipment; existing GM-authored mechanics are preserved.
  const restMetadata = (category: string, items: Array<{ name: string; automation?: import('./engine').Automation }>) => {
    for (const item of items) {
      const rules = entries(category).find((e) => e.name === item.name)?.automation;
      if (!rules) continue;
      if (rules.rest && !item.automation?.rest) {
        item.automation ??= {};
        item.automation.rest = structuredClone(rules.rest);
      }
      if (rules.mitigation && !item.automation?.mitigation) {
        item.automation ??= {};
        item.automation.mitigation = structuredClone(rules.mitigation);
      }
      for (const action of rules.actions ?? []) {
        if (!action.timing) continue;
        item.automation ??= {};
        const actions = item.automation.actions ??= [];
        const current = actions.find((a) => a.id === action.id);
        if (!current) actions.push(structuredClone(action));
        else {
          current.timing ??= action.timing;
          if (action.downtime && !current.downtime) current.downtime = structuredClone(action.downtime);
        }
      }
    }
  };
  restMetadata('weapons', [...c.activeWeapons, ...c.carriedWeapons]);
  restMetadata('armor', [...c.carriedArmor, ...(c.activeArmor ? [c.activeArmor] : [])]);
  restMetadata('items', c.inventory.filter((i) => i.type !== 'consumable'));
  restMetadata('consumables', c.inventory.filter((i) => i.type === 'consumable'));
  equipment('domain-cards', [...c.loadout, ...c.vault]);
  for (const card of [...c.loadout, ...c.vault]) {
    const entry = entries('domain-cards').find((e) => e.name === card.name && e.spells?.length);
    if (entry) { card.text = entry.text ?? card.text; card.spells = structuredClone(entry.spells); }
  }
  const classes = new Set([c.className, ...multiclassesOf(c).map((m) => m.className)]);
  const subclasses = new Set([c.subclass, ...multiclassesOf(c).map((m) => m.subclass.replace(/^[^:]+:\s*/, ''))]);
  for (const f of [...c.features, ...c.heritage]) {
    const stage = /Subclass (foundation|specialization|mastery)/i.exec(f.source)?.[1]?.toLowerCase();
    const candidates = [
      ...entries('classes').filter((e) => stage
        ? e.kind === 'subclass' && e.stage === stage && classes.has(e.parent ?? '') && subclasses.has(e.name.replace(/^[^:]+:\s*/, ''))
        : e.kind === 'class' && classes.has(e.name)),
      ...['ancestries', 'communities', 'transformations'].flatMap(entries).filter((e) => f.source.includes(e.name)),
    ];
    const feature = candidates.flatMap((e) => e.features ?? []).find((a) => a.name === f.name);
    if (feature) f.automation = feature.automation;
    else {
      const entry = candidates.find((e) => e.name === f.name);
      if (entry) f.automation = entry.automation;
    }
  }
  syncResources(c);
  for (const source of automationSources(c)) stripSceneUses(source.automation);
  for (const [key, use] of Object.entries(c.usage)) if (use.per === 'scene') delete c.usage[key];
}

function automationSources(c: CharacterSheet): Array<{ key: string; automation?: import('./engine').Automation }> {
  return [
    ...c.features.map((item) => ({ key: `feat:${item.name}`, automation: item.automation })),
    ...c.heritage.map((item) => ({ key: `feat:${item.name}`, automation: item.automation })),
    ...c.loadout.map((item) => ({ key: `card:${item.name}`, automation: item.automation })),
    ...c.activeWeapons.map((item) => ({ key: `weapon:${item.name}`, automation: item.automation })),
    ...c.carriedWeapons.map((item) => ({ key: `weapon:${item.name}`, automation: item.automation })),
    ...(c.activeArmor ? [{ key: `armor:${c.activeArmor.name}`, automation: c.activeArmor.automation }] : []),
    ...c.carriedArmor.map((item) => ({ key: `armor:${item.name}`, automation: item.automation })),
    ...c.vault.map((item) => ({ key: `card:${item.name}`, automation: item.automation })),
    ...c.inventory.map((item) => ({ key: `item:${item.name}`, automation: item.automation })),
  ];
}

function stripSceneUses(value: unknown): void {
  if (!value || typeof value !== 'object') return;
  if (Array.isArray(value)) {
    value.forEach(stripSceneUses);
    return;
  }
  const record = value as Record<string, unknown>;
  const uses = record.uses;
  if (uses && typeof uses === 'object' && (uses as Record<string, unknown>).per === 'scene') delete record.uses;
  Object.values(record).forEach(stripSceneUses);
}

export function featureTarget(c: CharacterSheet, key: string, actionId: string): 'actor' | 'party' | undefined {
  const [kind, ...rest] = key.split(':');
  const name = rest.join(':');
  const source = kind === 'feat'
    ? [...c.features, ...c.heritage].find((item) => item.name === name)
    : kind === 'card' ? c.loadout.find((item) => item.name === name)
      : kind === 'weapon' ? c.activeWeapons.find((item) => item.name === name)
        : kind === 'armor' ? c.activeArmor?.name === name ? c.activeArmor : undefined
          : kind === 'item' ? c.inventory.find((item) => item.name === name) : undefined;
  const action = source?.automation?.actions?.find((item) => item.id === actionId) as
    (import('./engine').ActionDef & { target?: 'actor' | 'party' }) | undefined;
  return action?.target;
}

export interface FeatureSheetState {
  actorId: string;
  featureRevision: number;
  hp?: CharacterSheet['hp'];
  stress?: CharacterSheet['stress'];
  hope?: CharacterSheet['hope'];
  armorTrack?: CharacterSheet['armorTrack'];
  effects?: CharacterSheet['effects'];
  usage?: CharacterSheet['usage'];
  resources?: CharacterSheet['resources'];
}

export function applyFeatureState(c: CharacterSheet, update: FeatureSheetState): boolean {
  if (update.featureRevision <= ((c as CharacterSheet & { featureRevision?: number }).featureRevision ?? 0)) return false;
  (c as CharacterSheet & { featureRevision?: number }).featureRevision = update.featureRevision;
  if (update.hp) c.hp = structuredClone(update.hp);
  if (update.stress) c.stress = structuredClone(update.stress);
  if (update.hope) c.hope = structuredClone(update.hope);
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
  const c = res.sheet ? normalizeSheet(res.sheet, actorName) : emptyCharacter(actorName);
  c.testingCharacter = res.testingCharacter === true;
  if (!c.testingCharacter) delete c.testingBuild;
  if (c.testingCharacter) {
    const data = await loadRulesData();
    const build = c.testingBuild ?? defaultTestingBuild(data);
    if (build.transformation !== 'Shapeshifter' || build.ancestryFeature) applyTestingBuild(data, c, build);
  } else if (res.sheet) {
    try { await refreshCharacterAutomation(c); }
    catch (e) {
      if (!onWarning) throw e;
      onWarning(`Could not refresh compendium rules; using saved rules: ${(e as Error).message}`);
    }
  }
  for (const source of automationSources(c)) stripSceneUses(source.automation);
  for (const [key, use] of Object.entries(c.usage)) if (use.per === 'scene') delete c.usage[key];
  return c;
}

/** Returns a function to call after every possible change; it saves (debounced) only when the sheet really differs from the last save. */
export function applyInventory(c: CharacterSheet, incoming: Partial<CharacterSheet>): boolean {
  if ((incoming.inventoryRevision ?? 0) <= (c.inventoryRevision ?? 0)) return false;
  c.inventoryRevision = incoming.inventoryRevision;
  c.inventory = incoming.inventory ?? [];
  c.activeWeapons = incoming.activeWeapons ?? [];
  c.carriedWeapons = incoming.carriedWeapons ?? [];
  c.carriedArmor = incoming.carriedArmor ?? [];
  c.activeArmor = incoming.activeArmor;
  if ((incoming.compendiumRevision ?? 0) > (c.compendiumRevision ?? 0)) {
    c.compendiumRevision = incoming.compendiumRevision;
    if (incoming.loadout) c.loadout = incoming.loadout;
    if (incoming.vault) c.vault = incoming.vault;
  }
  return true;
}

export type SheetSave = (() => void) & { flush: () => Promise<void>; refresh: () => Promise<void> };

export function autosave(actorId: string, c: CharacterSheet, onError: (message: string) => void, delayMs = 700): SheetSave {
  let last = '';
  let timer: number | undefined;
  let saving: Promise<void> | undefined;
  const flush = async () => {
    if (timer !== undefined) window.clearTimeout(timer);
    timer = undefined;
    while (saving) await saving;
    const json = JSON.stringify(c);
    if (json === last) return;
    const operation = async () => {
      try {
        await saveSheet(actorId, c);
        last = json;
      } catch (e) {
        if (e instanceof InventoryConflict) {
          applyInventory(c, e.sheet);
          applyFeatureState(c, { actorId, featureRevision: (e.sheet as { featureRevision?: number }).featureRevision ?? 0,
            hp: e.sheet.hp, stress: e.sheet.stress, hope: e.sheet.hope, armorTrack: e.sheet.armorTrack,
            effects: e.sheet.effects, usage: e.sheet.usage, resources: e.sheet.resources });
          document.dispatchEvent(new CustomEvent('dmmt-inventory-refreshed', { detail: actorId }));
        }
        onError(`Could not save the character sheet: ${(e as Error).message}`);
        throw e;
      }
    };
    saving = operation();
    try { await saving; } finally { saving = undefined; }
  };
  const backgroundFlush = () => { void flush().catch(() => { /* reported by flush */ }); };
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') backgroundFlush(); });
  const changed = () => {
    if (timer !== undefined) window.clearTimeout(timer);
    timer = window.setTimeout(backgroundFlush, delayMs);
  };
  return Object.assign(changed, {
    flush,
    refresh: async () => {
      const sheet = await getSheet(actorId);
      if (sheet.sheet && applyInventory(c, sheet.sheet)) {
        document.dispatchEvent(new CustomEvent('dmmt-inventory-refreshed', { detail: actorId }));
      }
      if (sheet.sheet && applyFeatureState(c, { actorId,
        featureRevision: (sheet.sheet as { featureRevision?: number }).featureRevision ?? 0,
        hp: sheet.sheet.hp, stress: sheet.sheet.stress, hope: sheet.sheet.hope, armorTrack: sheet.sheet.armorTrack,
        effects: sheet.sheet.effects, usage: sheet.sheet.usage, resources: sheet.sheet.resources })) {
        document.dispatchEvent(new CustomEvent('dmmt-feature-refreshed', { detail: actorId }));
      }
    },
  });
}

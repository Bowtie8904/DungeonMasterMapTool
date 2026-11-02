// Loads and saves a character sheet on the host (one JSON file per actor).

import { getSheet, InventoryConflict, saveSheet } from '../api';
import { CharacterSheet, emptyCharacter, normalizeSheet } from './data';

export async function loadCharacter(actorId: string, actorName: string): Promise<CharacterSheet> {
  const res = await getSheet(actorId);
  return res.sheet ? normalizeSheet(res.sheet, actorName) : emptyCharacter(actorName);
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
    },
  });
}

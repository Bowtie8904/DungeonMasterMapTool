import type { Armor, CharacterSheet, InventoryItem, Weapon } from './data';
import type { AttachmentDef, Automation } from './engine';

type Gear = Weapon | Armor;

const isWeapon = (gear: Gear): gear is Weapon => 'slot' in gear;

export function attachmentDef(item: InventoryItem): AttachmentDef | undefined {
  return item.custom ? undefined : item.automation?.attach;
}

/** Why `gear` cannot receive `item`, or null when it can. */
export function attachBlocker(gear: Gear, def: AttachmentDef): string | null {
  if ((def.to === 'weapon') !== isWeapon(gear)) return `Attach this to ${def.to === 'weapon' ? 'a weapon' : 'armor'}.`;
  if (isWeapon(gear) && gear.generated) return 'Generated weapons cannot hold attachments.';
  const requires = def.requires ?? {};
  if (requires.noFeature && gear.feature) return `${gear.name} already has a feature.`;
  if (isWeapon(gear)) {
    if (requires.range && gear.range.toLowerCase() !== requires.range.toLowerCase()) return `Requires a ${requires.range} weapon.`;
    if (requires.damageType && gear.damageType !== requires.damageType) return `Requires a ${requires.damageType === 'phys' ? 'physical' : 'magic'} weapon.`;
    if (def.trait && gear.attachments?.some(a => a.item.automation?.attach?.trait)) return `${gear.name} already has an attack-trait attachment.`;
  }
  if (requires.nameIncludes?.length && !requires.nameIncludes.some(part => gear.name.toLowerCase().includes(part.toLowerCase())))
    return `Requires a ${requires.nameIncludes.join(' or ')}.`;
  return null;
}

/** Equipped and carried gear that can receive this attachable item. */
export function attachTargets(c: CharacterSheet, def: AttachmentDef): Gear[] {
  const gear: Gear[] = def.to === 'weapon' ? [...c.activeWeapons, ...c.carriedWeapons]
    : [...(c.activeArmor ? [c.activeArmor] : []), ...c.carriedArmor];
  return gear.filter(g => !attachBlocker(g, def));
}

function mergeAutomation(base: Automation | undefined, extra: Automation | undefined): Automation | undefined {
  if (!extra) return base && structuredClone(base);
  const out = structuredClone(base ?? {}) as Record<string, unknown>;
  for (const [key, value] of Object.entries(structuredClone(extra))) {
    const current = out[key];
    if (Array.isArray(value)) out[key] = Array.isArray(current) ? [...current, ...value] : value;
    else if (current === undefined) out[key] = value;
  }
  return out as Automation;
}

function recompute(gear: Gear): void {
  const base = gear.attachmentBase ?? {};
  const defs = (gear.attachments ?? []).map(a => a.item.automation?.attach).filter((d): d is AttachmentDef => !!d);
  const feature = [...defs].reverse().find(d => d.feature)?.feature ?? base.feature;
  if (feature) gear.feature = structuredClone(feature); else delete gear.feature;
  const automation = defs.reduce<Automation | undefined>((auto, d) => mergeAutomation(auto, d.automation), base.automation);
  if (automation) gear.automation = automation; else delete gear.automation;
  if (isWeapon(gear)) {
    const trait = [...defs].reverse().find(d => d.trait)?.trait ?? (base as Weapon['attachmentBase'])?.trait;
    if (trait) gear.trait = trait;
  }
}

/** Moves one unit of `item` onto `gear`, which gains the item's feature/automation/trait. Returns report lines. */
export function attachItem(c: CharacterSheet, item: InventoryItem, gear: Gear): string[] {
  const def = attachmentDef(item);
  if (!def) throw new Error(`${item.name} cannot be attached.`);
  if (!c.inventory.includes(item) || item.qty < 1) throw new Error(`No ${item.name} left to attach.`);
  const blocker = attachBlocker(gear, def);
  if (blocker) throw new Error(blocker);
  if (!gear.attachments?.length) {
    gear.attachmentBase = {
      ...(gear.feature ? { feature: structuredClone(gear.feature) } : {}),
      ...(gear.automation ? { automation: structuredClone(gear.automation) } : {}),
      ...(isWeapon(gear) ? { trait: gear.trait } : {}),
    };
  }
  gear.attachments = [...gear.attachments ?? [], { item: { ...structuredClone(item), qty: 1 } }];
  item.qty--;
  if (item.qty <= 0) c.inventory = c.inventory.filter(i => i !== item);
  recompute(gear);
  return [`Attached ${item.name} to ${gear.name}.`,
    ...(def.feature ? [`${gear.name} gains ${def.feature.name}.`] : []),
    ...(def.trait ? [`${gear.name} attacks use ${def.trait}.`] : [])];
}

/** Removes the attachment at `index` from `gear`, restoring the baseline when none remain, and returns the item. */
export function detachItem(c: CharacterSheet, gear: Gear, index: number): string[] {
  const attachment = gear.attachments?.[index];
  if (!attachment) throw new Error('That attachment is no longer on this gear.');
  gear.attachments = gear.attachments!.filter((_, i) => i !== index);
  if (!gear.attachments.length) {
    const base = gear.attachmentBase ?? {};
    if (base.feature) gear.feature = structuredClone(base.feature); else delete gear.feature;
    if (base.automation) gear.automation = structuredClone(base.automation); else delete gear.automation;
    if (isWeapon(gear) && (base as Weapon['attachmentBase'])?.trait) gear.trait = (base as Weapon['attachmentBase'])!.trait!;
    delete gear.attachments;
    delete gear.attachmentBase;
  } else recompute(gear);
  const stack = c.inventory.find(i => !i.custom && i.name === attachment.item.name && i.type === attachment.item.type);
  if (stack) stack.qty++;
  else c.inventory.push({ ...attachment.item, qty: 1 });
  return [`Detached ${attachment.item.name} from ${gear.name}.`];
}
